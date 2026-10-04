#!/usr/bin/env python3
"""Rebuild the 236-row current review; the build155 residual cache is read-only.

This is an audit-local merger, not a production/release entry point. Draft rows
retain baseline context without claiming a fresh owner review or final gate.
"""

from __future__ import annotations

import argparse
import collections
import hashlib
import json
from pathlib import Path


DISPOSITIONS = {
    "fixed_current_source",
    "verified_no_remaining_software_change",
    "required_contract",
    "device_evidence_missing",
    "external_evidence_missing",
    "duplicate",
    "not_applicable",
    "software_remaining",
}
LABELS = {
    "fixed_current_source": "本轮源码已修",
    "verified_no_remaining_software_change": "复核无剩余软件改动",
    "required_contract": "现行功能/保护契约",
    "device_evidence_missing": "缺设备证据",
    "external_evidence_missing": "缺外部证据",
    "duplicate": "重复项",
    "not_applicable": "不适用",
    "software_remaining": "仍有软件工作",
}
LANES = ("bridge", "build", "core", "data", "docs", "legacy", "plugins", "runtime", "tests", "ui")


def read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def rows(document: dict, path: Path) -> list[dict]:
    value = document.get("items")
    if not isinstance(value, list):
        raise ValueError(f"{path}: expected an items array")
    if not all(isinstance(item, dict) for item in value):
        raise ValueError(f"{path}: each item must be an object")
    return value


def keyed(items: list[dict], label: str) -> dict[str, dict]:
    result = {}
    for item in items:
        identity = item.get("id")
        if not isinstance(identity, str) or not identity.startswith("D-"):
            raise ValueError(f"{label}: invalid audit ID {identity!r}")
        if identity in result:
            raise ValueError(f"{label}: duplicate audit ID {identity}")
        result[identity] = item
    return result


def baseline_disposition(item: dict) -> str:
    status = item["status"]
    if item.get("duplicate_of") or status == "duplicate":
        return "duplicate"
    if status.startswith("accepted_"):
        return "required_contract"
    if status == "requires_device_evidence":
        return "device_evidence_missing"
    if status in {"legal_or_origin_unknown", "historical_record_error_unverifiable"}:
        return "external_evidence_missing"
    if status == "not_applicable":
        return "not_applicable"
    # A baseline's cached partial/residual list is not a fresh software finding.
    return "verified_no_remaining_software_change"


def fingerprint(path: Path, repository: Path) -> dict:
    return {"path": path.relative_to(repository).as_posix(), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}


def evidence_kinds(row: dict) -> set[str]:
    kinds = set()
    for evidence in row.get("missingEvidence", []):
        if isinstance(evidence, dict) and evidence.get("kind") in {"device", "external"}:
            kinds.add(evidence["kind"])
    if row["currentDisposition"] == "device_evidence_missing":
        kinds.add("device")
    if row["currentDisposition"] == "external_evidence_missing":
        kinds.add("external")
    return kinds


