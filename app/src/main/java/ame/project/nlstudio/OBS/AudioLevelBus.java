package ame.project.nlstudio.OBS;

/**
 * Jembatan sederhana buat ngirim level volume mic & sistem dari AudioMixSource (jalan di thread
 * capture Service) ke UI (Activity) secara real-time, biar bisa nampilin VU meter kayak OBS.
 * Bukan event bus umum, sengaja simpel (cuma 1 listener) karena cuma dipakai 1 layar mixer.
 */
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class AudioLevelBus {

    public interface Listener {
        void onLevels(float micLevel, float systemLevel);
    }

    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public static void registerListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public static void unregisterListener(Listener l) {
        listeners.remove(l);
    }

    // Deprecated, use register/unregister
    public static void setListener(Listener l) {
        listeners.clear();
        if (l != null) listeners.add(l);
    }

    public static void publish(float micLevel, float systemLevel) {
        for (Listener l : listeners) {
            l.onLevels(micLevel, systemLevel);
        }
    }
}