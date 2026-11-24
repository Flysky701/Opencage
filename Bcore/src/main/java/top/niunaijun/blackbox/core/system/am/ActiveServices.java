package top.niunaijun.blackbox.core.system.am;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.IEmpty;
import top.niunaijun.blackbox.core.system.BProcessManagerService;
import top.niunaijun.blackbox.core.system.ProcessRecord;
import top.niunaijun.blackbox.core.system.pm.BPackageManagerService;
import top.niunaijun.blackbox.entity.UnbindRecord;
import top.niunaijun.blackbox.entity.am.RunningServiceInfo;
import top.niunaijun.blackbox.proxy.ProxyManifest;
import top.niunaijun.blackbox.proxy.record.ProxyServiceRecord;


@SuppressLint("NewApi")
public class ActiveServices {
    public static final String TAG = "ActiveServices";

    private final Map<ComponentName, RunningServiceRecord> mRunningServiceRecords = new HashMap<>();
    private final Map<IBinder, RunningServiceRecord> mRunningTokens = new HashMap<>();
    private final Map<IBinder, ConnectedServiceRecord> mConnectedServices = new HashMap<>();

    public ComponentName startService(Intent intent, String resolvedType, boolean requireForeground, int userId) {
        ResolveInfo resolved = resolveService(intent, resolvedType, userId);
        if (resolved == null) return null;
        ServiceInfo info = resolved.serviceInfo;
        ComponentName component = new ComponentName(info.packageName, info.name);
        ProcessRecord process = BProcessManagerService.get().startProcessLocked(
                info.packageName, info.processName, userId, -1, Binder.getCallingPid());
        if (process == null) return null;

        Intent target = new Intent(intent).setComponent(component);
        synchronized (mRunningServiceRecords) {
            RunningServiceRecord service = getOrCreateRunningServiceRecord(target);
            service.mServiceInfo = info;
            boolean wasStarted = service.mStarted;
            int previousId = service.mStartId.get();
            service.mStartId.incrementAndGet();
            service.mStarted = true;
            boolean accepted = false;
            try {
                Intent stub = createStubServiceIntent(target, info, process, service);
                accepted = BlackBoxCore.getContext().startService(stub) != null;
                return accepted ? component : null;
            } finally {
                if (!accepted) {
                    service.mStarted = wasStarted;
                    service.mStartId.set(previousId);
                    if (previousId == 0 && service.mBindCount.get() == 0) removeService(service);
                }
            }
        }
    }

    public int stopService(Intent intent, String resolvedType, int userId) {
        ResolveInfo resolved = resolveService(intent, resolvedType, userId);
        if (resolved == null) return 0;
        ServiceInfo info = resolved.serviceInfo;
        ComponentName component = new ComponentName(info.packageName, info.name);
        synchronized (mRunningServiceRecords) {
            RunningServiceRecord service = mRunningServiceRecords.get(component);
            return stopRunningServiceLocked(service, userId);
        }
    }

    private int stopRunningServiceLocked(RunningServiceRecord service, int userId) {
        if (service == null || (!service.mStarted && service.mBindCount.get() == 0)) return 0;
        ServiceInfo info = service.mServiceInfo;
        ProcessRecord process = BProcessManagerService.get().findProcessRecord(
                    info.packageName, info.processName, userId);
        if (process == null) {
            removeService(service);
            return 0;
        }

        // Leave earlier accepted starts cancelled even if this record is started again.
        service.mCancelledStartId = service.mStartId.get();
        service.mStarted = false;
        if (service.mBindCount.get() > 0) return 1;
        try {
            process.bActivityThread.stopService(
                    createStubServiceIntent(service.mIntent, info, process, service));
            return 1;
        } catch (RemoteException unavailable) {
            removeService(service);
            return 0;
        }
    }

    public Intent bindService(Intent intent, final IBinder binder, String resolvedType, int userId) {
        ResolveInfo resolveInfo = resolveService(intent, resolvedType, userId);
        if (resolveInfo == null)
            return intent;
        ServiceInfo serviceInfo = resolveInfo.serviceInfo;
        ProcessRecord processRecord = BProcessManagerService.get().startProcessLocked(
                serviceInfo.packageName,
                serviceInfo.processName,
                userId,
                -1, Binder.getCallingPid());

        if (processRecord == null) {
            throw new RuntimeException("Unable to create " + serviceInfo.name);
        }

        Intent target = new Intent(intent).setComponent(
                new ComponentName(serviceInfo.packageName, serviceInfo.name));
        RunningServiceRecord runningServiceRecord;
        String bindAction;
        synchronized (mRunningServiceRecords) {
            runningServiceRecord = getOrCreateRunningServiceRecord(target);
            bindAction = runningServiceRecord.mBindActions.computeIfAbsent(
                    new Intent.FilterComparison(target), key -> UUID.randomUUID().toString());
            runningServiceRecord.mServiceInfo = serviceInfo;

            if (binder != null) {
                ConnectedServiceRecord connectedService = mConnectedServices.get(binder);
                if (connectedService == null) {
                    connectedService = new ConnectedServiceRecord(binder, runningServiceRecord, userId);
                    try {
                        binder.linkToDeath(connectedService, 0);
                    } catch (RemoteException dead) {
                        return null;
                    }
                    mConnectedServices.put(binder, connectedService);
                    runningServiceRecord.incrementBindCountAndGet();
                }
            }
        }
        Intent stub = createStubServiceIntent(target, serviceInfo, processRecord, runningServiceRecord);
        // Android coalesces repeated binds to the same component and Intent filter.
        stub.setAction(bindAction);
        return stub;
    }

