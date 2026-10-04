#!/usr/bin/env python3
"""Merge only the six explicitly authorized final choose-template contributions."""
from pathlib import Path
import hashlib
import importlib.util
import json
import sys

ROOT = Path(__file__).resolve().parents[3]
AUDIT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / 'tools'))
spec = importlib.util.spec_from_file_location('ui_generator', ROOT / 'tools/prepare-ui-languages.py')
generator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(generator)
catalog = ROOT / 'tools/i18n/messages.json'
before = catalog.read_bytes()
messages = json.loads(before)
known = {item['zh']: item for item in messages}
sources, added, conflicts = [], [], []
canonical_overrides = []
for lane in ('ui', 'core', 'data', 'runtime', 'legacy', 'root'):
    path = AUDIT / ('contribution-choose-' + lane + '.json')
    entries = json.loads(path.read_text(encoding='utf-8'))
    if not isinstance(entries, list):
        raise RuntimeError('CHOOSE_CONTRIBUTION_MUST_BE_ARRAY: ' + lane)
    sources.append({'path': path.relative_to(ROOT).as_posix(),
                    'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'entries': len(entries)})
    for item in entries:
        if (item.get('uiFormat') is not True or not item.get('files') or not item['en']
                or generator.format_signature(item['zh']) != generator.format_signature(item['en'])):
            raise RuntimeError('CHOOSE_TEMPLATE_CONTRACT: ' + item['zh'])
        if item['zh'] == '%s 项 · %s':
            # Root-approved normalization after the full raw-newline regression
            # exposed overlap with the existing multi-line summary template.
            if item['en'] not in {'%s items · %s', '%s entries · %s'}:
                raise RuntimeError('UNREVIEWED_COUNT_TEMPLATE_WORDING')
            if item['en'] != '%s entries · %s':
                canonical_overrides.append({'zh': item['zh'], 'proposal': item['en'],
                                             'canonical': '%s entries · %s',
                                             'reason': 'Keep the established entries wording shared by the overlapping complete multi-line summary; first failing raw-newline regression is preserved in ui-lint-template-proof.'})
                item = dict(item, en='%s entries · %s')
            if item['zh'] in known and known[item['zh']]['en'] == '%s items · %s':
                if not known[item['zh']]['id'].startswith('build156_choose_'):
                    raise RuntimeError('COUNT_TEMPLATE_IS_NOT_THIS_AUTHORIZED_NEW_ENTRY')
                known[item['zh']]['en'] = '%s entries · %s'
        if item['zh'] in known:
            existing = known[item['zh']]
            if existing['en'] != item['en']:
                conflicts.append({'zh': item['zh'], 'canonical': existing['en'], 'proposal': item['en'],
                                  'decision': 'Retain previously established canonical English'})
            existing['uiFormat'] = True
            existing['files'] = sorted(set(existing.get('files', []) + item['files']))
        else:
            entry = dict(item, id='build156_choose_' + hashlib.sha256(item['zh'].encode()).hexdigest()[:12])
            known[entry['zh']] = entry
            messages.append(entry)
            added.append(entry['zh'])
if len({item['id'] for item in messages}) != len(messages) or len(known) != len(messages):
    raise RuntimeError('CHOOSE_CATALOG_ID_OR_ZH_DUPLICATE')
for item in messages:
    if item.get('uiFormat') and generator.format_signature(item['zh']) != generator.format_signature(item['en']):
        raise RuntimeError('UI_FORMAT_ARGUMENT_MISMATCH: ' + item['id'])
payload = (json.dumps(messages, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
if payload != before:
    catalog.write_bytes(payload)
receipt = {'schema': 1, 'status': 'MERGED_AUTHORIZED_CHOOSE_TEMPLATES',
           'catalog': catalog.relative_to(ROOT).as_posix(),
           'beforeSha256': hashlib.sha256(before).hexdigest(),
           'sha256': hashlib.sha256(payload).hexdigest(), 'sources': sources,
           'addedTemplates': len(added), 'addedChineseKeys': added, 'conflicts': conflicts,
           'authorizedContributionTemplates': sum(source['entries'] for source in sources),
           'canonicalOverrides': canonical_overrides,
           'entries': len(messages), 'formats': sum(bool(item.get('uiFormat')) for item in messages),
           'scope': 'Six authorized choose-template files only; existing canonical translations retained.'}
(AUDIT / 'choose-catalog-merge.json').write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({key: value for key, value in receipt.items() if key not in {'addedChineseKeys', 'sources'}}, ensure_ascii=True))
