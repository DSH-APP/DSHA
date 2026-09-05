package com.deepseekharness.app;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 脚本层的增量更新。
 *
 * 为什么需要：这一批修复里 fs-write-patch.sh、flatten-l2s.py、webserver-auth-patch.sh、
 * adb-shell.py、引导插件……全都是几 KB 的脚本，却要用户重下 384MB 的 APK 才能拿到。
 * 全部脚本加起来不到 200KB，实际更新通常只有几个文件、几十 KB。
 *
 * 定位（别把它做成第二套安装器）：
 * - **不替代内置离线包**。装机仍然是 APK 自带 306MB rootfs、装完即用、不依赖网络 ——
 *   网差的用户一次下载就能跑，这是我们相对同类项目的优势，不能拿去换体积。
 * - 只覆盖脚本层：assets 里的 .py/.sh/.js/.json 与内置插件源码。
 *   .so、rootfs、APK 本体一概不在范围内。
 * - 纯可选：默认不自动跑，用户手动触发；全部失败也只是维持现状，不影响任何已有功能。
 *
 * 落地方式：下载到 filesDir/runtime-overlay/，HarnessController.readAsset() 优先读
 * 覆盖层、没有才读 APK 内置。于是「回退到内置版本」只需删掉覆盖文件。
 *
 * 信任边界（务必看清楚，这是一条远程代码通道）：
 * - 下载地址只认写死的三个域名，不接受清单里出现的其他主机；
 * - 每个文件下载后校验 sha256，与清单不符即丢弃；
 * - 清单自身靠 HTTPS 保护完整性。
 * - **清单签名**：清单由 release 私钥签名（SHA256withRSA），APK 内置公钥
 *   （assets/runtime-update-pubkey.pem）验签，验不过整批拒绝、不降级放行。
 *   这一层把信任锚点从「GitHub 仓库没被黑」挪到「私钥没泄露」—— 后者我们能控制。
 * - 即便如此仍保持「手动触发 + 展示变更清单 + 只动脚本」三条约束：签名证明的是
 *   来源，不是内容无害，发错了一样要人能看见、能回退。
 */
public final class RuntimeUpdater {

    private RuntimeUpdater() {
    }

    /** 清单地址（多源，按顺序尝试）。与 tools/gen-runtime-manifest.py 的产出对应。
     *
     *  <p>指向 {@code stable} 分支而不是 {@code main}：main 现在走 1.2 alpha 线，
     *  上面那份清单由那条线自己生成，与 1.1.x 的 assets 内容对不上，客户端逐文件核对
     *  sha256 必然失败 —— 真机反馈的「清单签名验证失败，已拒绝这次更新」就是这么来的
     *  （issue #45）。而 main 不是我们该改的分支，改了会把 1.2 的用户带坏。
     *
     *  <p>分支名不能带斜杠：raw 的路径是 {@code /owner/repo/<ref>/<path>}，ref 里出现
     *  斜杠就没法区分 ref 和 path（{@code dev/1.1.x/x.json} 会被当成 ref=dev、
     *  path=1.1.x/x.json），jsdelivr 的 {@code @ref} 同理。所以另开 stable 这个名字，
     *  发版时把清单推到它上面。 */
    private static final String[] MANIFEST_URLS = {
            "https://raw.githubusercontent.com/qiannianhuanxiang/DSHA/stable/runtime-manifest.json",
            "https://cdn.jsdelivr.net/gh/qiannianhuanxiang/DSHA@stable/runtime-manifest.json",
            "https://ghproxy.net/https://raw.githubusercontent.com/qiannianhuanxiang/DSHA/stable/runtime-manifest.json",
    };

    /** 清单签名（base64 的 SHA256withRSA），与清单一一对应 */
    private static final String[] SIG_URLS = {
            "https://raw.githubusercontent.com/qiannianhuanxiang/DSHA/stable/runtime-manifest.sig",
            "https://cdn.jsdelivr.net/gh/qiannianhuanxiang/DSHA@stable/runtime-manifest.sig",
            "https://ghproxy.net/https://raw.githubusercontent.com/qiannianhuanxiang/DSHA/stable/runtime-manifest.sig",
    };

