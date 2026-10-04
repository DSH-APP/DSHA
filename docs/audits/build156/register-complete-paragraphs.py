#!/usr/bin/env python3
"""Register reviewed paragraph joins, separately from the one protected debt row."""
from pathlib import Path
import collections
import hashlib
import importlib.util
import json

ROOT = Path(__file__).resolve().parents[3]
AUDIT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('ui_checker', ROOT / 'tools/check-ui-i18n.py')
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)
before = json.loads((AUDIT / 'choose-concat-classification-before.json').read_text(encoding='utf-8'))
reviewed = [item for item in before['items'] if item['classification'] == 'independent_complete_paragraph_join']
if len(reviewed) != 9:
    raise RuntimeError('EXPECTED_NINE_REVIEWED_PARAGRAPH_JOINS')
rows, _ = checker.inventory(ROOT)
actual = collections.defaultdict(list)
for row in rows:
    actual[(row['kind'], row['file'], row['signature'])].append(row)

declarations, absorbed = [], []
for reviewed_row in reviewed:
    key = tuple(reviewed_row[name] for name in ('kind', 'file', 'signature'))
    matches = actual.get(key, [])
    path = ROOT / reviewed_row['file']
    source = path.read_text(encoding='utf-8')
    name = path.name
    if name == 'ExtractActivity.java':
        if matches or '未完成的操作：%s\\n最后记录阶段：%s' not in source:
            raise RuntimeError('EXTRACT_COMPLETE_FOOTER_NOT_ABSORBED')
        absorbed.append({'file': reviewed_row['file'], 'oldSignature': reviewed_row['signature'],
                         'sourceSha256': hashlib.sha256(path.read_bytes()).hexdigest(),
                         'reason': 'The complete footer is now inside the single registered maintenance body template, so its separate choose concatenation no longer exists.'})
        continue
    if len(matches) != 1:
        raise RuntimeError('PARAGRAPH_SIGNATURE_OR_OCCURRENCE_CHANGED: ' + str(key))
    if name == 'BackupTask.java':
        if '\\n已安全清理 %s 项 · %s' not in source:
            raise RuntimeError('PROGRESS_COUNT_IS_NOT_COMPLETE_TEMPLATE')
        reason = ('A complete translated stage sentence is followed by an independent newline-prefixed '
                  'progress line. The count line now uses the registered full template with raw entries '
                  'and formatted bytes; the six stage sentences do not embed label fragments.')
    elif name == 'EnvironmentMaintenance.java':
        if not all(template in source for template in ('旧环境清理暂缓，原件保留：%s', '旧事务归档暂缓，原件保留：%s')):
            raise RuntimeError('CLEANUP_LABELS_NOT_COMPLETE_TEMPLATES')
        reason = ('The complete translated rebuild-result sentence ends with a newline. The separately '
                  'assembled cleanup outcome is either its own complete static result or registered complete '
                  'error templates preserving raw error arguments. Joining these paragraphs is intentional.')
    elif name == 'RecoveryController.java':
        if not all(sentence in source for sentence in (
                ' 尚未配置模型凭据，可返回原生应急页面填写本次临时密钥。',
                ' 原生凭据暂不可读取，已保留原密文；可填写本次临时密钥。')):
            raise RuntimeError('CREDENTIAL_NOTICE_NOT_COMPLETE_SENTENCES')
        reason = ('The complete recovery-ready sentence is followed by an optional independently translated '
                  'credential notice. Each notice is a complete sentence with leading whitespace; no user '
                  'credential or incomplete label is concatenated here.')
    else:
        raise RuntimeError('UNREVIEWED_PARAGRAPH_FILE: ' + name)
    row = matches[0]
    declarations.append({**{name: row[name] for name in ('kind', 'file', 'signature', 'sourceSha256')},
                         'occurrences': 1, 'classification': 'independent_complete_paragraph_join',
                         'reason': reason,
                         'evidence': 'docs/audits/build156/choose-complete-paragraph-evidence.json'})
if len(declarations) != 8 or len(absorbed) != 1:
    raise RuntimeError('COMPLETE_PARAGRAPH_REVIEW_COUNT_MISMATCH')

baseline_path = ROOT / 'tools/i18n/ui-source-baseline.json'
baseline_before = baseline_path.read_bytes()
baseline = json.loads(baseline_before)
if len(baseline['findings']) != 1 or not baseline['findings'][0]['file'].endswith('/ShellService.java'):
    raise RuntimeError('PROTECTED_SOURCE_DEBT_WAS_CHANGED')
baseline['policy'] = ('Reject every new signature or increased occurrence. All product-owned label and '
                      'sentence fragments are repaired. The one protected external-source finding remains '
                      'a separate debt row. Individually reviewed complete-paragraph joins in nonFindings '
                      'require exact source SHA, signature and occurrence count; they cannot offset debt.')
baseline['nonFindings'] = declarations
payload = (json.dumps(baseline, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
baseline_path.write_bytes(payload)

evidence_rows = []
for declaration in declarations:
    path = ROOT / declaration['file']
    lines = path.read_text(encoding='utf-8').splitlines()
    row = actual[(declaration['kind'], declaration['file'], declaration['signature'])][0]
    evidence_rows.append({**declaration, 'line': row['line'],
                          'excerpt': '\n'.join(f'{number+1}: {lines[number]}' for number in
                                                range(max(0, row['line']-8), min(len(lines), row['line']+12)))})
receipt = {'schema': 1, 'status': 'REVIEWED_EXACT_COMPLETE_PARAGRAPHS',
           'reviewedBeforeCount': 9, 'activeExplicitNonFindings': 8, 'absorbedIntoWholeTemplate': absorbed,
           'baselineBeforeSha256': hashlib.sha256(baseline_before).hexdigest(),
           'baselineSha256': hashlib.sha256(payload).hexdigest(), 'protectedDebtCount': 1,
           'items': evidence_rows}
(AUDIT / 'choose-complete-paragraph-evidence.json').write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print('PASS: 8 exact complete-paragraph joins, 1 absorbed into whole Extract body; protected debt stays 1')
