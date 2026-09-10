export interface RafScheduler {
    schedule: (fn: () => void) => void;
    cancel: () => void;
}
export declare function createRafScheduler(raf: (cb: FrameRequestCallback) => number, caf: (id: number) => void): RafScheduler;
//# sourceMappingURL=raf-scheduler.d.ts.map