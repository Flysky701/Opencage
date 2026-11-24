package io.github.flysky701.opencage.privacy.internal;

import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.system.Os;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reversible environment for children of one selected, guarded guest process. */
final class ChildPolicy {
    // Enables the guarded child policy. Each build needs device verification.
    private static final boolean CHILD_PROBE_VALIDATED = true;
    private static final String TAG = "CageChildPolicy";
    private static final String[] KEYS = {"LD_PRELOAD", "CAGE_CHILD_SCOPE", "CAGE_CHILD_POLICY",
        "CAGE_CHILD_SIM", "CAGE_CHILD_TIMEZONE", "CAGE_CHILD_ZONE", "CAGE_CHILD_DIAGNOSTIC", "CAGE_CHILD_PROFILE"};
    private static final Map<String, String> saved = new LinkedHashMap<>();
    private static boolean active;

    private ChildPolicy() {}

    static synchronized boolean activate(Context guest, boolean sim, boolean timezone, String zone) {
        deactivate();
        if (!CHILD_PROBE_VALIDATED) {
            Log.i(TAG, "probe.pending.environmentUnchanged");
            return false;
        }
        boolean hasContext = guest != null, hasModules = sim || timezone, supportedZone = zone != null && !zone.isEmpty() && zone.length() < 64;
        Log.i(TAG, "guard.inputs context=" + hasContext + " modules=" + hasModules + " zone=" + supportedZone);
        if (!hasContext || !hasModules || !supportedZone) return false;
        String stage = "guard";
        try {
            String abi = guardedAbi();
            if (abi == null) return false;
            String pkg = guest.getPackageName();
            String current = top.niunaijun.blackbox.app.BActivityThread.getAppPackageName();
            boolean selected = GuestPrivacy.selected(pkg), currentMatches = pkg != null && pkg.equals(current);
            boolean infoMatches = pkg != null && pkg.equals(guest.getApplicationInfo().packageName);
            Log.i(TAG, "guard.identity selected=" + selected + " current=" + currentMatches + " info=" + infoMatches);
            if (!selected || !currentMatches || !infoMatches)
                throw new IllegalStateException("Selected guest identity unavailable");
            stage = "hostAsset";
            // Use the host context from core initialization.
            Context host = top.niunaijun.blackbox.BlackBoxCore.getContext();
            boolean hostMatches = host != null && "io.github.flysky701.opencage".equals(host.getApplicationInfo().packageName);
            Log.i(TAG, "guard.host matches=" + hostMatches);
            if (!hostMatches)
                throw new IllegalStateException("Host identity unavailable");
            File library = extract(host, abi);
            stage = "environment";
            for (String key : KEYS) saved.put(key, Os.getenv(key));
            String original = saved.get("LD_PRELOAD");
            String preload = library.getAbsolutePath() + (original == null || original.isEmpty() ? "" : " " + original);
            Os.setenv("CAGE_CHILD_SCOPE", pkg, true);
            Os.setenv("CAGE_CHILD_SIM", sim ? "1" : "0", true);
            Os.setenv("CAGE_CHILD_TIMEZONE", timezone ? "1" : "0", true);
            Os.setenv("CAGE_CHILD_ZONE", zone, true);
            Os.setenv("CAGE_CHILD_DIAGNOSTIC", "0", true);
            Os.setenv("CAGE_CHILD_PROFILE", "android-arm64", true);
            Os.setenv("CAGE_CHILD_POLICY", "1", true);
            Os.setenv("LD_PRELOAD", preload, true);
            active = true;
            Log.i(TAG, "environment.active abi=" + abi + " originalPreloadPreserved=true");
        } catch (Exception | LinkageError failure) {
            deactivate();
            Log.w(TAG, "activation.unavailable stage=" + stage + " exception=" + failure.getClass().getSimpleName());
            return false;
        }
        return active;
    }

