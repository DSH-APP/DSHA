import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client';
/**
 * Fullscreen answer for the ask-question card: the injected expand/collapse
 * toggle, the measured band marker, and the answer field's way back into the
 * card body.
 *
 * Registered from index.tsx next to the other composer effects. Everything
 * lives inside one mobile effect so a width change tears the listeners and the
 * reconciler task down together (and re-arms them on the way back to a phone).
 *
 * @param ctx - client root context: locale dictionary for the button labels.
 */
export declare function installQuestionFullscreen(ctx: ClientContext): void;
//# sourceMappingURL=question-fullscreen.d.ts.map