"""Read static UI dictionary literals from locked DSH browser bundles.

Only a JSON-like literal grammar (plus JS string escapes, shorthand/spread
properties and static identifier/member references) is accepted; bundles are
never executed. Namespaced message IDs remain stable when the upstream source
changes order.

Since DSH 0.2.2 upstream registers zh/en/ja/es/fr/de/ru/pt itself. DSHA only
adds the languages upstream lacks, so the catalogue keeps the zh/en source
columns and records, per namespace, which locales upstream already owns.
"""
import hashlib
import json
import re
from pathlib import Path

# DSHA-owned web languages; every other selectable language comes from upstream.
DSHA_LANGUAGES = ('zh-Hant', 'ko', 'ar', 'la')
EXTRA_LANGUAGES = DSHA_LANGUAGES
# Each DSHA language falls back the same way as its addLanguage definition.
DSHA_FALLBACK = {'zh-Hant': 'zh', 'ko': 'en', 'ar': 'en', 'la': 'en'}
SOURCE_LANGUAGES = ('zh', 'en')
SPACE = re.compile(r'(?:\s+|/\*[\s\S]*?\*/|//[^\n]*\n)')
IDENTIFIER = re.compile(r'[a-zA-Z_$][\w$]*')
LOCALE_TAG = re.compile(r'^[a-zA-Z]{2,3}(?:-[a-zA-Z0-9]{2,8})*$')
ESCAPES = {'n': '\n', 't': '\t', 'r': '\r', 'b': '\b', 'f': '\f', 'v': '\v', '0': '\0'}


class Literal:
    def __init__(self, text, names=None, offset=0):
        self.text, self.names, self.at = text, names or {}, offset

    def space(self):
        while self.at < len(self.text):
            match = SPACE.match(self.text, self.at)
            if not match:
                return
            self.at += len(match.group())

    def string(self):
        quote = self.text[self.at]
        self.at += 1
        parts = []
        while True:
            char = self.text[self.at]
            if char == quote:
                self.at += 1
                return ''.join(parts)
            if quote == '`' and self.text.startswith('${', self.at):
                raise ValueError('WEB_LITERAL_TEMPLATE')
            if char == '\n' and quote != '`':
                raise ValueError('WEB_LITERAL_STRING')
            if char != '\\':
                parts.append(char)
                self.at += 1
                continue
            escaped = self.text[self.at + 1]
            self.at += 2
            if escaped == 'x':
                parts.append(chr(int(self.text[self.at:self.at + 2], 16)))
                self.at += 2
            elif escaped == 'u' and self.text[self.at] == '{':
                end = self.text.index('}', self.at)
                parts.append(chr(int(self.text[self.at + 1:end], 16)))
                self.at = end + 1
            elif escaped == 'u':
                code = int(self.text[self.at:self.at + 4], 16)
                self.at += 4
                if 0xD800 <= code < 0xDC00 and self.text.startswith('\\u', self.at):
                    low = int(self.text[self.at + 2:self.at + 6], 16)
                    if 0xDC00 <= low < 0xE000:
                        code = 0x10000 + ((code - 0xD800) << 10) + (low - 0xDC00)
                        self.at += 6
                parts.append(chr(code))
            elif escaped == '\n':
                continue
            elif escaped == '0' and self.text[self.at].isdigit():
                raise ValueError('WEB_LITERAL_OCTAL')
            else:
                parts.append(ESCAPES.get(escaped, escaped))

    def value(self):
        self.space()
        char = self.text[self.at]
        if char in '"\'`':
            return self.string()
        if char in '{[':
            self.at += 1
            result = {} if char == '{' else []
            close = '}' if char == '{' else ']'
            while True:
                self.space()
                if self.text[self.at] == close:
                    self.at += 1
                    if char == '[' and self.text.startswith('.join(', self.at):
                        self.at += len('.join(')
                        separator = self.value()
                        self.space()
                        if not isinstance(separator, str) or self.text[self.at] != ')':
                            raise ValueError('WEB_LITERAL_JOIN')
                        self.at += 1
                        return separator.join(result)
                    return result
                if char == '{':
                    if self.text.startswith('...', self.at):
                        self.at += 3
                        spread = self.value()
                        if not isinstance(spread, dict):
                            raise ValueError('WEB_LITERAL_SPREAD')
                        result.update(spread)
                        self.space()
                        if self.text[self.at] == ',':
                            self.at += 1
                        elif self.text[self.at] != close:
                            raise ValueError('WEB_LITERAL_SEPARATOR')
                        continue
                    start = self.at
                    key = self.key()
                    self.space()
                    if self.text[self.at] == ':':
                        self.at += 1
                        result[key] = self.value()
                    else:
                        result[key] = self.resolve(key, start)
                else:
                    result.append(self.value())
                self.space()
                if self.text[self.at] == ',':
                    self.at += 1
                elif self.text[self.at] != close:
                    raise ValueError('WEB_LITERAL_SEPARATOR')
        start = self.at
        value = self.resolve(self.key(), start)
        # Static member chains such as builtinCommandTokens.zh.goal.
        while self.text.startswith('.', self.at) and IDENTIFIER.match(self.text, self.at + 1):
            self.at += 1
            member = self.key()
            if not isinstance(value, dict) or member not in value:
                raise KeyError(member)
            value = value[member]
        return value

    def resolve(self, name, position):
        """Use the closest preceding static definition of one bundle-scope name."""
        definitions = self.names.get(name)
        if not definitions:
            raise KeyError(name)
        earlier = [value for at, value in definitions if at < position]
        if earlier:
            return earlier[-1]
        if len(definitions) == 1:
            return definitions[0][1]
        raise KeyError(name)

    def key(self):
        self.space()
        if self.text[self.at] in '"\'':
            return self.string()
        match = IDENTIFIER.match(self.text, self.at)
        if not match:
            raise ValueError('WEB_LITERAL_IDENTIFIER')
        self.at += len(match.group())
        return match.group()


