"""One validated catalogue contract for generated Java and Android resources."""
import json
import re
from pathlib import Path

LANGUAGES = ('zh', 'zh-Hant', 'en', 'ru', 'ja', 'ko', 'ar', 'pt', 'la', 'fr')
RESOURCE_FOLDERS = {
    'zh': 'values', 'zh-Hant': 'values-b+zh+Hant', 'en': 'values-en',
    **{tag: 'values-' + tag for tag in LANGUAGES if tag not in ('zh', 'zh-Hant', 'en')},
}


def format_signature(value):
    """The bounded UiMessageFormat grammar; parameters cannot be added or reordered."""
    signature = []
    index = 0
    while index < len(value):
        if value[index] != '%':
            index += 1
            continue
        index += 1
        if index >= len(value) or value[index] not in 'sd%':
            raise ValueError('UI_FORMAT_UNSUPPORTED: ' + value)
        if value[index] != '%':
            signature.append(value[index])
        index += 1
    return signature


def android_format_signature(value):
    """Indexed Android strings may reorder arguments, while retaining each index and type."""
    result = []
    index = 0
    sequential = 0
    while index < len(value):
        if value[index] != '%':
            index += 1
            continue
        token = re.match(r'%(?:(\d+)\$)?([sd%])', value[index:])
        if token is None or token[2] == '%' and token[1]:
            raise ValueError('UI_ANDROID_FORMAT_UNSUPPORTED: ' + value)
        if token[2] != '%':
            sequential += 1
            argument = int(token[1]) if token[1] else sequential
            if argument < 1:
                raise ValueError('UI_ANDROID_FORMAT_ARGUMENT_INDEX: ' + value)
            result.append((argument, token[2]))
        index += len(token[0])
    return sorted(result)


def load_catalog(root):
    """Require every supported catalogue; missing translations must fail before packaging."""
    root = Path(root)
    messages = json.loads((root / 'tools/i18n/messages.json').read_text(encoding='utf-8'))
    extras_path = root / 'tools/i18n/messages-extra.json'
    extras = json.loads(extras_path.read_text(encoding='utf-8')) if extras_path.exists() else []
    if not isinstance(extras, list):
        raise ValueError('UI_EXTRA_CATALOG_NOT_ARRAY')
    messages += extras
    ids = {item['id'] for item in messages}
    if len(ids) != len(messages):
        raise ValueError('UI_DUPLICATE_MESSAGE_ID')
    if len({item['zh'] for item in messages}) != len(messages):
        raise ValueError('UI_DUPLICATE_MESSAGE_SOURCE')
    catalogs = {tag: {item['id']: item[tag] for item in messages} for tag in ('zh', 'en')}
    for tag in LANGUAGES:
        if tag in catalogs:
            continue
        path = root / 'tools/i18n/translations' / (tag + '.json')
        data = json.loads(path.read_text(encoding='utf-8'))
        extra_path = root / 'tools/i18n/translations-extra' / (tag + '.json')
        if extra_path.exists():
            extra_data = json.loads(extra_path.read_text(encoding='utf-8'))
            if not isinstance(data, dict) or not isinstance(extra_data, dict) or set(data) & set(extra_data):
                raise ValueError('UI_EXTRA_CATALOG_DUPLICATE: ' + tag)
            data.update(extra_data)
        if not isinstance(data, dict) or set(data) != ids:
            missing = sorted(ids - set(data)) if isinstance(data, dict) else sorted(ids)
            extra = sorted(set(data) - ids) if isinstance(data, dict) else []
            raise ValueError(f'UI_CATALOG_IDS: {tag}: missing={missing[:8]}, extra={extra[:8]}')
        catalogs[tag] = data
    for tag, data in catalogs.items():
        for item in messages:
            translated = data[item['id']]
            if not isinstance(translated, str) or not translated.strip():
                raise ValueError('UI_TRANSLATION_EMPTY: ' + tag + ':' + item['id'])
            if '\x00' in translated:
                raise ValueError('UI_TRANSLATION_NUL: ' + tag + ':' + item['id'])
            if item.get('uiFormat') and format_signature(item['zh']) != format_signature(translated):
                raise ValueError('UI_FORMAT_ARGUMENT_MISMATCH: ' + tag + ':' + item['id'])
            if item.get('androidFormat') and android_format_signature(item['zh']) != android_format_signature(translated):
                raise ValueError('UI_ANDROID_FORMAT_ARGUMENT_MISMATCH: ' + tag + ':' + item['id'])
    return messages, catalogs
