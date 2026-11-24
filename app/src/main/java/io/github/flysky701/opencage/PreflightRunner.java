package io.github.flysky701.opencage;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.env.BEnvironment;

import static io.github.flysky701.opencage.BundledApps.sha;

/** One fresh in-space probe session per run. Old files and partial results cannot authorize entry. */
final class PreflightRunner {
    interface Callback { void complete(CheckReport report); }
    static final String PROBE_PKG = "io.github.flysky701.opencage.preflightprobe";
    private static final int PROBE_VERSION = 7;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private volatile boolean canceled;

    void cancel() { canceled = true; }

    void run(Context context, Callback callback) {
        final Context app = context.getApplicationContext();
        final String request = UUID.randomUUID().toString();
        final String fingerprint = Checks.fingerprint(app);
        WORKER.execute(() -> {
            CheckReport result = new CheckReport(request, fingerprint,
                    SystemClock.elapsedRealtime(), System.currentTimeMillis());
            try {
                if (app instanceof CandidateApplication) {
                    ((CandidateApplication) app).prepareHostServices();
                }
                try {
                    ExitBinding.verifyLaunch(app);
                } catch (IOException exitFailed) {
                    result.error = "ExitBindingFailed";
                    result.add("exit_binding", "绑定出口核对", CheckPolicy.State.FAILED,
                            exitFailed.getMessage() != null ? exitFailed.getMessage()
                                    : "当前出口与绑定 IP 不一致，请在「绑定出口 IP」中核对。");
                    if (!canceled) MAIN.post(() -> { if (!canceled) callback.complete(result); });
                    return;
                }
                ensureProbe(app);
                Checks.Outer outer = Checks.outer(app);
                if (canceled) return;
                BundledApps.service().stopPackage(PROBE_PKG, 0);
                final long start = SystemClock.elapsedRealtime();
                MAIN.post(() -> {
                    if (canceled) return;
                    try {
                        Intent launch = new Intent(Intent.ACTION_MAIN)
                                .setComponent(new ComponentName(PROBE_PKG, PROBE_PKG + ".ProbeActivity$Preflight"))
                                .putExtra("cage.preflight.request", request)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        BlackBoxCore.get().startActivity(launch, 0);
                    } catch (Exception ignored) {
                        // Poll ends as unknown; never accept an old report.
                    }
                });
                JSONObject guest = null;
                File file = reportFile();
                while (!canceled && SystemClock.elapsedRealtime() - start < 55000) {
                    try {
                        JSONObject value = read(file);
                        if (CheckPolicy.fresh(request, value.optString("preflightRequest"), start,
                                value.optLong("startedElapsedRealtimeMillis"), value.optBoolean("complete"),
                                SystemClock.elapsedRealtime())
                                && PROBE_PKG.equals(value.optString("package"))
                                && value.optInt("probeVersion") == PROBE_VERSION) {
                            guest = value;
                            break;
                        }
                    } catch (Exception ignored) {
                    }
                    Thread.sleep(250);
                }
                if (canceled) return;
                if (guest == null) throw new IllegalStateException("ProbeUnavailable");
                Checks.evaluate(result, outer, guest);
                if (!fingerprint.equals(Checks.fingerprint(app))) {
                    result.add("environment_changed", "检查环境已变化", CheckPolicy.State.FAILED,
                            "检查期间网络或系统环境发生变化，请重新检查。");
                }
            } catch (Exception error) {
                result.error = error.getClass().getSimpleName();
                result.add("measurement", "检查未完成", CheckPolicy.State.UNKNOWN,
                        "未取得本轮空间检测结果。请返回重试；这不代表 SIM、VPN 或时区检查通过。");
            }
            if (!canceled) MAIN.post(() -> {
                if (!canceled) callback.complete(result);
            });
        });
    }

    private static File reportFile() {
        return new File(BEnvironment.getDataDir(PROBE_PKG, 0), "files/cage-preflight-result.json");
    }

    private static void ensureProbe(Context host) throws Exception {
        String recorded = host.getSharedPreferences("cage-android-ui", Context.MODE_PRIVATE)
                .getString("probeAsset", "");
        if (BuildInfo.PROBE_SHA.equals(recorded)) {
            try {
                PackageInfo info = BundledApps.installed(host, PROBE_PKG);
                if (matchesInstalledProbe(info)) return;
            } catch (Exception unavailable) {
                // An unverifiable installed APK follows the normal reinstall path.
            }
        }
        File dir = new File(host.getCodeCacheDir(), "cage-preflight");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Diagnostic cache");
        File apk = new File(dir, "probe-" + BuildInfo.PROBE_SHA + ".apk");
        try {
            try (InputStream in = host.getAssets().open("cage-android/preflight.apk");
                 FileOutputStream out = new FileOutputStream(apk)) {
                byte[] bytes = new byte[8192];
                for (int n; (n = in.read(bytes)) != -1;) out.write(bytes, 0, n);
                out.getFD().sync();
            }
            if (!BuildInfo.PROBE_SHA.equals(sha(apk))) throw new IllegalStateException("Diagnostic digest");
            PackageInfo info = host.getPackageManager().getPackageArchiveInfo(apk.getPath(), 0);
            if (info == null || !PROBE_PKG.equals(info.packageName) || info.getLongVersionCode() != PROBE_VERSION) {
                throw new IllegalStateException("Diagnostic package");
            }
            BundledApps.install(apk);
            PackageInfo installedInfo = BundledApps.installed(host, PROBE_PKG);
            if (!matchesInstalledProbe(installedInfo)) throw new IllegalStateException("Diagnostic install");
            host.getSharedPreferences("cage-android-ui", Context.MODE_PRIVATE)
                    .edit().putString("probeAsset", BuildInfo.PROBE_SHA).apply();
        } finally {
            apk.delete();
        }
    }

    private static boolean matchesInstalledProbe(PackageInfo info) throws Exception {
        if (info == null || !PROBE_PKG.equals(info.packageName) || info.getLongVersionCode() != PROBE_VERSION) {
            return false;
        }
        ApplicationInfo installed = info.applicationInfo;
        if (installed == null || installed.sourceDir == null || installed.sourceDir.isEmpty()) {
            return false;
        }
        File installedApk = new File(installed.sourceDir);
        return installedApk.isFile() && BuildInfo.PROBE_SHA.equals(sha(installedApk));
    }

    private static JSONObject read(File file) throws Exception {
        long length = file.length();
        if (length < 2 || length > 262144) throw new IOException("Report size");
        byte[] data = new byte[(int) length];
        try (FileInputStream in = new FileInputStream(file)) {
            int at = 0;
            while (at < data.length) {
                int count = in.read(data, at, data.length - at);
                if (count < 0) throw new IOException("Short report");
                at += count;
            }
        }
        return new JSONObject(new String(data, StandardCharsets.UTF_8));
    }
}
