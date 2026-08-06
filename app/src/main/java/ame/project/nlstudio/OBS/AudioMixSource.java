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

/**
 * Custom audio source: capture MIC, INTERNAL AUDIO (system/game), dan MUSIC (Kanae Player)
 * secara terpisah lalu mix manual. Fixes speed-up issues by using a fixed-size PCM pipeline
 * and zero-filling missing data.
 */
public class AudioMixSource extends AudioSource {

    private int sampleRate = 44100;
    private int channelCount = 2;
    private static final int CHANNEL_IN = AudioFormat.CHANNEL_IN_STEREO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;

    // Fixed frame size (e.g., 1024 samples = ~23.2ms at 44.1kHz)
    private static final int SAMPLES_PER_FRAME = 1024;

    /** Package name aplikasi Kanae Player - dipakai buat resolve UID capture-nya. */
    private static final String KANAE_PACKAGE_NAME = "ame.project.kanae";

    private final MediaProjection mediaProjection;
    private final int gameUid;

    /** UID proses Kanae Player, di-resolve di constructor. -1 kalau nggak ketemu/nggak terpasang. */
    private int kanaeUid = -1;

    private AudioRecord micRecord;
    private AudioRecord internalRecord;
    /** AudioRecord khusus capture audio Kanae Player (scoped by UID). Null kalau kanaeUid <= 0. */
    private AudioRecord musicRecord;

    private Thread captureThread;
    private Thread micCaptureThread;
    private Thread internalCaptureThread;
    /** Thread mode capture-langsung-dari-Kanae (dipakai kalau musicRecord != null). */
    private Thread musicCaptureThread;
    /** Thread mode lama: decode file lokal lewat musicUri (fallback kalau Kanae nggak ada). */
    private Thread musicDecodeThread;

    private volatile boolean running = false;
    private volatile boolean stopMusicThread = false;
    private GetMicrophoneData callback;

    private volatile float micGain = 1.0f;
    private volatile float internalGain = 1.0f;
    private volatile float musicGain = 1.0f;

    private volatile boolean micEnabled = true;
    private volatile boolean internalEnabled = true;
    private volatile boolean musicEnabled = false;

    private android.net.Uri musicUri;
    private final android.content.Context context;
    private final BlockingQueue<byte[]> micAudioQueue = new ArrayBlockingQueue<>(20);
    private final BlockingQueue<byte[]> internalAudioQueue = new ArrayBlockingQueue<>(20);
    private final BlockingQueue<byte[]> musicAudioQueue = new ArrayBlockingQueue<>(40);

    private int bufferSizeBytes;
    private long lastLevelPublishMs = 0L;

    private long nextPtsUs = 0;

    public AudioMixSource(android.content.Context context, MediaProjection mediaProjection, int gameUid) {
        super();
        this.context = context;
        this.mediaProjection = mediaProjection;
        this.gameUid = gameUid;

        try {
            kanaeUid = context.getPackageManager()
                    .getApplicationInfo(KANAE_PACKAGE_NAME, 0).uid;
            // android.util.Log.d("AudioMixSource", "Kanae Player terdeteksi, uid=" + kanaeUid + " -> bus Music pakai capture langsung.");
        } catch (Exception e) {
            kanaeUid = -1;
            // android.util.Log.w("AudioMixSource", "Kanae Player (" + KANAE_PACKAGE_NAME + ") tidak terpasang, bus Music fallback ke mode file lokal.");
        }
    }

    public void setMicGain(float gain) {
        this.micGain = Math.max(0f, Math.min(2f, gain));
    }

    public void setInternalGain(float gain) {
        this.internalGain = Math.max(0f, Math.min(2f, gain));
    }

    public void setMusicGain(float gain) {
        this.musicGain = Math.max(0f, Math.min(2f, gain));
    }

