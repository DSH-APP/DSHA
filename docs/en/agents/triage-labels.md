# Triage terms and label boundaries

Verification date: 2026-10-21. Source: the corresponding Chinese triage-label guide, using published F10 `087724c` as a reference baseline. This guide was first written in F11. It does not claim that the following labels exist remotely, have been used, or are authorized for modification.

The five names below explain triage intent. Before changing anything, read the target repository's existing labels and the user's requirements. Do not independently create or rename labels or perform bulk status changes.

| Name | Meaning | Exit condition |
|---|---|---|
| `needs-triage` | Scope, factual layer, or priority has not yet been established. | Obtain a reviewable trigger/scope and complete the initial classification. |
| `needs-info` | Information required to decide the issue is missing. | Obtain the specified information; continue work that can already progress independently. |
| `ready-for-agent` | Scope, authorization, inputs, and acceptable outcomes are sufficient for automated work. | Produce the change and validation, then request review or complete the authorized action. |
| `ready-for-human` | A user/maintainer decision, permission, or actual device participation is required. | State the precise blocker and needed choice. Do not replace work already possible with a vague request to confirm. |
| `wontfix` | An explicit decision places the request outside the product scope or rejects the proposed approach. | Record the reason and source of the decision. Failure to reproduce or missing information does not mean it will not be fixed. |

Labels do not replace factual classification. Fill a documentation gap with real content and links. Synchronize current guidance when the design changes. An implementation defect needs a concrete commitment and call-chain evidence. Missing validation must name the absent input or execution proof. An HMR field, old device log, or a static search that found no consumer cannot be declared successful or ineffective through a label alone.

Status does not replace execution evidence either. `ready-for-agent` is not publication or messaging authorization. `ready-for-human` does not prevent preparing reviewable work first. `wontfix` never authorizes deleting user originals. Follow the [issue workflow](issue-tracker.md) and [AGENTS](../../../AGENTS.en.md) for triage and closure.
