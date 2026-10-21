/**
 * Namespace id for the drawer-control dictionaries.
 *
 * Package-prefixed on purpose: the host's `locale.register` THROWS on a
 * duplicate namespace (no merge, no override), and that throw fails the whole
 * plugin fiber — a dead web boot on narrow screens. Downstream copies of this
 * plugin keep our namespace, so a bare, generic name is a loaded gun:
 * dsh-pocket embeds a copy of the mobile adaptation and still registers
 * `mobileNav` (issue #165).
 */
export const NS = 'dshWebMobileNav'

/** Simplified Chinese dictionary (the key-set source of truth). */
export const zh = {
  'open': '打开目录',
  'close': '收起目录',
  'backdrop': '点击关闭目录',
  'back': '返回',
  'backToConversation': '返回会话',
  'sessionLog': '导出会话日志',
  'files': '文件浏览',
  'fileUpload': '添加文件',
  'fileUploadImage': '上传图片',
  'fileUploadAttachment': '上传附件',
  'previewFullscreen': '全屏预览',
  'previewExitFullscreen': '退出全屏',
  'deleteSession': '删除会话',
  'deleteConfirmTitle': '删除会话？',
  'deleteConfirmDesc': '将删除「{title}」的完整会话记录，此操作不可恢复。',
  'deleteConfirmYes': '删除',
  'deleteConfirmNo': '取消',
  'deletePending': '正在删除…',
  'deleteErrorBusy': '该会话正在运行且无法停止，请稍后重试。',
  'deleteErrorNotFound': '会话不存在或已被删除。',
  'deleteErrorResolve': '无法确定要删除的会话，请重试。',
  'deleteErrorGeneric': '删除失败：{message}',
} as const

/** English dictionary, key-identical to the Chinese source of truth. */
export const en: Record<MobileNavKey, string> = {
  'open': 'Open directory',
  'close': 'Close directory',
  'backdrop': 'Click to close directory',
  'back': 'Back',
  'backToConversation': 'Back to conversation',
  'sessionLog': 'Session log',
  'files': 'Files',
  'fileUpload': 'Add files',
  'fileUploadImage': 'Upload image',
  'fileUploadAttachment': 'Upload attachment',
  'previewFullscreen': 'Fullscreen preview',
  'previewExitFullscreen': 'Exit fullscreen',
  'deleteSession': 'Delete session',
  'deleteConfirmTitle': 'Delete session?',
  'deleteConfirmDesc': 'The complete log of “{title}” will be permanently removed. This cannot be undone.',
  'deleteConfirmYes': 'Delete',
  'deleteConfirmNo': 'Cancel',
  'deletePending': 'Deleting…',
  'deleteErrorBusy': 'This session is running and could not be stopped. Try again later.',
  'deleteErrorNotFound': 'The session does not exist or was already deleted.',
  'deleteErrorResolve': 'Could not identify the session to delete. Please try again.',
  'deleteErrorGeneric': 'Delete failed: {message}',
}

/** Key domain of the {@link NS} namespace (zh is the source of truth). */
export type MobileNavKey = keyof typeof zh