    /** APK 内置的验签公钥（从 release keystore 的证书导出，4096 bit RSA） */
    private static final String PUBKEY_ASSET = "runtime-update-pubkey.pem";

    /** 只允许从这些主机下载。清单被篡改成指向别处时，这一层能挡住。 */
    private static final String[] ALLOWED_HOSTS = {
            "raw.githubusercontent.com", "cdn.jsdelivr.net", "ghproxy.net",
    };

    /** 单个热更新资产的硬上限。assets 脚本与内置插件源码不该大于这个量级。 */
    private static final int MAX_RUNTIME_ASSET_BYTES = 16 * 1024 * 1024;

    /** 覆盖层目录：readAsset 优先读这里 */
    public static File overlayDir(Context ctx) {
        return new File(ctx.getFilesDir(), "runtime-overlay");
    }

    /** 覆盖层里某个资产的落点。名字里的 / 会被保留（内置插件是多级路径）。
     *
     *  <p>越界一律落到一个哨兵名上，绝不返回覆盖层之外的路径：清单虽然是签名的，
     *  但让签名成为唯一屏障太单薄，而且我们自己的生成脚本也可能产出带 {@code ../}
     *  的 asset 名（{@code os.path.relpath} + assets 下的外部符号链接）。
     *  规范化后比对前缀，比逐个字符判断可靠（能一并处理符号链接）。 */
    public static File overlayFile(Context ctx, String asset) {
        File dir = overlayDir(ctx);
        if (!AssetPath.isSafe(asset)) return new File(dir, REJECTED_NAME);
        File f = new File(dir, asset);
        try {
            String base = dir.getCanonicalPath();
            String want = f.getCanonicalPath();
            if (!want.equals(base) && !want.startsWith(base + File.separator)) {
                return new File(dir, REJECTED_NAME);
            }
        } catch (Throwable e) {
            return new File(dir, REJECTED_NAME);   // 算不出规范路径就当越界（fail-closed）
        }
        return f;
    }

    /** 越界 asset 的统一落点：读它一定读不到东西，写它也只污染覆盖层内一个文件。 */
    private static final String REJECTED_NAME = "__rejected_asset__";

    public static final class Item {
        public String asset;
        public String sha256;
        public int size;
        public List<String> urls = new ArrayList<>();
    }

    public static final class Result {
        public boolean ok;
        public String message = "";
        public int updated;
        public int skipped;
        public int failed;
        public final List<String> changed = new ArrayList<>();
    }

