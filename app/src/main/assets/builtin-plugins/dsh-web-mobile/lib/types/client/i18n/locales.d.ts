/** `mobileNav` namespace dictionaries: drawer controls. */
export declare const NS = "mobileNav";
/** Simplified Chinese dictionary (the key-set source of truth). */
export declare const zh: {
    readonly open: "打开目录";
    readonly close: "收起目录";
    readonly backdrop: "点击关闭目录";
    readonly sessionLog: "导出会话日志";
    readonly files: "文件浏览";
    readonly previewFullscreen: "全屏预览";
    readonly previewExitFullscreen: "退出全屏";
    readonly deleteSession: "删除会话";
    readonly deleteConfirmTitle: "删除会话？";
    readonly deleteConfirmDesc: "将删除「{title}」的完整会话记录，此操作不可恢复。";
    readonly deleteConfirmYes: "删除";
    readonly deleteConfirmNo: "取消";
    readonly deletePending: "正在删除…";
    readonly deleteErrorBusy: "该会话正在运行且无法停止，请稍后重试。";
    readonly deleteErrorNotFound: "会话不存在或已被删除。";
    readonly deleteErrorResolve: "无法确定要删除的会话，请重试。";
    readonly deleteErrorGeneric: "删除失败：{message}";
};
/** English dictionary, key-identical to the Chinese source of truth. */
export declare const en: Record<MobileNavKey, string>;
/** Key domain of the `mobileNav` namespace (zh is the source of truth). */
export type MobileNavKey = keyof typeof zh;
//# sourceMappingURL=locales.d.ts.map