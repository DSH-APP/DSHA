/** Minimal structural slice of the host cordis Context that apply() needs. */
export interface HostContext {
    /** Register one disposable installer; its return value disposes on unload. */
    effect(install: () => unknown, label?: string): unknown;
}
export declare function apply(ctx: HostContext): void;
//# sourceMappingURL=index.d.ts.map