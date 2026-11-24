package top.niunaijun.blackbox.fake.hook;

import android.util.Log;

import java.util.HashMap;
import java.util.Map;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.fake.delegate.AppInstrumentation;

import top.niunaijun.blackbox.fake.service.HCallbackProxy;
import top.niunaijun.blackbox.fake.service.IAccessibilityManagerProxy;
import top.niunaijun.blackbox.fake.service.IAccountManagerProxy;
import top.niunaijun.blackbox.fake.service.IActivityClientProxy;
import top.niunaijun.blackbox.fake.service.IActivityManagerProxy;
import top.niunaijun.blackbox.fake.service.IActivityTaskManagerProxy;
import top.niunaijun.blackbox.fake.service.IAlarmManagerProxy;
import top.niunaijun.blackbox.fake.service.IAppOpsManagerProxy;
import top.niunaijun.blackbox.fake.service.IAutofillManagerProxy;
import top.niunaijun.blackbox.fake.service.ISensitiveContentProtectionManagerProxy;

import top.niunaijun.blackbox.fake.service.IDeviceIdentifiersPolicyProxy;
import top.niunaijun.blackbox.fake.service.IDevicePolicyManagerProxy;
import top.niunaijun.blackbox.fake.service.IDisplayManagerProxy;
import top.niunaijun.blackbox.fake.service.IGraphicsStatsProxy;
import top.niunaijun.blackbox.fake.service.IJobServiceProxy;
import top.niunaijun.blackbox.fake.service.ILauncherAppsProxy;
import top.niunaijun.blackbox.fake.service.IMediaSessionManagerProxy;
import top.niunaijun.blackbox.fake.service.IAudioServiceProxy;
import top.niunaijun.blackbox.fake.service.ISensorPrivacyManagerProxy;
import top.niunaijun.blackbox.fake.service.IMiuiSecurityManagerProxy;
import top.niunaijun.blackbox.fake.service.NetworkPermissionCompat;

import top.niunaijun.blackbox.fake.service.AudioPermissionProxy;

import top.niunaijun.blackbox.fake.service.INetworkManagementServiceProxy;
import top.niunaijun.blackbox.fake.service.INotificationManagerProxy;
import top.niunaijun.blackbox.fake.service.IPackageManagerProxy;
import top.niunaijun.blackbox.fake.service.IPermissionManagerProxy;

import top.niunaijun.blackbox.fake.service.IPowerManagerProxy;
import top.niunaijun.blackbox.fake.service.IStorageManagerProxy;

import top.niunaijun.blackbox.fake.service.ITelephonyRegistryProxy;
import top.niunaijun.blackbox.fake.service.IUserManagerProxy;
import top.niunaijun.blackbox.fake.service.IWifiManagerProxy;
import top.niunaijun.blackbox.fake.service.IWindowManagerProxy;
import top.niunaijun.blackbox.fake.service.context.ContentServiceStub;
import top.niunaijun.blackbox.fake.service.context.RestrictionsManagerStub;
import top.niunaijun.blackbox.fake.service.libcore.OsStub;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.BuildCompat;



public class HookManager {
    public static final String TAG = "HookManager";

    private static final HookManager sHookManager = new HookManager();

    private final Map<Class<?>, IInjectHook> mInjectors = new HashMap<>();

    public static HookManager get() {
        return sHookManager;
    }

