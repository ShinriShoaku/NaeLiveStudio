package ame.project.nlsdk;

import ame.project.nlsdk.IKanaeCallback;

interface IKanaeService {
    void registerCallback(IKanaeCallback callback);
    void unregisterCallback(IKanaeCallback callback);

    // Control methods
    void playPause();
    void skip();
    void stop();
    void requestMusic(String queryOrUrl);
    void setVolume(float volume);
    float getVolume();

    // TikTok Controls
    void connectTikTok(String username);
    void disconnectTikTok();
    boolean isTikTokConnected();

    // Data methods
    String getCurrentSongJson();
    String getQueueJson();
    void requestQueue();
    boolean isPlaying();
}
