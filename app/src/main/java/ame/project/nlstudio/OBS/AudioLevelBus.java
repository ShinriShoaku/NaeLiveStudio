package ame.project.nlstudio.OBS;

/**
 * Jembatan sederhana buat ngirim level volume mic & sistem dari AudioMixSource (jalan di thread
 * capture Service) ke UI (Activity) secara real-time, biar bisa nampilin VU meter kayak OBS.
 * Bukan event bus umum, sengaja simpel (cuma 1 listener) karena cuma dipakai 1 layar mixer.
 */
public class AudioLevelBus {

    public interface Listener {
        void onLevels(float micLevel, float systemLevel);
    }

    private static volatile Listener listener;

    public static void setListener(Listener l) {
        listener = l;
    }

    public static void publish(float micLevel, float systemLevel) {
        Listener l = listener;
        if (l != null) l.onLevels(micLevel, systemLevel);
    }
}