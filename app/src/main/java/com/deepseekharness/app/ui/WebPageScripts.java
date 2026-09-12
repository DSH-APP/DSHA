package com.deepseekharness.app.ui;
import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
public final class WebPageScripts {
    private WebPageScripts() { }
    public static String compatibility(Context context) {
        return language(context) + "\n" + read(context, "web-integration/es-compat.js") + "\n"
                + read(context, "web-integration/compat.js") + "\n" + read(context, "web-integration/startup.js");
    }
    public static String language(Context context) {
        String id=new com.deepseekharness.app.core.ConfigStore(context).getUiLanguage();
        return "window.__DSHA_LANGUAGE__='"+id+"';window.dispatchEvent(new CustomEvent('dsha-language'));"
            +"if(!window.__dshaLanguageSelectionBound){window.__dshaLanguageSelectionBound=true;window.addEventListener('dsha-language-selected',e=>{if(e.detail==='en'||e.detail==='zh')window.DshaLanguage?.postMessage(e.detail);});}";
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
