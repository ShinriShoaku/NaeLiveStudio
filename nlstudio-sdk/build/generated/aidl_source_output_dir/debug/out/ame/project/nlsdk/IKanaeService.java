/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: /home/shinri/Android/Sdk/build-tools/36.0.0/aidl -p/home/shinri/Android/Sdk/platforms/android-36/framework.aidl -o/home/shinri/AndroidStudioProjects/NLStudio/nlstudio-sdk/build/generated/aidl_source_output_dir/debug/out -I/home/shinri/AndroidStudioProjects/NLStudio/nlstudio-sdk/src/main/aidl -I/home/shinri/AndroidStudioProjects/NLStudio/nlstudio-sdk/src/debug/aidl -I/home/shinri/.gradle/caches/9.4.1/transforms/df99dcf543215087add86bb244d8ce17/transformed/core-1.16.0/aidl -I/home/shinri/.gradle/caches/9.4.1/transforms/8141ad173a4c7ce82b600ea5c6273f21/transformed/versionedparcelable-1.1.1/aidl -d/tmp/aidl11115580539045692645.d /home/shinri/AndroidStudioProjects/NLStudio/nlstudio-sdk/src/main/aidl/ame/project/nlsdk/IKanaeService.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package ame.project.nlsdk;
public interface IKanaeService extends android.os.IInterface
{
  /** Default implementation for IKanaeService. */
  public static class Default implements ame.project.nlsdk.IKanaeService
  {
    @Override public void registerCallback(ame.project.nlsdk.IKanaeCallback callback) throws android.os.RemoteException
    {
    }
    @Override public void unregisterCallback(ame.project.nlsdk.IKanaeCallback callback) throws android.os.RemoteException
    {
    }
    // Control methods
    @Override public void playPause() throws android.os.RemoteException
    {
    }
    @Override public void skip() throws android.os.RemoteException
    {
    }
    @Override public void stop() throws android.os.RemoteException
    {
    }
    @Override public void requestMusic(java.lang.String queryOrUrl) throws android.os.RemoteException
    {
    }
    @Override public void setVolume(float volume) throws android.os.RemoteException
    {
    }
    @Override public float getVolume() throws android.os.RemoteException
    {
      return 0.0f;
    }
    // TikTok Controls
    @Override public void connectTikTok(java.lang.String username) throws android.os.RemoteException
    {
    }
    @Override public void disconnectTikTok() throws android.os.RemoteException
    {
    }
    @Override public boolean isTikTokConnected() throws android.os.RemoteException
    {
      return false;
    }
    // Data methods
    @Override public java.lang.String getCurrentSongJson() throws android.os.RemoteException
    {
      return null;
    }
    @Override public java.lang.String getQueueJson() throws android.os.RemoteException
    {
      return null;
    }
    @Override public void requestQueue() throws android.os.RemoteException
    {
    }
    @Override public boolean isPlaying() throws android.os.RemoteException
    {
      return false;
    }
    @Override
    public android.os.IBinder asBinder() {
      return null;
    }
  }
  /** Local-side IPC implementation stub class. */
  public static abstract class Stub extends android.os.Binder implements ame.project.nlsdk.IKanaeService
  {
    /** Construct the stub and attach it to the interface. */
    @SuppressWarnings("this-escape")
    public Stub()
    {
      this.attachInterface(this, DESCRIPTOR);
    }
    /**
     * Cast an IBinder object into an ame.project.nlsdk.IKanaeService interface,
     * generating a proxy if needed.
     */
    public static ame.project.nlsdk.IKanaeService asInterface(android.os.IBinder obj)
    {
      if ((obj==null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin!=null)&&(iin instanceof ame.project.nlsdk.IKanaeService))) {
        return ((ame.project.nlsdk.IKanaeService)iin);
      }
      return new ame.project.nlsdk.IKanaeService.Stub.Proxy(obj);
    }
    @Override public android.os.IBinder asBinder()
    {
      return this;
    }
    @Override public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException
    {
      java.lang.String descriptor = DESCRIPTOR;
      if (code >= android.os.IBinder.FIRST_CALL_TRANSACTION && code <= android.os.IBinder.LAST_CALL_TRANSACTION) {
        data.enforceInterface(descriptor);
      }
      if (code == INTERFACE_TRANSACTION) {
        reply.writeString(descriptor);
        return true;
      }
      switch (code)
      {
        case TRANSACTION_registerCallback:
        {
          ame.project.nlsdk.IKanaeCallback _arg0;
          _arg0 = ame.project.nlsdk.IKanaeCallback.Stub.asInterface(data.readStrongBinder());
          this.registerCallback(_arg0);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_unregisterCallback:
        {
          ame.project.nlsdk.IKanaeCallback _arg0;
          _arg0 = ame.project.nlsdk.IKanaeCallback.Stub.asInterface(data.readStrongBinder());
          this.unregisterCallback(_arg0);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_playPause:
        {
          this.playPause();
          reply.writeNoException();
          break;
        }
        case TRANSACTION_skip:
        {
          this.skip();
          reply.writeNoException();
          break;
        }
        case TRANSACTION_stop:
        {
          this.stop();
          reply.writeNoException();
          break;
        }
        case TRANSACTION_requestMusic:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          this.requestMusic(_arg0);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_setVolume:
        {
          float _arg0;
          _arg0 = data.readFloat();
          this.setVolume(_arg0);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_getVolume:
        {
          float _result = this.getVolume();
          reply.writeNoException();
          reply.writeFloat(_result);
          break;
        }
        case TRANSACTION_connectTikTok:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          this.connectTikTok(_arg0);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_disconnectTikTok:
        {
          this.disconnectTikTok();
          reply.writeNoException();
          break;
        }
        case TRANSACTION_isTikTokConnected:
        {
          boolean _result = this.isTikTokConnected();
          reply.writeNoException();
          reply.writeInt(((_result)?(1):(0)));
          break;
        }
        case TRANSACTION_getCurrentSongJson:
        {
          java.lang.String _result = this.getCurrentSongJson();
          reply.writeNoException();
          reply.writeString(_result);
          break;
        }
        case TRANSACTION_getQueueJson:
        {
          java.lang.String _result = this.getQueueJson();
          reply.writeNoException();
          reply.writeString(_result);
          break;
        }
        case TRANSACTION_requestQueue:
        {
          this.requestQueue();
          reply.writeNoException();
          break;
        }
        case TRANSACTION_isPlaying:
        {
          boolean _result = this.isPlaying();
          reply.writeNoException();
          reply.writeInt(((_result)?(1):(0)));
          break;
        }
        default:
        {
          return super.onTransact(code, data, reply, flags);
        }
      }
      return true;
    }
    private static class Proxy implements ame.project.nlsdk.IKanaeService
    {
      private android.os.IBinder mRemote;
      Proxy(android.os.IBinder remote)
      {
        mRemote = remote;
      }
      @Override public android.os.IBinder asBinder()
      {
        return mRemote;
      }
      public java.lang.String getInterfaceDescriptor()
      {
        return DESCRIPTOR;
      }
      @Override public void registerCallback(ame.project.nlsdk.IKanaeCallback callback) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongInterface(callback);
          boolean _status = mRemote.transact(Stub.TRANSACTION_registerCallback, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public void unregisterCallback(ame.project.nlsdk.IKanaeCallback callback) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongInterface(callback);
          boolean _status = mRemote.transact(Stub.TRANSACTION_unregisterCallback, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      // Control methods
      @Override public void playPause() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_playPause, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public void skip() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_skip, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public void stop() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_stop, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public void requestMusic(java.lang.String queryOrUrl) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(queryOrUrl);
          boolean _status = mRemote.transact(Stub.TRANSACTION_requestMusic, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public void setVolume(float volume) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeFloat(volume);
          boolean _status = mRemote.transact(Stub.TRANSACTION_setVolume, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public float getVolume() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        float _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getVolume, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readFloat();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      // TikTok Controls
      @Override public void connectTikTok(java.lang.String username) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(username);
          boolean _status = mRemote.transact(Stub.TRANSACTION_connectTikTok, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public void disconnectTikTok() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_disconnectTikTok, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public boolean isTikTokConnected() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        boolean _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_isTikTokConnected, _data, _reply, 0);
          _reply.readException();
          _result = (0!=_reply.readInt());
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      // Data methods
      @Override public java.lang.String getCurrentSongJson() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        java.lang.String _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getCurrentSongJson, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readString();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public java.lang.String getQueueJson() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        java.lang.String _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getQueueJson, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readString();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public void requestQueue() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_requestQueue, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public boolean isPlaying() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        boolean _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_isPlaying, _data, _reply, 0);
          _reply.readException();
          _result = (0!=_reply.readInt());
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
    }
    static final int TRANSACTION_registerCallback = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);
    static final int TRANSACTION_unregisterCallback = (android.os.IBinder.FIRST_CALL_TRANSACTION + 1);
    static final int TRANSACTION_playPause = (android.os.IBinder.FIRST_CALL_TRANSACTION + 2);
    static final int TRANSACTION_skip = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3);
    static final int TRANSACTION_stop = (android.os.IBinder.FIRST_CALL_TRANSACTION + 4);
    static final int TRANSACTION_requestMusic = (android.os.IBinder.FIRST_CALL_TRANSACTION + 5);
    static final int TRANSACTION_setVolume = (android.os.IBinder.FIRST_CALL_TRANSACTION + 6);
    static final int TRANSACTION_getVolume = (android.os.IBinder.FIRST_CALL_TRANSACTION + 7);
    static final int TRANSACTION_connectTikTok = (android.os.IBinder.FIRST_CALL_TRANSACTION + 8);
    static final int TRANSACTION_disconnectTikTok = (android.os.IBinder.FIRST_CALL_TRANSACTION + 9);
    static final int TRANSACTION_isTikTokConnected = (android.os.IBinder.FIRST_CALL_TRANSACTION + 10);
    static final int TRANSACTION_getCurrentSongJson = (android.os.IBinder.FIRST_CALL_TRANSACTION + 11);
    static final int TRANSACTION_getQueueJson = (android.os.IBinder.FIRST_CALL_TRANSACTION + 12);
    static final int TRANSACTION_requestQueue = (android.os.IBinder.FIRST_CALL_TRANSACTION + 13);
    static final int TRANSACTION_isPlaying = (android.os.IBinder.FIRST_CALL_TRANSACTION + 14);
  }
  /** @hide */
  public static final java.lang.String DESCRIPTOR = "ame.project.nlsdk.IKanaeService";
  public void registerCallback(ame.project.nlsdk.IKanaeCallback callback) throws android.os.RemoteException;
  public void unregisterCallback(ame.project.nlsdk.IKanaeCallback callback) throws android.os.RemoteException;
  // Control methods
  public void playPause() throws android.os.RemoteException;
  public void skip() throws android.os.RemoteException;
  public void stop() throws android.os.RemoteException;
  public void requestMusic(java.lang.String queryOrUrl) throws android.os.RemoteException;
  public void setVolume(float volume) throws android.os.RemoteException;
  public float getVolume() throws android.os.RemoteException;
  // TikTok Controls
  public void connectTikTok(java.lang.String username) throws android.os.RemoteException;
  public void disconnectTikTok() throws android.os.RemoteException;
  public boolean isTikTokConnected() throws android.os.RemoteException;
  // Data methods
  public java.lang.String getCurrentSongJson() throws android.os.RemoteException;
  public java.lang.String getQueueJson() throws android.os.RemoteException;
  public void requestQueue() throws android.os.RemoteException;
  public boolean isPlaying() throws android.os.RemoteException;
}
