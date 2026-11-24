package io.github.flysky701.opencage.runtime;

import android.os.Build;
import android.os.Process;

/** Process-local SIM/timezone reads for selected guests, never system properties. */
public final class OpenPrivacyProperties {
    private OpenPrivacyProperties() {}
    public static synchronized boolean activate(boolean sim, boolean timezone) {
        return activate(sim, timezone, "Asia/Tokyo");
    }
    public static synchronized boolean activate(boolean sim, boolean timezone, String zone) {
        String pkg = top.niunaijun.blackbox.app.BActivityThread.getAppPackageName();
        boolean supported = Build.VERSION.SDK_INT >= 34 && Process.is64Bit()
                && Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0
                && "arm64-v8a".equals(Build.SUPPORTED_ABIS[0]);
        if (!supported || !io.github.flysky701.opencage.privacy.internal.GuestPrivacy.selected(pkg)) return false;
        System.loadLibrary("blackbox");
        return nativeSetPolicy((sim ? 1 : 0) | (timezone ? 2 : 0), zone == null || zone.isEmpty() ? "Asia/Tokyo" : zone);
    }
    public static synchronized void deactivate() { nativeSetPolicy(0, null); }
    private static native boolean nativeSetPolicy(int flags, String zone);
}