    public void unbindService(IBinder binder, int userId) {
        synchronized (mRunningServiceRecords) {
            ConnectedServiceRecord record = mConnectedServices.get(binder);
            if (record != null) releaseConnection(record);
        }
    }

    private void releaseConnection(ConnectedServiceRecord record) {
        if (mConnectedServices.get(record.binder) != record) return;
        mConnectedServices.remove(record.binder);
        record.service.decrementBindCountAndGet();
        record.binder.unlinkToDeath(record, 0);
    }

    public boolean stopServiceToken(ComponentName component, IBinder token, int startId, int userId) {
        synchronized (mRunningServiceRecords) {
            RunningServiceRecord service = mRunningTokens.get(token);
            if (service == null || !service.mIntent.getComponent().equals(component)) return false;
            if (startId != -1 && (startId <= 0 || startId != service.mStartId.get())) return false;
            return stopRunningServiceLocked(service, userId) > 0;
        }
    }

    public void onStartCommand(Intent proxyIntent, int userId) {
    }

    public void onServiceDestroy(Intent proxyIntent, int userId) {
        if (proxyIntent == null) return;
        ProxyServiceRecord proxy = ProxyServiceRecord.create(proxyIntent);
        synchronized (mRunningServiceRecords) {
            RunningServiceRecord service = mRunningTokens.get(proxy.mToken);
            if (service != null && !service.mStarted && service.mBindCount.get() == 0
                    && proxy.mStartId == service.mCancelledStartId) {
                removeService(service);
            }
        }
    }

    public UnbindRecord onServiceUnbind(Intent proxyIntent, int userId) throws RemoteException {
        if (proxyIntent == null) return null;
        ProxyServiceRecord proxy = ProxyServiceRecord.create(proxyIntent);
        synchronized (mRunningServiceRecords) {
            RunningServiceRecord service = mRunningTokens.get(proxy.mToken);
            if (service == null || proxy.mServiceIntent == null) return null;
            UnbindRecord record = new UnbindRecord();
            record.setComponentName(proxy.mServiceIntent.getComponent());
            record.setBindCount(service.mBindCount.get());
            record.setStartId(service.mStarted ? service.mStartId.get() : 0);
            record.setCancelledStartId(service.mCancelledStartId);
            return record;
        }
    }

    private Intent createStubServiceIntent(Intent targetIntent, ServiceInfo serviceInfo, ProcessRecord processRecord, RunningServiceRecord runningServiceRecord) {
        Intent stub = new Intent();
        ComponentName stubComp = new ComponentName(BlackBoxCore.getHostPkg(), ProxyManifest.getProxyService(processRecord.bpid));
        stub.setComponent(stubComp);
        stub.setAction(UUID.randomUUID().toString());
        ProxyServiceRecord.saveStub(stub, targetIntent, serviceInfo, runningServiceRecord, processRecord.userId, runningServiceRecord.mStartId.get());
        return stub;
    }

    private RunningServiceRecord getOrCreateRunningServiceRecord(Intent intent) {
        RunningServiceRecord runningServiceRecord = findRunningServiceRecord(intent);
        if (runningServiceRecord == null) {
            runningServiceRecord = new RunningServiceRecord();
            runningServiceRecord.mIntent = intent;
            mRunningServiceRecords.put(intent.getComponent(), runningServiceRecord);
            mRunningTokens.put(runningServiceRecord, runningServiceRecord);
        }
        return runningServiceRecord;
    }

    private RunningServiceRecord findRunningServiceRecord(Intent intent) {
        return mRunningServiceRecords.get(intent.getComponent());
    }

    private void removeService(RunningServiceRecord service) {
        mRunningServiceRecords.remove(service.mIntent.getComponent(), service);
        mRunningTokens.remove(service);
    }

    public void onTaskRemoved(String packageName, int userId, Intent rootIntent) {
        synchronized (mRunningServiceRecords) {
            for (RunningServiceRecord service : new ArrayList<>(mRunningServiceRecords.values())) {
                ServiceInfo info = service.mServiceInfo;
                if (!service.mStarted || info == null || !packageName.equals(info.packageName)) continue;
                if ((info.flags & ServiceInfo.FLAG_STOP_WITH_TASK) != 0) {
                    stopRunningServiceLocked(service, userId);
                    continue;
                }
                ProcessRecord process = BProcessManagerService.get()
                        .findProcessRecord(packageName, info.processName, userId);
                if (process == null || process.bActivityThread == null) continue;
                try {
                    process.bActivityThread.onServiceTaskRemoved(
                            createStubServiceIntent(service.mIntent, info, process, service), rootIntent);
                } catch (RemoteException unavailable) {
                    android.util.Log.w(TAG, "Unable to deliver guest task removal", unavailable);
                }
            }
        }
    }

