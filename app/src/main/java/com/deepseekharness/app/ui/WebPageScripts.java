package com.deepseekharness.app.ui;
import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
public final class WebPageScripts {
    private WebPageScripts() { }
    public static String compatibility(Context context) {
        return read(context, "web-integration/compat.js") + "\n" + read(context, "web-integration/startup.js");
    }
    private static String read(Context context, String path) {
        try (InputStream in = context.getAssets().open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer,0,n);
            return new String(out.toByteArray(),StandardCharsets.UTF_8);
        } catch(IOException error) { return ""; }
    }
    public static String back(Context context) {
        try (InputStream in = context.getAssets().open("web-integration/page.js")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer,0,n);
            return "(function(){" + new String(out.toByteArray(), StandardCharsets.UTF_8) + ";return window.__dshaPageBack();})()";
        } catch (IOException error) { return "false"; }
    }
}
