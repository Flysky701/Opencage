package top.niunaijun.blackbox.fake.provider;

import android.content.Context;
import android.content.pm.ProviderInfo;
import android.net.Uri;

import java.io.File;
import java.util.List;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.utils.compat.BuildCompat;


public class FileProviderHandler {

    public static Uri convertFileUri(Context context, Uri uri) {
        if (BuildCompat.isN()) {
            File file = convertFile(context, uri);
            if (file == null)
                return null;
            return BlackBoxCore.getBStorageManager().getUriForFile(file.getAbsolutePath());
        }
        return uri;
    }

    public static File convertFile(Context context, Uri uri) {
        if (uri == null || !"content".equals(uri.getScheme())) return null;
        String uriAuthority = uri.getAuthority();
        if (uriAuthority == null || uriAuthority.isEmpty()) return null;

        List<ProviderInfo> providers = BActivityThread.getProviders();
        if (providers == null) return null;
        for (ProviderInfo provider : providers) {
            if (provider == null || provider.authority == null) continue;
            for (String authority : provider.authority.split(";")) {
                if (!uriAuthority.equals(authority.trim())) continue;
                try {
                    File fileForUri = FileProvider.getFileForUri(context, uriAuthority, uri);
                    if (fileForUri != null && fileForUri.exists()) {
                        return fileForUri;
                    }
                } catch (Exception ignored) {
                }
                break;
            }
        }
        return null;
    }
}
