#!/usr/bin/env python3
"""Check local documentation targets without fetching URLs or masking current broken links.

Default: maintained root/current docs/ADR/agent guides. --all also scans historical
Markdown. --paths-file checks the exact LF-separated Markdown paths in a source
stage; --root should then point at that stage. Historical non-document evidence
is reported separately, not claimed to be distributed or verified. Fragments are
not validated; this tool proves target existence, not semantic correctness.
"""
import argparse
import hashlib
import html
import json
from pathlib import Path
import re
import sys
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[1]
CURRENT = (
    "README.md", "README.en.md", "AGENTS.md", "BUILD.md", "CONTRIBUTING.md",
    "THIRD_PARTY_NOTICES.md", "docs/documentation-index.md",
    "docs/documentation-gap-matrix.md", "docs/project-architecture-handbook.md",
    "docs/device-permissions-architecture.md", "docs/runtime-data-plugin-architecture.md",
    "docs/runtime-engines.md", "docs/runtime-and-network.md", "docs/device-channels.md",
    "docs/ports-and-networking.md", "docs/dns-configuration.md", "docs/lan.md",
    "docs/virtual-screen-architecture.md", "docs/virtual-screen-guide.md",
    "docs/references-and-provenance.md", "docs/plugins.md",
    "docs/plugin-runtime-and-permissions.md", "docs/backup-format-v5.md",
    "docs/backup-and-restore.md", "docs/config-snapshots.md", "docs/configuration-recovery-host.md",
    "docs/install-and-maintenance.md", "docs/startup-and-diagnostics.md", "docs/update.md",
    "docs/emergency-recovery.md", "docs/terminal.md", "docs/picture-in-picture.md",
    "docs/i18n.md", "docs/overlay-and-notifications.md", "docs/browser-engines.md",
    "docs/bridge-and-files.md", "docs/web-file-sharing.md", "docs/security-model.md",
    "docs/security-model.en.md", "docs/engineering-standard.md", "docs/module-map.md",
    "docs/source-provenance.md", "docs/download-verification.md", "docs/download-verification.en.md",
    "docs/接手指南.md", "docs/agents/domain.md", "docs/agents/issue-tracker.md",
    "docs/agents/triage-labels.md", "docs/adr/0001-build154-boundaries.md",
    "docs/adr/0002-current-threat-model.md", "docs/adr/0003-virtual-screen-lifecycle-and-input.md",
)
CURRENT_SET = frozenset(CURRENT)
HISTORICAL_PREFIXES = ("docs/releases/", "docs/source-updates/", "docs/history/", "tools/history/", "docs/audits/")
HISTORICAL_TOP = frozenset((
    "CHANGELOG.md", "docs/android-standard.md", "docs/android-low.md", "docs/ROADMAP.md",
    "docs/adb-flow-audit.md", "docs/backup-upgrade-acceptance.md", "docs/backup-upgrade-baseline.md",
    "docs/device-acceptance-2026-09-05.md", "docs/extract-startup-speed-2026-09-10.md",
    "docs/functional-audit-rc1.4.md", "docs/issue67-recovery.md", "docs/maintenance-rc1.4-verification.md",
    "docs/optimization-backup-verification.md", "docs/optimization-implementation.md",
    "docs/optimization-runtime-verification.md", "docs/optimization-site-verification.md",
    "docs/optimization-transfer-verification.md", "docs/optimization-web-verification.md",
    "docs/pid-stop-fix-2026-09-10.md", "docs/plugin-download-npm-fix-2026-09-05.md",
    "docs/plugin-layout-install-fix-2026-09-05.md", "docs/release-rc1.2-2026-09-06.md",
    "docs/release-rc1.4-2026-09-07.md", "docs/stability-acceptance.md",
    "docs/terminal-process-reaping-handoff.md", "docs/ui-layout-plan-rc1.4.md",
    "docs/ui-layout-rc1.4-verification.md", "docs/update-flow-rc1.4-verification.md",
    "docs/upgrade-dsh-0.1.5-alpha.2.md",
))
INLINE = re.compile(r'!?\[[^\]\n]*\]\(\s*(?:<([^>\n]+)>|((?:\\.|[^\s)])+))(?:\s+["\'][^\n]*?["\'])?\s*\)')
DEFINITION = re.compile(r'^\s{0,3}\[[^\]\n]+\]:\s*(?:<([^>\n]+)>|(\S+))', re.MULTILINE)
HTML = re.compile(r'\b(?:href|src|srcset)\s*=\s*(["\'])(.*?)\1', re.IGNORECASE)
FENCE = re.compile(r'^\s{0,3}(`{3,}|~{3,})')
WINDOWS_PATH = re.compile(r'^[A-Za-z]:[/\\]')

