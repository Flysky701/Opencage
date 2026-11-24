package io.github.flysky701.opencage.privacy;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.IBinder;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import black.android.net.BRIConnectivityManagerStub;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;

/** Redacts VPN link configuration from synchronous queries in the guest's ConnectivityManager. */
final class GuestLinkProperties extends BinderInvocationStub {
    private final ConnectivityManager manager;
    private final IBinder binder;

    private GuestLinkProperties(ConnectivityManager manager, IBinder binder) {
        super(binder);
        this.manager = manager;
        this.binder = binder;
        onlyProxy(true);
    }

    static void install(Context guest) {
        ConnectivityManager manager = guest.getSystemService(ConnectivityManager.class);
        IBinder binder = BRServiceManager.get().getService(Context.CONNECTIVITY_SERVICE);
        if (manager == null || binder == null) return;
        GuestLinkProperties hook = new GuestLinkProperties(manager, binder);
        try {
            hook.injectHook();
            if (hook.getProxyInvocation() == null) return;
            Field service = ConnectivityManager.class.getDeclaredField("mService");
            service.setAccessible(true);
            service.set(manager, hook.getProxyInvocation());
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            Log.w("CagePrivacy", "Guest link metadata filter is unavailable.");
        }
    }

    @Override protected Object getWho() {
        return BRIConnectivityManagerStub.get().asInterface(binder);
    }

    @Override protected void inject(Object base, Object proxy) {}

    @Override public boolean isBadEnv() {
        return false;
    }

    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        Object result = super.invoke(proxy, method, args);
        if (!(result instanceof LinkProperties)) return result;
        Network network = args != null && args.length > 0 && args[0] instanceof Network
                ? (Network) args[0] : manager.getActiveNetwork();
        NetworkCapabilities caps = network == null ? null : manager.getNetworkCapabilities(network);
        if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return new LinkProperties();
        return result;
    }
}
