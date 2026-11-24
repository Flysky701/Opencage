package top.niunaijun.blackbox.proxy;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.BadParcelableException;
import android.os.IBinder;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.dispatcher.AppServiceDispatcher;
import top.niunaijun.blackbox.proxy.record.ProxyActivityRecord;
import top.niunaijun.blackbox.utils.compat.BuildCompat;


public class ProxyService extends Service {
    public static final String TAG = "StubService";

    @Override
    public IBinder onBind(Intent intent) {
        return AppServiceDispatcher.get().onBind(intent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        AppServiceDispatcher.get().onStartCommand(intent, flags, startId);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        AppServiceDispatcher.get().onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        AppServiceDispatcher.get().onConfigurationChanged(newConfig);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        AppServiceDispatcher.get().onLowMemory();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        AppServiceDispatcher.get().onTrimMemory(level);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return AppServiceDispatcher.get().onUnbind(intent);
    }

    @Override
    public void onRebind(Intent intent) {
        AppServiceDispatcher.get().onRebind(intent);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        if (rootIntent == null || rootIntent.getComponent() == null
                || !BlackBoxCore.getHostPkg().equals(rootIntent.getComponent().getPackageName())) return;
        IBinder root;
        try {
            root = ProxyActivityRecord.getActivityRecord(rootIntent);
        } catch (BadParcelableException malformed) {
            return;
        }
        if (root != null) BlackBoxCore.getBActivityManager().onProxyTaskRemoved(root);
    }

    private void showNotification() {
        Notification.Builder builder = new Notification.Builder(getApplicationContext(), getPackageName() + ".blackbox_proxy")
                .setPriority(Notification.PRIORITY_MAX);
        if (BuildCompat.isOreo()) {
            startForeground(BlackBoxCore.getHostPkg().hashCode(), builder.build());
        }
    }

    public static class P0 extends ProxyService {

    }

    public static class P1 extends ProxyService {

    }

    public static class P2 extends ProxyService {

    }

    public static class P3 extends ProxyService {

    }

    public static class P4 extends ProxyService {

    }

    public static class P5 extends ProxyService {

    }

    public static class P6 extends ProxyService {

    }

    public static class P7 extends ProxyService {

    }

    public static class P8 extends ProxyService {

    }

    public static class P9 extends ProxyService {

    }

    public static class P10 extends ProxyService {

    }

    public static class P11 extends ProxyService {

    }

    public static class P12 extends ProxyService {

    }

    public static class P13 extends ProxyService {

    }

    public static class P14 extends ProxyService {

    }

    public static class P15 extends ProxyService {

    }
}