# These are unchanged upstream documents, not promises that their repository's
# other files are bundled in DSHA. Bind every exception to exact source bytes,
# source path and literal target. Do not generalize this to vendor/history trees.
UPSTREAM_SOURCE_RELATIVE = {
    "app/src/main/assets/builtin-plugins/dsh-web-mobile/README.md": {
        "sha256": "852f84c7a58695bfca8730e003155c2a061d58e31f648e88d10e0528499b63ab",
        "targets": {
            "assets/banner.png": "https://github.com/mexiaosqwq/dsh-web-mobile/blob/10e58ce0c4ed424c7fbd9c91d48db06c6061b4e4/assets/banner.png",
            "assets/drawer.png": "https://github.com/mexiaosqwq/dsh-web-mobile/blob/10e58ce0c4ed424c7fbd9c91d48db06c6061b4e4/assets/drawer.png",
            "assets/hero.png": "https://github.com/mexiaosqwq/dsh-web-mobile/blob/10e58ce0c4ed424c7fbd9c91d48db06c6061b4e4/assets/hero.png",
            "assets/settings.png": "https://github.com/mexiaosqwq/dsh-web-mobile/blob/10e58ce0c4ed424c7fbd9c91d48db06c6061b4e4/assets/settings.png",
            "AGENTS.md": "https://github.com/mexiaosqwq/dsh-web-mobile/blob/10e58ce0c4ed424c7fbd9c91d48db06c6061b4e4/AGENTS.md",
        },
    },
    "tools/termux-jni/LICENSE.upstream.md": {
        "sha256": "a8992ad867b1b4ea442693acb2e1ee829a814758e79a099bd8243a04ae383304",
        "targets": {
            "terminal-emulator": "https://github.com/termux/termux-app/tree/6e2689f55295fa444be8ac8592c527c2c5ef3253/terminal-emulator",
            "terminal-view": "https://github.com/termux/termux-app/tree/6e2689f55295fa444be8ac8592c527c2c5ef3253/terminal-view",
            "termux-shared/LICENSE.md": "https://github.com/termux/termux-app/blob/6e2689f55295fa444be8ac8592c527c2c5ef3253/termux-shared/LICENSE.md",
        },
    },
}


def upstream_source_reference(source, source_sha256, target):
    pinned = UPSTREAM_SOURCE_RELATIVE.get(source)
    if pinned is None or pinned["sha256"] != source_sha256:
        return None
    return pinned["targets"].get(target)


def prose(text):
    """Keep line numbers while excluding fenced examples and HTML comments."""
    output, fence = [], None
    for line in text.splitlines(keepends=True):
        found = FENCE.match(line)
        if found:
            marker = found.group(1)
            if fence is None:
                fence = marker[0], len(marker)
            elif marker[0] == fence[0] and len(marker) >= fence[1]:
                fence = None
            output.append("\n" if line.endswith("\n") else "")
        else:
            output.append(("\n" if line.endswith("\n") else "") if fence else line)
    return re.sub(r'<!--[\s\S]*?-->', lambda m: "\n" * m.group(0).count("\n"), "".join(output))


def targets(text):
    clean = prose(text)
    rows = []
    for regex in (INLINE, DEFINITION):
        for match in regex.finditer(clean):
            rows.append((clean.count("\n", 0, match.start()) + 1, match.group(1) or match.group(2)))
    for match in HTML.finditer(clean):
        line = clean.count("\n", 0, match.start()) + 1
        values = match.group(2)
        if clean[match.start():match.start() + 6].lower() == "srcset":
            rows.extend((line, part.strip().split()[0]) for part in values.split(",") if part.strip())
        else:
            rows.append((line, values))
    return sorted(set(rows))


