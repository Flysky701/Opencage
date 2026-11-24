package top.niunaijun.blackbox.proxy;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.entity.AppConfig;
import top.niunaijun.blackbox.utils.compat.BundleCompat;


public class ProxyContentProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return false;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (method.equals("_Black_|_init_process_")) {
            assert extras != null;
            extras.setClassLoader(AppConfig.class.getClassLoader());
            AppConfig appConfig = extras.getParcelable(AppConfig.KEY);
            BlackBoxCore.currentActivityThread().initProcess(appConfig);

            Bundle bundle = new Bundle();
            BundleCompat.putBinder(bundle, "_Black_|_client_", BlackBoxCore.currentActivityThread());
            bundle.putInt("_Black_|_pid_", android.os.Process.myPid());
            return bundle;
        }
        return super.call(method, arg, extras);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    public static class P0 extends ProxyContentProvider {

    }

    public static class P1 extends ProxyContentProvider {

    }

    public static class P2 extends ProxyContentProvider {

    }

    public static class P3 extends ProxyContentProvider {

    }

    public static class P4 extends ProxyContentProvider {

    }

    public static class P5 extends ProxyContentProvider {

    }

    public static class P6 extends ProxyContentProvider {

    }

    public static class P7 extends ProxyContentProvider {

    }

    public static class P8 extends ProxyContentProvider {

    }

    public static class P9 extends ProxyContentProvider {

    }

    public static class P10 extends ProxyContentProvider {

    }

    public static class P11 extends ProxyContentProvider {

    }

    public static class P12 extends ProxyContentProvider {

    }

    public static class P13 extends ProxyContentProvider {

    }

    public static class P14 extends ProxyContentProvider {

    }

    public static class P15 extends ProxyContentProvider {

    }
}
