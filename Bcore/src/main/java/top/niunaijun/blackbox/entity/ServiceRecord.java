package top.niunaijun.blackbox.entity;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;


public class ServiceRecord {
    private Service mService;
    private final Map<Intent.FilterComparison, BoundInfo> mBounds = new ConcurrentHashMap<>();
    private int mStartId;
    private IBinder mToken;

    public class BoundInfo {
        private IBinder mIBinder;
        private boolean mPublished;
        private boolean mGuestBound;
        private boolean mRebind;
        private AtomicInteger mBindCount = new AtomicInteger(0);

        public int incrementAndGetBindCount() {
            return mBindCount.incrementAndGet();
        }

        public int decrementAndGetBindCount() {
            return mBindCount.decrementAndGet();
        }

        public IBinder getIBinder() {
            return mIBinder;
        }

        public void setIBinder(IBinder IBinder) {
            mIBinder = IBinder;
            mPublished = true;
            mGuestBound = true;
        }
    }

    public int getStartId() {
        return mStartId;
    }

    public void setStartId(int startId) {
        mStartId = startId;
    }

    public IBinder getToken() {
        return mToken;
    }

    public void setToken(IBinder token) {
        mToken = token;
    }

    public Service getService() {
        return mService;
    }

    public void setService(Service service) {
        mService = service;
    }

    public IBinder getBinder(Intent intent) {
        BoundInfo boundInfo = getOrCreateBoundInfo(intent);
        return boundInfo.getIBinder();
    }

    public boolean hasBinder(Intent intent) {
        BoundInfo boundInfo = getOrCreateBoundInfo(intent);
        return boundInfo.mPublished;
    }

    public void addBinder(Intent intent, final IBinder iBinder) {
        final Intent.FilterComparison filterComparison = new Intent.FilterComparison(intent);
        BoundInfo boundInfo = getOrCreateBoundInfo(intent);
        boundInfo.setIBinder(iBinder);
        if (iBinder == null) return;
        try {
            iBinder.linkToDeath(new IBinder.DeathRecipient() {
                @Override
                public void binderDied() {
                    iBinder.unlinkToDeath(this, 0);
                    mBounds.remove(filterComparison, boundInfo);
                }
            }, 0);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    public int incrementAndGetBindCount(Intent intent) {
        BoundInfo boundInfo = getOrCreateBoundInfo(intent);
        return boundInfo.incrementAndGetBindCount();
    }

    public boolean decreaseConnectionCount(Intent intent) {
        Intent.FilterComparison filterComparison = new Intent.FilterComparison(intent);
        BoundInfo boundInfo = mBounds.get(filterComparison);
        if (boundInfo == null)
            return true;
        int i = boundInfo.decrementAndGetBindCount();
        if (i <= 0) {

            return true;
        }
        return false;
    }

    public BoundInfo getOrCreateBoundInfo(Intent intent) {
        return mBounds.computeIfAbsent(new Intent.FilterComparison(intent), key -> new BoundInfo());
    }

    public boolean hasConnections() {
        for (BoundInfo binding : mBounds.values()) {
            if (binding.mBindCount.get() > 0) return true;
        }
        return false;
    }

    public boolean takeRebind(Intent intent) {
        BoundInfo binding = getOrCreateBoundInfo(intent);
        boolean rebind = binding.mRebind;
        binding.mRebind = false;
        if (rebind) binding.mGuestBound = true;
        return rebind;
    }

    public boolean takeGuestBound(Intent intent) {
        BoundInfo binding = getOrCreateBoundInfo(intent);
        boolean guestBound = binding.mGuestBound;
        binding.mGuestBound = false;
        return guestBound;
    }

    public void setRebind(Intent intent, boolean rebind) {
        getOrCreateBoundInfo(intent).mRebind = rebind;
    }
}