def historical(path):
    # Unknown/new pages remain current: adding a page cannot silently waive its targets.
    return path not in CURRENT_SET and (path in HISTORICAL_TOP or path.startswith(HISTORICAL_PREFIXES))


def inspect(root, paths, strict_history=False):
    root = root.resolve()
    rows, documents = [], []
    for raw in sorted(set(paths)):
        source = Path(raw.replace("\\", "/"))
        try:
            full = (root / source).resolve()
            relative = full.relative_to(root).as_posix()
        except ValueError:
            rows.append({"source": raw, "line": 0, "target": raw, "status": "error", "kind": "source-outside-root"})
            continue
        if not full.is_file():
            rows.append({"source": relative, "line": 0, "target": raw, "status": "error", "kind": "source-missing"})
            continue
        documents.append(relative)
        source_bytes = full.read_bytes()
        source_sha256 = hashlib.sha256(source_bytes).hexdigest()
        for line, target in targets(source_bytes.decode("utf-8-sig")):
            target = html.unescape(target)
            target = re.sub(r'\\([()\s])', r'\1', target)
            if not target or target.startswith("#") or target.startswith("//"):
                continue
            parsed = urlsplit(target)
            if parsed.scheme and not WINDOWS_PATH.match(target):
                continue
            local = unquote(parsed.path)
            if not local:
                continue
            old = historical(relative)
            entry = {"source": relative, "line": line, "target": target,
                     "classification": "historical" if old else "current"}
            if WINDOWS_PATH.match(local) or local.startswith("/"):
                entry.update(status="warning" if old and not strict_history else "error", kind="local-absolute-evidence")
                rows.append(entry)
                continue
            resolved = (full.parent / local).resolve()
            try:
                resolved.relative_to(root)
            except ValueError:
                entry.update(status="error", kind="target-outside-root")
                rows.append(entry)
                continue
            if resolved.exists():
                entry.update(status="ok", kind="available")
            elif upstream_source_reference(relative, source_sha256, target):
                entry.update(
                    status="error" if strict_history else "warning",
                    kind="upstream-source-relative",
                    sourceSha256=source_sha256,
                    upstreamUrl=upstream_source_reference(relative, source_sha256, target),
                    localAssetAvailable=False,
                )
            elif Path(local).suffix.lower() in (".md", ".markdown"):
                entry.update(status="error", kind="missing-document")
            elif old and not strict_history:
                entry.update(status="warning", kind="historical-unavailable-evidence")
            else:
                entry.update(status="error", kind="missing-current-target")
            rows.append(entry)
    counts = {key: sum(row["status"] == key for row in rows) for key in ("ok", "warning", "error")}
    return {"schema": 1, "root": str(root), "scope": documents,
            "fragmentValidation": False, "networkRequests": False,
            "counts": counts, "results": rows}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("paths", nargs="*", help="Markdown paths relative to --root")
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--paths-file", type=Path, help="UTF-8 LF-separated paths; # comments allowed")
    parser.add_argument("--all", action="store_true", help="include historical docs; no recursive app/build scan")
    parser.add_argument("--strict-history", action="store_true", help="also fail unavailable historical evidence and pinned upstream-relative references")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)
    paths = args.paths
    if args.paths_file:
        paths += [line.strip() for line in args.paths_file.read_text(encoding="utf-8-sig").splitlines()
                  if line.strip() and not line.lstrip().startswith("#")]
    if not paths:
        paths = list(CURRENT)
        if args.all:
            paths += [p.relative_to(args.root).as_posix() for p in (args.root / "docs").rglob("*.md")]
            paths += [p.name for p in args.root.glob("*.md")]
    result = inspect(args.root, paths, args.strict_history)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for row in result["results"]:
        if row["status"] == "error":
            print(f'{row["source"]}:{row["line"]}: {row["kind"]}: {row["target"]}')
    print(json.dumps({"documents": len(result["scope"]), **result["counts"]}, ensure_ascii=False))
    return 1 if result["counts"]["error"] else 0


if __name__ == "__main__":
    sys.exit(main())