def merge(repository: Path, require_all: bool) -> dict:
    audit = repository / "docs/audits/build156"
    baseline_path = repository / "docs/audits/build155/execution-ledger-final.json"
    baseline = read_json(baseline_path)
    baseline_rows = rows(baseline, baseline_path)
    source = keyed(baseline_rows, "build155")
    if len(source) != 236:
        raise ValueError(f"build155: expected 236 exact IDs, got {len(source)}")
    owners, assignments, reports = {}, {}, {}
    inputs = [fingerprint(baseline_path, repository)]
    pending_lanes = []
    for lane in LANES:
        assignment_path = audit / f"assignment-{lane}.json"
        assignment = read_json(assignment_path)
        assigned = keyed([item["source"] for item in rows(assignment, assignment_path)], str(assignment_path))
        assignments[lane] = assigned
        inputs.append(fingerprint(assignment_path, repository))
        for identity in assigned:
            if identity in owners:
                raise ValueError(f"assignment: {identity} belongs to both {owners[identity]} and {lane}")
            owners[identity] = lane
        report_path = audit / f"review-{lane}.json"
        if not report_path.exists():
            pending_lanes.append(lane)
            continue
        report = read_json(report_path)
        reviewed = keyed(rows(report, report_path), str(report_path))
        if reviewed.keys() != assigned.keys():
            missing = sorted(assigned.keys() - reviewed.keys())
            extra = sorted(reviewed.keys() - assigned.keys())
            raise ValueError(f"{report_path}: missing={missing}; extra={extra}")
        for identity, row in reviewed.items():
            if row.get("currentDisposition") not in DISPOSITIONS:
                raise ValueError(f"{report_path}: {identity} has a noncanonical currentDisposition")
            if not isinstance(row.get("files", []), list) or not isinstance(row.get("checks", []), list):
                raise ValueError(f"{report_path}: {identity} files/checks must be arrays")
            if not isinstance(row.get("softwareChanges", []), list) or not isinstance(row.get("missingEvidence", []), list):
                raise ValueError(f"{report_path}: {identity} softwareChanges/missingEvidence must be separate arrays")
            if row.get("duplicate_of") and row["duplicate_of"] not in source:
                raise ValueError(f"{report_path}: {identity} refers to an unknown duplicate")
        reports[lane] = reviewed
        inputs.append(fingerprint(report_path, repository))
    if owners.keys() != source.keys():
        raise ValueError(f"assignment coverage differs: missing={sorted(source.keys()-owners.keys())}; extra={sorted(owners.keys()-source.keys())}")
    if require_all and pending_lanes:
        raise ValueError("fresh owner reports missing: " + ", ".join(pending_lanes))
    current = []
    for old in baseline_rows:
        identity, lane = old["id"], owners[old["id"]]
        fresh = reports.get(lane, {}).get(identity)
        row = {
            "id": identity,
            "title": assignments[lane][identity]["title"],
            "owner": lane,
            "baselineStatus": old["status"],
            "baselineResidual": old.get("residual", []),
            "baselineDisposition": baseline_disposition(old),
            "currentDisposition": fresh["currentDisposition"] if fresh else baseline_disposition(old),
            "reviewPending": fresh is None,
            "duplicate_of": (fresh or old).get("duplicate_of", old.get("duplicate_of", "")),
            "files": fresh.get("files", []) if fresh else [],
            "checks": fresh.get("checks", []) if fresh else [],
            "softwareChanges": fresh.get("softwareChanges", []) if fresh else [],
            "missingEvidence": fresh.get("missingEvidence", []) if fresh else [],
            "residual": fresh.get("residual", []) if fresh else [],
            "changed": bool(fresh.get("changed", False)) if fresh else False,
        }
        if fresh:
            for optional in (
                "reason", "findings", "evidenceReferences", "deferredIntegratedChecks", "verificationLimits"
            ):
                if optional in fresh:
                    row[optional] = fresh[optional]
        current.append(row)
    checked = [row for row in current if not row["reviewPending"]]
    return {
        "schemaVersion": 1,
        "phase": "final_owner_review" if not pending_lanes else "draft_owner_review_incomplete",
        "sourceCoverage": 236,
        "reviewCoverage": len(checked),
        "ownerCounts": {lane: len(assignments[lane]) for lane in LANES},
        "pendingOwnerLanes": pending_lanes,
        "reviewPendingIds": [row["id"] for row in current if row["reviewPending"]],
        "statusCounts": dict(collections.Counter(row["currentDisposition"] for row in checked)),
        "remainingSoftwareIds": [row["id"] for row in checked if row["currentDisposition"] == "software_remaining"],
        "deviceEvidenceIds": [row["id"] for row in checked if "device" in evidence_kinds(row)],
        "externalEvidenceIds": [row["id"] for row in checked if "external" in evidence_kinds(row)],
        "requiredContractIds": [row["id"] for row in checked if row["currentDisposition"] == "required_contract"],
        "duplicateIds": [row["id"] for row in checked if row["currentDisposition"] == "duplicate"],
        "ignoredBaselineResidualActionIds": baseline.get("residualActionIds", []),
        "interpretation": [
            "Only fresh exact-owner review rows contribute to current remaining/evidence caches.",
            "Pending rows contain provisional build155 context, never a current verification claim.",
            "Required compatibility, integrity and user-authorized open policy are contracts, not unfixed bugs.",
            "Software changes and missing device/external evidence are separate and may coexist.",
            "The user subsequently authorized physical phone acceptance. Earlier owner no-device statements retain their historical scope; current device acceptance comes only from final APK/source-bound receipts and is not inferred by this merger.",
            "This merger does not certify APK gates, physical installation, legal origin, persistent-disk power loss or deployment.",
        ],
        "inputFingerprints": inputs,
        "items": current,
    }


