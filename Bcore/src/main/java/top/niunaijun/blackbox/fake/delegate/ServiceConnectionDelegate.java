package top.niunaijun.blackbox.fake.delegate;

import android.app.IServiceConnection;
import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;

import android.os.Build;
import android.os.Parcel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import black.android.app.BRIServiceConnectionO;
import top.niunaijun.blackbox.utils.compat.BuildCompat;


public class ServiceConnectionDelegate extends IServiceConnection.Stub {
    private static final Map<IBinder, ConnectionGroup> GROUPS = new HashMap<>();
    private final IServiceConnection mConn;
    private final ComponentName mComponentName;
    private final int mUserId;

    private ServiceConnectionDelegate(IServiceConnection connection, ComponentName component, int userId) {
        mConn = connection;
        mComponentName = component;
        mUserId = userId;
    }

    public int getUserId() {
        return mUserId;
    }

    public static synchronized Binding acquire(IServiceConnection base, Intent intent,
                                               String resolvedType, int userId) throws RemoteException {
        IBinder binder = base.asBinder();
        ConnectionGroup group = GROUPS.get(binder);
        if (group == null) {
            group = new ConnectionGroup(binder);
            binder.linkToDeath(group, 0);
            GROUPS.put(binder, group);
        }

        TargetKey target = new TargetKey(intent, resolvedType, userId);
        ServiceConnectionDelegate delegate = group.delegates.get(target);
        if (delegate != null) return new Binding(delegate, false);

        delegate = new ServiceConnectionDelegate(base, intent.getComponent(), userId);
        group.delegates.put(target, delegate);
        return new Binding(delegate, true);
    }

    public static synchronized void discard(IBinder binder, Binding binding) {
        ConnectionGroup group = GROUPS.get(binder);
        if (group == null || !binding.created) return;
        group.delegates.values().remove(binding.delegate);
        if (group.delegates.isEmpty()) {
            GROUPS.remove(binder);
            binder.unlinkToDeath(group, 0);
        }
    }

    public static synchronized List<ServiceConnectionDelegate> removeAll(IBinder binder) {
        ConnectionGroup group = GROUPS.remove(binder);
        if (group == null) return Collections.emptyList();
        binder.unlinkToDeath(group, 0);
        return new ArrayList<>(group.delegates.values());
    }

    public static final class Binding {
        public final ServiceConnectionDelegate delegate;
        public final boolean created;

        private Binding(ServiceConnectionDelegate delegate, boolean created) {
            this.delegate = delegate;
            this.created = created;
        }
    }

    private static final class ConnectionGroup implements IBinder.DeathRecipient {
        final IBinder original;
        final Map<TargetKey, ServiceConnectionDelegate> delegates = new HashMap<>();

        ConnectionGroup(IBinder original) {
            this.original = original;
        }

        @Override
        public void binderDied() {
            synchronized (ServiceConnectionDelegate.class) {
                if (GROUPS.get(original) == this) GROUPS.remove(original);
            }
        }
    }

    private static final class TargetKey {
        final Intent.FilterComparison intent;
        final String resolvedType;
        final int userId;

        TargetKey(Intent target, String resolvedType, int userId) {
            this.intent = new Intent.FilterComparison(new Intent(target));
            this.resolvedType = resolvedType;
            this.userId = userId;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof TargetKey)) return false;
            TargetKey key = (TargetKey) other;
            return userId == key.userId && intent.equals(key.intent)
                    && Objects.equals(resolvedType, key.resolvedType);
        }

        @Override
        public int hashCode() {
            return Objects.hash(intent, resolvedType, userId);
        }
    }

    @Override
    public void connected(ComponentName name, IBinder service) throws RemoteException {
        connected(name, service, false);
    }

    /**
     * Fix 8 (API 37): IServiceConnection gained a new IBinderSession parameter in Android 17.
     * Since we compile against SDK 36, IBinderSession isn't available as a type.
     * Override onTransact() to intercept the Binder dispatch before super() tries to call
     * the abstract 4-param connected() method and throws AbstractMethodError.
     *
     * Transaction code FIRST_CALL_TRANSACTION (1) is used for connected() in the Stub.
     * On API 37 the Parcel layout is: [descriptor] ComponentName IBinder(service)
     *   IBinder(IBinderSession) boolean(dead)
     * We read all four, discard the session, and delegate to our 3-param implementation.
     */
    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        if (Build.VERSION.SDK_INT >= 37
                && code == android.os.IBinder.FIRST_CALL_TRANSACTION) {
            data.enforceInterface(DESCRIPTOR);
            // AIDL always precedes a nullable Parcelable arg with a presence flag
            // (readInt() != 0) before the actual writeToParcel payload — must be
            // consumed first or every subsequent read is off by 4 bytes.
            ComponentName name = null;
            if (data.readInt() != 0) {
                name = ComponentName.CREATOR.createFromParcel(data);
            }
            IBinder service = data.readStrongBinder();
            // Read and discard IBinderSession (it extends IBinder internally)
            if (data.dataAvail() > 4) { // more than a boolean left → session IBinder present
                data.readStrongBinder();
            }
            boolean dead = data.readBoolean();
            this.connected(name, service, dead);
            if (reply != null) reply.writeNoException();
            return true;
        }
        return super.onTransact(code, data, reply, flags);
    }

    public void connected(ComponentName name, IBinder service, boolean dead) throws RemoteException {
        service = io.github.flysky701.opencage.runtime.StoreBinderTransport.connect(mComponentName, service);
        service = io.github.flysky701.opencage.space.AccountTransport.wrapIntegrity(mComponentName, service);
        if (BuildCompat.isOreo()) {
            BRIServiceConnectionO.get(mConn).connected(mComponentName, service, dead);
        } else {
            mConn.connected(mComponentName, service);
        }
    }
}
