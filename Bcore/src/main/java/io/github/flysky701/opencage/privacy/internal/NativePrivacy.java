package io.github.flysky701.opencage.privacy.internal;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/** ARM library read policy. Installation is not a claim that every native path is covered. */
final class NativePrivacy {
    private static boolean loaded;
    private static boolean active;
    private static int diagnosticGeneration;

    private NativePrivacy() {}

    static synchronized boolean bind(Context guest, String packageName, String zone,
            boolean sim, boolean timezone, boolean vpn, Set<String> observedInterfaces) {
        Log.i("CageNativePrivacy", "java.bind.enter");
        deactivate();
        Log.i("CageNativePrivacy", "java.bind.after.deactivate");
        boolean nativeArm64 = Build.VERSION.SDK_INT >= 34 && Process.is64Bit()
            && Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0
            && "arm64-v8a".equals(Build.SUPPORTED_ABIS[0]);
        if (!nativeArm64) return false;
        if (!GuestPrivacy.selected(packageName) || zone == null || zone.isEmpty()) return false;
        try {
            Log.i("CageNativePrivacy", "java.bind.before.applicationInfo");
            ApplicationInfo info = guest.getApplicationInfo();
            Log.i("CageNativePrivacy", "java.bind.after.applicationInfo");
            boolean contextMatches = packageName.equals(guest.getPackageName());
            boolean infoMatches = info != null && packageName.equals(info.packageName);
            boolean sourceReady = info != null && info.sourceDir != null && !info.sourceDir.isEmpty();
            boolean dataReady = info != null && info.dataDir != null && !info.dataDir.isEmpty();
            Log.i("CageNativePrivacy", "java.bind.identity context=" + contextMatches + " info=" + infoMatches
                + " source=" + sourceReady + " data=" + dataReady);
            if (!contextMatches || !infoMatches || !sourceReady || !dataReady)
                throw new IllegalStateException("Guest native policy identity unavailable");
            LinkedHashSet<String> roots = new LinkedHashSet<>();
            Log.i("CageNativePrivacy", "java.bind.before.roots");
            addPrivateRoots(roots, packageName);
            Log.i("CageNativePrivacy", "java.bind.after.roots");
            if (!loaded) {
                Log.i("CageNativePrivacy", "java.bind.before.loadLibrary");
                System.loadLibrary("cage_privacy");
                Log.i("CageNativePrivacy", "java.bind.after.loadLibrary");
                loaded = true;
            }
            int flags = (sim ? 1 : 0) | (timezone ? 2 : 0) | (vpn ? 4 : 0);
            String[] interfaces;
            synchronized (observedInterfaces) { interfaces = observedInterfaces.toArray(new String[0]); }
            Log.i("CageNativePrivacy", "java.bind.before.nativeBind");
            nativeSetZone(zone);
            // Open Cage uses the bundled ByteHook without a host loader bridge.
            int result = nativeBind(0L, roots.toArray(new String[0]), interfaces, flags, false, nativeArm64);
            Log.i("CageNativePrivacy", "java.bind.after.nativeBind");
            boolean ok = vpn ? (result == 2) : (result > 0);
            Log.i("CageNativePrivacy", "bind.result=" + result);
            if (!ok) {
                deactivate();
                return false;
            }
            active = true;
            scheduleDiagnostics(guest, info, packageName);
            return active;
        } catch (Exception | LinkageError failure) {
            deactivate();
            Log.w("CageNativePrivacy", "bind.unavailable." + failure.getClass().getSimpleName()
                + (failure instanceof IllegalStateException ? "." + failure.getMessage() : ""));
            return false;
        }
    }

    private static void addPrivateRoots(Set<String> roots, String pkg) {
        Context host = top.niunaijun.blackbox.BlackBoxCore.getContext();
        if (!"io.github.flysky701.opencage".equals(host.getApplicationInfo().packageName)
                || !pkg.equals(top.niunaijun.blackbox.app.BActivityThread.getAppPackageName()))
            throw new IllegalStateException("Native policy host/guest mismatch");
        int user = top.niunaijun.blackbox.app.BActivityThread.getUserId();
        File base = top.niunaijun.blackbox.core.env.BEnvironment.getVirtualRoot();
        File[] selected = {top.niunaijun.blackbox.core.env.BEnvironment.getAppDir(pkg),
            top.niunaijun.blackbox.core.env.BEnvironment.getDataDir(pkg, user),
            top.niunaijun.blackbox.core.env.BEnvironment.getDeDataDir(pkg, user)};
        String prefix = physicalPath(base) + "/data/";
        for (File root : selected) {
            String path = physicalPath(root);
            if (!path.startsWith(prefix) || !path.endsWith("/" + pkg))
                throw new IllegalStateException("Native policy private root mismatch");
            roots.add(path);
        }
        Log.i("CageNativePrivacy", "java.bind.blackboxRoots.verified");
    }