    private static String guardedAbi() throws Exception {
        if (!(Build.VERSION.SDK_INT >= 34 && Process.is64Bit()
                && Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0
                && "arm64-v8a".equals(Build.SUPPORTED_ABIS[0]))) return null;
        // Read the executable headers to verify the host and system ISA. Each
        // child independently checks its own ISA and raw system properties.
        boolean parentArm64 = elfMachine(new File("/proc/self/exe")) == 183;
        boolean systemArm64 = elfMachine(new File("/system/bin/sh")) == 183
            && elfMachine(new File("/system/bin/getprop")) == 183
            && elfMachine(new File("/system/bin/date")) == 183;
        Log.i(TAG, "guard.arm64 parentArm64=" + parentArm64 + " systemArm64=" + systemArm64);
        return parentArm64 && systemArm64 ? "arm64-v8a" : null;
    }

    private static int elfMachine(File file) throws Exception {
        byte[] header = new byte[64];
        int offset = 0;
        try (InputStream in = new FileInputStream(file)) {
            while (offset < header.length) {
                int count = in.read(header, offset, header.length - offset);
                if (count < 0) return -1;
                offset += count;
            }
        }
        if (header[0] != 127 || header[1] != 'E' || header[2] != 'L' || header[3] != 'F'
                || header[4] != 2 || header[5] != 1) return -1;
        return (header[18] & 255) | ((header[19] & 255) << 8);
    }

    static synchronized void deactivate() {
        if (saved.isEmpty()) { active = false; return; }
        Exception failure = null;
        for (Map.Entry<String, String> entry : saved.entrySet()) {
            try {
                if (entry.getValue() == null) Os.unsetenv(entry.getKey());
                else Os.setenv(entry.getKey(), entry.getValue(), true);
            } catch (Exception problem) { if (failure == null) failure = problem; }
        }
        active = false;
        if (failure != null) throw new IllegalStateException("Cannot restore child policy environment", failure);
        saved.clear();
        Log.i(TAG, "environment.restored");
    }

    static synchronized boolean isActive() { return active; }

    private static String digest(byte[] data) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(data))
            result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }

    private static byte[] bytes(InputStream input) throws Exception {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] block = new byte[8192];
            for (int count; (count = in.read(block)) >= 0;) {
                if (out.size() + count > 2 * 1024 * 1024) throw new IllegalStateException("Unexpected child library size");
                out.write(block, 0, count);
            }
            return out.toByteArray();
        }
    }

    private static File extract(Context host, String abi) throws Exception {
        byte[] data = bytes(host.getAssets().open("cage-child/" + abi + "/libcage_child_properties.so"));
        if (data.length < 64 || data[0] != 127 || data[1] != 'E' || data[2] != 'L' || data[3] != 'F'
                || data[4] != 2 || data[5] != 1 || (data[18] & 255) != 183 || data[19] != 0)
            throw new IllegalStateException("Unexpected child library ABI");
        File root = host.getCodeCacheDir().getCanonicalFile();
        File directory = new File(root, "cage-child/" + abi).getCanonicalFile();
        if (!directory.getPath().startsWith(root.getPath() + "/")) throw new IllegalStateException("Child library root mismatch");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("Child library directory unavailable");
        String expected = digest(data);
        File target = new File(directory, "libcage_child_" + expected + ".so");
        if (target.isFile() && expected.equals(digest(bytes(new FileInputStream(target))))) return target;
        File pending = File.createTempFile("policy-", ".tmp", directory);
        try {
            try (FileOutputStream out = new FileOutputStream(pending)) { out.write(data); out.getFD().sync(); }
            if (!pending.setReadable(true, true) || !pending.setReadOnly()) throw new java.io.IOException("Child library mode unavailable");
            if (!pending.renameTo(target)) throw new java.io.IOException("Child library publication unavailable");
        } finally { if (pending.exists()) pending.delete(); }
        return target;
    }
}
