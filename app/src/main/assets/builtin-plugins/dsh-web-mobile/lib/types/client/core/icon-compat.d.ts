import type { ReactElement } from 'react';
/**
 * 宿主 `@deepseek-ai/dsh-client-ui-primitives` 的图标按名字取（真机取证 2026-09-23）。
 *
 * 宿主下限 0.2.2-rc.1 起只认 `IconXxxOutlineRegular`（0.2.2-rc.1 的 primitives
 * `lib/index.js` 里 `Outline16` 0 命中）；旧代 `IconXxxOutline16` 候选已随低代际分支删除。
 * 仍按「运行时查表」取而不是具名 import：CI 的 typecheck 用的是钉在 devDependencies 的
 * `0.1.0-rc.6` 类型快照，那一代根本没有 `OutlineRegular` 这些导出名，具名 import 会编译失败；
 * 写死的名字一旦被宿主改掉，React 渲染会直接抛「Element type is invalid」（0.1.7 真机上
 * 回形针/下载/文件夹/侧栏四个图标就是这么坏掉的）。宿主下次改命名，往候选数组里补一个名字即可。
 *
 * 两点刻意的设计：
 * 1. 图标组件的类型**本地定义**（`HostIcon`），不引宿主导出的类型面 —— 不同代的
 *    primitives 类型面不同，而且能不能解析到取决于环境（CI 能、探针环境不能），
 *    引用它会让生成的 `.d.ts` 不稳定（实测：本机退化成 `any`、CI 是真类型，
 *    直接顶掉 `lib is fresh` 这道闸）。
 * 2. 候选名都不存在时返回空组件，宁可少画一个图标，也不让整个组件树炸掉。
 */
/** 宿主图标组件的形状（各代 props 一致：size / className）。 */
export type HostIcon = (props: {
    size?: number;
    className?: string;
}) => ReactElement | null;
/** 输入区文件入口（回形针）。 */
export declare const IconPaperclip: HostIcon;
/** 会话头部的目录抽屉开关。 */
export declare const IconPanelLeft: HostIcon;
/** 会话头部的 Files/右侧栏入口。 */
export declare const IconFolderOpen: HostIcon;
//# sourceMappingURL=icon-compat.d.ts.map