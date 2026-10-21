# Browser engines, desktop UA, and loading failures

Verification date: 2026-10-21. Source: the corresponding Chinese maintenance guide and current working-tree implementation, using published F10 `087724c` as a reference baseline. Mechanism documentation does not establish WebView/Gecko phone validation or frame-rate results for the current release.

## Current selection

| Flavor / setting | Actual selection |
|---|---|
| Standard | System WebView; no bundled Gecko fallback implementation |
| Low: explicit Gecko | Bundled GeckoView |
| Low: automatic, API<26 | Prefer Gecko |
| Low: automatic, API≥26 | Try WebView when its provider can be identified; prefer Gecko when the provider is absent or its query fails. |

[ConfigFragment](../../app/src/main/java/com/deepseekharness/app/ui/ConfigFragment.java) is the settings entry point. **Browser engine** shows only System WebView in Standard; Low offers **Automatic engine / Gecko**. The default Gecko preference is false. Saving affects later selection; it does not replace a live engine inside an already-loaded page. See [Standard PreviewFallback](../../app/src/standard/java/com/deepseekharness/app/ui/PreviewFallback.java) and [Low PreviewFallback](../../app/src/low/java/com/deepseekharness/app/ui/PreviewFallback.java). Low's WebView path tests the page's actual required capabilities rather than declaring failure from a UA version number.

[WebPreviewActivity](../../app/src/main/java/com/deepseekharness/app/ui/WebPreviewActivity.java) implements system WebView. [GeckoPreviewActivity](../../app/src/low/java/com/deepseekharness/app/ui/GeckoPreviewActivity.java) is compiled only into Low. The proot/proroot container engine is selected independently of the browser engine. Changing browser engines does not replace Ubuntu or DSH.

## What desktop-page mode changes

The setting defaults off and is stored as `desktopMode`. When creating a WebView, [WebPreviewPolicy.desktopUserAgent](../../app/src/main/java/com/deepseekharness/app/util/WebPreviewPolicy.java) replaces the Android platform marker and removes the Mobile marker while retaining the real Chrome version. Gecko selects desktop user-agent mode when creating its session. This affects the page's response to its UA. It does not create desktop resolution, disable the mobile plugin, or promise to rewrite the viewport or CSS layout.

The UA is read when a browser instance is created. A retained instance in the same generation keeps its original creation settings. After changing the preference, the instance must be recreated before the new UA can be verified. Resuming a hidden page alone is not proof of switching. Page CSS, viewport, the mobile plugin, and the system window still determine layout together.

## Authentication, origin, and restoration

Formal preview accepts only the current DSH service identity at `http://127.0.0.1:<actual-port>`. Navigation waits for the asynchronous cookie-injection result, preventing an unauthenticated first page. Local cookies are not injected into arbitrary external websites. Generation, instance, View, and navigation tickets jointly protect callbacks so old pages or results cannot approve a new page's operations.

WebView enables JavaScript and DOM storage, and disables file access, third-party cookies, and mixed content. Files pass through the restricted chooser, FileProvider, and streaming bridge. Only top-level external navigation is intercepted; plugin iframes retain normal browser behavior. See [Web file interaction](web-file-sharing.md) and the [security model](security-model.md) for attachments, sharing, saving, and access boundaries.

A same-generation instance is retained through rotation and full-screen/PiP/hidden transitions. Stopping or changing the DSH identity prevents continued use of the old page. System reclamation of the app process or renderer can require reopening; complete preservation of unsent forms is not guaranteed. See [system PiP](picture-in-picture.md).

## DeepSeek browser account authorization

F11 added a dedicated native facade to BrowserLogin in pinned DSH `0.2.1-rc.2`, avoiding a mismatch between synchronous `about:blank` pre-opening and later asynchronous popup navigation in the two embedded browsers. The current local page's native navigation handles the asynchronous destination. Only the exact `https://platform.deepseek.com[:443]/dsh/authorize` target is exempt from the user-gesture requirement. Other external links retain the gesture requirement. Native code independently verifies the current page, actual local origin, and exact authorization target; similar domains or paths are not accepted.

Gecko enables its nativeOAuth marker only after receiving a native port message with a valid `observationNonce`. A page-created marker cannot replace native checks. The callback remains `/oauth/callback` on the same dynamic Web port. DSH's original constant-time state comparison and PKCE are not relaxed by this navigation adaptation. Duplicate parameters, wrong methods or paths, expiry, and cancellation must be rejected by the real service. Unknown results are not replayed. The [LAN proxy](lan.md) separately governs callback-path and parameter preservation.

Entry points are [DeepSeekOAuthNavigation](../../app/src/main/java/com/deepseekharness/app/util/DeepSeekOAuthNavigation.java) and the [pinned runtime patch](../../app/src/main/assets/deepseek-native-oauth-patch.json). Related regressions are the [Java navigation checks](../../app/src/test/java/com/deepseekharness/app/util/DeepSeekOAuthNavigationTest.java) and [pinned official-module browser/HTTP checks](../../tools/test-deepseek-native-oauth.mjs). Historical F11 software evidence reports eight groups each in Chromium and Firefox and ten Java cases, covering wrong state, duplicate parameters, methods and paths, disconnected watches, one-time PKCE exchange, cancellation/expiry, and exchange failure. Its initial failure caused by an overly broad diagnostic boundary and subsequent correction are retained. These checks used no real platform-account credentials and performed no phone, system-external-browser, or complete mobile OAuth validation. They do not prove that the change uniquely explains a historical screenshot, and they are not current-release device evidence.

## What failures mean

| Symptom | Handling boundary |
|---|---|
| WebView initialization or required-capability check fails | Low may try Gecko. Standard asks the user to enable or update Android System WebView/Chrome, or open in an external browser. |
| The current WebView renderer crashes | First destroy the dead page and resources. Low may attempt Gecko once; Standard displays a retry message. |
| Android reclaims the renderer for memory | Display a reopen message without automatically classifying it as a crash. |
| HTTP401/403 | Bounded reauthentication against the current run; on continued failure, return to the launch page and enter again rather than looping with an old token. |
| Ordinary connection error or slow service startup | Inspect the launch page and logs. Not every network failure switches engines. |

Automatic fallback requires the current startup identity, a valid same-service URL, and a live host. [openGeckoAfterWebViewFailure](../../app/src/main/java/com/deepseekharness/app/ui/WebPreviewActivity.java) records one attempt, cancels file and microphone requests, and destroys the old View first. Standard's fallback implementation returns false. Low's mechanism must not be advertised as a bundled Standard backup engine.

Microphone access is requested on demand only for audio-only requests from the current local page. `RECORD_AUDIO` and `MODIFY_AUDIO_SETTINGS` have different roles. Ordinary startup does not pregrant recording. Camera and screen-audio requests are rejected, and an old page's permission callback cannot approve a new page. See [BrowserMicrophone](../../app/src/main/java/com/deepseekharness/app/ui/BrowserMicrophone.java).

Verification entry points include [WebPreviewPolicyTest](../../app/src/test/java/com/deepseekharness/app/util/WebPreviewPolicyTest.java), [PreviewPageSessionTest](../../app/src/test/java/com/deepseekharness/app/util/PreviewPageSessionTest.java), [BrowserUploadRequestStateTest](../../app/src/test/java/com/deepseekharness/app/util/BrowserUploadRequestStateTest.java), and [BrowserDiagnosticTest](../../app/src/test/java/com/deepseekharness/app/util/BrowserDiagnosticTest.java). This documentation work checked implementation only. System-provider, media-permission, and renderer behavior still require device evidence.
