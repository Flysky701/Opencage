package top.niunaijun.blackbox.fake.service;

import android.os.IBinder;

import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;

/** Routes guest IPhoneSubInfo Binder calls through the selected-guest privacy policy. */
public final class IPhoneSubInfoProxy extends BinderInvocationStub {
    public IPhoneSubInfoProxy() {
        super(BRServiceManager.get().getService("iphonesubinfo"));
    }

    @Override
    protected Object getWho() {
        IBinder binder = BRServiceManager.get().getService("iphonesubinfo");
        if (binder == null) return null;
        try {
            return Class.forName("com.android.internal.telephony.IPhoneSubInfo$Stub")
                    .getMethod("asInterface", IBinder.class)
                    .invoke(null, binder);
        } catch (ReflectiveOperationException unavailable) {
            return null;
        }
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService("iphonesubinfo");
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }
}
