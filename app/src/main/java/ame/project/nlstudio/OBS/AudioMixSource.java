package ame.project.nlstudio.OBS;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Process;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.pedro.encoder.Frame;
import com.pedro.encoder.input.audio.GetMicrophoneData;
import com.pedro.encoder.input.sources.audio.AudioSource;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Custom audio source: capture MIC and INTERNAL AUDIO separately and mix them manually.
 * Fixes speed-up issues by using a fixed-size PCM pipeline and zero-filling missing data.
 */
public class AudioMixSource extends AudioSource {

    private int sampleRate = 44100;
    private int channelCount = 2;
    private static final int CHANNEL_IN = AudioFormat.CHANNEL_IN_STEREO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;

    // Fixed frame size (e.g., 1024 samples = ~23.2ms at 44.1kHz)
    private static final int SAMPLES_PER_FRAME = 1024;

    private final MediaProjection mediaProjection;
    private final int gameUid;

    private AudioRecord micRecord;
    private AudioRecord internalRecord;
    private Thread captureThread;
    private Thread internalCaptureThread;
    private volatile boolean running = false;
    private GetMicrophoneData callback;

    private volatile float micGain = 1.0f;
    private volatile float internalGain = 1.0f;

    private volatile boolean micEnabled = true;
    private volatile boolean internalEnabled = true;

    private int bufferSizeBytes;
    private long lastLevelPublishMs = 0L;

    // Internal audio queue to synchronize with the main capture loop
    private final BlockingQueue<byte[]> internalAudioQueue = new ArrayBlockingQueue<>(20);

