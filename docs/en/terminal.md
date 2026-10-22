# Terminals: PTY, simple mode, and verified closure

Verification date: 2026-10-21. Source: the corresponding Chinese maintenance guide and current working-tree implementation, using published F10 `087724c` as a reference baseline. This page describes the implementation contract; it does not establish a current release result or additional device or command-execution evidence. Historical audit APKs are not user entry points.

## Entry points and mode selection

Main's **Terminal** page can switch between PTY and simple mode. The choice is stored locally as `term_pty`. Switching pages retains existing sessions in the other mode; it does not convert one shell into another protocol. The application-level [TerminalSessionOwner](../../app/src/main/java/com/deepseekharness/app/core/TerminalSessionOwner.java) owns both tab sets. Fragments only attach observers, so navigation, rotation, and language recreation do not intentionally restart terminal processes. If Android ends the app process, restoration of the original shell process is not guaranteed.

| Mode | Suitable work | Behavior and limits |
|---|---|---|
| PTY | vim, htop, tmux, interactive prompts, individual keystrokes | [PtySession](../../app/src/main/java/com/deepseekharness/app/PtySession.java) connects Termux terminal-emulator/JNI. The extra key row provides Ctrl, Alt, Esc, Tab, and arrow keys; font controls and pinch scaling are available. |
| Simple | Submitting complete command lines and reading text output | A pipe-based shell queues commands and sends them serially. It does not emulate a complete terminal; use PTY for interactive input or TUIs. |

The UI entry points are [PtyTerminalFragment](../../app/src/main/java/com/deepseekharness/app/ui/PtyTerminalFragment.java) and [TerminalFragment](../../app/src/main/java/com/deepseekharness/app/ui/TerminalFragment.java). New tabs use the current environment preparation and runtime selection. Existing tabs retain the process identity captured at birth. See [runtime engines](runtime-engines.md) for engine selection and compatibility boundaries.

## Sessions, numbering, and command state

[TerminalTabs](../../app/src/main/java/com/deepseekharness/app/util/TerminalTabs.java) uses permanently increasing internal IDs. Display numbers reuse gaps only after successful closure. A closing tab or a failed closure continues occupying its number; display numbers must never route an old callback to a new session. PTY and simple mode retain separate tab sets and selections.

The simple terminal's [TerminalSession](../../app/src/main/java/com/deepseekharness/app/util/TerminalSession.java) progresses through `STOPPED → STARTING → READY/BUSY → STOPPING`, with failures entering `FAILED`. Its startup handshake verifies the fixed bootstrap's PID, session, and birth identity before any user command is sent. Unsent commands may wait for readiness. An initialization timeout is not evidence that a user command ran.

**Interrupt** terminates this simple shell's process group and creates a replacement only after exit is confirmed. Shell-local state such as `cd` and `export` is lost. Unsent commands remain queued; sent commands are never replayed automatically. A partial write failure leaves execution unknown. The UI asks the user to inspect the result rather than automatically repeating a potentially consequential command. Closing a tab cancels its unsent queue.

Simple output has ANSI sequences removed and sensitive text redacted. When the buffer exceeds 300000 characters, the last 100000 remain. This is a display limit, not a complete command audit archive. A simple command is limited to 16384 units of Java string length; empty input and NUL are rejected.

## Closure, maintenance, and failure

Closing a tab first marks it as closing, then waits for exit on a worker thread. [TerminalSessionOwner](../../app/src/main/java/com/deepseekharness/app/core/TerminalSessionOwner.java) removes it only after confirmation. Failure clears the closing flag and retains the original session for another closure attempt. Environment maintenance closes both modes and also covers PTY processes forked before their tabs were published.

[TerminalProcessCloser](../../app/src/main/java/com/deepseekharness/app/runtime/TerminalProcessCloser.java) checks saved process identities and session relationships. Temporary `/proc` unreadability, a timeout, or launcher exit must not be treated as proof that the entire guest has exited. Failed verification retains the environment occupancy barrier. Processes are not force-killed by bare PID, port, or name. The active environment cannot be moved or rebuilt while that barrier remains.

Terminals, plugins, and DSH share the application's Android UID execution domain. The native terminal permits shell execution. The device bridge's command allowlist does not create a strong sandbox around that terminal. Assess commands against their actual working directory and permissions; see the [security model](security-model.md).

## Verification entry points

After a relevant change, select the applicable [TerminalTabsTest](../../app/src/test/java/com/deepseekharness/app/util/TerminalTabsTest.java), [TerminalSessionTest](../../app/src/test/java/com/deepseekharness/app/util/TerminalSessionTest.java), [TerminalSessionOwnerTest](../../app/src/test/java/com/deepseekharness/app/core/TerminalSessionOwnerTest.java), and [TerminalRuntimeTest](../../app/src/test/java/com/deepseekharness/app/runtime/TerminalRuntimeTest.java). They cover numbering and closure, queues and unknown execution, ownership, and launch inputs respectively. Their presence does not mean they were rerun for this documentation work. Actual PTY, IME, and ROM `/proc` behavior requires device evidence for the exact release APK.
