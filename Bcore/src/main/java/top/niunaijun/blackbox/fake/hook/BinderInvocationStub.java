package top.niunaijun.blackbox.fake.hook;

import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

import java.io.FileDescriptor;
import java.util.Map;

import black.android.os.BRServiceManager;


public abstract class BinderInvocationStub extends ClassInvocationStub implements IBinder {
    private IBinder mBaseBinder;

    public BinderInvocationStub(IBinder baseBinder) {
        mBaseBinder = baseBinder;
    }

    @Override
    protected void onBindMethod() {
    }

    @Override
    public String getInterfaceDescriptor() throws RemoteException {
        return mBaseBinder.getInterfaceDescriptor();
    }

    @Override
    public boolean pingBinder() {
        return mBaseBinder.pingBinder();
    }

    @Override
    public boolean isBinderAlive() {
        return mBaseBinder.isBinderAlive();
    }

    @Override
    public IInterface queryLocalInterface(String descriptor) {
        return (IInterface) getProxyInvocation();
    }

    @Override
    public void dump(FileDescriptor fd, String[] args) throws RemoteException {
        mBaseBinder.dump(fd, args);
    }

    @Override
    public void dumpAsync(FileDescriptor fd, String[] args) throws RemoteException {
        mBaseBinder.dumpAsync(fd, args);
    }

    @Override
    public boolean transact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        return mBaseBinder.transact(code, data, reply, flags);
    }

    @Override
    public void linkToDeath(DeathRecipient recipient, int flags) throws RemoteException {
        mBaseBinder.linkToDeath(recipient, flags);
    }

    @Override
    public boolean unlinkToDeath(DeathRecipient recipient, int flags) {
        return mBaseBinder.unlinkToDeath(recipient, flags);
    }


    protected void replaceSystemService(String name) {
        Map<String, IBinder> services = BRServiceManager.get().sCache();
        services.put(name, this);
    }
}
