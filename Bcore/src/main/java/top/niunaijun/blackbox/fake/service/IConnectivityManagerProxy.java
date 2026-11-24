package top.niunaijun.blackbox.fake.service;

import android.content.Context;
import android.net.ConnectivityManager;
import android.os.IBinder;

import java.lang.reflect.Field;

import black.android.net.BRIConnectivityManagerStub;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;

/** Routes guest ConnectivityManager Binder calls through the selected-guest privacy policy. */
public final class IConnectivityManagerProxy extends BinderInvocationStub {
    private static volatile Object sProxyInvocation;

    public IConnectivityManagerProxy() {
        super(BRServiceManager.get().getService(Context.CONNECTIVITY_SERVICE));
    }

    @Override
    protected Object getWho() {
        IBinder binder = BRServiceManager.get().getService(Context.CONNECTIVITY_SERVICE);
        return binder == null ? null : BRIConnectivityManagerStub.get().asInterface(binder);
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        sProxyInvocation = proxyInvocation;
        replaceSystemService(Context.CONNECTIVITY_SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    /** Patches an already-instantiated ConnectivityManager so its cached mService uses this proxy. */
    public static void installOn(Context context) {
        Object proxy = sProxyInvocation;
        if (context == null || proxy == null) return;
        try {
            ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
            if (manager == null) return;
            Field service = ConnectivityManager.class.getDeclaredField("mService");
            service.setAccessible(true);
            if (service.get(manager) != proxy) {
                service.set(manager, proxy);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
    }
}