    private long nextPtsUs = 0;

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
        if (this.internalEnabled == enabled) return;
        this.internalEnabled = enabled;
        // If disabled mid-stream, we can stop the record to be 100% sure no capture happens.
        // It will be restarted by setupRecords if needed, or we can just leave it stopped.
        if (!enabled && internalRecord != null && internalRecord.getState() == AudioRecord.STATE_INITIALIZED) {
            try {
                internalRecord.stop();
            } catch (Exception ignored) {}
        } else if (enabled && internalRecord != null && running) {
            try {
                internalRecord.startRecording();
            } catch (Exception ignored) {}
        }
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(api = Build.VERSION_CODES.Q)
    private void setupRecords() {
        // Use a buffer size that is a multiple of our frame size
        int minBuffer = AudioRecord.getMinBufferSize(sampleRate, CHANNEL_IN, ENCODING);
        bufferSizeBytes = Math.max(minBuffer, SAMPLES_PER_FRAME * channelCount * 2) * 2;

        micRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate, CHANNEL_IN, ENCODING, bufferSizeBytes
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
                        .setSampleRate(sampleRate)
                        .setChannelMask(CHANNEL_IN)
                        .build())
                .setBufferSizeInBytes(bufferSizeBytes)
                .setAudioPlaybackCaptureConfig(configBuilder.build())
                .build();
    }

    @Override
    protected boolean create(int sampleRate, boolean isStereo, boolean echoCanceler, boolean noiseSuppressor) {
        this.sampleRate = sampleRate;
        this.channelCount = isStereo ? 2 : 1;
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

            internalAudioQueue.clear();
            nextPtsUs = 0;

            // Dedicated thread to pull data from Internal Record into the queue
            internalCaptureThread = new Thread(this::internalCaptureLoop, "AudioMixSource-internal");
            internalCaptureThread.setPriority(Thread.MAX_PRIORITY);
            internalCaptureThread.start();

            // Main loop that mixes and pushes to the encoder
            captureThread = new Thread(this::mainCaptureLoop, "AudioMixSource-main");
            captureThread.setPriority(Thread.MAX_PRIORITY);
            captureThread.start();
        }
    }

    @Override
    public void stop() {
        running = false;
        try {
            if (captureThread != null) captureThread.join(200);
            if (internalCaptureThread != null) internalCaptureThread.join(200);
        } catch (InterruptedException ignored) {
        }
        captureThread = null;
        internalCaptureThread = null;

        releaseRecord(micRecord);
        releaseRecord(internalRecord);
        micRecord = null;
        internalRecord = null;
        AudioLevelBus.publish(0f, 0f);
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
            if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop();
            }
        } catch (Exception ignored) {
        }
        record.release();
    }

    private void internalCaptureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        int bytesPerFrame = SAMPLES_PER_FRAME * channelCount * 2;
        while (running) {
            byte[] buf = new byte[bytesPerFrame];
            int read = internalRecord.read(buf, 0, bytesPerFrame);
            if (read > 0) {
                if (internalAudioQueue.size() >= 10) {
                    internalAudioQueue.poll();
                }
                internalAudioQueue.offer(read == bytesPerFrame ? buf : java.util.Arrays.copyOf(buf, read));
            } else if (read < 0) {
                break;
            }
        }
    }

    private void mainCaptureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        int bytesPerFrame = SAMPLES_PER_FRAME * channelCount * 2;
        byte[] micBuf = new byte[bytesPerFrame];
        byte[] mixedBuf = new byte[bytesPerFrame];

        while (running) {
            int micReadCount;
            byte[] internalBuf;
            int internalReadLen;

            // MASTER CLOCK LOGIC
            if (micEnabled && micRecord != null) {
                // Mic is the master clock (blocking read)
                micReadCount = micRecord.read(micBuf, 0, bytesPerFrame);
                if (micReadCount < 0) break;
                
                // Try to get internal audio, don't wait long if it's missing (zero-fill later)
                // If internal audio is disabled, we don't even poll the queue
                if (internalEnabled) {
                    internalBuf = internalAudioQueue.poll();
                    internalReadLen = (internalBuf != null) ? internalBuf.length : 0;
                } else {
                    internalBuf = null;
                    internalReadLen = 0;
                }
            } else {
                // Internal is the master clock. 
                // We use the queue as a regulator. If queue is empty, we wait to maintain cadence.
                try {
                    if (internalEnabled) {
                        internalBuf = internalAudioQueue.poll(100, TimeUnit.MILLISECONDS);
                    } else {
                        // Even if internal is disabled, if mic is also disabled, we must wait to maintain timing
                        Thread.sleep(SAMPLES_PER_FRAME * 1000L / sampleRate);
                        internalBuf = null;
                    }
                    
                    if (internalBuf != null) {
                        internalReadLen = internalBuf.length;
                    } else {
                        // Timeout or disabled: generate silence to keep the timeline moving
                        internalReadLen = 0; 
                    }
                } catch (InterruptedException e) {
                    continue;
                }
                micReadCount = 0;
            }

            mixPcm16(micBuf, (micEnabled ? micReadCount : 0), micGain,
                    (internalEnabled && internalBuf != null) ? internalBuf : new byte[0], 
                    (internalEnabled ? internalReadLen : 0), internalGain,
                    mixedBuf, bytesPerFrame);

            mixAndPush(mixedBuf, bytesPerFrame);
            
            publishLevelsThrottled(micBuf, micReadCount, 
                    internalBuf != null ? internalBuf : new byte[0], internalReadLen);
        }
    }

    private void publishLevelsThrottled(byte[] micBuf, int micLen, byte[] internalBuf, int internalLen) {
        long now = System.currentTimeMillis();
        if (now - lastLevelPublishMs < 66) return;
        lastLevelPublishMs = now;

        float micLevel = micEnabled ? calcRmsLevel(micBuf, micLen) * micGain : 0f;
        float systemLevel = internalEnabled ? calcRmsLevel(internalBuf, internalLen) * internalGain : 0f;
        AudioLevelBus.publish(Math.min(1f, micLevel), Math.min(1f, systemLevel));
    }

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

    /**
     * Mixes two PCM16 buffers. Zero-fills if inputs are smaller than size.
     */
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
        if (callback == null || size <= 0) return;

        int bytesPerFrame = channelCount * 2;
        int validSize = size - (size % bytesPerFrame);
        if (validSize <= 0) return;

        long nowUs = System.nanoTime() / 1000L;
        if (nextPtsUs == 0) {
            nextPtsUs = nowUs;
        }

        // Use Arrays.copyOf to avoid data corruption if encoder is slow
        callback.inputPCMData(new Frame(java.util.Arrays.copyOf(pcm, validSize), 0, validSize, nextPtsUs));

        long frames = validSize / bytesPerFrame;
        nextPtsUs += (frames * 1_000_000L) / sampleRate;
    }
}
