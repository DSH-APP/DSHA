package com.deepseekharness.app.util;

/** Shared names for external file interfaces; user archives and machine-state scopes remain distinct. */
public final class CredentialPaths {
  public static final String BRIDGE_TOKEN = CredentialPathRules.BRIDGE_TOKEN,
      BRIDGE_HEADERS = CredentialPathRules.BRIDGE_HEADERS,
      BRIDGE_STATUS = CredentialPathRules.BRIDGE_STATUS,
      LEGACY_API_KEY = CredentialPathRules.LEGACY_API_KEY;

  private CredentialPaths() {}

  public static boolean machine(String name) {
    if (name == null) return false;
    if (CredentialPathRules.MACHINE_EXACT.contains(name)) return true;
    for (String prefix : CredentialPathRules.MACHINE_PREFIXES)
      if (name.startsWith(prefix)) return true;
    return false;
  }

  public static boolean backupMachine(String name) {
    return name != null && (machine(name) || CredentialPathRules.BACKUP_ADDITIONAL.contains(name));
  }

  public static boolean sessionTrash(String root, String relative) {
    return CredentialPathRules.SESSION_ROOT.equals(root)
        && relative != null
        && (relative.equals(CredentialPathRules.SESSION_TRASH)
            || relative.startsWith(CredentialPathRules.SESSION_TRASH + "/"));
  }

  public static boolean credentialName(String name) {
    if (name == null) return true;
    if (machine(name) || CredentialPathRules.CREDENTIAL_EXACT.contains(name)) return true;
    for (String prefix : CredentialPathRules.CREDENTIAL_PREFIXES)
      if (name.startsWith(prefix)) return true;
    return false;
  }

  public static boolean deniedRelative(String relative) {
    if (relative == null) return true;
    String path = relative.replace('\\', '/');
    for (String part : path.split("/")) if (credentialName(part)) return true;
    while (path.startsWith("/")) path = path.substring(1);
    for (String prefix : CredentialPathRules.GUEST_PREFIXES)
      if (path.startsWith(prefix)) return true;
    return false;
  }
}