    public RunningServiceInfo getRunningServiceInfo(String callerPackage, int userId) {
        RunningServiceInfo result = new RunningServiceInfo();
        if (callerPackage == null) return result;

        List<ActivityManager.RunningServiceInfo> ownServices = new ArrayList<>();
        synchronized (mRunningServiceRecords) {
            for (RunningServiceRecord record : mRunningServiceRecords.values()) {
                ServiceInfo service = record.mServiceInfo;
                if (service != null && callerPackage.equals(service.packageName)) {
                    ActivityManager.RunningServiceInfo info = new ActivityManager.RunningServiceInfo();
                    info.service = new ComponentName(service.packageName, service.name);
                    info.process = service.processName;
                    info.started = record.mStarted;
                    info.clientCount = record.mBindCount.get();
                    ownServices.add(info);
                }
            }
        }

        ActivityManager manager = (ActivityManager)
                BlackBoxCore.getContext().getSystemService(Context.ACTIVITY_SERVICE);
        Map<Integer, ActivityManager.RunningServiceInfo> hostServices = new HashMap<>();
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            hostServices.put(service.pid, service);
        }

        for (ActivityManager.RunningServiceInfo info : ownServices) {
            ProcessRecord process = BProcessManagerService.get()
                    .findProcessRecord(callerPackage, info.process, userId);
            if (process == null) continue;
            ActivityManager.RunningServiceInfo hostService = hostServices.get(process.pid);
            if (hostService == null) continue;
            info.process = process.processName;
            copyHostServiceFields(info, hostService);
            result.mRunningServiceInfoList.add(info);
        }
        return result;
    }

    private static void copyHostServiceFields(
            ActivityManager.RunningServiceInfo info, ActivityManager.RunningServiceInfo host) {
        info.pid = host.pid;
        info.uid = host.uid;
        info.foreground = host.foreground;
        info.activeSince = host.activeSince;
        info.crashCount = host.crashCount;
        info.lastActivityTime = host.lastActivityTime;
        info.restarting = host.restarting;
        info.flags = host.flags & ~ActivityManager.RunningServiceInfo.FLAG_STARTED;
        if (info.started) info.flags |= ActivityManager.RunningServiceInfo.FLAG_STARTED;
        info.clientPackage = host.clientPackage;
        info.clientLabel = host.clientLabel;
    }

    public IBinder peekService(Intent intent, String resolvedType, int userId) {
        ResolveInfo resolveInfo = resolveService(intent, resolvedType, userId);
        if (resolveInfo == null)
            return null;
        ProcessRecord processRecord =
                BProcessManagerService.get().findProcessRecord(resolveInfo.serviceInfo.packageName, resolveInfo.serviceInfo.processName, userId);
        if (processRecord == null)
            return null;
        try {
            return processRecord.bActivityThread.peekService(new Intent(intent).setComponent(
                    new ComponentName(resolveInfo.serviceInfo.packageName, resolveInfo.serviceInfo.name)));
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return null;
    }

    private ResolveInfo resolveService(Intent intent, String resolvedType, int userId) {
        return BPackageManagerService.get().resolveService(intent, 0, resolvedType, userId);
    }

    public static class RunningServiceRecord extends IEmpty.Stub {
        
        private final AtomicInteger mStartId = new AtomicInteger(0);
        private final AtomicInteger mBindCount = new AtomicInteger(0);
        private final Map<Intent.FilterComparison, String> mBindActions = new HashMap<>();
        private boolean mStarted;
        private int mCancelledStartId;
        
        private ServiceInfo mServiceInfo;
        private Intent mIntent;

        public int decrementBindCountAndGet() {
            return mBindCount.decrementAndGet();
        }

        public int incrementBindCountAndGet() {
            return mBindCount.incrementAndGet();
        }
    }

    private final class ConnectedServiceRecord implements IBinder.DeathRecipient {
        final IBinder binder;
        final RunningServiceRecord service;

        final int userId;

        ConnectedServiceRecord(IBinder binder, RunningServiceRecord service, int userId) {
            this.binder = binder;
            this.service = service;
            this.userId = userId;
        }

        @Override
        public void binderDied() {
            synchronized (mRunningServiceRecords) {
                if (mConnectedServices.get(binder) != this) return;
                releaseConnection(this);
                if (service.mStarted || service.mBindCount.get() != 0
                        || !mRunningTokens.containsKey(service)) return;

                // Virtual death and Android onUnbind can arrive in either order.
                // Recheck once on the guest thread, where its filter callbacks run.
                ServiceInfo info = service.mServiceInfo;
                ProcessRecord process = BProcessManagerService.get().findProcessRecord(
                        info.packageName, info.processName, userId);
                if (process == null) {
                    removeService(service);
                    return;
                }
                try {
                    process.bActivityThread.stopService(
                            createStubServiceIntent(service.mIntent, info, process, service));
                } catch (RemoteException unavailable) {
                    removeService(service);
                }
            }
        }
    }
}
