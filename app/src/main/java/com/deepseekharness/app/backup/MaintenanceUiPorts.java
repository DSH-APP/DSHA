package com.deepseekharness.app.backup;

import android.content.Intent;

/** The application composition root supplies notification destinations to data services. */
public interface MaintenanceUiPorts {
  Intent dataProtectionPage(boolean nativeBackup, long maintenanceId);

  Intent runtimePage();

  static MaintenanceUiPorts fromOwner(Object owner) {
    if (!(owner instanceof MaintenanceUiPorts))
      throw new IllegalStateException("MAINTENANCE_UI_OWNER_UNAVAILABLE");
    return (MaintenanceUiPorts) owner;
  }
}
