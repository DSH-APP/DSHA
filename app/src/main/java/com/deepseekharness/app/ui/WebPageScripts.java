package com.deepseekharness.app.ui;
import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
public final class WebPageScripts {
    private WebPageScripts() { }
    public static String compatibility(Context context) {
        String section="";
        if(context instanceof android.app.Activity){String url=((android.app.Activity)context).getIntent().getStringExtra("url");
            if(((android.app.Activity)context).getIntent().getBooleanExtra("dsha_open_models",false)||(url!=null&&url.endsWith("#dsha-models")))section="window.__DSHA_OPEN_MODELS__=true;window.dispatchEvent(new Event('dsha-open-models'));\n";}
        return "window.__DSHA_NATIVE_PLUGINS__=true;\n"+section + language(context) + "\n" + read(context, "web-integration/es-compat.js") + "\n"
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
