/** 一次点击应当如何处理。 */
export type PluginCardTapDecision = 'open' | 'ignore';
/** 判定所需的最小信息（调用方负责查 DOM，便于单测）。 */
export interface PluginCardTapTarget {
    /** 点击是否落在条目自己的「打开详情」按钮内部（含其文字/子节点）。 */
    readonly insideOpen: boolean;
    /** 点击是否落在别的交互控件上（开关、链接、输入框…）。 */
    readonly onInteractive: boolean;
}
/**
 * 判定一次点击要不要转发给「打开详情」按钮。
 *
 * - 点标题本身 ⇒ 'ignore'（宿主自己会开，转发会双重触发）；
 * - 点开关/链接/输入框等交互件 ⇒ 'ignore'（绝不抢它们的点击）；
 * - 其余（描述、图标、徽标、留白）⇒ 'open'，由调用方转发。
 * @param target - 命中的元素信息。
 * @returns 'open' 需要转发；'ignore' 放行。
 */
export declare function decidePluginCardTap(target: PluginCardTapTarget): PluginCardTapDecision;
//# sourceMappingURL=plugin-card-tap-core.d.ts.map