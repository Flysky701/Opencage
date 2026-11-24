package io.github.flysky701.opencage.privacy;

import android.content.Context;

import top.niunaijun.blackbox.fake.service.IConnectivityManagerProxy;

/** Control-plane bypass and compatibility entry point for the full guest privacy stack. */
public final class GuestPrivacy {
    private static final ThreadLocal<Integer> CONTROL_PLANE = ThreadLocal.withInitial(() -> 0);

    private GuestPrivacy() {}

    public static void enterControlPlane() {
        CONTROL_PLANE.set(CONTROL_PLANE.get() + 1);
    }

    public static void exitControlPlane() {
        int depth = CONTROL_PLANE.get();
        if (depth <= 1) CONTROL_PLANE.remove();
        else CONTROL_PLANE.set(depth - 1);
    }

    public static boolean inControlPlane() {
        Integer depth = CONTROL_PLANE.get();
        return depth != null && depth > 0;
    }

    public static void beforeBind(String packageName) {
        io.github.flysky701.opencage.privacy.internal.GuestPrivacy.beforeBind(packageName);
    }

    public static void bind(String packageName, Context guest) {
        IConnectivityManagerProxy.installOn(guest);
        io.github.flysky701.opencage.privacy.internal.GuestPrivacy.bindGuestContext(guest);
    }

    public static void applyActivity(Context activity) {
        if (!top.niunaijun.blackbox.BlackBoxCore.get().isBlackProcess() || activity == null) return;
        IConnectivityManagerProxy.installOn(activity);
        io.github.flysky701.opencage.privacy.internal.GuestPrivacy.apply(activity);
    }
}
