# Floating status, request confirmation, and task notifications

Verification date: 2026-10-21. Source: the corresponding Chinese maintenance guide and current working-tree implementation, using published F10 `087724c` as a reference baseline. This page distinguishes three kinds of UI and adds no system permission or device validation.

## Streaming floating status

**Settings → Floating status** is edited by [OverlayFragment](../../app/src/main/java/com/deepseekharness/app/ui/OverlayFragment.java). Controls first update a draft. **Save style** writes the real preferences. **Preview floating status** can temporarily show the actual system overlay; leaving the page removes the preview. Enabling it requires system overlay permission. The page opens system settings and rechecks on return. System permission, the app toggle, and successful window creation are separate outcomes.

| Item | Default / range | Effective behavior and boundary |
|---|---|---|
| Show floating status | Off | Saving enables it. Turning it off removes the window; runtime events also recheck the toggle and permission. |
| Lines / font | 3 lines / 12sp | 1–6 lines; saved changes refresh the style. |
| Hold time / opacity | 6 seconds / 85% | Hold time2–60 seconds; opacity20–100%. |
| Show reasoning | Off | Controls overlay display only; it does not change model configuration or message data. |
| Show executed commands | On | Command text may be visible while using other apps. |
| Confirm commands in the overlay | On | Also requires an enabled overlay, system permission, and a valid pending request. This is not an independent authorization source. |

[OverlayController](../../app/src/main/java/com/deepseekharness/app/OverlayController.java) handles stream events, styling, and window resources. [OverlayFrameBuffer](../../app/src/main/java/com/deepseekharness/app/util/OverlayFrameBuffer.java) prevents stale frame updates from recreating a new window. Ordinary stream text cannot overwrite confirmation UI while a confirmation is pending. Display expiry or hiding does not prove that a business operation stopped. After overlay permission is revoked, events cannot continue creating an authorized overlay; notification and native entry points remain subject to their own system availability.

## Allow, deny, and assistant questions

[HttpShellService](../../app/src/main/java/com/deepseekharness/app/HttpShellService.java) creates a one-request identity and bridge generation for requests requiring app confirmation. It offers notification buttons, a foreground native dialog, and an overlay entry when available. They resolve the same pending request rather than creating three grants. [ConfirmReceiver](../../app/src/main/java/com/deepseekharness/app/ConfirmReceiver.java) accepts only the specified action and complete request identity. An old notification, dialog, or runtime generation cannot approve a later request.

Confirmation waits at most60 seconds. Denial, expiry without an answer, an unavailable window, and an invalidated run are distinct results; none may be fabricated as Allow. Screen operations must recheck the actual target, frame or node, and current identity after the prompt closes. Pressing Allow must not immediately act on a page still covered by the prompt. Stopping, revocation, and policy changes invalidate the corresponding queued requests. See [device permissions](device-permissions-architecture.md) and [virtual-screen architecture](virtual-screen-architecture.md).

App confirmation does not override the device-command allowlist. `POLICY_BLOCKED` has no Allow anyway button. DSH tool approval, plugin permissions, and arbitrary native-terminal code are different controls. Overlay confirmation must not be presented as a strong system sandbox.

Assistant questions from `/app/ask` are managed by [BridgeAskDialog](../../app/src/main/java/com/deepseekharness/app/BridgeAskDialog.java) and [BridgeQuestions](../../app/src/main/java/com/deepseekharness/app/util/BridgeQuestions.java). They display at most three options and require an available foreground Activity. Backgrounding, destruction, Back, or window disappearance cancels that question; disappearance is not a default answer. Questions are managed separately from command Allow/Deny.

## Agent task notifications

[AgentTaskNotifications](../../app/src/main/java/com/deepseekharness/app/AgentTaskNotifications.java) uses the ordinary `dsh_task_progress` channel, separately from the Harness foreground-service notification. It does not request a promoted capsule. Bridge registration binds the actual port, generation, instance, client, and nonce. Each task additionally binds session, turn, owner, and an increasing sequence; an old event cannot control a new task.

The native **Tasks** page remains available for status. Notifications are posted while the app is in the background and canceled while it is in the foreground. Notification-permission failure is caught, leaving the native task page available. Notices show stage and elapsed time without copying chat text; the public lock-screen version contains less information. Progress generally refreshes at1Hz, while terminal events can refresh immediately.

**Stop** sends a request bound to that specific task and first displays **Stop requested; waiting for end**. It is neither exit proof nor a request to stop all of DSH. Producer acknowledgment and terminal events advance the state. Timeout or disconnection conservatively displays **Status cannot be confirmed**. **Return to chat** verifies the current runtime identity before routing to the conversation; an old notification must not restart an old generation. Dismissing a notice changes presentation only and does not implicitly stop the task. See [AgentTaskState](../../app/src/main/java/com/deepseekharness/app/util/AgentTaskState.java).

## Software verification and limits

Regression entry points are [OverlayFrameBufferTest](../../app/src/test/java/com/deepseekharness/app/util/OverlayFrameBufferTest.java), [BridgeConfirmationsTest](../../app/src/test/java/com/deepseekharness/app/util/BridgeConfirmationsTest.java), [BridgeQuestionsTest](../../app/src/test/java/com/deepseekharness/app/util/BridgeQuestionsTest.java), [AgentTaskRegistrationTest](../../app/src/test/java/com/deepseekharness/app/util/AgentTaskRegistrationTest.java), and [AgentTaskStateTest](../../app/src/test/java/com/deepseekharness/app/util/AgentTaskStateTest.java). This documentation work did not execute tests or interact with system windows. Notification permission, background restrictions, overlay layering, and target rechecks still require release-APK device evidence.
