package ame.project.nlstudio.OBS;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.pedro.encoder.Frame;
import com.pedro.encoder.input.audio.GetMicrophoneData;
import com.pedro.encoder.input.sources.audio.AudioSource;

/**
 * Custom audio source: capture MIC dan AUDIO SISTEM (atau audio dari satu aplikasi/game
 * tertentu lewat UID) secara TERPISAH, lalu di-mix manual dengan volume (gain) masing-masing
 * bisa diatur real-time (0.0f = mute, 1.0f = normal, sampai 2.0f = 2x lebih keras).
 */
public class AudioMixSource extends AudioSource {

    public static final int SAMPLE_RATE = 44100;
    private static final int CHANNEL_IN = AudioFormat.CHANNEL_IN_STEREO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;

    private final MediaProjection mediaProjection;
    private final int gameUid;

    private AudioRecord micRecord;
    private AudioRecord internalRecord;
    private Thread captureThread;
    private volatile boolean running = false;
    private GetMicrophoneData callback;

    private volatile float micGain = 1.0f;
    private volatile float internalGain = 1.0f;

    private boolean micEnabled = true;
    private boolean internalEnabled = true;

    private int bufferSizeBytes;
    private long lastLevelPublishMs = 0L;

    public AudioMixSource(MediaProjection mediaProjection, int gameUid) {
        super();
        this.mediaProjection = mediaProjection;
        this.gameUid = gameUid;
    }

    public void setMicGain(float gain) {
        this.micGain = Math.max(0f, Math.min(2f, gain));
    }

    public void setInternalGain(float gain) {
        this.internalGain = Math.max(0f, Math.min(2f, gain));
    }

    public void setMicEnabled(boolean enabled) {
        this.micEnabled = enabled;
    }

    public void setInternalEnabled(boolean enabled) {
        this.internalEnabled = enabled;
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(api = Build.VERSION_CODES.Q)
    private void setupRecords() {
        bufferSizeBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING) * 2;

        micRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, CHANNEL_IN, ENCODING, bufferSizeBytes
        );

        AudioPlaybackCaptureConfiguration.Builder configBuilder =
                new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                        .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(android.media.AudioAttributes.USAGE_UNKNOWN);

        if (gameUid > 0) {
            configBuilder.addMatchingUid(gameUid);
        }

        internalRecord = new AudioRecord.Builder()
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(ENCODING)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_IN)
                        .build())
                .setBufferSizeInBytes(bufferSizeBytes)
                .setAudioPlaybackCaptureConfig(configBuilder.build())
                .build();
    }

    @Override
    protected boolean create(int sampleRate, boolean isStereo, boolean echoCanceler, boolean noiseSuppressor) {
        return true;
    }

    @Override
    public void start(@NonNull GetMicrophoneData getMicrophoneData) {
        this.callback = getMicrophoneData;
        if (running) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setupRecords();
            micRecord.startRecording();
            internalRecord.startRecording();
            running = true;

            captureThread = new Thread(this::captureLoop, "AudioMixSource-capture");
            captureThread.start();
        }
    }

    @Override
    public void stop() {
        running = false;
        try {
            if (captureThread != null) captureThread.join(500);
        } catch (InterruptedException ignored) {
        }
        releaseRecord(micRecord);
        releaseRecord(internalRecord);
        micRecord = null;
        internalRecord = null;
        AudioLevelBus.publish(0f, 0f); // reset VU meter di UI pas audio berhenti
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void release() {
        stop();
    }

    private void releaseRecord(AudioRecord record) {
        if (record == null) return;
        try {
            record.stop();
        } catch (Exception ignored) {
        }
        record.release();
    }

    private void captureLoop() {
        int frameBytes = bufferSizeBytes;
        byte[] micBuf = new byte[frameBytes];
        byte[] internalBuf = new byte[frameBytes];
        byte[] mixed = new byte[frameBytes];

        while (running) {
            int micRead = micEnabled ? micRecord.read(micBuf, 0, frameBytes) : 0;
            int internalRead = internalEnabled ? internalRecord.read(internalBuf, 0, frameBytes) : 0;
            int size = Math.max(micRead, internalRead);
            if (size <= 0) continue;

            mixPcm16(micBuf, micEnabled ? micRead : 0, micGain,
                    internalBuf, internalEnabled ? internalRead : 0, internalGain,
                    mixed, size);

            mixAndPush(mixed, size);
            publishLevelsThrottled(micBuf, micEnabled ? micRead : 0, internalBuf, internalEnabled ? internalRead : 0);
        }
    }

    /** Hitung level RMS mic & sistem terus kirim ke AudioLevelBus buat VU meter, dibatasi ~15x/detik biar gak flood UI. */
    private void publishLevelsThrottled(byte[] micBuf, int micLen, byte[] internalBuf, int internalLen) {
        long now = System.currentTimeMillis();
        if (now - lastLevelPublishMs < 66) return;
        lastLevelPublishMs = now;

        float micLevel = micEnabled ? calcRmsLevel(micBuf, micLen) * micGain : 0f;
        float systemLevel = internalEnabled ? calcRmsLevel(internalBuf, internalLen) * internalGain : 0f;
        AudioLevelBus.publish(Math.min(1f, micLevel), Math.min(1f, systemLevel));
    }

    /** RMS PCM16 dinormalisasi ke 0..1, dikasih sedikit boost karena audio jarang beneran full-scale. */
    private float calcRmsLevel(byte[] buf, int len) {
        if (len <= 1) return 0f;
        long sumSquares = 0;
        int samples = 0;
        for (int i = 0; i + 1 < len; i += 2) {
            short s = (short) ((buf[i] & 0xFF) | (buf[i + 1] << 8));
            sumSquares += (long) s * s;
            samples++;
        }
        if (samples == 0) return 0f;
        double rms = Math.sqrt((double) sumSquares / samples);
        float normalized = (float) (rms / 32768.0) * 3.5f;
        return Math.min(1f, normalized);
    }

    private void mixPcm16(byte[] a, int aLen, float gainA,
                          byte[] b, int bLen, float gainB,
                          byte[] out, int size) {
        for (int i = 0; i + 1 < size; i += 2) {
            short sa = (i + 1 < aLen) ? (short) ((a[i] & 0xFF) | (a[i + 1] << 8)) : 0;
            short sb = (i + 1 < bLen) ? (short) ((b[i] & 0xFF) | (b[i + 1] << 8)) : 0;

            int mixedSample = Math.round(sa * gainA) + Math.round(sb * gainB);
            if (mixedSample > Short.MAX_VALUE) mixedSample = Short.MAX_VALUE;
            if (mixedSample < Short.MIN_VALUE) mixedSample = Short.MIN_VALUE;

            out[i] = (byte) (mixedSample & 0xFF);
            out[i + 1] = (byte) ((mixedSample >> 8) & 0xFF);
        }
    }

    private void mixAndPush(byte[] pcm, int size) {
        if (callback != null) {
            callback.inputPCMData(new Frame(pcm, 0, size, System.nanoTime() / 1000));
        }
    }
}