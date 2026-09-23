export type CssElementDescriptor = {
    tag: string;
    classes?: string[];
    ancestors?: CssElementDescriptor[];
};
export type CssRuleBlock = {
    selector: string;
    body: string;
};
/**
 * Selector/body pairs from a stylesheet string, at every nesting level: the
 * conditional at-rules (@media / @supports / @layer) that wrap the mobile
 * rules are descended into, which is the whole point — the message text rule
 * lives inside `@media (hover: none), (pointer: coarse)`, so a reader that
 * skipped at-rule blocks would report nothing and its guard would be green
 * forever.
 */
export declare function findRuleBlocks(css: string): CssRuleBlock[];
/**
 * Whether `el`'s described path (its ancestors, outermost first, then itself)
 * can satisfy the selector. The subject of an arm — its rightmost token — may
 * be any element on that path, because a font-size declared on the message
 * column is inherited by the text inside it, so both belong to one family.
 */
export declare function matchesSelectorText(selector: string, el: CssElementDescriptor): boolean;
/**
 * The `font-size` a stylesheet declares for the family `el` stands for, with
 * the selector that declared it, or null when no matching rule declares one.
 * Throws when matching rules disagree — an ambiguous family is a guard bug,
 * not something to report as a pass.
 */
export declare function fontSizeFor(css: string, el: CssElementDescriptor): {
    value: string;
    selector: string;
} | null;
//# sourceMappingURL=css-rules.d.ts.map