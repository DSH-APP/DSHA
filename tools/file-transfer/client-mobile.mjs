// Fixed mobile bundle transformer. The legacy browser share flow is preserved;
// Android persistent files use metadata preparation followed by native streaming.
import { readFileSync } from 'node:fs';

export function applyFileTransferClient(source) {
  const replace = (before, after) => {
    if (source.split(before).length !== 2) throw new Error('file transfer mobile anchor: ' + before.slice(0, 80));
    source = source.replace(before, after);
  };
  const controls = readFileSync(new URL('./client-controls.js', import.meta.url), 'utf8');
  // A native Share is an explicit destination. Cancellation, size rejection or
  // an unavailable chooser must never silently become an anchor/SAF download.
  replace('async function deliverFile(file, deps) {', `async function deliverFile(file, deps) {
    if (deps.nativeShare) {
        try {
            await deps.share({ files: [file], title: file.name });
            return { kind: 'shared' }; // chooser opened; no recipient-delivery claim
        } catch (error) {
            if (isAbortError(error)) return { kind: 'cancelled' };
            throw error;
        }
    }`);
  replace('    return {\n        canShare: typeof bridge?.canShareFile',
    '    return {\n        nativeShare: typeof bridge?.shareFile === \'function\',\n        canShare: typeof bridge?.canShareFile');
  replace('? data => bridge.shareFile(data.files[0])', `? async data => {
                if (data.files[0].size > 32 * 1024 * 1024) throw new Error(fileCopy().shareLimit);
                const result = await bridge.shareFile(data.files[0]);
                if (result?.status !== 'chooser-opened') throw new Error('The system share picker did not open');
            }`);
  // 3.2.2 上游已把「下载记录」并入抽屉品牌行菜单（window.DSHA.openDownloads 存在时才显示），
  // 不再在手机顶栏追加第二个下载入口；原生分享/另存为等其他本地差异保持。
  replace('function FilePreviewShareButton({ absolutePath, sessionId, readFileRange, t }) {',
    'function LegacyFilePreviewShareButton({ absolutePath, sessionId, readFileRange, t }) {');
  replace('function FilesRowShare({ sessionId, readFileRange, t }) {',
    'function LegacyFilesRowShare({ sessionId, readFileRange, t }) {');
  replace('const ROW_BUTTON = \'file-share-row\';', "const ROW_BUTTON = 'file-share-row';\n" + controls);
  const anchor = "                scope.slots.inject('sidebar.right.tab.files.actions', () => scope.slots.register({";
  replace(anchor, `                scope.slots.inject('deliverables.file.actions', () => scope.slots.register({
                    name: 'deliverables.file.actions', id: 'dsha-file-delivery-actions', order: 90,
                }, file_share_controls_tsx_1.FileDeliveryActions)),
                scope.slots.inject('deliverables.review.file.actions', () => scope.slots.register({
                    name: 'deliverables.review.file.actions', id: 'dsha-file-review-actions', order: 90,
                }, file_share_controls_tsx_1.FileDeliveryActions)),
` + anchor);
  replace("exports.FilesRowShare = FilesRowShare;", "exports.FilesRowShare = FilesRowShare;\nexports.FileDeliveryActions = FileDeliveryActions;");
  replace("            const stops = [\n                scope.slots.inject('sidebar.right.tab.document.actions'", "            const stops = [\n                file_share_controls_tsx_1.installNativeFileActions(),\n                scope.slots.inject('sidebar.right.tab.document.actions'");
  replace('exports.FileDeliveryActions = FileDeliveryActions;', 'exports.FileDeliveryActions = FileDeliveryActions;\nexports.installNativeFileActions = installNativeFileActions;');
  return source;
}
