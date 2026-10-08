# WebView 文件分享与保存

build164 起，DSHA 正式聊天页面提供文件接口。内置移动 UI 3.0.5 的文件分享按钮已接入；用户点文件树或预览中的分享按钮即可打开 Android 选择器。

第三方网页插件可传入 `File` 或 `Blob`：

```js
const file = new File(['文件内容'], 'report.md', { type: 'text/markdown' });
if (window.DSHA?.canShareFile(file)) {
  await window.DSHA.shareFile(file);
} else if (window.DSHA?.saveFile) {
  await window.DSHA.saveFile(file);
}
```

- `canShareFile(file)`：本接口支持的单文件分享上限为 32 MiB。
- `shareFile(file)`：完整传输后打开 Android 分享选择器，返回 `{status: 'chooser-opened'}`。用户仍需在选择器及接收应用中完成分享；该结果不代表文件已经发送给他人。
- `saveFile(file)`：完整传输后选择保存位置，写入并读回校验成功才返回 `{status: 'saved'}`。取消会拒绝 Promise，错误名为 `AbortError`；写入或校验失败也会拒绝。最大文件大小沿用 2 GiB 下载限制。
- `File.name` 保留为显示名称并在原生侧去除路径字符。普通 `Blob` 使用 `download.bin`，因此推荐构造带名称的 `File`。

当前顶层同源页面的 `a[download]` Blob/Data 下载也会保留 `download` 属性中的文件名，采用同一个原生保存流程。

接口只在支持 WebMessageListener 的系统 WebView、当前正式 DSH 顶层页面中注入，绑定本机实际端口与运行实例。普通浏览器和 Gecko 应先检测接口，再沿用 Web Share 或自身下载能力。接口接收网页已有的文件字节，不接收本机路径；分享使用应用缓存中的内容 URI和临时读取授权。分享缓存保留供接收应用读取，下次分享时清理超过24小时的本接口临时文件。

关联：[issue99](https://github.com/DSH-APP/DSHA/issues/99)。