    /**
     * Set URI file musik lokal untuk mode FALLBACK (dipakai hanya kalau Kanae Player tidak
     * terpasang / kanaeUid tidak berhasil di-resolve). Kalau bus Music sedang jalan dalam mode
     * capture-Kanae, method ini sengaja di-ignore supaya kedua sumber tidak bentrok.
     */
    public void setMusicUri(android.net.Uri uri) {
        if (kanaeUid > 0) {
            // android.util.Log.w("AudioMixSource", "setMusicUri() diabaikan: bus Music sedang pakai capture Kanae Player.");
            return;
        }

        if (uri == null && musicUri == null) return;
        if (uri != null && uri.equals(musicUri)) return;

        // android.util.Log.d("AudioMixSource", "setMusicUri: " + uri);
        this.musicUri = uri;

        // Restart decode thread if URI changed mid-stream
        if (running) {
            stopMusicThread = true;
            if (musicDecodeThread != null) {
                try {
                    musicDecodeThread.join(200);
                } catch (InterruptedException ignored) {
                    musicDecodeThread.interrupt();
                }
                musicDecodeThread = null;
            }
            musicAudioQueue.clear();
            stopMusicThread = false;

            this.musicEnabled = uri != null;
            if (musicEnabled && musicUri != null) {
                musicDecodeThread = new Thread(this::musicDecodeLoop, "AudioMixSource-music");
                musicDecodeThread.start();
            }
        } else {
            this.musicEnabled = uri != null;
        }
    }

    public void setMicEnabled(boolean enabled) {
        this.micEnabled = enabled;
        if (!enabled) micAudioQueue.clear();
    }

    public void setInternalEnabled(boolean enabled) {
        if (this.internalEnabled == enabled) return;
        this.internalEnabled = enabled;
        if (!enabled) internalAudioQueue.clear();
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

    /** Enable/disable bus Music tanpa mengubah sumbernya (capture-Kanae ATAU file lokal). */
    public void setMusicEnabled(boolean enabled) {
        this.musicEnabled = enabled;
        if (!enabled) musicAudioQueue.clear();
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(api = Build.VERSION_CODES.Q)
    private void setupRecords() {
        // Ensure any existing records are released before re-initializing
        releaseRecord(micRecord);
        releaseRecord(internalRecord);
        releaseRecord(musicRecord);
        micRecord = null;
        internalRecord = null;
        musicRecord = null;

        // Use a buffer size that is a multiple of our frame size
        int minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, ENCODING);
        bufferSizeBytes = Math.max(minBuffer, SAMPLES_PER_FRAME * 2) * 2;

        // android.util.Log.d("AudioMixSource", "setupRecords: minBuffer=" + minBuffer + " bufferSizeBytes=" + bufferSizeBytes);

        micRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate, AudioFormat.CHANNEL_IN_MONO, ENCODING, bufferSizeBytes
        );

        // ── Bus Internal/System: audio game/lainnya. Kanae di-EXCLUDE dari sini biar nggak
        //    ikut kehitung dobel (audio Kanae akan ditangkap sendiri lewat musicRecord). ──
        AudioPlaybackCaptureConfiguration.Builder configBuilder =
                new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                        .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(android.media.AudioAttributes.USAGE_UNKNOWN);

        if (gameUid > 0) {
            configBuilder.addMatchingUid(gameUid);
        }
        if (kanaeUid > 0) {
            configBuilder.excludeUid(kanaeUid);
        }

        internalRecord = new AudioRecord.Builder()
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(ENCODING)
                        .setSampleRate(sampleRate)
                        .setChannelMask(CHANNEL_IN) // Internal capture usually supports stereo
                        .build())
                .setBufferSizeInBytes(bufferSizeBytes)
                .setAudioPlaybackCaptureConfig(configBuilder.build())
                .build();