    private static String physicalPath(File path) {
        if (path == null || !path.isAbsolute()) throw new IllegalStateException("Missing physical path");
        String value = path.getAbsolutePath();
        if (!value.startsWith("/data/") || value.endsWith("/") || value.contains("//")
                || value.contains("/../") || value.endsWith("/..")
                || value.contains("/./") || value.endsWith("/.") || value.indexOf('\0') >= 0)
            throw new IllegalStateException("Invalid physical path");
        return value;
    }

    static synchronized void observeInterface(String name) {
        if (loaded && active && name != null) nativeObserveInterface(name);
    }

    static synchronized void deactivate() {
        active = false;
        ++diagnosticGeneration;
        if (loaded) nativeDeactivate();
    }

    private static void scheduleDiagnostics(Context guest, ApplicationInfo info, final String packageName) {
        try {
            File data = new File(info.dataDir).getCanonicalFile();
            File files = guest.getFilesDir().getCanonicalFile();
            if (!files.getPath().startsWith(data.getPath() + File.separator)) throw new IllegalStateException("Diagnostic data root mismatch");
            final File output = new File(files, "cage-native-privacy-diagnostic.txt").getCanonicalFile();
            if (!files.equals(output.getParentFile())) throw new IllegalStateException("Diagnostic file root mismatch");
            final String context = "context.package=" + packageName + "\ncontext.source=" + info.sourceDir
                + "\ncontext.data=" + info.dataDir + "\ncontext.nativeLibrary=" + info.nativeLibraryDir + "\n";
            final int generation = ++diagnosticGeneration;
            Handler handler = new Handler(Looper.getMainLooper());
            int[] delays = {0, 1000, 4000, 8000};
            for (int index = 0; index < delays.length; ++index) {
                final int sequence = index;
                handler.postDelayed(new Runnable() {
                    @Override public void run() { captureDiagnostics(output, context, packageName, generation, sequence); }
                }, delays[index]);
            }
            Log.i("CageNativePrivacy", "observation.schedule.0_1_4_8seconds");
        } catch (Exception failure) {
            Log.i("CageNativePrivacy", "observation.schedule.unavailable." + failure.getClass().getSimpleName());
        }
    }

    private static synchronized void captureDiagnostics(File output, String context, String packageName,
            int generation, int sequence) {
        if (!active || generation != diagnosticGeneration) return;
        try {
            String current = top.niunaijun.blackbox.app.BActivityThread.getAppPackageName();
            if (!GuestPrivacy.selected(packageName) || !packageName.equals(current)) return;
            String host = HostLoadPolicy.diagnostics();
            Log.i("CageNativePrivacy", "host.observation." + sequence + " " + host);
            String observation = (sequence == 0 ? context + nativeCapture(-1) : "") + nativeCapture(sequence)
                + "host.loader=" + host + "\n";
            try (FileOutputStream stream = new FileOutputStream(output, sequence != 0)) {
                stream.write(observation.getBytes(StandardCharsets.UTF_8));
                stream.getFD().sync();
            }
            Log.i("CageNativePrivacy", "observation.privateFile.written." + sequence);
        } catch (Exception | LinkageError failure) {
            Log.i("CageNativePrivacy", "observation.unavailable." + sequence + "." + failure.getClass().getSimpleName());
        }
    }

    static synchronized String diagnostics() {
        return loaded ? nativeDiagnostics() + "\n" + HostLoadPolicy.diagnostics() : "library.not.loaded";
    }

    private static native void nativeSetZone(String zone);
    private static native int nativeBind(long api, String[] roots, String[] interfaces, int flags,
        boolean synchronizeLoads, boolean arm64Native);
    private static native void nativeObserveInterface(String name);
    private static native void nativeDeactivate();
    private static native String nativeDiagnostics();
    private static native String nativeCapture(int sequence);
}
