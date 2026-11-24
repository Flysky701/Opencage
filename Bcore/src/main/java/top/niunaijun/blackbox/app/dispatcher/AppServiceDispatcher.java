package top.niunaijun.blackbox.app.dispatcher;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.IBinder;

import java.util.HashMap;
import java.util.Map;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.entity.ServiceRecord;
import top.niunaijun.blackbox.entity.UnbindRecord;
import top.niunaijun.blackbox.proxy.record.ProxyServiceRecord;

import static android.app.Service.START_NOT_STICKY;



public class AppServiceDispatcher {
    public static final String TAG = "AppServiceDispatcher";

    private static final AppServiceDispatcher sServiceDispatcher = new AppServiceDispatcher();

    private Map<ComponentName, ServiceRecord> mService = new HashMap<>();

    public static AppServiceDispatcher get() {
        return sServiceDispatcher;
    }

    private final Handler mHandler = BlackBoxCore.get().getHandler();

    public IBinder onBind(Intent proxyIntent) {
        ProxyServiceRecord serviceRecord = ProxyServiceRecord.create(proxyIntent);
        Intent intent = serviceRecord.mServiceIntent;
        ServiceInfo serviceInfo = serviceRecord.mServiceInfo;

        if (intent == null || serviceInfo == null)
            return null;



        Service service = getOrCreateService(serviceRecord);
        if (service == null)
            return null;
        intent.setExtrasClassLoader(service.getClassLoader());

        ServiceRecord record = findRecord(intent);
        record.incrementAndGetBindCount(intent);
        if (record.hasBinder(intent)) {
            if (record.takeRebind(intent)) {
                service.onRebind(intent);
            }
            return record.getBinder(intent);
        }

        try {
            IBinder iBinder = service.onBind(intent);
            io.github.flysky701.opencage.runtime.MicrogNativeBinderCompat.afterBind(serviceInfo, intent);
            iBinder = io.github.flysky701.opencage.runtime.StoreBinderTransport.publish(serviceInfo, iBinder);
            record.addBinder(intent, iBinder);
            return iBinder;
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return null;
    }

    public int onStartCommand(Intent proxyIntent, int flags, int startId) {
        ProxyServiceRecord stubRecord = ProxyServiceRecord.create(proxyIntent);
        if (stubRecord.mServiceIntent == null || stubRecord.mServiceInfo == null) {
            return START_NOT_STICKY;
        }


        UnbindRecord current = BlackBoxCore.getBActivityManager().onServiceUnbind(proxyIntent, stubRecord.mUserId);
        if (current == null || !current.allowsStart(stubRecord.mStartId)) return START_NOT_STICKY;
        Service service = getOrCreateService(stubRecord);
        if (service == null) return START_NOT_STICKY;
        stubRecord.mServiceIntent.setExtrasClassLoader(service.getClassLoader());
        ServiceRecord record = findRecord(stubRecord.mServiceIntent);
        record.setStartId(stubRecord.mStartId);
        try {
            int i = service.onStartCommand(stubRecord.mServiceIntent, flags, stubRecord.mStartId);
            BlackBoxCore.getBActivityManager().onStartCommand(proxyIntent, stubRecord.mUserId);
            return i;
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return START_NOT_STICKY;
    }

    public void onDestroy() {
        if (mService.size() > 0) {
            for (ServiceRecord record : mService.values()) {
                try {
                    record.getService().onDestroy();
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
        }
        mService.clear();

    }

    public void onConfigurationChanged(Configuration newConfig) {
        if (mService.size() > 0) {
            for (ServiceRecord record : mService.values()) {
                try {
                    record.getService().onConfigurationChanged(newConfig);
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
        }

    }

    public void onLowMemory() {
        if (mService.size() > 0) {
            for (ServiceRecord record : mService.values()) {
                try {
                    record.getService().onLowMemory();
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
        }

    }

    public void onTrimMemory(int level) {
        if (mService.size() > 0) {
            for (ServiceRecord record : mService.values()) {
                try {
                    record.getService().onTrimMemory(level);
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
        }
        
    }

    public void onRebind(Intent proxyIntent) {
        ProxyServiceRecord stub = ProxyServiceRecord.create(proxyIntent);
        Intent intent = stub.mServiceIntent;
        if (intent == null) return;
        ServiceRecord record = findRecord(intent);
        if (!hasPublishedBinding(stub, record)) return;

        Service service = record.getService();
        intent.setExtrasClassLoader(service.getClassLoader());
        record.incrementAndGetBindCount(intent);
        if (record.takeRebind(intent)) service.onRebind(intent);
    }

    private boolean hasPublishedBinding(ProxyServiceRecord stub, ServiceRecord record) {
        return record != null && record.getService() != null && stub.mToken != null
                && stub.mToken.equals(record.getToken()) && record.hasBinder(stub.mServiceIntent);
    }

    public boolean onUnbind(Intent proxyIntent) {
        ProxyServiceRecord stubRecord = ProxyServiceRecord.create(proxyIntent);
        Intent intent = stubRecord.mServiceIntent;
        if (intent == null || stubRecord.mServiceInfo == null) return false;

        try {
            UnbindRecord state = BlackBoxCore.getBActivityManager().onServiceUnbind(proxyIntent, stubRecord.mUserId);
            ServiceRecord record = findRecord(intent);
            if (state == null || !hasPublishedBinding(stubRecord, record)) return false;
            Service service = record.getService();
            intent.setExtrasClassLoader(service.getClassLoader());
            if (record.decreaseConnectionCount(intent)) {
                boolean rebind = record.takeGuestBound(intent) && service.onUnbind(intent);
                // The guest callback can itself change the started state.
                UnbindRecord afterCallback = BlackBoxCore.getBActivityManager()
                        .onServiceUnbind(proxyIntent, stubRecord.mUserId);
                if (afterCallback != null && afterCallback.getStartId() == 0
                        && afterCallback.getBindCount() == 0 && !record.hasConnections()) {
                    destroyService(proxyIntent, intent, record, stubRecord.mUserId,
                            afterCallback.getCancelledStartId());
                    return false;
                }
                record.setRebind(intent, rebind);
            }
            // The host must notify us when Android reuses its cached Binder,
            // independently of whether the guest requested onRebind.
            return true;
        } catch (Throwable error) {
            error.printStackTrace();
        }
        return false;
    }

    public IBinder peekService(Intent intent) {
        ServiceRecord record = findRecord(intent);
        if (record == null) {
            return null;
        }
        return record.getBinder(intent);
    }

    public void stopService(Intent proxyIntent) {
        if (proxyIntent == null) return;
        mHandler.post(() -> {
            ProxyServiceRecord stop = ProxyServiceRecord.create(proxyIntent);
            if (stop.mServiceIntent == null) return;
            UnbindRecord current = BlackBoxCore.getBActivityManager().onServiceUnbind(proxyIntent, stop.mUserId);
            if (current == null || current.getStartId() != 0 || current.getBindCount() != 0
                    || stop.mStartId != current.getCancelledStartId()) return;

            ServiceRecord record = findRecord(stop.mServiceIntent);
            if (record == null) {
                // This accepted start may have been cancelled before the guest was created.
                acknowledgeDestroy(proxyIntent, stop.mUserId, stop.mStartId);
            } else if (record.getService() != null && java.util.Objects.equals(stop.mToken, record.getToken())
                    && record.getStartId() <= stop.mStartId && !record.hasConnections()) {
                destroyService(proxyIntent, stop.mServiceIntent, record, stop.mUserId, stop.mStartId);
            }
        });
    }

    private void destroyService(Intent proxyIntent, Intent intent, ServiceRecord record, int userId,
                                int cancelledStartId) {
        mService.remove(intent.getComponent(), record);
        try {
            record.getService().onDestroy();
        } finally {
            acknowledgeDestroy(proxyIntent, userId, cancelledStartId);
        }
    }

    private void acknowledgeDestroy(Intent proxyIntent, int userId, int cancelledStartId) {
        BlackBoxCore.getBActivityManager().onServiceDestroy(
                ProxyServiceRecord.withStartId(proxyIntent, cancelledStartId), userId);
    }

    public void onTaskRemoved(Intent proxyIntent, Intent rootIntent) {
        if (proxyIntent == null || rootIntent == null) return;
        mHandler.post(() -> {
            ProxyServiceRecord removed = ProxyServiceRecord.create(proxyIntent);
            if (removed.mServiceIntent == null || removed.mToken == null
                    || removed.mUserId != BActivityThread.getUserId()) return;
            ServiceRecord record = findRecord(removed.mServiceIntent);
            if (record == null || record.getService() == null
                    || !removed.mToken.equals(record.getToken())) return;
            Service service = record.getService();
            Intent original = new Intent(rootIntent);
            original.setExtrasClassLoader(service.getClassLoader());
            service.onTaskRemoved(original);
        });
    }

    private ServiceRecord findRecord(Intent intent) {
        return mService.get(intent.getComponent());
    }

    private Service getOrCreateService(ProxyServiceRecord proxyServiceRecord) {
        Intent intent = proxyServiceRecord.mServiceIntent;
        ServiceInfo serviceInfo = proxyServiceRecord.mServiceInfo;
        IBinder token = proxyServiceRecord.mToken;

        ServiceRecord record = findRecord(intent);
        if (record != null && record.getService() != null) {
            return record.getService();
        }
        Service service = BlackBoxCore.currentActivityThread().createService(serviceInfo, token);
        if (service == null)
            return null;
        record = new ServiceRecord();
        record.setService(service);
        record.setToken(token);
        mService.put(intent.getComponent(), record);
        return service;
    }
}
