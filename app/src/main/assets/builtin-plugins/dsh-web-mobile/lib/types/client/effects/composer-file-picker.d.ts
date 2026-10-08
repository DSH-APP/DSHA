import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client';
/** 两个选项：图片走 image 通配，附件优先走桥（见文件头「三路并存」）。 */
export type FilePickerKind = 'image' | 'file';
/**
 * 选项对应的 accept（空串 = 不改宿主 input 的属性）。
 *
 * 2026-10-07 实验记录（已回退）：曾给附件那路显式写全通配 accept，想验证「空的
 * acceptTypes」是不是让 DSHA 起了文档选择器（DownloadsUI）而不是店主想要的
 * 「打开方式」+ 应用列表。店主真机实测**形态没变**。
 * 2026-10-08 补上根因：`WebPreviewActivity.onShowFileChooser` 是
 * `params.createIntent()` 直接 launch，少包一层 `Intent.createChooser`，而「全通配 accept」
 * 与「不写 accept」生成的 Intent 完全一致 ⇒ 插件只能影响 accept、换不出 chooser 形态。
 * 已提 DSH-APP/DSHA#101；桥（`window.DSHA.pickFile`）落地后附件那路改走桥。
 * 所以这里仍是「不改属性」，不给宿主 input 写多余的 accept。
 * @param kind - 用户选择的入口。
 * @returns 要临时写到宿主 input 上的 accept；空串表示不动该属性。
 */
export declare function acceptForKind(kind: FilePickerKind): string;
/** 本效果用到的两条文案（不把 locales 的类型拖进 effects 层）。 */
type PickerTranslate = (key: 'fileUploadImage' | 'fileUploadAttachment') => string;
/**
 * App 侧文件选择桥（DSH-APP/DSHA#101 的备选方案）。
 *
 * 命名空间与插件已经在用的分享桥（`window.DSHA.canShareFile` / `shareFile`）一致；
 * 这里只声明本效果用得到的那一个方法，缺方法就当作没有桥（半实现的桥不能把附件路带进死胡同）。
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
 * @returns 通过 `instanceof File` 的元素。
 */
export declare function pickResultFiles(value: unknown): File[];
/**
 * 桥的结果该走哪条路。
 *
 * 用户取消（`null` / `undefined` / 空数组）**不回落** —— 否则一次取消会紧接着弹出第二个
 * 选择器；桥抛错或返回脏数据才回落，宁可回落到现状也不能让按钮点了没反应。
 * @param value - 桥的 resolve 值。
 * @param failed - 桥是否抛错（Promise reject / 同步抛出）。
 * @returns 分类结果。
 */
export declare function classifyBridgeResult(value: unknown, failed: boolean): BridgePickOutcome;
/**
 * 把桥挑好的文件交给宿主 intake：写进 hidden input 再派发 `change`。
 *
 * 与 `input.click()` 那条路是同一个入口 —— 校验、大小/数量策略、上传、草稿生成全部仍归宿主。
 * @param files - 桥返回的文件。
 * @returns 是否成功交给宿主（false 时调用方回落 `openHostPicker`）。
 */
export declare function handFilesToHost(files: File[]): boolean;
/**
 * 附件那一路：App 侧有 `pickFile` 桥就先走桥（原生自己弹「打开方式」），没有就回落宿主 input。
 *
 * 回落链：桥不存在 → 直接 `openHostPicker('file')`；桥抛错 / 返回脏数据 / 注入失败 →
 * 也回落（那时候 App 侧若已合入 #101，宿主 input 自己就会弹 chooser）。
 * @param t - mobileNav 文案。
 */
export declare function openAttachmentPicker(t: PickerTranslate): Promise<void>;
/**
 * 安装手机档的输入框文件入口弹层。
 * @param ctx - client 上下文。
 */
export declare function installComposerFilePicker(ctx: ClientContext): void;
export {};
//# sourceMappingURL=composer-file-picker.d.ts.map