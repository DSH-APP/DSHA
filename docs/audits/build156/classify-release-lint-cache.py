#!/usr/bin/env python3
"""Classify an explicitly verified Gradle analysis/report-cache contract.

This does not change the strict timestamp path in classify-release-lint.py.
It requires two completed manifests, exact source snapshots, only the authorized
DeviceGrants semantic delta, actual analysis task execution, and every old/current
XML location's unchanged source excerpt before accepting cached report bytes.
"""
import argparse
import collections
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
AUDIT = Path(__file__).resolve().parent
DEVICE = 'app/src/main/java/com/deepseekharness/app/ui/DeviceGrantsFragment.java'


def require(value, message):
    if not value:
        raise RuntimeError(message)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def ref(path):
    return {'path': path.relative_to(ROOT).as_posix(), 'sha256': sha(path)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', required=True)
    parser.add_argument('--manifest-sha256', required=True)
    parser.add_argument('--snapshot-sha256', required=True)
    parser.add_argument('--prior-classification', type=Path, required=True)
    args = parser.parse_args()
    require(re.fullmatch(r'[a-f0-9-]{36}', args.run), 'INVALID_RUN')
    run = ROOT / 'app/build/stability-acceptance' / args.run
    manifest_path = run / 'manifest.json'
    require(sha(manifest_path) == args.manifest_sha256, 'MANIFEST_SHA_MISMATCH')
    manifest = read(manifest_path)
    require(manifest['status'] == 'PASS_WITH_EXPLICIT_DEVICE_GAPS', 'CURRENT_SOFTWARE_INCOMPLETE')
    snapshot_path = Path(manifest['sourceSnapshot']['path'])
    require(snapshot_path.resolve().is_relative_to(run.resolve()), 'SNAPSHOT_OUTSIDE_RUN')
    require(sha(snapshot_path) == args.snapshot_sha256 == manifest['sourceSnapshot']['sha256'],
            'CURRENT_SNAPSHOT_SHA_MISMATCH')
    snapshot = read(snapshot_path)
    for name, expected in snapshot.items():
        require(sha(ROOT / name) == expected, 'CURRENT_SOURCE_DRIFT:' + name)

    prior_path = (ROOT / args.prior_classification).resolve()
    require(prior_path.is_relative_to((ROOT / 'app/build/audit-build156/history').resolve()),
            'PRIOR_CLASSIFICATION_NOT_PRESERVED_HISTORY')
    prior = read(prior_path)
    prior_run = ROOT / 'app/build/stability-acceptance' / prior['run']
    prior_manifest_path = prior_run / 'manifest.json'
    prior_manifest = read(prior_manifest_path)
    require(prior_manifest['status'] == 'PASS_WITH_EXPLICIT_DEVICE_GAPS', 'PRIOR_SOFTWARE_INCOMPLETE')
    prior_snapshot_path = Path(prior_manifest['sourceSnapshot']['path'])
    require(sha(prior_snapshot_path) == prior_manifest['sourceSnapshot']['sha256'], 'PRIOR_SNAPSHOT_SHA')
    prior_snapshot = read(prior_snapshot_path)
    require(set(prior_snapshot) == set(snapshot), 'SOURCE_INVENTORY_CHANGED')
    changed = [name for name in snapshot if snapshot[name] != prior_snapshot[name]]
    require(changed == [DEVICE], 'UNAUTHORIZED_CACHED_REPORT_SOURCE_DELTA:' + repr(changed))
    fix_path = AUDIT / 'bridge-a11y-bound-status-fix.json'
    fix = read(fix_path)
    require(fix['sourceFrozen'] is True and fix['file'] == DEVICE
            and fix['beforeSha256'] == prior_snapshot[DEVICE]
            and fix['afterSha256'] == snapshot[DEVICE], 'SEMANTIC_FIX_PROOF_MISMATCH')
    device_lines = (ROOT / DEVICE).read_text(encoding='utf-8').splitlines()
    method_start = next(n + 1 for n, line in enumerate(device_lines)
                        if 'private void refreshA11yStatus(' in line)

    command = next(c for c in manifest['commands'] if c['name'] == 'gradle-verification')
    log_path = Path(command['log'])
    require(command['exitCode'] == 0 and sha(log_path) == command['sha256'], 'GRADLE_NOT_ACTUAL_SUCCESS')
    # Windows Gradle/child-tool diagnostics may mix legacy encodings. Exact
    # ASCII task lines are compared as bytes; the entire original log SHA is
    # already required, so no lossy decoding substitutes for execution proof.
    log = log_path.read_bytes()
    task_lines = {line.strip() for line in log.splitlines()}
    tasks = []
    for flavor in ('Standard', 'Low'):
        analyze = '> Task :app:lintAnalyze' + flavor + 'Release'
        report = '> Task :app:lintReport' + flavor + 'Release UP-TO-DATE'
        entry = '> Task :app:lint' + flavor + 'Release'
        require(analyze.encode('ascii') in task_lines and report.encode('ascii') in task_lines
                and entry.encode('ascii') in task_lines,
                'ANALYZE_EXECUTION_REPORT_CACHE_CONTRACT_MISSING:' + flavor)
        tasks.extend([analyze, report, entry])
    require(b'BUILD SUCCESSFUL' in log, 'GRADLE_SUCCESS_LINE_MISSING')

    spec = importlib.util.spec_from_file_location('lint_classifier', AUDIT / 'classify-release-lint.py')
    classifier = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(classifier)
    floor, floor_ns = classifier.source_floor()
    reports, mapping = [], []
    for flavor in ('standard', 'low'):
        path = ROOT / ('app/build/reports/lint-results-' + flavor + 'Release.xml')
        old = next(report for report in prior['reports'] if report['flavor'] == flavor)
        require(sha(path) == old['sha256'], 'REPORT_CACHE_BYTES_CHANGED:' + flavor)
        xml = ET.parse(path).getroot()
        issues = xml.findall('issue')
        require(len(issues) == len(old['issues']), 'CACHED_ISSUE_COUNT_CHANGED')
        classified = []
        for index, (item, before) in enumerate(zip(issues, old['issues'])):
            row = {'issueIndex': index, 'id': item.get('id'), 'severity': item.get('severity'),
                   'message': item.get('message'),
                   'locations': [dict(loc.attrib) for loc in item.findall('location')]}
            require(all(before[key] == value for key, value in row.items()), 'CACHED_ISSUE_IDENTITY_DRIFT')
            evidence = [classifier.source_evidence(loc) for loc in row['locations']]
            require(len(evidence) == len(before['sourceEvidence']), 'SOURCE_LOCATION_COUNT_DRIFT')
            for current, previous in zip(evidence, before['sourceEvidence']):
                keys = ('file', 'line', 'xmlLocation', 'available', 'excerpt', 'objectType', 'members', 'byteLength')
                require(all(current.get(key) == previous.get(key) for key in keys),
                        'CURRENT_WARNED_SOURCE_LOCATION_DRIFT:' + str(current.get('file')))
                if current.get('file') == DEVICE:
                    require(current['line'] < method_start, 'CHANGED_METHOD_OVERLAPS_WARNING')
                else:
                    require(current.get('sha256') == previous.get('sha256'), 'OTHER_WARNED_SOURCE_SHA_CHANGED')
                mapping.append({'flavor': flavor, 'issueIndex': index,
                                'file': current.get('file'), 'line': current.get('line'),
                                'locationAndExcerptUnchanged': True,
                                'currentSourceSha256': current.get('sha256'),
                                'priorSourceSha256': previous.get('sha256')})
            row.update(classifier.classify(row, evidence, flavor))
            row['sourceEvidence'] = evidence
            classified.append(row)
        reports.append({'flavor': flavor, 'path': path.relative_to(ROOT).as_posix(), 'sha256': sha(path),
                        'mtimeUtc': classifier.stamp(path.stat().st_mtime),
                        'freshRelativeToProductionInputs': path.stat().st_mtime_ns >= floor_ns,
                        'cacheReused': True, 'timestampsFresh': False,
                        'lintAttributes': dict(xml.attrib), 'issues': classified})

    summary = {report['flavor']: {'warnings': sum(row['severity'] == 'Warning' for row in report['issues']),
                                 'errors': sum(row['severity'] == 'Error' for row in report['issues']),
                                 'fatal': sum(row['severity'] == 'Fatal' for row in report['issues']),
                                 'allIssues': len(report['issues']),
                                 'classificationCounts': dict(collections.Counter(row['classification'] for row in report['issues']))}
               for report in reports}
    contract = {'schema': 1, 'status': 'PASS_VERIFIED_ANALYSIS_REPORT_CACHE', 'run': args.run,
                'atUtc': datetime.now(timezone.utc).isoformat(), 'cacheReused': True, 'timestampsFresh': False,
                'manifest': ref(manifest_path), 'sourceSnapshot': ref(snapshot_path),
                'allCurrentSourceHashesVerified': len(snapshot), 'sourceDelta': changed,
                'fixEvidence': ref(fix_path), 'changedMethodStartLine': method_start,
                'priorClassification': ref(prior_path), 'priorSourceSnapshot': ref(prior_snapshot_path),
                'gradle': {**ref(log_path), 'exitCode': 0, 'actualTaskLines': tasks},
                'sourceFloor': {'path': floor.relative_to(ROOT).as_posix(), 'mtimeUtc': classifier.stamp(floor_ns / 1e9)},
                'issueRowsVerified': sum(len(report['issues']) for report in reports),
                'allLocationMappingsVerified': len(mapping), 'locationMappings': mapping,
                'scope': 'Actual completed lintAnalyze and cached lintReport, identical XML bytes, current complete source snapshot, and every source location/excerpt verified unchanged. No timestamp changed and no general timestamp waiver.'}
    proof_path = AUDIT / 'release-lint-analysis-cache-contract.json'
    proof_path.write_text(json.dumps(contract, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    document = {'schema': 1, 'status': 'CLASSIFIED_CURRENT', 'build': 156, 'indexBase': 0,
                'run': args.run, 'cacheReused': True, 'timestampsFresh': False,
                'cacheContract': ref(proof_path), 'sourceFloor': contract['sourceFloor'],
                'reports': reports, 'summary': summary,
                'softwareActionRequired': [{'flavor': report['flavor'], 'issueIndex': row['issueIndex'],
                                            'id': row['id'], 'message': row['message']}
                                           for report in reports for row in report['issues'] if row['softwareActionRequired']],
                'unknownReviewRequired': [{'flavor': report['flavor'], 'issueIndex': row['issueIndex'],
                                          'id': row['id'], 'message': row['message']}
                                         for report in reports for row in report['issues'] if row['confidence'] == 'uncertain'],
                'scope': 'Current source classifications bound to verified completed analysis and explicitly reused XML. XML timestamps are older than the semantic fix; no fresh-XML claim, timestamp alteration, device coverage or source suppression.'}
    require(len(document['unknownReviewRequired']) == 5
            and all(row['id'] == 'Autofill' for row in document['unknownReviewRequired']), 'UNKNOWN_SCOPE_REVIEW_REQUIRED')
    (AUDIT / 'release-lint-classification.json').write_text(json.dumps(document, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    print('PASS verified report cache: actual analysis, all' + str(len(snapshot)) + ' source hashes, '
          + str(contract['issueRowsVerified']) + ' exact XML rows / ' + str(len(mapping))
          + ' unchanged source locations; cacheReused=true timestampsFresh=false; unknown5 retained')


if __name__ == '__main__':
    main()
