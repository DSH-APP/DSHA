package com.deepseekharness.app.core;

import android.content.Context;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/** 旧 Android 缺少新根证书时补充随包 Mozilla CA；仍验证完整证书链与主机名。 */
public final class TrustedNetwork {
    private static SSLSocketFactory cached;
    private TrustedNetwork() { }
    public static synchronized SSLSocketFactory sockets(Context context) throws Exception {
        if (cached != null) return cached;
        TrustManagerFactory system = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        system.init((KeyStore) null);
        KeyStore roots = KeyStore.getInstance(KeyStore.getDefaultType()); roots.load(null, null);
        try (InputStream stream = context.getAssets().open("ca-certificates.crt")) {
            int index = 0;
            for (Certificate certificate : CertificateFactory.getInstance("X.509").generateCertificates(stream))
                roots.setCertificateEntry("dsha-ca-" + index++, certificate);
        }
        TrustManagerFactory bundled = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); bundled.init(roots);
        ArrayList<X509TrustManager> managers = new ArrayList<>();
        for (TrustManager manager : system.getTrustManagers()) if (manager instanceof X509TrustManager) managers.add((X509TrustManager) manager);
        for (TrustManager manager : bundled.getTrustManagers()) if (manager instanceof X509TrustManager) managers.add((X509TrustManager) manager);
        X509TrustManager combined = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String type) throws CertificateException { throw new CertificateException(com.deepseekharness.app.util.UiText.text("仅用于 HTTPS 服务端认证")); }
            @Override public void checkServerTrusted(X509Certificate[] chain, String type) throws CertificateException {
                CertificateException last = null;
                for (X509TrustManager manager : managers) {
                    try { manager.checkServerTrusted(chain, type); return; }
                    catch (CertificateException e) { last = e; }
                }
                throw last == null ? new CertificateException(com.deepseekharness.app.util.UiText.text("没有可用的信任根")) : last;
            }
            @Override public X509Certificate[] getAcceptedIssuers() {
                ArrayList<X509Certificate> issuers = new ArrayList<>();
                for (X509TrustManager manager : managers) java.util.Collections.addAll(issuers, manager.getAcceptedIssuers());
                return issuers.toArray(new X509Certificate[0]);
            }
        };
        SSLContext ssl = SSLContext.getInstance("TLS"); ssl.init(null, new TrustManager[]{combined}, null);
        cached = ssl.getSocketFactory(); return cached;
    }
}