    /** 拉清单 → 比对 → 下载有差异的。返回给 UI 直接展示的结果。 */
    public static Result checkAndApply(Context ctx, HarnessController c, boolean dryRun) {
        Result r = new Result();
        byte[] raw = null;
        String json = null;
        String from = "";
        boolean sawManifest = false;
        boolean sawSignature = false;
        // 清单与签名必须从同一个镜像成对取。旧实现先拿第一个清单、再拿第一个签名，
        // CDN 刷新不同步时就把 A 的新清单配 B 的旧签名，明明有可用源却必然验签失败。
        for (int i = 0; i < MANIFEST_URLS.length && i < SIG_URLS.length; i++) {
            byte[] candidate = httpGetBytes(MANIFEST_URLS[i], 8000, 15000, 1 << 20);
            if (candidate == null) continue;
            String body = new String(candidate, StandardCharsets.UTF_8);
            if (!body.contains("\"files\"")) continue;
            sawManifest = true;
            String sig = httpGet(SIG_URLS[i], 8000, 15000);
            if (sig == null || sig.trim().length() <= 64) continue;
            sawSignature = true;
            if (!verifyManifest(ctx, candidate, sig.trim())) continue;
            raw = candidate;
            json = body;
            from = hostOf(MANIFEST_URLS[i]);
            break;
        }
        if (json == null || raw == null) {
            if (!sawManifest) {
                r.message = "拉不到更新清单（三个源都失败）——网络不通时这个功能直接跳过，不影响使用";
            } else if (!sawSignature) {
                r.message = "拿不到与清单同源的签名，已放弃这次更新。\n"
                        + "（增量更新是一条远程代码通道，没有签名就不应用 —— 不影响现有功能）";
            } else {
                r.message = "所有镜像的清单签名都验证失败，已拒绝这次更新。\n"
                        + "可能是镜像缓存不一致，稍后再试；若持续失败请到 GitHub 反馈。";
            }
            return r;
        }
        List<Item> items = parse(json);
        if (items.isEmpty()) {
            r.message = "清单为空或格式无法识别（来源 " + from + "）";
            return r;
        }

        for (Item it : items) {
            String localHash = localSha256(ctx, c, it.asset);
            if (it.sha256.equalsIgnoreCase(localHash)) {
                r.skipped++;
                continue;
            }
            if (dryRun) {
                r.changed.add(it.asset);
                r.updated++;
                continue;
            }
            if (download(ctx, it)) {
                r.changed.add(it.asset);
                r.updated++;
            } else {
                r.failed++;
            }
        }
        r.ok = r.failed == 0;
        if (r.updated == 0 && r.failed == 0) {
            r.message = "脚本已是最新（比对 " + items.size() + " 个文件，来源 " + from + "）";
        } else if (dryRun) {
            r.message = "有 " + r.updated + " 个脚本可更新（共 " + items.size() + " 个）";
        } else {
            r.message = "已更新 " + r.updated + " 个脚本"
                    + (r.failed > 0 ? "，" + r.failed + " 个失败（保留原版本）" : "")
                    + "。重启 Web 后生效";
        }
        return r;
    }

    /** 本地当前生效版本的 sha256：覆盖层优先，其次 APK 内置 assets。 */
    private static String localSha256(Context ctx, HarnessController c, String asset) {
        File over = overlayFile(ctx, asset);
        if (over.isFile()) {
            try (InputStream in = new java.io.FileInputStream(over)) {
                return sha256(in);
            } catch (Throwable ignored) {
            }
        }
        try (InputStream in = ctx.getAssets().open(asset)) {
            return sha256(in);
        } catch (Throwable e) {
            return "";   // 内置里也没有 → 视为需要下载（清单里新增的文件）
        }
    }