    public void init() {
        if (BlackBoxCore.get().isBlackProcess() || BlackBoxCore.get().isServerProcess()) {
            addInjector(new IDisplayManagerProxy());
            addInjector(new OsStub());
            addInjector(new IActivityManagerProxy());
            addInjector(new IPackageManagerProxy());
            if (android.os.Build.VERSION.SDK_INT >= 28)
                addInjector(new top.niunaijun.blackbox.fake.service.ICrossProfileAppsProxy());
            if (android.os.Build.VERSION.SDK_INT >= 33)
                addInjector(new top.niunaijun.blackbox.fake.service.ILocaleManagerProxy());

            addInjector(new HCallbackProxy());
            addInjector(new IAppOpsManagerProxy());
            addInjector(new INotificationManagerProxy());
            addInjector(new IAlarmManagerProxy());
            addInjector(new ContentServiceStub());
            addInjector(new IWindowManagerProxy());
            addInjector(new IUserManagerProxy());
            addInjector(new RestrictionsManagerStub());
            addInjector(new IMediaSessionManagerProxy());
            addInjector(new IAudioServiceProxy());
            addInjector(new ISensorPrivacyManagerProxy());
            addInjector(new IMiuiSecurityManagerProxy());

            addInjector(new AudioPermissionProxy());
            addInjector(new IStorageManagerProxy());
            addInjector(new ILauncherAppsProxy());
            addInjector(new IJobServiceProxy());
            addInjector(new IAccessibilityManagerProxy());
            addInjector(new ITelephonyRegistryProxy());
            addInjector(new top.niunaijun.blackbox.fake.service.ITelephonyManagerProxy());
            addInjector(new top.niunaijun.blackbox.fake.service.ISubProxy());
            addInjector(new top.niunaijun.blackbox.fake.service.IPhoneSubInfoProxy());
            addInjector(new top.niunaijun.blackbox.fake.service.IConnectivityManagerProxy());
            addInjector(new IDevicePolicyManagerProxy());
            addInjector(new IAccountManagerProxy());
            addInjector(new NetworkPermissionCompat());

            addInjector(new IPowerManagerProxy());
            addInjector(AppInstrumentation.get());

            addInjector(new IWifiManagerProxy());

            if (BuildCompat.isS()) {
                addInjector(new IActivityClientProxy(null));
                addInjector(new ISensitiveContentProtectionManagerProxy());
            }

            if (BuildCompat.isR()) {
                addInjector(new IPermissionManagerProxy());
            }

            if (BuildCompat.isQ()) {
                addInjector(new IActivityTaskManagerProxy());
            }

            if (BuildCompat.isOreo()) {
                addInjector(new IAutofillManagerProxy());
                addInjector(new IDeviceIdentifiersPolicyProxy());
            }

            if (BuildCompat.isN()) {
                addInjector(new INetworkManagementServiceProxy());
            }

            if (BuildCompat.isM()) {
                addInjector(new IGraphicsStatsProxy());
            }
        }
        injectAll();
    }

    public void checkEnv(Class<?> clazz) {
        IInjectHook iInjectHook = mInjectors.get(clazz);
        if (iInjectHook != null && iInjectHook.isBadEnv()) {
            Log.d(TAG, "checkEnv: " + clazz.getSimpleName() + " is bad env");
            iInjectHook.injectHook();
        }
    }

    public void checkAll() {
        for (Class<?> aClass : mInjectors.keySet()) {
            IInjectHook iInjectHook = mInjectors.get(aClass);
            if (iInjectHook != null && iInjectHook.isBadEnv()) {
                Log.d(TAG, "checkEnv: " + aClass.getSimpleName() + " is bad env");
                iInjectHook.injectHook();
            }
        }
    }

    void addInjector(IInjectHook injectHook) {
        mInjectors.put(injectHook.getClass(), injectHook);
    }

    void injectAll() {
        for (IInjectHook value : mInjectors.values()) {
            try {
                Slog.d(TAG, "hook: " + value);
                value.injectHook();
            } catch (Exception e) {
                Slog.d(TAG, "hook error: " + value);
                
                handleHookError(value, e);
            }
        }
    }

    
    private void handleHookError(IInjectHook hook, Exception e) {
        String hookName = hook.getClass().getSimpleName();
        
        
        Slog.e(TAG, "Hook failed: " + hookName + " - " + e.getMessage(), e);
        
        
        if (hookName.contains("ActivityManager") || 
            hookName.contains("PackageManager") ||
            hookName.contains("WebView") ||
            hookName.contains("ContentProvider")) {
            
            Slog.w(TAG, "Critical hook failed: " + hookName + ", attempting recovery");
            
            try {
                
                if (hook.isBadEnv()) {
                    Slog.d(TAG, "Attempting to recover hook: " + hookName);
                    hook.injectHook();
                }
            } catch (Exception recoveryException) {
                Slog.e(TAG, "Hook recovery failed: " + hookName, recoveryException);
            }
        }
    }

    
    public boolean areCriticalHooksInstalled() {
        String[] criticalHooks = {
            "IActivityManagerProxy",
            "IPackageManagerProxy"
        };
        
        for (String hookName : criticalHooks) {
            boolean found = false;
            for (Class<?> hookClass : mInjectors.keySet()) {
                if (hookClass.getSimpleName().equals(hookName)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                Slog.w(TAG, "Critical hook missing: " + hookName);
                return false;
            }
        }
        
        Slog.d(TAG, "All critical hooks are installed");
        return true;
    }

    
    public void reinitializeHooks() {
        Slog.d(TAG, "Reinitializing all hooks");
        
        
        mInjectors.clear();
        
        
        init();
        
        Slog.d(TAG, "Hook reinitialization completed");
    }
}
