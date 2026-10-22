import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client';
/** 两个选项：图片走 image 通配，附件优先走桥（见文件头「三路并存」）。 */
export type FilePickerKind = 'image' | 'file';
/**
 * 选项对应的 accept（空串 = 不改宿主 input 的属性）。
 *
 * 2026-10-07 试过给附件那路显式写全通配 accept（想换出系统「打开方式」应用列表）：真机形态没变，
 * 已回退 —— 根因在 App 侧少包一层 `Intent.createChooser`（见文件头）。所以这里仍是「不改属性」。
 * @param kind - 用户选择的入口。
 * @returns 要临时写到宿主 input 上的 accept；空串表示不动该属性。
 */
export declare function acceptForKind(kind: FilePickerKind): string;
/** 本效果用到的两条文案（不把 locales 的类型拖进 effects 层）。 */
type PickerTranslate = (key: 'fileUploadImage' | 'fileUploadAttachment') => string;
/**
 * App 侧文件选择桥（DSH-APP/DSHA#101 的备选方案）：命名空间同分享桥
 * （`window.DSHA.canShareFile` / `shareFile`），只声明本效果用得到的那一个方法 ——
 * 缺方法就当作没有桥（半实现的桥不能把附件路带进死胡同）。
 */
export interface DshaPickFileBridge {
    pickFile?: (options: DshaPickFileOptions) => Promise<unknown>;
}
/** 桥的入参：accept 空数组 = 不限类型（与宿主 hidden input 不写 accept 等价）。 */
export interface DshaPickFileOptions {
    accept: string[];
    multiple: boolean;
    title: string;
}
/** 桥的结果分类：拿到文件 / 用户取消 / 该回落宿主 input。 */
export type BridgePickOutcome = 'files' | 'cancelled' | 'fallback';
/**
 * 取出可用的 `pickFile`（绑定好 this）；没有桥或方法不是函数时返回 undefined。
 * @param bridge - `window.DSHA`。
 * @returns 可直接调用的桥方法。
 */
export declare function bridgePickFile(bridge: DshaPickFileBridge | undefined): ((options: DshaPickFileOptions) => Promise<unknown>) | undefined;
/**
 * 桥的返回值 → 可信的 `File` 列表（脏数据一律丢弃，不把非 File 塞进宿主 input）。
 * @param value - 桥的 resolve 值。
 */
export declare function pickResultFiles(value: unknown): File[];
/**
 * 桥的结果该走哪条路：用户取消（`null` / `undefined` / 空数组）**不回落** —— 否则一次取消
 * 会紧接着弹出第二个选择器；桥抛错或返回脏数据才回落，宁可回落到现状也不能点了没反应。
 * @param value - 桥的 resolve 值。
 * @param failed - 桥是否抛错（Promise reject / 同步抛出）。
 * @returns 分类结果。
 */
export declare function classifyBridgeResult(value: unknown, failed: boolean): BridgePickOutcome;
/**
 * 把桥挑好的文件交给宿主 intake：写进 hidden input 再派发 `change`。与 `input.click()` 那条路
 * 是同一个入口 —— 校验、大小/数量策略、上传、草稿全部仍归宿主。目标元素**由调用方传入**
 * （发起时捕获的那一个），这里不再全局查询：桥是异步的，resolve 时命中的 input 可能已经
 * 属于另一个会话。
 * @param files - 桥返回的文件。
 * @param input - 发起时捕获的宿主 input；null（当时就没有）时不交接。
 * @returns 是否成功交给宿主（false 时调用方回落 `openHostPicker`）。
 */
export declare function handFilesToHost(files: File[], input: HTMLInputElement | null): boolean;
/**
 * 附件那一路：App 侧有 `pickFile` 桥就先走桥（原生自己弹「打开方式」），没有就回落宿主 input。
 *
 * 回落链：桥不存在 → 直接 `openHostPicker('file')`；桥抛错 / 脏数据 / 注入失败 → 也回落（那时
 * 若 App 侧已合入 #101，宿主 input 自己就会弹 chooser）。
 * @param t - 插件 `NS` 命名空间文案。
 * @param readSessionId - 取当前会话 id；宿主缺 sessions 服务时传 undefined（只校验元素身份）。
 */
export declare function openAttachmentPicker(t: PickerTranslate, readSessionId?: () => string | null): Promise<void>;
/** 锚点盒（`getBoundingClientRect()` 的子集，纯核不依赖 DOMRect）。 */
export interface PickerAnchorBox {
    top: number;
    right: number;
    bottom: number;
    left: number;
}
/** 浮层自身尺寸。 */
export interface PickerSize {
    width: number;
    height: number;
}
/** 可见几何带 —— layout 视口坐标下「真正看得见」的那一段横带 / 竖带。 */
export interface PickerVisibleBand {
    top: number;
    bottom: number;
    left: number;
    right: number;
}
/**
 * 浮层落位纯核（无 DOM，2026-10-13 抽出并导出，好让数值被测试钉住）：
 * 锚点上方优先 8px，放不下就翻到下方，最后夹进可见带、四舍五入到整像素。
 *
 * 为什么是「带」而不是「视口高」：`getBoundingClientRect()` 与 `position: fixed` 的
 * top/left 都是 layout 视口坐标，与视觉视口可能只差一个平移（iOS 键盘滚动模型，Safari
 * 不认 `interactive-widget`；Chromium 自 2026-10-18 起键盘缩 layout 视口，两条通道重合）
 * —— 锚点盒与返回值都**不需要**换算，只有「夹进哪一段」得用视觉视口那一段（`visibleBand()`）。
 * @param anchor - 锚点（回形针）盒，layout 视口坐标。
 * @param own - 浮层自身尺寸。
 * @param band - 可见带，layout 视口坐标。
 * @returns 写进 `style.top` / `style.left` 的整像素值。
 */
export declare function computePickerPlacement(anchor: PickerAnchorBox, own: PickerSize, band: PickerVisibleBand): {
    top: number;
    left: number;
};
/**
 * 安装手机档的输入框文件入口弹层。
 * @param ctx - client 上下文。
 */
export declare function installComposerFilePicker(ctx: ClientContext): void;
export {};
//# sourceMappingURL=composer-file-picker.d.ts.map