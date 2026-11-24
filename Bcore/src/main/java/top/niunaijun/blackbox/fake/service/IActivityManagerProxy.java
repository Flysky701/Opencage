package top.niunaijun.blackbox.fake.service;

import android.Manifest;
import android.app.ActivityManager;
import android.app.IServiceConnection;
import android.content.ComponentName;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.os.IBinder;
import android.os.IInterface;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import black.android.app.BRActivityManagerNative;
import black.android.app.BRActivityManagerOreo;
import black.android.app.BRLoadedApkReceiverDispatcher;
import black.android.app.BRLoadedApkReceiverDispatcherInnerReceiver;
import black.android.app.BRLoadedApkServiceDispatcher;
import black.android.app.BRLoadedApkServiceDispatcherInnerConnection;
import black.android.content.BRContentProviderNative;
import black.android.content.pm.BRUserInfo;
import black.android.util.BRSingleton;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.core.env.AppSystemEnv;
import top.niunaijun.blackbox.entity.AppConfig;
import top.niunaijun.blackbox.entity.am.RunningAppProcessInfo;
import top.niunaijun.blackbox.entity.am.RunningServiceInfo;
import top.niunaijun.blackbox.fake.delegate.ContentProviderDelegate;
import top.niunaijun.blackbox.fake.delegate.InnerReceiverDelegate;
import top.niunaijun.blackbox.fake.delegate.ServiceConnectionDelegate;
import top.niunaijun.blackbox.fake.frameworks.BActivityManager;
import top.niunaijun.blackbox.fake.frameworks.BPackageManager;
import top.niunaijun.blackbox.fake.hook.ClassInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.fake.hook.ScanClass;
import top.niunaijun.blackbox.fake.service.base.PkgMethodProxy;
import top.niunaijun.blackbox.fake.service.context.providers.ContentProviderStub;
import top.niunaijun.blackbox.proxy.ProxyManifest;
import top.niunaijun.blackbox.proxy.record.ProxyBroadcastRecord;
import top.niunaijun.blackbox.proxy.record.ProxyPendingRecord;
import top.niunaijun.blackbox.utils.MethodParameterUtils;
import top.niunaijun.blackbox.utils.Reflector;
import top.niunaijun.blackbox.utils.compat.ActivityManagerCompat;
import top.niunaijun.blackbox.utils.compat.BuildCompat;
import top.niunaijun.blackbox.utils.compat.ParceledListSliceCompat;
import top.niunaijun.blackbox.utils.compat.TaskDescriptionCompat;
import top.niunaijun.blackbox.utils.Slog;

import static android.content.Context.RECEIVER_EXPORTED;
import static android.content.Context.RECEIVER_NOT_EXPORTED;
import static android.content.pm.PackageManager.GET_META_DATA;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;


@ScanClass(ActivityManagerCommonProxy.class)
public class IActivityManagerProxy extends ClassInvocationStub {
    public static final String TAG = "ActivityManagerStub";

    @Override
    protected Object getWho() {
        Object iActivityManager = null;
        if (BuildCompat.isOreo()) {
            iActivityManager = BRActivityManagerOreo.get().IActivityManagerSingleton();
        } else if (BuildCompat.isL()) {
            iActivityManager = BRActivityManagerNative.get().gDefault();
        }
        return BRSingleton.get(iActivityManager).get();
    }

    @Override
    protected void inject(Object base, Object proxy) {
        Object iActivityManager = null;
        if (BuildCompat.isOreo()) {
            iActivityManager = BRActivityManagerOreo.get().IActivityManagerSingleton();
        } else if (BuildCompat.isL()) {
            iActivityManager = BRActivityManagerNative.get().gDefault();
        }
        BRSingleton.get(iActivityManager)._set_mInstance(proxy);
    }

    @Override
    public boolean isBadEnv() {
        return getProxyInvocation() != getWho();
    }