        // ── Bus Music: HANYA audio yang diputar oleh Kanae Player (di-scope by UID). ──
        if (kanaeUid > 0) {
            AudioPlaybackCaptureConfiguration musicConfig =
                    new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                            .addMatchingUid(kanaeUid)
                            .build();

            musicRecord = new AudioRecord.Builder()
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(ENCODING)
                            .setSampleRate(sampleRate)
                            .setChannelMask(CHANNEL_IN)
                            .build())
                    .setBufferSizeInBytes(bufferSizeBytes)
                    .setAudioPlaybackCaptureConfig(musicConfig)
                    .build();
        } else {
            musicRecord = null;
        }
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
        // android.util.Log.i("AudioMixSource", "start() called. running=" + running);
        if (running) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                setupRecords();
                micRecord.startRecording();
                internalRecord.startRecording();
                if (musicRecord != null) {
                    musicRecord.startRecording();
                }
                running = true;
                stopMusicThread = false;
                // android.util.Log.i("AudioMixSource", "Records started.");
            } catch (Exception e) {
                // android.util.Log.e("AudioMixSource", "Exception starting records", e);
                return;
            }

            micAudioQueue.clear();
            internalAudioQueue.clear();
            musicAudioQueue.clear();
            nextPtsUs = 0;

            // 1. Thread Tangkap Mic
            micCaptureThread = new Thread(this::micCaptureLoop, "AudioMixSource-mic");
            micCaptureThread.setPriority(Thread.MAX_PRIORITY);
            micCaptureThread.start();

            // 2. Thread Tangkap Sistem
            internalCaptureThread = new Thread(this::internalCaptureLoop, "AudioMixSource-internal");
            internalCaptureThread.setPriority(Thread.MAX_PRIORITY);
            internalCaptureThread.start();

            // 3. Thread Musik: prioritas capture langsung dari Kanae Player, fallback ke decode
            //    file lokal cuma kalau Kanae nggak terdeteksi terpasang.
            if (musicRecord != null) {
                musicEnabled = true;
                musicCaptureThread = new Thread(this::musicCaptureLoop, "AudioMixSource-music-kanae");
                musicCaptureThread.setPriority(Thread.MAX_PRIORITY);
                musicCaptureThread.start();
            } else if (musicEnabled && musicUri != null) {
                musicDecodeThread = new Thread(this::musicDecodeLoop, "AudioMixSource-music-file");
                musicDecodeThread.start();
            }

            // 4. Thread Utama (Mixing & Pushing)
            captureThread = new Thread(this::mainCaptureLoop, "AudioMixSource-main");
            captureThread.setPriority(Thread.MAX_PRIORITY);
            captureThread.start();
            // android.util.Log.i("AudioMixSource", "All threads started.");
        }
    }

    @Override
    public void stop() {
        running = false;
        stopMusicThread = true;

        // FIX (ANR saat Stop Record): sebelumnya urutannya "join thread dulu, baru
        // release() record". Kalau salah satu thread capture (mic/internal/music) lagi
        // STUCK di dalam AudioRecord.read() secara native - misal gara-gara AudioFlinger
        // gagal terus bikin ulang record (lihat logcat: "createRecord_l ... status -22" /
        // "restoreRecord_l ... dead IAudioRecord", biasanya kejadian abis app sempat
        // di-background lalu dibuka lagi) - thread itu TIDAK akan pernah selesai dalam
        // waktu join(200ms) di bawah. Kode lama lanjut manggil record.release() SETELAH
        // itu, padahal thread capture-nya MASIH aktif dan MASIH nge-block di dalam
        // read() pada objek AudioRecord yang sama. release() yang dipanggil dari thread
        // lain (di sini dari main thread, lewat StreamService.stopEverything()) jadi ikut
        // ke-block nunggu native lock yang sama dipegang oleh read() yang stuck itu ->
        // APLIKASI ANR pas user tekan Stop.
        //
        // Fix: panggil record.stop() (BUKAN release()) DULU di semua AudioRecord, SEBELUM
        // join thread. AudioRecord.stop() dijamin aman dipanggil dari thread lain untuk
        // "membangunkan" read() yang lagi blocking di thread lain - read() itu langsung
        // return 0/partial read - jadi capture loop bisa segera keluar dari while(running)
        // dan thread-nya beneran selesai SEBELUM kita release().
        safeStopRecord(micRecord);
        safeStopRecord(internalRecord);
        safeStopRecord(musicRecord);

        // INTERRUPT all threads first to break any sleep/wait
        if (captureThread != null) captureThread.interrupt();
        if (micCaptureThread != null) micCaptureThread.interrupt();
        if (internalCaptureThread != null) internalCaptureThread.interrupt();
        if (musicCaptureThread != null) musicCaptureThread.interrupt();
        if (musicDecodeThread != null) musicDecodeThread.interrupt();

        try {
            if (captureThread != null) captureThread.join(200);
            if (micCaptureThread != null) micCaptureThread.join(200);
            if (internalCaptureThread != null) internalCaptureThread.join(200);
            if (musicCaptureThread != null) musicCaptureThread.join(200);
            if (musicDecodeThread != null) musicDecodeThread.join(200);
        } catch (InterruptedException ignored) {
            // Already interrupted above
        }
        captureThread = null;
        micCaptureThread = null;
        internalCaptureThread = null;
        musicCaptureThread = null;
        musicDecodeThread = null;

        micAudioQueue.clear();
        internalAudioQueue.clear();
        musicAudioQueue.clear();

        releaseRecord(micRecord);
        releaseRecord(internalRecord);
        releaseRecord(musicRecord);
        micRecord = null;
        internalRecord = null;
        musicRecord = null;
        AudioLevelBus.publish(0f, 0f, 0f);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void release() {
        stop();
    }

    /** Panggil AudioRecord.stop() dengan aman - dipakai buat "membangunkan" thread capture
     *  yang lagi blocking di read(), SEBELUM di-release(). Lihat catatan panjang di stop(). */
    private void safeStopRecord(AudioRecord record) {
        if (record == null) return;
        try {
            if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop();
            }
        } catch (Exception ignored) {
        }
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

    private void micCaptureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        int samplesPerFrame = SAMPLES_PER_FRAME;
        int bytesPerFrameMono = samplesPerFrame * 2;
        byte[] monoBuf = new byte[bytesPerFrameMono];
        // android.util.Log.d("AudioMixSource", "micCaptureLoop started. mono bytes=" + bytesPerFrameMono);
        while (running && !Thread.currentThread().isInterrupted()) {
            int read = micRecord.read(monoBuf, 0, bytesPerFrameMono);
            if (read > 0) {
                // Convert mono to stereo (copy each sample twice)
                byte[] stereoBuf = new byte[read * 2];
                for (int i = 0; i < read / 2; i++) {
                    stereoBuf[i * 4] = monoBuf[i * 2];
                    stereoBuf[i * 4 + 1] = monoBuf[i * 2 + 1];
                    stereoBuf[i * 4 + 2] = monoBuf[i * 2];
                    stereoBuf[i * 4 + 3] = monoBuf[i * 2 + 1];
                }
                if (micAudioQueue.size() >= 10) micAudioQueue.poll();
                micAudioQueue.offer(stereoBuf);
            } else if (read < 0) {
                // android.util.Log.e("AudioMixSource", "micCaptureLoop error: " + read);
                break;
            } else {
                try { Thread.sleep(10); } catch (InterruptedException e) { 
                    Thread.currentThread().interrupt();
                    break; 
                }
            }
        }
        // android.util.Log.d("AudioMixSource", "micCaptureLoop finished.");
    }

    private void internalCaptureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        int bytesPerFrame = SAMPLES_PER_FRAME * channelCount * 2;
        byte[] buf = new byte[bytesPerFrame];
        // android.util.Log.d("AudioMixSource", "internalCaptureLoop started.");
        while (running && !Thread.currentThread().isInterrupted()) {
            int read = internalRecord.read(buf, 0, bytesPerFrame);
            if (read > 0) {
                if (internalAudioQueue.size() >= 10) internalAudioQueue.poll();
                internalAudioQueue.offer(java.util.Arrays.copyOf(buf, read));
            } else if (read < 0) {
                // android.util.Log.e("AudioMixSource", "internalCaptureLoop error: " + read);
                break;
            } else {
                try { Thread.sleep(10); } catch (InterruptedException e) { 
                    Thread.currentThread().interrupt();
                    break; 
                }
            }
        }
        // android.util.Log.d("AudioMixSource", "internalCaptureLoop finished.");
    }

    /**
     * Capture audio Kanae Player secara langsung dari OS (AudioPlaybackCaptureConfiguration
     * scoped by UID). Ini pengganti musicDecodeLoop() untuk kasus normal - tidak ada data yang
     * lewat AIDL sama sekali, murni tangkap PCM dari mixer audio Android.
     */
    private void musicCaptureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        int bytesPerFrame = SAMPLES_PER_FRAME * channelCount * 2;
        byte[] buf = new byte[bytesPerFrame];
        // android.util.Log.d("AudioMixSource", "musicCaptureLoop (Kanae) started.");
        while (running && !Thread.currentThread().isInterrupted()) {
            int read = musicRecord.read(buf, 0, bytesPerFrame);
            if (read > 0) {
                if (musicAudioQueue.size() >= 10) musicAudioQueue.poll();
                musicAudioQueue.offer(java.util.Arrays.copyOf(buf, read));
            } else if (read < 0) {
                // android.util.Log.e("AudioMixSource", "musicCaptureLoop error: " + read);
                break;
            } else {
                try { Thread.sleep(10); } catch (InterruptedException e) { 
                    Thread.currentThread().interrupt();
                    break; 
                }
            }
        }
        // android.util.Log.d("AudioMixSource", "musicCaptureLoop finished.");
    }

    private void mainCaptureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        int bytesPerFrame = SAMPLES_PER_FRAME * channelCount * 2;
        byte[] mixedBuf = new byte[bytesPerFrame];
        long frameDurationMs = (SAMPLES_PER_FRAME * 1000L) / sampleRate;

        // android.util.Log.d("AudioMixSource", "mainCaptureLoop started. cadence=" + frameDurationMs + "ms");

        long loopCounter = 0;
        while (running && !Thread.currentThread().isInterrupted()) {
            loopCounter++;
            long startTime = System.currentTimeMillis();

            byte[] micBuf = (micEnabled && running) ? micAudioQueue.poll() : null;
            byte[] internalBuf = (internalEnabled && running) ? internalAudioQueue.poll() : null;
            byte[] musicBuf = (musicEnabled && running) ? musicAudioQueue.poll() : null;

            int micLen = (micBuf != null) ? micBuf.length : 0;
            int internalLen = (internalBuf != null) ? internalBuf.length : 0;
            int musicLen = (musicBuf != null) ? musicBuf.length : 0;

            mixPcm16Multi(micBuf != null ? micBuf : new byte[0], micLen, micGain,
                    internalBuf != null ? internalBuf : new byte[0], internalLen, internalGain,
                    musicBuf != null ? musicBuf : new byte[0], musicLen, musicGain,
                    mixedBuf, bytesPerFrame);

            mixAndPush(mixedBuf, bytesPerFrame);
            publishLevelsThrottled(micBuf != null ? micBuf : new byte[0], micLen,
                    internalBuf != null ? internalBuf : new byte[0], internalLen,
                    musicBuf != null ? musicBuf : new byte[0], musicLen);

            if (loopCounter % 100 == 0) {
                // android.util.Log.v("AudioMixSource", "mainCaptureLoop status: micQ=" + micAudioQueue.size() + " sysQ=" + internalAudioQueue.size() + " musQ=" + musicAudioQueue.size() + " micEnabled=" + micEnabled + " sysEnabled=" + internalEnabled + " musEnabled=" + musicEnabled);
            }

            // Jaga cadence waktu
            long elapsed = System.currentTimeMillis() - startTime;
            long sleepTime = frameDurationMs - elapsed;
            if (sleepTime > 0) {
                try { Thread.sleep(sleepTime); } catch (InterruptedException e) { break; }
            }
        }
        // android.util.Log.d("AudioMixSource", "mainCaptureLoop finished.");
    }

    private void publishLevelsThrottled(byte[] micBuf, int micLen, byte[] internalBuf, int internalLen, byte[] musicBuf, int musicLen) {
        long now = System.currentTimeMillis();
        if (now - lastLevelPublishMs < 66) return;
        lastLevelPublishMs = now;

        float micLevel = micEnabled ? calcRmsLevel(micBuf, micLen) * micGain : 0f;
        float systemLevel = internalEnabled ? calcRmsLevel(internalBuf, internalLen) * internalGain : 0f;
        float musicLevel = musicEnabled ? calcRmsLevel(musicBuf, musicLen) * musicGain : 0f;

        // We need to update AudioLevelBus to support 3 channels, or pack it
        AudioLevelBus.publish(Math.min(1f, micLevel), Math.min(1f, systemLevel), Math.min(1f, musicLevel));
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

    private void mixPcm16Multi(byte[] a, int aLen, float gainA,
                               byte[] b, int bLen, float gainB,
                               byte[] c, int cLen, float gainC,
                               byte[] out, int size) {
        for (int i = 0; i + 1 < size; i += 2) {
            short sa = (i + 1 < aLen) ? (short) ((a[i] & 0xFF) | (a[i + 1] << 8)) : 0;
            short sb = (i + 1 < bLen) ? (short) ((b[i] & 0xFF) | (b[i + 1] << 8)) : 0;
            short sc = (i + 1 < cLen) ? (short) ((c[i] & 0xFF) | (c[i + 1] << 8)) : 0;

            int mixedSample = Math.round(sa * gainA) + Math.round(sb * gainB) + Math.round(sc * gainC);
            if (mixedSample > Short.MAX_VALUE) mixedSample = Short.MAX_VALUE;
            if (mixedSample < Short.MIN_VALUE) mixedSample = Short.MIN_VALUE;

            out[i] = (byte) (mixedSample & 0xFF);
            out[i + 1] = (byte) ((mixedSample >> 8) & 0xFF);
        }
    }

    /**
     * Mode FALLBACK (dipakai hanya kalau Kanae Player tidak terpasang): decode file lokal lewat
     * MediaExtractor/MediaCodec, persis fitur lama.
     */
    private void musicDecodeLoop() {
        if (musicUri == null) return;
        // android.util.Log.d("AudioMixSource", "musicDecodeLoop: Starting for URI: " + musicUri);
        android.media.MediaExtractor extractor = new android.media.MediaExtractor();
        android.media.MediaCodec codec = null;
        try {
            extractor.setDataSource(context, musicUri, null);
            int audioTrackIndex = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                android.media.MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(android.media.MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrackIndex = i;
                    break;
                }
            }
            if (audioTrackIndex < 0) {
                // android.util.Log.e("AudioMixSource", "musicDecodeLoop: No audio track found in " + musicUri);
                return;
            }
            extractor.selectTrack(audioTrackIndex);
            android.media.MediaFormat format = extractor.getTrackFormat(audioTrackIndex);

            // Force 16-bit PCM output if the decoder supports it
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                format.setInteger(android.media.MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
            }

            String mime = format.getString(android.media.MediaFormat.KEY_MIME);
            codec = android.media.MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();
            // android.util.Log.d("AudioMixSource", "musicDecodeLoop: Decoder started. mime=" + mime);

            android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
            int bytesPerFrame = SAMPLES_PER_FRAME * channelCount * 2;
            boolean inputDone = false;
            long totalFramesPushed = 0;

            // Reusable buffer for accumulation to avoid constant allocations
            byte[] accumulator = new byte[bytesPerFrame * 4];
            int accumulatorPos = 0;
            int currentChannels = 2;

            while (running && !stopMusicThread) {
                if (!inputDone) {
                    int inputIndex = codec.dequeueInputBuffer(10000);
                    if (inputIndex >= 0) {
                        java.nio.ByteBuffer inputBuffer = codec.getInputBuffer(inputIndex);
                        int sampleSize = extractor.readSampleData(inputBuffer, 0);
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                int outputIndex = codec.dequeueOutputBuffer(info, 10000);
                if (outputIndex >= 0) {
                    java.nio.ByteBuffer outputBuffer = codec.getOutputBuffer(outputIndex);
                    if (outputBuffer != null && info.size > 0) {
                        outputBuffer.position(info.offset);

                        // Handle Mono to Stereo conversion and accumulation
                        if (currentChannels == 1) {
                            while (outputBuffer.remaining() >= 2 && running && !stopMusicThread) {
                                short sample = outputBuffer.getShort();
                                // Write same sample to both L and R in accumulator
                                accumulator[accumulatorPos++] = (byte)(sample & 0xFF);
                                accumulator[accumulatorPos++] = (byte)((sample >> 8) & 0xFF);
                                accumulator[accumulatorPos++] = (byte)(sample & 0xFF);
                                accumulator[accumulatorPos++] = (byte)((sample >> 8) & 0xFF);

                                if (accumulatorPos >= bytesPerFrame) {
                                    if (pushFrame(accumulator, bytesPerFrame)) {
                                        totalFramesPushed++;
                                    }
                                    accumulatorPos = 0;
                                }
                            }
                        } else {
                            // Already Stereo (or multi-channel, simplified to Stereo by taking first 2)
                            while (outputBuffer.remaining() > 0 && running && !stopMusicThread) {
                                int toCopy = Math.min(outputBuffer.remaining(), accumulator.length - accumulatorPos);
                                outputBuffer.get(accumulator, accumulatorPos, toCopy);
                                accumulatorPos += toCopy;

                                while (accumulatorPos >= bytesPerFrame) {
                                    byte[] frame = new byte[bytesPerFrame];
                                    System.arraycopy(accumulator, 0, frame, 0, bytesPerFrame);
                                    if (pushFrame(frame, bytesPerFrame)) {
                                        totalFramesPushed++;
                                    }
                                    // Move leftover to start
                                    int leftover = accumulatorPos - bytesPerFrame;
                                    if (leftover > 0) {
                                        System.arraycopy(accumulator, bytesPerFrame, accumulator, 0, leftover);
                                    }
                                    accumulatorPos = leftover;
                                }
                            }
                        }

                        if (totalFramesPushed % 1000 == 0 && totalFramesPushed > 0) {
                            // android.util.Log.v("AudioMixSource", "musicDecodeLoop: pushed " + totalFramesPushed + " frames");
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false);
                } else if (outputIndex == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    android.media.MediaFormat newFormat = codec.getOutputFormat();
                    currentChannels = newFormat.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT);
                    // android.util.Log.i("AudioMixSource", "musicDecodeLoop: Format changed: " + newFormat);
                }

                if ((info.flags & android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    // android.util.Log.d("AudioMixSource", "musicDecodeLoop: Looping");
                    extractor.seekTo(0, android.media.MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                    codec.flush();
                    accumulatorPos = 0;
                    inputDone = false;
                }
            }
        } catch (Exception e) {
            // android.util.Log.e("AudioMixSource", "musicDecodeLoop error", e);
        } finally {
            if (codec != null) { try { codec.stop(); codec.release(); } catch(Exception ignored){} }
            extractor.release();
        }
    }

    private boolean pushFrame(byte[] frameData, int size) {
        byte[] frame = (frameData.length == size) ? frameData : java.util.Arrays.copyOf(frameData, size);
        if (musicAudioQueue.offer(frame)) {
            return true;
        } else {
            musicAudioQueue.poll();
            return musicAudioQueue.offer(frame);
        }
    }


    private void mixAndPush(byte[] pcm, int size) {
        if (callback == null) {
            // android.util.Log.w("AudioMixSource", "mixAndPush: callback is null");
            return;
        }
        if (size <= 0) return;

        int bytesPerFrame = channelCount * 2;
        int validSize = size - (size % bytesPerFrame);
        if (validSize <= 0) return;

        long nowUs = System.nanoTime() / 1000L;
        if (nextPtsUs == 0) {
            nextPtsUs = nowUs;
        }

        // android.util.Log.v("AudioMixSource", "Pushing PCM frame: size=" + validSize + " pts=" + nextPtsUs);
        callback.inputPCMData(new Frame(java.util.Arrays.copyOf(pcm, validSize), 0, validSize, nextPtsUs));

        long frames = validSize / bytesPerFrame;
        nextPtsUs += (frames * 1_000_000L) / sampleRate;
    }
}