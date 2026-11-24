package top.niunaijun.blackbox.proxy;

import java.util.Locale;

import top.niunaijun.blackbox.BlackBoxCore;


public class ProxyManifest {
    public static final int FREE_COUNT = 16;

    public static boolean isProxy(String msg) {
        if (msg == null) return false;
        if (getBindProvider().equals(msg)) return true;
        for (int i = 0; i < FREE_COUNT; i++) {
            if (getProxyAuthorities(i).equals(msg)) return true;
        }
        return false;
    }

    public static String getBindProvider() {
        return BlackBoxCore.getHostPkg() + ".blackbox.SystemCallProvider";
    }

    public static String getProxyAuthorities(int index) {
        return String.format(Locale.ROOT, "%s.proxy_content_provider_%d", BlackBoxCore.getHostPkg(), index);
    }

    public static String getProxyPendingActivity(int index) {
        return String.format(Locale.ROOT, "top.niunaijun.blackbox.proxy.ProxyPendingActivity$P%d", index);
    }

    public static String getProxyActivity(int index) {
        return String.format(Locale.ROOT, "top.niunaijun.blackbox.proxy.ProxyActivity$P%d", index);
    }

    public static String TransparentProxyActivity(int index) {
        return String.format(Locale.ROOT, "top.niunaijun.blackbox.proxy.TransparentProxyActivity$P%d", index);
    }

    public static String getProxyService(int index) {
        return String.format(Locale.ROOT, "top.niunaijun.blackbox.proxy.ProxyService$P%d", index);
    }

    public static String getProxyJobService(int index) {
        return String.format(Locale.ROOT, "top.niunaijun.blackbox.proxy.ProxyJobService$P%d", index);
    }

    public static String getProxyFileProvider() {
        return BlackBoxCore.getHostPkg() + ".blackbox.FileProvider";
    }

    public static String getProxyReceiver() {
        return BlackBoxCore.getHostPkg() + ".stub_receiver";
    }

    public static String getProcessName(int bPid) {
        return BlackBoxCore.getHostPkg() + ":p" + bPid;
    }
}
