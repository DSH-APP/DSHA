/**
 * `mobileNavFileShare` namespace: file-share copy, registered by
 * installFileShare (effects/file-share.ts) through ctx.locale.register — kept
 * out of i18n/locales.ts so the feature owns its dictionary end to end.
 */
export declare const FILE_SHARE_NS = "mobileNavFileShare";
/** Simplified Chinese dictionary (the key-set source of truth). */
export declare const fileShareZh: {
    readonly share: "分享";
    readonly shareFile: "分享「{name}」";
    readonly downloadFile: "下载「{name}」";
    readonly sharing: "正在准备文件…";
    readonly downloadedUnsupported: "当前环境不支持直接分享；已开始下载，请在系统保存位置确认";
    readonly downloadedTooLarge: "文件超过 50 MB；已开始下载，请在系统保存位置确认";
    readonly downloadedShareFailed: "分享未能完成；已开始下载，请在系统保存位置确认";
    readonly errorTooLarge: "文件太大（超过 200 MB），无法在手机端分享或下载";
    readonly errorNotFound: "文件不存在或已被移动";
    readonly errorGeneric: "分享失败：{message}";
};
/** Key union of the namespace. */
export type FileShareKey = keyof typeof fileShareZh;
/** English dictionary, key-identical to the Chinese source of truth. */
export declare const fileShareEn: Record<FileShareKey, string>;
declare module '@deepseek-ai/dsh-client-ui-slots' {
    interface LocaleNamespaceMap {
        /** File-share controls copy (files tree rows + preview header). */
        'mobileNavFileShare': FileShareKey;
    }
}
//# sourceMappingURL=file-share-locale.d.ts.map