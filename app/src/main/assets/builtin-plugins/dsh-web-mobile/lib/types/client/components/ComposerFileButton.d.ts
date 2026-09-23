import type { PropsLocale, PropsRuntime } from '@deepseek-ai/dsh-client-ui-slots';
import { NS } from '../i18n/locales.ts';
/** Full props for the composer file entry. */
export interface ComposerFileButtonProps extends PropsRuntime<'conversation.input.left'>, PropsLocale<typeof NS> {
}
/**
 * Mobile-only composer file entry, kept visible outside the "+" command menu.
 *
 * The 0.1.6-alpha.2 host deleted the composer's paperclip attach button: the
 * only file entry left is the 「文件」row inside the "+" listbox (the trigger's
 * aria-label is 「添加文件或调用指令」). The host still mounts its own hidden
 * `input[type=file]` in the composer tool row and its own command opens the
 * native dialog with exactly `fileInputRef.current?.click()`, so this control
 * triggers that same input instead of reimplementing intake: file validation,
 * upload and the availability policy all stay host-owned.
 *
 * The control is contributed to the host-declared `conversation.input.left`
 * list slot ("Compact controls at the left of the composer tool row"), which
 * keeps it inside the tools lane beside the plus button without touching
 * host-owned React DOM. The seat is session-scoped, so the hero/blank phase
 * (no session) keeps the "+" menu as its only file entry.
 *
 * Availability mirrors the host's `canAcceptDrop` as far as it is observable:
 * a non-plain input phase (adjudicating/claimed/submitting = the machine is
 * busy) and a subagent session both refuse attachments. The host's own
 * `locked` / `addFiles === undefined` arms are package-private, so a missing
 * session seat also disables the control. Hidden entirely on wide screens
 * (CSS media query, and the shared desktop hide block in misc.css.ts).
 */
export declare function ComposerFileButton({ useInput, useSession, t }: ComposerFileButtonProps): import("react").JSX.Element;
//# sourceMappingURL=ComposerFileButton.d.ts.map