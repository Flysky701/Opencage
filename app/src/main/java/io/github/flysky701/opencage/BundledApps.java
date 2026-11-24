package io.github.flysky701.opencage;

import android.content.Context;
import android.content.pm.PackageInfo;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.system.pm.IBPackageManagerService;
import top.niunaijun.blackbox.entity.pm.InstallOption;
import top.niunaijun.blackbox.entity.pm.InstallResult;

final class BundledApps {
    interface Progress { void show(String message); }
    static IBPackageManagerService service() throws IOException {
        IBPackageManagerService service = BlackBoxCore.getBPackageManager().getService();
        if (service == null) throw new IOException("空间服务未就绪，请点重试准备");
        return service;
    }
    static PackageInfo installed(Context host, String pkg) throws Exception {
        PackageInfo info = service().getPackageInfo(pkg, 0, 0);
        if (info == null) return null;
        File expected = new File(host.getDataDir(), "blackbox/data/app/" + pkg + "/base.apk");
        if (info.applicationInfo == null || info.applicationInfo.sourceDir == null || info.applicationInfo.sourceDir.isEmpty()
                || !expected.getCanonicalFile().equals(new File(info.applicationInfo.sourceDir).getCanonicalFile()))
            throw new IOException("应用路径不属于当前空间：" + pkg);
        return info;
    }
    static List<PackageInfo> list() throws Exception { return service().getInstalledPackages(0, 0); }
    static void install(File apk) throws Exception {
        InstallResult result = service().installPackageAsUser(apk.getAbsolutePath(), InstallOption.installByStorage(), 0);
        if (result == null || !result.success) throw new IOException(result == null ? "安装服务未返回结果" : result.msg);
    }
    static synchronized void prepare(Context host, Progress progress) throws Exception {
        JSONArray items = new JSONObject(readAsset(host, "bundle/manifest.json")).getJSONArray("apps");
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            String label = item.getString("label"), pkg = item.getString("package");
            PackageInfo current = installed(host, pkg);
            boolean updateProbe = current != null && "io.github.flysky701.opencage.probe".equals(pkg)
                    && current.getLongVersionCode() < item.getLong("versionCode");
            if (current != null && !updateProbe) {
                progress.show(pkg + " " + current.versionName + " 已在空间内，保留现有版本和数据");
                continue;
            }
            progress.show("正在准备 " + label + "（" + (i + 1) + "/" + items.length() + "）…");
            File temporary = File.createTempFile("bundle-", ".apk", host.getCacheDir());
            try {
                try (InputStream in = host.getAssets().open("bundle/" + item.getString("file"));
                     FileOutputStream out = new FileOutputStream(temporary)) {
                    byte[] buffer = new byte[65536]; int count;
                    while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                }
                String expectedSha = item.getString("sha256");
                if (!expectedSha.equals(sha(temporary))) throw new IOException(label + " 文件校验失败");
                PackageInfo archive = host.getPackageManager().getPackageArchiveInfo(temporary.getPath(), 0);
                if (archive == null || !pkg.equals(archive.packageName)
                        || archive.getLongVersionCode() != item.getLong("versionCode"))
                    throw new IOException(label + " 包信息不匹配");
                install(temporary);
                PackageInfo saved = installed(host, pkg);
                if (saved == null || !pkg.equals(saved.packageName)
                        || saved.getLongVersionCode() != item.getLong("versionCode"))
                    throw new IOException(label + " 安装后读取失败");
                if (!expectedSha.equals(sha(new File(saved.applicationInfo.sourceDir))))
                    throw new IOException(label + " 安装后文件校验失败");
                progress.show(label + " 准备完成");
            } finally { temporary.delete(); }
        }
    }
    static String sha(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[8192];
            for (int count; (count = in.read(bytes)) != -1;) digest.update(bytes, 0, count);
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", value & 255));
        return hex.toString();
    }
    static String readAsset(Context host, String path) throws IOException {
        try (InputStream in = host.getAssets().open(path); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = in.read(buffer)) != -1) bytes.write(buffer, 0, count);
            return bytes.toString("UTF-8");
        }
    }
}
