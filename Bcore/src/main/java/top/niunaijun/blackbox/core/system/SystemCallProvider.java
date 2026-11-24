package top.niunaijun.blackbox.core.system;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;

import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.BundleCompat;


public class SystemCallProvider extends ContentProvider {
    public static final String TAG = "SystemCallProvider";

    @Override
    public boolean onCreate() {
        try {
            Slog.d(TAG, "SystemCallProvider onCreate called");
            return initSystem();
        } catch (Exception e) {
            Slog.e(TAG, "Error in SystemCallProvider onCreate", e);
            return false;
        }
    }

    private boolean initSystem() {
        try {
            Slog.d(TAG, "Initializing BlackBox system...");
            BlackBoxSystem.getSystem().startup();
            Slog.d(TAG, "BlackBox system initialized successfully");
            return true;
        } catch (Exception e) {
            Slog.e(TAG, "Failed to initialize BlackBox system", e);
            return false;
        }
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle pending = io.github.flysky701.opencage.space.GuestPendingActivity.call(this, method, arg, extras);
        if (pending != null) return pending;
        try {
            Slog.d(TAG, "call: " + method + ", " + extras);
            if (io.github.flysky701.opencage.runtime.GuestAccountBridge.METHOD.equals(method))
                return io.github.flysky701.opencage.runtime.GuestAccountBridge.identify(this, arg, extras);
            Bundle installer = io.github.flysky701.opencage.space.GuestInstaller.provide(this, method, arg, extras);
            if (installer != null) return installer;
            if ("VM".equals(method)) {
                Bundle bundle = new Bundle();
                if (extras != null) {
                    String name = extras.getString("_B_|_server_name_");
                    Slog.d(TAG, "Requesting service: " + name);
                    IBinder service = ServiceManager.getService(name);
                    if (service != null) {
                        BundleCompat.putBinder(bundle, "_B_|_server_", service);
                        Slog.d(TAG, "Service " + name + " provided successfully");
                    } else {
                        Slog.w(TAG, "Service " + name + " not found");
                    }
                }
                return bundle;
            }
            return super.call(method, arg, extras);
        } catch (Exception e) {
            Slog.e(TAG, "Error in SystemCallProvider call method: " + method, e);
            
            Bundle errorBundle = new Bundle();
            errorBundle.putString("error", e.getMessage());
            return errorBundle;
        }
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
}
