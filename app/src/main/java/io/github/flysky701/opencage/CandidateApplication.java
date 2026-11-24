package io.github.flysky701.opencage;

import android.content.Context;
import android.content.pm.PackageManager;

public final class CandidateApplication extends CageApplication {
    private boolean hostServicesReady;

    // Called on the launcher's serial worker. Guest and server startup use their original lifecycle.
    void prepareHostServices() {
        if (hostServicesReady) return;
        top.niunaijun.blackbox.BlackBoxCore.get().ensureBlackProcessInitialized();
        top.niunaijun.blackbox.core.system.ServiceManager.initBlackManager();
        hostServicesReady = true;
    }

    @Override protected void attachBaseContext(Context base) {
        // The framework starts every :pN guest slot here, including background services.
        // Pin it before the shared base class can initialize a guest or its providers.
        if (GuestVpn.isGuestProcess(base.getPackageName(), getProcessName())) {
            if (!phoneAccessRestricted(base)) throw new SecurityException("Host has phone or SMS read access");
            GuestVpn.protect(base);
        }
        super.attachBaseContext(base);
    }

    static boolean phoneAccessRestricted(Context base) {
        for (String permission : new String[] {android.Manifest.permission.READ_PHONE_STATE,
                android.Manifest.permission.READ_PHONE_NUMBERS, android.Manifest.permission.READ_SMS,
                "android.permission.READ_PRIVILEGED_PHONE_STATE"}) {
            if (base.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }
}
