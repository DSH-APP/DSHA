# Domains and sources of truth for agent handoff

Verification date: 2026-10-21. Source: the corresponding Chinese agent-domain guide, published F10 `087724c` as a reference baseline, and the current working tree. This maintenance workflow was first written in F11. It is not a recovered historical `docs/agents/domain.md` original and does not reconstruct decisions from an old conversation. Start with [README](../../../README.en.md), [AGENTS](../../../AGENTS.en.md), and the [architecture handbook](../project-architecture-handbook.md).

## Identify state ownership first

| Domain | Source of truth / owner | Evidence that cannot be replaced |
|---|---|---|
| APK and fixed inputs | Gradle, locks, managed descriptors, and the actual APK | A README version string does not prove package content or signature. |
| Web execution | HarnessController and its lifecycle collaborators | A UI label saying stopped is not guest/launcher exit proof. |
| Data and plugin transactions | HostDataTransaction, ManagedRuntimeTransaction, and their journals | Directory existence and an old health receipt do not replace this operation's target/digest checks. |
| Device grants | Android, DeviceGrants, CapabilityBroker, and runtime identity | Plugin-tool approval is not system permission. |
| User and external content | User originals and fixed candidates | An archive's claim of health does not grant current execution authority; an external reference does not mean the external contents were backed up. |

The [module map](../module-map.md), [permissions architecture](../device-permissions-architecture.md), and [runtime/data guide](../runtime-data-plugin-architecture.md) provide detailed change maps. Put new responsibilities into the appropriate collaborator rather than accumulating unrelated behavior in a central controller.

## Check documentation, code, and decisions separately

The user's current decisions constrain the scope. Code describes actual behavior. Specifications and ADRs explain design boundaries. A release or device receipt proves only the source/APK and time that it names. When these disagree, record the specific call chain and resolve the conflict. Do not automatically let code override user decisions, and do not restore retired erasure or an old approval gate merely to match a historical report.

Existing decisions begin with [ADR0001](../adr/0001-build154-boundaries.md), [ADR0002](../adr/0002-current-threat-model.md), and [ADR0003](../adr/0003-virtual-screen-lifecycle-and-input.md). A new decision must explain context, consequences, and evidence. Missing old files do not authorize invented history. Create `GLOSSARY.md` only when shared terminology actually requires it. Its current absence does not block maintenance or justify an empty placeholder. Every new concrete reference must exist and be included in source distribution.

## Collaboration and verification

Divide mutually exclusive work within the user's authorization. During parallel changes, name file owners, excluded areas, the baseline, and completion evidence. After the source freezes, one owner generates managed proofs, signs/delivers, and produces the final file list. Reading another task's output does not automatically authorize messages to that user's chat, email, or external services.

Start with the smallest related test selection, then expand according to the change. Record failures, skips, platforms, and cache use. Keep source, host, browser, actual APK, and physical-device evidence separate. A static `@Test` count is not an execution count. Device actions, ADB, publication, and long-running validation follow the current authorization. Filling documentation gaps does not authorize those actions. See [build and release](../build-and-release.md) for evidence binding.