    /** 下载单个文件：多源尝试 → 校验 sha256 → 同目录临时文件 + 原子改名。 */
    private static boolean download(Context ctx, Item it) {
        File dst = overlayFile(ctx, it.asset);
        File parent = dst.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            return false;
        }
        for (String u : it.urls) {
            if (!hostAllowed(u)) {
                continue;   // 清单指向未授权主机：跳过，不是下载失败而是拒绝
            }
            byte[] body = httpGetBytes(u, 8000, 20000, it.size);
            if (body == null) {
                continue;
            }
            String got = sha256(body);
            if (!got.equalsIgnoreCase(it.sha256)) {
                continue;   // 内容与清单不符：可能是镜像缓存了旧版，换下一个源
            }
            File tmp;
            try { tmp = File.createTempFile("runtime-", ".part", parent); }
            catch (java.io.IOException e) { continue; }
            try (FileOutputStream fo = new FileOutputStream(tmp)) {
                fo.write(body);
                fo.getFD().sync();   // 先落盘再改名，防断电留半个文件
            } catch (Throwable e) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                continue;
            }
            try {
                SafeFiles.replace(tmp, dst);
                return true;
            } catch (java.io.IOException e) {
                // 不删旧版本。更新失败与旧版本损坏是两件完全不同的事。
                tmp.delete();
            }
        }
        return false;
    }

    /** 把覆盖层整个清掉，回到 APK 内置版本。出问题时的退路。 */
    public static boolean resetOverlay(Context ctx) {
        return deleteRecursively(overlayDir(ctx));
    }

    /** 覆盖层里现有多少个文件（配置页展示用） */
    public static int overlayCount(Context ctx) {
        return countFiles(overlayDir(ctx));
    }

    /** 用 APK 内置公钥验证清单签名（SHA256withRSA）。任何异常都当作验签失败。 */
    private static boolean verifyManifest(Context ctx, byte[] manifestBytes, String sigBase64) {
        try {
            String pem;
            try (InputStream in = ctx.getAssets().open(PUBKEY_ASSET)) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) {
                    bos.write(buf, 0, n);
                }
                pem = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            }
            String b64 = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] der = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
            java.security.PublicKey pub = java.security.KeyFactory.getInstance("RSA")
                    .generatePublic(new java.security.spec.X509EncodedKeySpec(der));
            java.security.Signature v = java.security.Signature.getInstance("SHA256withRSA");
            v.initVerify(pub);
            v.update(manifestBytes);
            return v.verify(android.util.Base64.decode(sigBase64, android.util.Base64.DEFAULT));
        } catch (Throwable e) {
            android.util.Log.w("DSHA", "清单验签失败: " + e);
            return false;
        }
    }

    // ==================== 内部工具 ====================

    private static boolean hostAllowed(String url) {
        String h = hostOf(url);
        for (String a : ALLOWED_HOSTS) {
            if (h.equalsIgnoreCase(a)) return true;
        }
        return false;
    }

    private static String hostOf(String url) {
        try {
            return new URL(url).getHost();
        } catch (Throwable e) {
            return "";
        }
    }

    /** 极简清单解析：只认我们自己生成的结构，不引第三方 JSON 库。 */
    private static List<Item> parse(String json) {
        List<Item> out = new ArrayList<>();
        try {
            org.json.JSONObject root = new org.json.JSONObject(json);
            org.json.JSONArray arr = root.optJSONArray("files");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Item it = new Item();
                it.asset = o.optString("asset", "");
                it.sha256 = o.optString("sha256", "");
                it.size = o.optInt("size", 0);
                org.json.JSONArray us = o.optJSONArray("urls");
                if (us != null) {
                    for (int j = 0; j < us.length(); j++) {
                        it.urls.add(us.optString(j, ""));
                    }
                }
                // 缺字段的条目直接丢：宁可少更新，不要写入来源不明的内容。
                // asset 名还要过一次路径校验 —— 它会被当成覆盖层下的相对路径直接落盘，
                // 带 ../ 就能写到私有目录别处（shared_prefs 里有 API key 密文和 LAN token）。
                if (!it.asset.isEmpty() && it.sha256.matches("[0-9a-fA-F]{64}")
                        && it.size > 0 && it.size <= MAX_RUNTIME_ASSET_BYTES && !it.urls.isEmpty()
                        && AssetPath.isSafe(it.asset)) {
                    out.add(it);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static String httpGet(String url, int connMs, int readMs) {
        byte[] b = httpGetBytes(url, connMs, readMs, 1 << 20);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    private static byte[] httpGetBytes(String url, int connMs, int readMs, int maxBytes) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(connMs);
            conn.setReadTimeout(readMs);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "DSHA/" + BuildConfig.VERSION_NAME);
            if (conn.getResponseCode() != 200) return null;
            try (InputStream in = conn.getInputStream()) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                int total = 0;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > maxBytes) return null;   // 防超大响应把内存吃光
                    bos.write(buf, 0, n);
                }
                return bos.toByteArray();
            }
        } catch (Throwable e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String sha256(byte[] data) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Throwable e) {
            return "";
        }
    }

    private static String sha256(InputStream in) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
            return hex(md.digest());
        } catch (Throwable e) {
            return "";
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xf, 16));
            sb.append(Character.forDigit(x & 0xf, 16));
        }
        return sb.toString();
    }

    private static boolean deleteRecursively(File f) {
        if (f == null || !f.exists()) return true;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    deleteRecursively(k);
                }
            }
        }
        return f.delete();
    }

    private static int countFiles(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return 1;
        int n = 0;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                n += countFiles(k);
            }
        }
        return n;
    }
}
