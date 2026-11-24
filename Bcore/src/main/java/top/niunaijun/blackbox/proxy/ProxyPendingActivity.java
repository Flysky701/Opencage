package top.niunaijun.blackbox.proxy;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.proxy.record.ProxyPendingRecord;
import top.niunaijun.blackbox.utils.Slog;


public class ProxyPendingActivity extends Activity {
    public static final String TAG = "ProxyPendingActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (io.github.flysky701.opencage.space.GuestPendingActivity.finishProxy(this)) return;
        finish();
        ProxyPendingRecord pendingActivityRecord = ProxyPendingRecord.create(getIntent());
        Slog.d(TAG, "ProxyPendingActivity: " + pendingActivityRecord);
        if (pendingActivityRecord.mTarget == null)
            return;
        pendingActivityRecord.mTarget.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        pendingActivityRecord.mTarget.setExtrasClassLoader(BlackBoxCore.getApplication().getClassLoader());
        startActivity(pendingActivityRecord.mTarget);
    }

    public static class P0 extends ProxyPendingActivity {

    }

    public static class P1 extends ProxyPendingActivity {

    }

    public static class P2 extends ProxyPendingActivity {

    }

    public static class P3 extends ProxyPendingActivity {

    }

    public static class P4 extends ProxyPendingActivity {

    }

    public static class P5 extends ProxyPendingActivity {

    }

    public static class P6 extends ProxyPendingActivity {

    }

    public static class P7 extends ProxyPendingActivity {

    }

    public static class P8 extends ProxyPendingActivity {

    }

    public static class P9 extends ProxyPendingActivity {

    }

    public static class P10 extends ProxyPendingActivity {

    }

    public static class P11 extends ProxyPendingActivity {

    }

    public static class P12 extends ProxyPendingActivity {

    }

    public static class P13 extends ProxyPendingActivity {

    }

    public static class P14 extends ProxyPendingActivity {

    }

    public static class P15 extends ProxyPendingActivity {

    }
}