def markdown(document: dict) -> str:
    def safe(value):
        return str(value).replace("|", "\\|").replace("\n", " ")

    text = [
        "# build156：236 项当前处置矩阵",
        "",
        f"原始项 **236**；本轮 owner 回执 **{document['reviewCoverage']}/236**。此表由 `merge-ledger.py` 从十份分配和当前回执重建，未复核行仅保留 155 背景，不算本轮完成。",
        "",
        "软件改动与缺失证据分别列出。历史读取、未知退出屏障、原件保护、完整性复核、开放正式插件与终端属于现行契约，不算未修 bug。旧 `residualActionIds` 不参与新缓存。",
        "",
        "| 当前集合（只计收到回执的行） | 数量 | ID |",
        "|---|---:|---|",
    ]
    for key, label in (("remainingSoftwareIds", "仍需软件修复"), ("deviceEvidenceIds", "缺设备证据"), ("externalEvidenceIds", "缺外部证据"), ("requiredContractIds", "须保留的契约"), ("duplicateIds", "重复项"), ("reviewPendingIds", "待本轮 owner 回执")):
        identities = document[key]
        text.append(f"| {label} | {len(identities)} | {', '.join(identities) or '—'} |")
    text += ["", "用户已追加真机验收授权。设备与正式交付的实际通过范围以最终绑定 APK/源码与真实检查的回执为准；本矩阵不提前宣称验收 PASS，也不把 owner 较早的“未操作手机”记录当作当前授权限制。单设备不等于完整设备矩阵。本轮未部署网站、未发布 GitHub。Linux tmpfs 行为不能证明持久磁盘、Android FUSE 或掉电恢复。来源授权和旧 63 位摘要没有可信材料时保持未知。", "", "| ID | owner | 当前分类 | 软件改动 / 当前残余或缺失证据 |", "|---|---|---|---|"]
    for row in document["items"]:
        label = "待回执（155背景：" + LABELS[row["currentDisposition"]] + "）" if row["reviewPending"] else LABELS[row["currentDisposition"]]
        notes = list(row["softwareChanges"]) + list(row["residual"])
        notes += [value.get("detail", str(value)) if isinstance(value, dict) else value for value in row["missingEvidence"]]
        if row["duplicate_of"]:
            notes.append("归并 " + row["duplicate_of"])
        text.append(f"| {row['id']} | {row['owner']} | {label} | {safe('; '.join(map(str, notes)) or row['title'])} |")
    return "\n".join(text) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--require-all", action="store_true", help="reject drafts without all ten complete current reports")
    parser.add_argument("--check", action="store_true", help="validate without writing")
    arguments = parser.parse_args()
    repository = Path(__file__).resolve().parents[3]
    document = merge(repository, arguments.require_all)
    if not arguments.check:
        target = Path(__file__).parent
        (target / "classification-draft.json").write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
        (target / "剩余现状.md").write_text(markdown(document), encoding="utf-8", newline="\n")
    print(f"236 exact IDs; owner review {document['reviewCoverage']}/236; software remaining {len(document['remainingSoftwareIds'])}; pending lanes {','.join(document['pendingOwnerLanes']) or 'none'}")


if __name__ == "__main__":
    main()
