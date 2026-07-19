/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: /home/shinri/Android/Sdk/build-tools/36.0.0/aidl -p/home/shinri/Android/Sdk/platforms/android-36/framework.aidl -o/home/shinri/AndroidStudioProjects/NLStudio/nlstudio-sdk/build/generated/aidl_source_output_dir/debug/out -I/home/shinri/AndroidStudioProjects/NLStudio/nlstudio-sdk/src/main/aidl -I/home/shinri/AndroidStudioProjects/NLStudio/nlstudio-sdk/src/debug/aidl -I/home/shinri/.gradle/caches/9.4.1/transforms/df99dcf543215087add86bb244d8ce17/transformed/core-1.16.0/aidl -I/home/shinri/.gradle/caches/9.4.1/transforms/8141ad173a4c7ce82b600ea5c6273f21/transformed/versionedparcelable-1.1.1/aidl -d/tmp/aidl10444257916538726697.d /home/shinri/AndroidStudioProjects/NLStudio/nlstudio-sdk/src/main/aidl/ame/project/nlsdk/IKanaeCallback.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package ame.project.nlsdk;
public interface IKanaeCallback extends android.os.IInterface
{
  /** Default implementation for IKanaeCallback. */
  public static class Default implements ame.project.nlsdk.IKanaeCallback
  {
    @Override public void onTrackChanged(java.lang.String title, java.lang.String artist, java.lang.String duration, java.lang.String thumbnail) throws android.os.RemoteException
    {
    }
    @Override public void onLyricsChanged(java.lang.String lyrics) throws android.os.RemoteException
    {
    }
    @Override public void onQueueChanged(java.lang.String queueJson) throws android.os.RemoteException
    {
    }
    @Override public void onPlaybackStatusChanged(boolean isPlaying, long position, long duration) throws android.os.RemoteException
    {
    }
    // TikTok Events
    @Override public void onChatMessage(java.lang.String user, java.lang.String message) throws android.os.RemoteException
    {
    }
    @Override public void onGiftMessage(java.lang.String user, java.lang.String gift, java.lang.String giftUrl, int count) throws android.os.RemoteException
    {
    }
    @Override public void onTikTokStatus(boolean connected, java.lang.String username) throws android.os.RemoteException
    {
    }
    // Additional Overlay Data
    @Override public void onUserJoined(java.lang.String user, java.lang.String profileUrl) throws android.os.RemoteException
    {
    }
    @Override public void onUserLiked(java.lang.String user, java.lang.String profileUrl, int count) throws android.os.RemoteException
    {
    }
    @Override public void onUserFollowed(java.lang.String user, java.lang.String profileUrl) throws android.os.RemoteException
    {
    }
    @Override public void onUserShared(java.lang.String user, java.lang.String profileUrl) throws android.os.RemoteException
    {
    }
    @Override
    public android.os.IBinder asBinder() {
      return null;
    }
  }
  /** Local-side IPC implementation stub class. */
  public static abstract class Stub extends android.os.Binder implements ame.project.nlsdk.IKanaeCallback
  {
    /** Construct the stub and attach it to the interface. */
    @SuppressWarnings("this-escape")
    public Stub()
    {
      this.attachInterface(this, DESCRIPTOR);
    }
    /**
     * Cast an IBinder object into an ame.project.nlsdk.IKanaeCallback interface,
     * generating a proxy if needed.
     */
    public static ame.project.nlsdk.IKanaeCallback asInterface(android.os.IBinder obj)
    {
      if ((obj==null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin!=null)&&(iin instanceof ame.project.nlsdk.IKanaeCallback))) {
        return ((ame.project.nlsdk.IKanaeCallback)iin);
      }
      return new ame.project.nlsdk.IKanaeCallback.Stub.Proxy(obj);
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
        case TRANSACTION_onTrackChanged:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          java.lang.String _arg1;
          _arg1 = data.readString();
          java.lang.String _arg2;
          _arg2 = data.readString();
          java.lang.String _arg3;
          _arg3 = data.readString();
          this.onTrackChanged(_arg0, _arg1, _arg2, _arg3);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_onLyricsChanged:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          this.onLyricsChanged(_arg0);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_onQueueChanged:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          this.onQueueChanged(_arg0);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_onPlaybackStatusChanged:
        {
          boolean _arg0;
          _arg0 = (0!=data.readInt());
          long _arg1;
          _arg1 = data.readLong();
          long _arg2;
          _arg2 = data.readLong();
          this.onPlaybackStatusChanged(_arg0, _arg1, _arg2);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_onChatMessage:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          java.lang.String _arg1;
          _arg1 = data.readString();
          this.onChatMessage(_arg0, _arg1);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_onGiftMessage:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          java.lang.String _arg1;
          _arg1 = data.readString();
          java.lang.String _arg2;
          _arg2 = data.readString();
          int _arg3;
          _arg3 = data.readInt();
          this.onGiftMessage(_arg0, _arg1, _arg2, _arg3);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_onTikTokStatus:
        {
          boolean _arg0;
          _arg0 = (0!=data.readInt());
          java.lang.String _arg1;
          _arg1 = data.readString();
          this.onTikTokStatus(_arg0, _arg1);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_onUserJoined:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          java.lang.String _arg1;
          _arg1 = data.readString();
          this.onUserJoined(_arg0, _arg1);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_onUserLiked:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          java.lang.String _arg1;
          _arg1 = data.readString();
          int _arg2;
          _arg2 = data.readInt();
          this.onUserLiked(_arg0, _arg1, _arg2);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_onUserFollowed:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          java.lang.String _arg1;
          _arg1 = data.readString();
          this.onUserFollowed(_arg0, _arg1);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_onUserShared:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          java.lang.String _arg1;
          _arg1 = data.readString();
          this.onUserShared(_arg0, _arg1);
          reply.writeNoException();
          break;
        }
        default:
        {
          return super.onTransact(code, data, reply, flags);
        }
      }
      return true;
    }
    private static class Proxy implements ame.project.nlsdk.IKanaeCallback
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
      @Override public void onTrackChanged(java.lang.String title, java.lang.String artist, java.lang.String duration, java.lang.String thumbnail) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(title);
          _data.writeString(artist);
          _data.writeString(duration);
          _data.writeString(thumbnail);
          boolean _status = mRemote.transact(Stub.TRANSACTION_onTrackChanged, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public void onLyricsChanged(java.lang.String lyrics) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(lyrics);
          boolean _status = mRemote.transact(Stub.TRANSACTION_onLyricsChanged, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public void onQueueChanged(java.lang.String queueJson) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(queueJson);
          boolean _status = mRemote.transact(Stub.TRANSACTION_onQueueChanged, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public void onPlaybackStatusChanged(boolean isPlaying, long position, long duration) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(((isPlaying)?(1):(0)));
          _data.writeLong(position);
          _data.writeLong(duration);
          boolean _status = mRemote.transact(Stub.TRANSACTION_onPlaybackStatusChanged, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      // TikTok Events
      @Override public void onChatMessage(java.lang.String user, java.lang.String message) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(user);
          _data.writeString(message);
          boolean _status = mRemote.transact(Stub.TRANSACTION_onChatMessage, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public void onGiftMessage(java.lang.String user, java.lang.String gift, java.lang.String giftUrl, int count) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(user);
          _data.writeString(gift);
          _data.writeString(giftUrl);
          _data.writeInt(count);
          boolean _status = mRemote.transact(Stub.TRANSACTION_onGiftMessage, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public void onTikTokStatus(boolean connected, java.lang.String username) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(((connected)?(1):(0)));
          _data.writeString(username);
          boolean _status = mRemote.transact(Stub.TRANSACTION_onTikTokStatus, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      // Additional Overlay Data
      @Override public void onUserJoined(java.lang.String user, java.lang.String profileUrl) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(user);
          _data.writeString(profileUrl);
          boolean _status = mRemote.transact(Stub.TRANSACTION_onUserJoined, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public void onUserLiked(java.lang.String user, java.lang.String profileUrl, int count) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(user);
          _data.writeString(profileUrl);
          _data.writeInt(count);
          boolean _status = mRemote.transact(Stub.TRANSACTION_onUserLiked, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public void onUserFollowed(java.lang.String user, java.lang.String profileUrl) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(user);
          _data.writeString(profileUrl);
          boolean _status = mRemote.transact(Stub.TRANSACTION_onUserFollowed, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public void onUserShared(java.lang.String user, java.lang.String profileUrl) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(user);
          _data.writeString(profileUrl);
          boolean _status = mRemote.transact(Stub.TRANSACTION_onUserShared, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
    }
    static final int TRANSACTION_onTrackChanged = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);
    static final int TRANSACTION_onLyricsChanged = (android.os.IBinder.FIRST_CALL_TRANSACTION + 1);
    static final int TRANSACTION_onQueueChanged = (android.os.IBinder.FIRST_CALL_TRANSACTION + 2);
    static final int TRANSACTION_onPlaybackStatusChanged = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3);
    static final int TRANSACTION_onChatMessage = (android.os.IBinder.FIRST_CALL_TRANSACTION + 4);
    static final int TRANSACTION_onGiftMessage = (android.os.IBinder.FIRST_CALL_TRANSACTION + 5);
    static final int TRANSACTION_onTikTokStatus = (android.os.IBinder.FIRST_CALL_TRANSACTION + 6);
    static final int TRANSACTION_onUserJoined = (android.os.IBinder.FIRST_CALL_TRANSACTION + 7);
    static final int TRANSACTION_onUserLiked = (android.os.IBinder.FIRST_CALL_TRANSACTION + 8);
    static final int TRANSACTION_onUserFollowed = (android.os.IBinder.FIRST_CALL_TRANSACTION + 9);
    static final int TRANSACTION_onUserShared = (android.os.IBinder.FIRST_CALL_TRANSACTION + 10);
  }
  /** @hide */
  public static final java.lang.String DESCRIPTOR = "ame.project.nlsdk.IKanaeCallback";
  public void onTrackChanged(java.lang.String title, java.lang.String artist, java.lang.String duration, java.lang.String thumbnail) throws android.os.RemoteException;
  public void onLyricsChanged(java.lang.String lyrics) throws android.os.RemoteException;
  public void onQueueChanged(java.lang.String queueJson) throws android.os.RemoteException;
  public void onPlaybackStatusChanged(boolean isPlaying, long position, long duration) throws android.os.RemoteException;
  // TikTok Events
  public void onChatMessage(java.lang.String user, java.lang.String message) throws android.os.RemoteException;
  public void onGiftMessage(java.lang.String user, java.lang.String gift, java.lang.String giftUrl, int count) throws android.os.RemoteException;
  public void onTikTokStatus(boolean connected, java.lang.String username) throws android.os.RemoteException;
  // Additional Overlay Data
  public void onUserJoined(java.lang.String user, java.lang.String profileUrl) throws android.os.RemoteException;
  public void onUserLiked(java.lang.String user, java.lang.String profileUrl, int count) throws android.os.RemoteException;
  public void onUserFollowed(java.lang.String user, java.lang.String profileUrl) throws android.os.RemoteException;
  public void onUserShared(java.lang.String user, java.lang.String profileUrl) throws android.os.RemoteException;
}
