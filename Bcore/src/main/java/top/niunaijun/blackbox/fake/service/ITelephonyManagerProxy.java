package top.niunaijun.blackbox.fake.service;

import android.content.Context;
import android.os.IBinder;

import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;

/** Routes guest ITelephony Binder calls through the selected-guest privacy policy. */
public final class ITelephonyManagerProxy extends BinderInvocationStub {
    public ITelephonyManagerProxy() {
        super(BRServiceManager.get().getService(Context.TELEPHONY_SERVICE));
    }

    @Override
    protected Object getWho() {
        IBinder binder = BRServiceManager.get().getService(Context.TELEPHONY_SERVICE);
        if (binder == null) return null;
        try {
            return Class.forName("com.android.internal.telephony.ITelephony$Stub")
                    .getMethod("asInterface", IBinder.class)
                    .invoke(null, binder);
        } catch (ReflectiveOperationException unavailable) {
            return null;
        }
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.TELEPHONY_SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }
}