def static_names(source):
    names = {}
    for match in re.finditer(r'\b(?:const|let|var)\s+([\w$]+)\s*=\s*', source):
        try:
            reader = Literal(source, names, match.end())
            value = reader.value()
            if isinstance(value, str) or (isinstance(value, (dict, list)) and value):
                names.setdefault(match[1], []).append((match.start(), value))
        except (ValueError, KeyError, IndexError):
            pass
    return names


def loop_dictionaries(reader, names, position):
    """`for (const [locale, dict] of NAME) register(NS, locale, dict)` with a static pair array."""
    head = reader.text.rfind('for (const [', 0, position)
    match = re.compile(r'for \(const \[([\w$]+), ([\w$]+)\] of ([\w$]+)\)').match(reader.text, head) if head >= 0 else None
    if not match or position - match.end() > 400:
        raise KeyError('WEB_REGISTER_LOOP')
    pairs = reader.resolve(match[3], match.start())
    if not isinstance(pairs, list) or not all(isinstance(row, list) and len(row) == 2 for row in pairs):
        raise ValueError('WEB_REGISTER_LOOP_PAIRS')
    return match[1], match[2], dict(pairs)


def dictionaries(source):
    names = static_names(source)
    rows = []
    for match in re.finditer(r'\blocale\.register\(\s*', source):
        reader = Literal(source, names, match.end())
        namespace = reader.value()
        reader.space()
        if reader.text[reader.at] != ',':
            raise ValueError('WEB_REGISTER_SEPARATOR')
        reader.at += 1
        reader.space()
        start = reader.at
        # register(NS, locale, dict): the three-argument form inside a static pair loop.
        triple = re.compile(r'([\w$]+)\s*,\s*([\w$]+)\s*\)').match(reader.text, start)
        if triple:
            locale_name, dict_name, values = loop_dictionaries(reader, names, match.start())
            if (triple[1], triple[2]) != (locale_name, dict_name):
                raise ValueError('WEB_REGISTER_LOOP_ARGUMENTS: ' + str(namespace))
        else:
            values = reader.value()
        if not isinstance(namespace, str) or not isinstance(values, dict) \
                or not set(SOURCE_LANGUAGES) <= set(values) \
                or not all(isinstance(tag, str) and LOCALE_TAG.match(tag) for tag in values):
            raise ValueError('WEB_REGISTER_LITERAL: ' + str(namespace))
        if set(values['zh']) != set(values['en']):
            raise ValueError('WEB_REGISTER_KEYS: ' + namespace)
        for tag, entries in values.items():
            if not isinstance(entries, dict) or not all(isinstance(text, str) for text in entries.values()):
                raise ValueError('WEB_REGISTER_TEXT: ' + namespace + ':' + tag)
            if not set(entries) <= set(values['en']):
                raise ValueError('WEB_REGISTER_EXTRA_KEYS: ' + namespace + ':' + tag)
        rows.append((namespace, values))
    return rows


def extract(modules, transform=lambda relative, data: data, version=None):
    messages, inputs, namespaces = [], {}, {}
    for source in sorted((Path(modules) / '@deepseek-ai').glob('*/lib/client*.js')):
        raw = source.read_bytes()
        if b'locale.register(' not in raw:
            continue
        relative = source.relative_to(modules).as_posix()
        prepared = transform(relative, raw)
        found = dictionaries(prepared.decode('utf-8'))
        if not found:
            continue
        inputs[relative] = hashlib.sha256(raw).hexdigest()
        for namespace, values in found:
            if namespace in namespaces:
                raise ValueError('WEB_NAMESPACE_DUPLICATE: ' + namespace)
            upstream = sorted(tag for tag in values if tag not in SOURCE_LANGUAGES)
            owned = {tag for tag in upstream if tag.lower() in {own.lower() for own in DSHA_LANGUAGES}}
            if owned:
                # Upstream now ships one of DSHA's languages here: registering it again throws.
                raise ValueError('WEB_NAMESPACE_UPSTREAM_OWNS_DSHA_LANGUAGE: ' + namespace + ':' + ','.join(sorted(owned)))
            namespaces[namespace] = {'module': relative, 'upstreamLocales': upstream}
            for key in sorted(values['zh']):
                row = {'id': namespace + ':' + key, 'namespace': namespace,
                       'key': key, 'zh': values['zh'][key], 'en': values['en'][key]}
                # Upstream languages missing this key fall back to en at runtime.
                missing = [tag for tag in upstream if key not in values[tag]]
                if missing:
                    row['upstreamMissing'] = missing
                messages.append(row)
    messages.sort(key=lambda row: row['id'])
    return {'schema': 2, 'dshVersion': version, 'inputs': inputs,
            'namespaces': dict(sorted(namespaces.items())), 'messages': messages}