    @Override
    protected void onBindMethod() {
        super.onBindMethod();
        addMethodHook(new PkgMethodProxy("getAppStartMode"));
        addMethodHook(new PkgMethodProxy("setAppLockedVerifying"));
        addMethodHook(new PkgMethodProxy("reportJunkFromApp"));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        try {
            return super.invoke(proxy, method, args);
        } catch (SecurityException e) {
            
            String methodName = method.getName();
            Slog.w(TAG, "ActivityManager invoke: SecurityException in " + methodName + ", returning safe default", e);
            
            
            if (methodName.startsWith("set") || methodName.startsWith("update")) {
                return null; 
            } else if (methodName.startsWith("get") || methodName.startsWith("query")) {
                return null; 
            } else if (methodName.startsWith("start") || methodName.startsWith("bind")) {
                return false; 
            } else if (methodName.startsWith("stop") || methodName.startsWith("unbind")) {
                return true; 
            } else {
                return null; 
            }
        } catch (Exception e) {
            Slog.e(TAG, "ActivityManager invoke: Unexpected error in " + method.getName(), e);
            return super.invoke(proxy, method, args);
        }
    }

    @ProxyMethod("getContentProvider")
    public static class GetContentProvider extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int authIndex = getAuthIndex();
            Object auth = args[authIndex];
            Object content = null;

