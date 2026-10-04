package com.deepseekharness.app.util;

import java.net.URI;

/** 麦克风只开放给本次已验证的本机网页来源，不把相同端口的不同主机名视为同源。 */
public final class MicrophonePolicy {
  private MicrophonePolicy() {}

  private static URI local(String value) {
    try {
      URI uri = new URI(value);
      if (!"http".equalsIgnoreCase(uri.getScheme())
          || uri.getRawUserInfo() != null
          || uri.getPort() < 1
          || uri.getPort() > 65535
          || uri.getHost() == null) return null;
      String host = uri.getHost();
      return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) ? uri : null;
    } catch (Exception invalid) {
      return null;
    }
  }

  public static boolean trustedOrigin(String requestingOrigin, String baseUrl) {
    URI source = local(requestingOrigin), base = local(baseUrl);
    return source != null
        && base != null
        && source.getPort() == base.getPort()
        && source.getHost().equalsIgnoreCase(base.getHost());
  }

  public static boolean audioOnly(String[] resources) {
    return resources != null
        && resources.length == 1
        && "android.webkit.resource.AUDIO_CAPTURE".equals(resources[0]);
  }
}
