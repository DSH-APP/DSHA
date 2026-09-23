// 由 AIDL 生成（构建环境无 aarch64 aidl 时的手工等价产物）。
// 对应 app/src/main/aidl/com/deepseekharness/app/IShellService.aidl
package com.deepseekharness.app;

public interface IShellService extends android.os.IInterface {
    /** Default implementation for IShellService. */
    public static class Default implements IShellService {
        @Override public String exec(String cmd) throws android.os.RemoteException { return null; }
        @Override public void destroy() throws android.os.RemoteException { }
        @Override public android.os.IBinder asBinder() { return null; }
    }

    /** Local-side IPC implementation stub class. */
    public static abstract class Stub extends android.os.Binder implements IShellService {
        /** Construct the stub at attach it to the interface. */
        public Stub() { this.attachInterface(this, DESCRIPTOR); }
        public static IShellService asInterface(android.os.IBinder obj) {
            if ((obj == null)) { return null; }
            android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (((iin != null) && (iin instanceof IShellService))) { return ((IShellService) iin); }
            return new Proxy(obj);
        }
        @Override public android.os.IBinder asBinder() { return this; }
        @Override public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException {
            String descriptor = DESCRIPTOR;
            switch (code) {
                case INTERFACE_TRANSACTION: { reply.writeString(descriptor); return true; }
                case TRANSACTION_exec: {
                    data.enforceInterface(descriptor);
                    String _arg0 = data.readString();
                    String _result = this.exec(_arg0);
                    reply.writeNoException();
                    reply.writeString(_result);
                    return true;
                }
                case TRANSACTION_destroy: {
                    data.enforceInterface(descriptor);
                    this.destroy();
                    reply.writeNoException();
                    return true;
                }
                default: return super.onTransact(code, data, reply, flags);
            }
        }
        private static class Proxy implements IShellService {
            private android.os.IBinder mRemote;
            Proxy(android.os.IBinder remote) { mRemote = remote; }
            @Override public android.os.IBinder asBinder() { return mRemote; }
            @Override public String exec(String cmd) throws android.os.RemoteException {
                android.os.Parcel _data = android.os.Parcel.obtain();
                android.os.Parcel _reply = android.os.Parcel.obtain();
                String _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(cmd);
                    boolean _status = mRemote.transact(Stub.TRANSACTION_exec, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readString();
                } finally { _reply.recycle(); _data.recycle(); }
                return _result;
            }
            @Override public void destroy() throws android.os.RemoteException {
                android.os.Parcel _data = android.os.Parcel.obtain();
                android.os.Parcel _reply = android.os.Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(Stub.TRANSACTION_destroy, _data, _reply, 0);
                    _reply.readException();
                } finally { _reply.recycle(); _data.recycle(); }
            }
        }
        static final int TRANSACTION_exec = android.os.IBinder.FIRST_CALL_TRANSACTION + 0;
        static final int TRANSACTION_destroy = 16777114;
    }
    public static final String DESCRIPTOR = "com.deepseekharness.app.IShellService";
    public String exec(String cmd) throws android.os.RemoteException;
    public void destroy() throws android.os.RemoteException;
}