            if (auth instanceof String) {
                if (ProxyManifest.isProxy((String) auth)) {
                    return method.invoke(who, args);
                }

                if (BuildCompat.isQ()) {
                    args[1] = BlackBoxCore.getHostPkg();
                }

                if (auth.equals("settings")
                        || auth.equals("media")
                        || auth.equals("telephony")
                        || auth.equals("com.huawei.android.launcher.settings")
                        || auth.equals("com.hihonor.android.launcher.settings")) {
                    content = method.invoke(who, args);
                    ContentProviderDelegate.update(content, (String) auth);
                    return content;
                } else {
                    

                    ProviderInfo providerInfo = BlackBoxCore.getBPackageManager()
                            .resolveContentProvider(
                                    (String) auth, GET_META_DATA, BActivityThread.getUserId());
                    if (providerInfo == null) {
                        
                        return null;
                    }

                    
                    IBinder providerBinder = null;
                    if (BActivityThread.getAppPid() != -1) {
                        AppConfig appConfig = BlackBoxCore.getBActivityManager()
                                .initProcess(
                                        providerInfo.packageName,
                                        providerInfo.processName,
                                        BActivityThread.getUserId());
                        if (appConfig.bpid != BActivityThread.getAppPid()) {
                            providerBinder = BlackBoxCore.getBActivityManager()
                                    .acquireContentProviderClient(providerInfo);
                        }
                        args[authIndex] = ProxyManifest.getProxyAuthorities(appConfig.bpid);
                        args[getUserIndex()] = BlackBoxCore.getHostUserId();
                    }
                    if (providerBinder == null)
                        return null;

                    content = method.invoke(who, args);
                    Reflector.with(content).field("info").set(providerInfo);
                    Reflector.with(content)
                            .field("provider")
                            .set(
                                    new ContentProviderStub()
                                            .wrapper(
                                                    BRContentProviderNative.get().asInterface(providerBinder),
                                                    providerInfo.packageName));
                }

                return content;
            }
            return method.invoke(who, args);
        }

        protected int getAuthIndex() {
            
            if (BuildCompat.isQ()) {
                return 2;
            } else {
                return 1;
            }
        }

        protected int getUserIndex() {
            return getAuthIndex() + 1;
        }
    }

    @ProxyMethod("startService")
    public static class StartService extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Intent intent = (Intent) args[1];
            String resolvedType = (String) args[2];
            ResolveInfo resolveInfo = BlackBoxCore.getBPackageManager().resolveService(intent, 0, resolvedType, BActivityThread.getUserId());
            if (resolveInfo == null) {
                return method.invoke(who, args);
            }

            int requireForegroundIndex = getRequireForeground();
            boolean requireForeground = false;
            if (requireForegroundIndex != -1) {
                requireForeground = (boolean) args[requireForegroundIndex];
            }
            return BlackBoxCore.getBActivityManager().startService(intent, resolvedType, requireForeground, BActivityThread.getUserId());
        }

        public int getRequireForeground() {
            if (BuildCompat.isOreo()) {
                return 3;
            }
            return -1;
        }
    }

    @ProxyMethod("stopService")
    public static class StopService extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                Intent intent = (Intent) args[1];
                String resolvedType = (String) args[2];
                
                                        
                        if (intent != null && intent.getComponent() != null) {
                            String servicePackage = intent.getComponent().getPackageName();
                            String currentPackage = BActivityThread.getAppPackageName();

                            
                            if (!servicePackage.equals(currentPackage)) {
                                Slog.w(TAG, "StopService: Attempting to stop service from different package: " +
                                        servicePackage + " (current: " + currentPackage + "), returning false");
                                return 0;
                            }
                        }
                
                return BlackBoxCore.getBActivityManager().stopService(intent, resolvedType, BActivityThread.getUserId());
            } catch (SecurityException e) {
                Slog.w(TAG, "StopService: SecurityException caught, returning false", e);
                return 0;
            } catch (Exception e) {
                Slog.e(TAG, "StopService: Error stopping service", e);
                return 0;
            }
        }
    }

    @ProxyMethod("stopServiceToken")
    public static class StopServiceToken extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            ComponentName component = (ComponentName) args[0];
            IBinder token = (IBinder) args[1];
            int startId = (Integer) args[2];
            if (component == null || token == null
                    || !component.getPackageName().equals(BActivityThread.getAppPackageName())) {
                return false;
            }
            return BlackBoxCore.getBActivityManager().stopServiceToken(
                    component, token, startId, BActivityThread.getUserId());
        }
    }

    @ProxyMethod("setActivityLocusContext")
    public static class SetActivityLocusContext extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                                        
                        if (args != null && args.length >= 2) {
                            String targetPackage = (String) args[1];
                            String currentPackage = BActivityThread.getAppPackageName();

                            
                            if (targetPackage != null && !targetPackage.equals(currentPackage)) {
                                Slog.w(TAG, "SetActivityLocusContext: Attempting to set locus context for different package: " +
                                        targetPackage + " (current: " + currentPackage + "), returning success");
                                return null; 
                            }
                        }
                
                
                return method.invoke(who, args);
            } catch (SecurityException e) {
                Slog.w(TAG, "SetActivityLocusContext: SecurityException caught, returning success", e);
                return null; 
            } catch (Exception e) {
                Slog.e(TAG, "SetActivityLocusContext: Error setting locus context", e);
                return null; 
            }
        }
    }

    public static Object BindServiceCommon(Object who, Method method, Object[] args,int callingPackageIndex) throws Throwable {
        Intent requested = (Intent) args[2];
        String requestedPackage = requested == null ? null : requested.getComponent() != null
                ? requested.getComponent().getPackageName() : requested.getPackage();
        boolean privateOnly = io.github.flysky701.opencage.runtime.GuestServicePolicy.privateOnly(
                requestedPackage, requested == null ? null : requested.getAction());
        if (requested == null) return method.invoke(who, args);
        String resolvedType = (String) args[3];
        IServiceConnection connection = (IServiceConnection) args[4];
        int requestedUserId = requested.getIntExtra("_B_|_UserId", -1);
        int userId = requestedUserId == -1 ? BActivityThread.getUserId() : requestedUserId;
        ResolveInfo resolved = BlackBoxCore.getBPackageManager().resolveService(requested, 0, resolvedType, userId);
        if (resolved == null) {
            if (privateOnly) return 0;
            if (AppSystemEnv.isOpenPackage(requested.getComponent())) {
                args[callingPackageIndex] = BlackBoxCore.getHostPkg();
            }
            return method.invoke(who, args);
        }

        Intent target = new Intent(requested).setComponent(
                new ComponentName(resolved.serviceInfo.packageName, resolved.serviceInfo.name));
        if (connection == null) {
            return bindVirtualService(who, method, args, callingPackageIndex,
                    target, resolvedType, userId, null);
        }

        IBinder original = connection.asBinder();
        synchronized (original) {
            // Serialize operations for this dispatcher only. Connection callbacks do not take this lock.
            ServiceConnectionDelegate.Binding binding =
                    ServiceConnectionDelegate.acquire(connection, target, resolvedType, userId);
            boolean accepted = false;
            try {
                Object result = bindVirtualService(who, method, args, callingPackageIndex,
                        target, resolvedType, userId, binding.delegate);
                accepted = result instanceof Number && ((Number) result).intValue() > 0;
                return result;
            } finally {
                if (!accepted && binding.created) {
                    ServiceConnectionDelegate.discard(original, binding);
                    try {
                        BlackBoxCore.getBActivityManager().unbindService(binding.delegate.asBinder(), userId);
                    } catch (RuntimeException error) {
                        Slog.w(TAG, "Unable to roll back virtual service binding", error);
                    }
                }
            }
        }
    }

    private static Object bindVirtualService(Object who, Method method, Object[] args,
                                             int callingPackageIndex, Intent target, String resolvedType,
                                             int userId, ServiceConnectionDelegate delegate) throws Throwable {
        Intent proxyIntent = BlackBoxCore.getBActivityManager().bindService(target,
                delegate == null ? null : delegate.asBinder(), resolvedType, userId);
        if (proxyIntent == null || proxyIntent.getComponent() == null
                || !BlackBoxCore.getHostPkg().equals(proxyIntent.getComponent().getPackageName())) {
            return 0;
        }

        Object[] proxyArgs = args.clone();
        proxyArgs[2] = proxyIntent;
        proxyArgs[4] = delegate;
        proxyArgs[callingPackageIndex] = BlackBoxCore.getHostPkg();
        int flagsIndex = getFlagsIndex(proxyArgs);
        if (flagsIndex >= 0) {
            Object flags = proxyArgs[flagsIndex];
            if (flags instanceof Long) {
                proxyArgs[flagsIndex] = (Long) flags & ~(long) Context.BIND_EXTERNAL_SERVICE;
            } else {
                proxyArgs[flagsIndex] = (Integer) flags & ~Context.BIND_EXTERNAL_SERVICE;
            }
        }
        return method.invoke(who, proxyArgs);
    }

    private static int getFlagsIndex(Object[] args) {
        
        for (int i = 5; i < args.length; i++) {
            Object arg = args[i];
            if (arg instanceof Integer || arg instanceof Long) {
                return i;
            }
        }
        return -1;
    }
    @ProxyMethod("bindService")
    public static class BindService extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return BindServiceCommon(who,method,args,6);
        }

        @Override
        protected boolean isEnable() {
            return BlackBoxCore.get().isBlackProcess() || BlackBoxCore.get().isServerProcess();
        }
    }

    
    @ProxyMethod("bindServiceInstance")
    public static class bindServiceInstance extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return BindServiceCommon(who,method,args,7);
        }

        @Override
        protected boolean isEnable() {
            return BlackBoxCore.get().isBlackProcess() || BlackBoxCore.get().isServerProcess();
        }
    }

    
    @ProxyMethod("bindIsolatedService")
    public static class BindIsolatedService extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            
            args[6] = null;
            return BindServiceCommon(who,method,args,7);
        }

        @Override
        protected boolean isEnable() {
            return BlackBoxCore.get().isBlackProcess() || BlackBoxCore.get().isServerProcess();
        }
    }

    @ProxyMethod("unbindService")
    public static class UnbindService extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            IServiceConnection iServiceConnection = (IServiceConnection) args[0];
            if (iServiceConnection == null) {
                return method.invoke(who, args);
            }
            IBinder original = iServiceConnection.asBinder();
            synchronized (original) {
                List<ServiceConnectionDelegate> delegates = ServiceConnectionDelegate.removeAll(original);
                if (delegates.isEmpty()) return method.invoke(who, args);

                boolean unbound = false;
                Throwable failure = null;
                for (ServiceConnectionDelegate delegate : delegates) {
                    try {
                        BlackBoxCore.getBActivityManager().unbindService(delegate.asBinder(), delegate.getUserId());
                    } catch (Exception error) {
                        Slog.w(TAG, "Unable to release virtual service binding", error);
                        if (failure == null) failure = error;
                    }
                    try {
                        unbound |= Boolean.TRUE.equals(method.invoke(who, delegate));
                    } catch (Exception error) {
                        if (failure == null) failure = error;
                    }
                }
                // A dispatcher may also have a platform-only binding.
                try {
                    unbound |= Boolean.TRUE.equals(method.invoke(who, args));
                } catch (Exception error) {
                    if (failure == null) failure = error;
                }
                if (failure != null) throw failure;
                return unbound;
            }
        }
    }

    @ProxyMethod("getRunningAppProcesses")
    public static class GetRunningAppProcesses extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            RunningAppProcessInfo runningAppProcesses = BActivityManager.get().getRunningAppProcesses(BActivityThread.getAppPackageName(), BActivityThread.getUserId());
            if (runningAppProcesses == null) {
                return new ArrayList<>();
            }
            return runningAppProcesses.mAppProcessInfoList;
        }
    }

    @ProxyMethod("getServices")
    public static class GetServices extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int maxNum = (int) args[0];
            if (maxNum <= 0) return new ArrayList<>();
            RunningServiceInfo runningServices = BActivityManager.get().getRunningServices(BActivityThread.getAppPackageName(), BActivityThread.getUserId());
            if (runningServices == null) {
                return new ArrayList<>();
            }
            int size = runningServices.mRunningServiceInfoList.size();
            if (size <= maxNum) return runningServices.mRunningServiceInfoList;
            return new ArrayList<>(runningServices.mRunningServiceInfoList.subList(0, maxNum));
        }
    }

    @ProxyMethod("getIntentSender")
    public static class GetIntentSender extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int type = (int) args[0];
            Intent[] intents = (Intent[]) args[getIntentsIndex(args)];
            MethodParameterUtils.replaceFirstAppPkg(args);

            for (int i = 0; i < intents.length; i++) {
                Intent intent = intents[i];
                switch (type) {
                    case ActivityManagerCompat.INTENT_SENDER_ACTIVITY:
                        Intent shadow = new Intent();
                        shadow.setComponent(new ComponentName(BlackBoxCore.getHostPkg(), ProxyManifest.getProxyPendingActivity(BActivityThread.getAppPid())));
                        ProxyPendingRecord.saveStub(shadow, intent, BActivityThread.getUserId());
                        intents[i] = shadow;
                        break;
                }
            }
            io.github.flysky701.opencage.space.GuestPendingActivity.prepare(method, args);
            IInterface invoke = (IInterface) method.invoke(who, args);
            io.github.flysky701.opencage.space.GuestPendingActivity.created(invoke, method, args);
            if (invoke != null) {
                String[] packagesForUid = BPackageManager.get().getPackagesForUid(BActivityThread.getCallingBUid());
                if (packagesForUid.length < 1) {
                    packagesForUid = new String[]{BlackBoxCore.getHostPkg()};
                }
                BlackBoxCore.getBActivityManager().getIntentSender(invoke.asBinder(), packagesForUid[0], BActivityThread.getCallingBUid());
            }
            return invoke;
        }

        private int getIntentsIndex(Object[] args) {
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof Intent[]) {
                    return i;
                }
            }
            if (BuildCompat.isR()) {
                return 6;
            } else {
                return 5;
            }
        }
    }

    @ProxyMethod("getPackageForIntentSender")
    public static class getPackageForIntentSender extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            IInterface invoke = (IInterface) args[0];
            return BlackBoxCore.getBActivityManager().getPackageForIntentSender(invoke.asBinder());
        }
    }

    @ProxyMethod("getUidForIntentSender")
    public static class getUidForIntentSender extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            IInterface invoke = (IInterface) args[0];
            return BlackBoxCore.getBActivityManager().getUidForIntentSender(invoke.asBinder());
        }
    }

    @ProxyMethod("getIntentSenderWithSourceToken")
    public static class GetIntentSenderWithSourceToken extends GetIntentSender {
    }

    @ProxyMethod("getIntentSenderWithFeature")
    public static class GetIntentSenderWithFeature extends GetIntentSender {
    }

    @ProxyMethod("broadcastIntentWithFeature")
    public static class BroadcastIntentWithFeature extends BroadcastIntent {
    }

    @ProxyMethod("broadcastIntent")
    public static class BroadcastIntent extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int intentIndex = getIntentIndex(args);
            Intent intent = (Intent) args[intentIndex];
            String resolvedType = (String) args[intentIndex + 1];
            Intent proxyIntent = BlackBoxCore.getBActivityManager().sendBroadcast(intent, resolvedType, BActivityThread.getUserId());
            if (proxyIntent != null) {
                proxyIntent.setExtrasClassLoader(BActivityThread.getApplication().getClassLoader());
                ProxyBroadcastRecord.saveStub(proxyIntent, intent, BActivityThread.getUserId());
                args[intentIndex] = proxyIntent;
            }
            
            for (int i = 0; i < args.length; i++) {
                Object o = args[i];
                if (o instanceof String[]) {
                    args[i] = null;
                }
            }
            return method.invoke(who, args);
        }

        int getIntentIndex(Object[] args) {
            for (int i = 0; i < args.length; i++) {
                Object arg = args[i];
                if (arg instanceof Intent) {
                    return i;
                }
            }
            return 1;
        }
    }

    @ProxyMethod("unregisterReceiver")
    public static class unregisterReceiver extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("finishReceiver")
    public static class finishReceiver extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("publishService")
    public static class PublishService extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("peekService")
    public static class PeekService extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceLastAppPkg(args);
            Intent intent = (Intent) args[0];
            String resolvedType = (String) args[1];
            IBinder peek = BlackBoxCore.getBActivityManager().peekService(intent, resolvedType, BActivityThread.getUserId());
            return peek;
        }
    }

    
    @ProxyMethod("sendIntentSender")
    public static class SendIntentSender extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return 0;
        }
    }

    
    @ProxyMethod("registerReceiverWithFeature")
    public static class RegisterReceiverWithFeature extends MethodHook{
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            int receiverIndex = getReceiverIndex();
            if (args[receiverIndex] != null) {
                IIntentReceiver intentReceiver = (IIntentReceiver) args[receiverIndex];
                IIntentReceiver proxy = InnerReceiverDelegate.createProxy(intentReceiver);

                WeakReference<?> weakReference = BRLoadedApkReceiverDispatcherInnerReceiver.get(intentReceiver).mDispatcher();
                if (weakReference != null) {
                    BRLoadedApkReceiverDispatcher.get(weakReference.get())._set_mIIntentReceiver(proxy);
                }

                args[receiverIndex] = proxy;
            }
            
            if (args[getPermissionIndex()] != null) {
                args[getPermissionIndex()] = null;
            }

            if (BuildCompat.isU()) {
                int flagsIndex = args.length - 1;
                int flags = (int)args[flagsIndex];
                if((flags & RECEIVER_NOT_EXPORTED) == 0 && (flags & RECEIVER_EXPORTED) == 0){
                    flags |= RECEIVER_NOT_EXPORTED;
                }
                args[flagsIndex] = flags;
            }

            return method.invoke(who, args);
        }

        public int getReceiverIndex() {
            if (BuildCompat.isS()) {
                return 4;
            }
            return 3;
        }

        public int getPermissionIndex() {
            if (BuildCompat.isS()) {
                return 6;
            }
            return 5;
        }
    }

    
    @ProxyMethod("registerReceiver")
    public static class RegisterReceiver extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            int receiverIndex = 2;
            if (args[receiverIndex] != null) {
                IIntentReceiver intentReceiver = (IIntentReceiver) args[receiverIndex];
                IIntentReceiver proxy = InnerReceiverDelegate.createProxy(intentReceiver);

                WeakReference<?> weakReference = BRLoadedApkReceiverDispatcherInnerReceiver.get(intentReceiver).mDispatcher();
                if (weakReference != null) {
                    BRLoadedApkReceiverDispatcher.get(weakReference.get())._set_mIIntentReceiver(proxy);
                }

                args[receiverIndex] = proxy;
            }
            int permissionIndex = 4;
            
            if (args[permissionIndex] != null) {
                args[permissionIndex] = null;
            }
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("grantUriPermission")
    public static class GrantUriPermission extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceLastUid(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("setServiceForeground")
    public static class setServiceForeground extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            
            
            for (int i = args.length - 1; i >= 0; i--) {
                if (args[i] instanceof Integer) {
                    args[i] = 0; 
                    break;
                }
            }
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getHistoricalProcessExitReasons")
    public static class getHistoricalProcessExitReasons extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return ParceledListSliceCompat.create(new ArrayList<>());
        }
    }

    @ProxyMethod("getCurrentUser")
    public static class getCurrentUser extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Object blackBox = BRUserInfo.get()._new(BActivityThread.getUserId(), "BlackBox", BRUserInfo.get().FLAG_PRIMARY());
            return blackBox;
        }
    }

    // Android 14+ Context checks use (permission, pid, uid, deviceId).
    // The last integer is a device ID, not a UID. Keep the system's decision for
    // the real host, and require the guest to have declared the permission too.
    @ProxyMethod("checkPermissionForDevice")
    public static class CheckPermissionForDevice extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (args != null && args.length == 4 && args[1] instanceof Integer
                    && args[2] instanceof Integer && (int) args[1] == android.os.Process.myPid()
                    && (int) args[2] == BActivityThread.getBUid()
                    && BActivityThread.getAppPackageName() != null) {
                android.content.pm.PackageInfo guest = BlackBoxCore.getBPackageManager().getService()
                        .getPackageInfo(BActivityThread.getAppPackageName(),
                                PackageManager.GET_PERMISSIONS, BActivityThread.getUserId());
                if (guest == null || guest.requestedPermissions == null
                        || !java.util.Arrays.asList(guest.requestedPermissions).contains(args[0]))
                    return PackageManager.PERMISSION_DENIED;
                Object[] mapped = args.clone();
                mapped[2] = BlackBoxCore.getHostUid();
                return method.invoke(who, mapped);
            }
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("checkPermission")
    public static class checkPermission extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceLastUid(args);
            String permission = (String) args[0];
            if (permission.equals(Manifest.permission.ACCOUNT_MANAGER)
                    || permission.equals(Manifest.permission.SEND_SMS)) {
                return PackageManager.PERMISSION_GRANTED;
            }
            
            
            if (isAudioPermission(permission)) {
                Slog.d(TAG, "ActivityManager checkPermission: Granting audio permission: " + permission);
                return PackageManager.PERMISSION_GRANTED;
            }

            
            if (isStorageOrMediaPermission(permission)) {
                Slog.d(TAG, "ActivityManager checkPermission: Granting storage/media permission: " + permission);
                return PackageManager.PERMISSION_GRANTED;
            }
            
            return method.invoke(who, args);
        }
    }

    
    private static boolean isAudioPermission(String permission) {
        if (permission == null) return false;
        return permission.equals(Manifest.permission.RECORD_AUDIO)
                || permission.equals(Manifest.permission.CAPTURE_AUDIO_OUTPUT)
                || permission.equals(Manifest.permission.MODIFY_AUDIO_SETTINGS)
                || permission.equals("android.permission.FOREGROUND_SERVICE_MICROPHONE")
                || permission.equals("android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION")
                || permission.equals("android.permission.FOREGROUND_SERVICE_CAMERA")
                || permission.equals("android.permission.FOREGROUND_SERVICE_LOCATION")
                || permission.equals("android.permission.FOREGROUND_SERVICE_HEALTH")
                || permission.equals("android.permission.FOREGROUND_SERVICE_DATA_SYNC")
                || permission.equals("android.permission.FOREGROUND_SERVICE_SPECIAL_USE")
                || permission.equals("android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED")
                || permission.equals("android.permission.FOREGROUND_SERVICE_PHONE_CALL")
                || permission.equals("android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE");
    }

    @ProxyMethod("checkUriPermission")
    public static class checkUriPermission extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return PERMISSION_GRANTED;
        }
    }

    
    private static boolean isStorageOrMediaPermission(String permission) {
        if (permission == null) return false;
        if (permission.equals(Manifest.permission.READ_EXTERNAL_STORAGE)
                || permission.equals(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            return true;
        }
        if (permission.equals(Manifest.permission.READ_MEDIA_AUDIO)
                || permission.equals(Manifest.permission.READ_MEDIA_VIDEO)
                || permission.equals(Manifest.permission.READ_MEDIA_IMAGES)
                || permission.equals("android.permission.READ_MEDIA_VISUAL")
                || permission.equals("android.permission.READ_MEDIA_AURAL")
                || permission.equals(Manifest.permission.ACCESS_MEDIA_LOCATION)) {
            return true;
        }
        if (permission.equals("android.permission.READ_MEDIA_AUDIO_USER_SELECTED")
                || permission.equals("android.permission.READ_MEDIA_VIDEO_USER_SELECTED")
                || permission.equals("android.permission.READ_MEDIA_IMAGES_USER_SELECTED")
                || permission.equals("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
                || permission.equals("android.permission.READ_MEDIA_AURAL_USER_SELECTED")) {
                return true;
        }
        return false;
    }

    
    @ProxyMethod("setTaskDescription")
    public static class SetTaskDescription extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            ActivityManager.TaskDescription td = (ActivityManager.TaskDescription) args[1];
            args[1] = TaskDescriptionCompat.fix(td);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("setRequestedOrientation")
    public static class setRequestedOrientation extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(who, args);
            } catch (Throwable e) {
                e.printStackTrace();
            }
            return 0;
        }
    }

    @ProxyMethod("registerUidObserver")
    public static class registerUidObserver extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return 0;
        }
    }

    @ProxyMethod("unregisterUidObserver")
    public static class unregisterUidObserver extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return 0;
        }
    }

    @ProxyMethod("updateConfiguration")
    public static class updateConfiguration extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return 0;
        }
    }
}
