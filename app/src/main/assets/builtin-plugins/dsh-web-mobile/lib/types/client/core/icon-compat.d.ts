import type { ReactElement } from 'react';
/**
 * 宿主 `@deepseek-ai/dsh-client-ui-primitives` 的图标命名有两代（真机取证 2026-09-23）：
 *
 *   · `0.1.0-rc.6`（本仓库锁文件 → CI 的 `pnpm verify` 按它 typecheck）：`IconXxxOutline16`
 *   · `0.1.7-alpha.1`（DSHA 真机宿主）：`IconXxxOutlineRegular`
 *
 * 仓库声明的 peer 范围（`^0.1.0-rc.6 || >=0.1.1-rc.0 <0.2.0 || >=0.1.2-a <0.2.0`）同时覆盖两代，
 * 但两边**导出的名字互不相交**：写死任何一代，另一代拿到的就是 `undefined`，
 * React 渲染时直接抛「Element type is invalid」（0.1.7 真机上回形针/下载/文件夹/侧栏四个图标
 * 就是这么坏掉的）。
 *
 * 所以这里按「运行时哪个存在用哪个」取名。宿主下次改命名，只需往候选数组里补一个名字。
 *
 * 两点刻意的设计：
 * 1. 图标组件的类型**本地定义**（`HostIcon`），不引宿主导出的类型面 —— 不同代的
 *    primitives 类型面不同，而且能不能解析到取决于环境（CI 能、探针环境不能），
 *    引用它会让生成的 `.d.ts` 不稳定（实测：本机退化成 `any`、CI 是真类型，
 *    直接顶掉 `lib is fresh` 这道闸）。
 * 2. 两代都没有时返回空组件，宁可少画一个图标，也不让整个组件树炸掉。
 */
/** 宿主图标组件的形状（各代 props 一致：size / className）。 */
export type HostIcon = (props: {
    size?: number;
    className?: string;
}) => ReactElement | null;
/** 输入区文件入口（回形针）。 */
export declare const IconPaperclip: HostIcon;
/** 抽屉页脚的会话日志导出。 */
export declare const IconDownload: HostIcon;
/** 会话头部的目录抽屉开关。 */
export declare const IconPanelLeft: HostIcon;
/** 会话头部的 Files/右侧栏入口。 */
export declare const IconFolderOpen: HostIcon;
//# sourceMappingURL=icon-compat.d.ts.map