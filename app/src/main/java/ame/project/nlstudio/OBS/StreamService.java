package ame.project.nlstudio.OBS;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.MediaMetadataRetriever;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.pedro.common.ConnectChecker;
import com.pedro.encoder.input.sources.audio.MicrophoneSource;
import com.pedro.encoder.input.sources.audio.MixAudioSource;
import com.pedro.encoder.utils.gl.AspectRatioMode;
import com.pedro.encoder.input.sources.video.ScreenSource;
import com.pedro.encoder.utils.CodecUtil;
import com.pedro.encoder.utils.ViewPort;
import com.pedro.library.rtmp.RtmpStream;
import com.pedro.library.view.GlStreamInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;


/**
 * Foreground Service yang memegang instance RtmpStream (RootEncoder).
 *
 * Mendukung:
 *  1. LIVE STREAMING (ACTION_START) -> push ke server RTMP.
 *  2. TEST RECORD (ACTION_TEST_RECORD) -> rekam lokal ke file MP4 tanpa push.
 *  3. AUDIO MIXER (audioSourceIndex == 4) -> AudioMixSource dengan volume mic & audio
 *     sistem/game diatur terpisah (EXTRA_MIC_GAIN / EXTRA_SYSTEM_GAIN), dan bisa
 *     diperbarui real-time lewat ACTION_UPDATE_AUDIO_GAIN tanpa restart stream.
 *  4. SCENE (ACTION_SWITCH_SCENE) -> ganti video source on-the-fly antara layar HP
 *     (ScreenSource), gambar statis, atau video file, tanpa memutus koneksi RTMP dan
 *     tanpa minta izin screen-capture ulang (MediaProjection disimpan & dipakai lagi).
 *
 * Catatan: nama/parameter method di RootEncoder bisa berubah antar versi.
 * Contoh resmi: https://github.com/pedroSG94/RootEncoder/tree/master/app/src/main/java/com/pedro/streamer/screen
 */
public class StreamService extends Service implements ConnectChecker {

    private static final String TAG = "RES-SVC";

    /**
     * Diagnostik pakai reflection: dump semua method getter yang namanya mengandung
     * "width"/"height"/"resolution" beserta nilai aktualnya saat runtime. Ini SENGAJA pakai
     * reflection (bukan manggil method spesifik) supaya TIDAK bisa gagal compile walau nama
     * method di versi RootEncoder yang kepasang beda dari yang saya kira. Aman dipanggil kapan
     * saja, error di dalam ditelan per-method (tidak bikin crash aplikasi).
     */
    private void logResolutionGetters(Object obj, String label) {
        if (obj == null) {
            Log.d(TAG, "reflect[" + label + "]: object null, skip");
            return;
        }
        try {
            Log.d(TAG, "reflect[" + label + "]: class=" + obj.getClass().getName());
            for (java.lang.reflect.Method m : obj.getClass().getMethods()) {
                String lower = m.getName().toLowerCase();
                if (m.getParameterCount() == 0
                        && (lower.contains("width") || lower.contains("height") || lower.contains("resolution"))) {
                    try {
                        m.setAccessible(true);
                        Object result = m.invoke(obj);
                        Log.d(TAG, "reflect[" + label + "]: " + m.getName() + "() = " + result);
                    } catch (Exception ignoredInner) {
                        // beberapa getter mungkin butuh state tertentu (misal harus streaming dulu), skip aja
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "reflect[" + label + "]: gagal total", e);
        }
    }

    /**
     * Dump SEMUA method no-arg & field publik dari getGlInterface() yang namanya nyerempet ke
     * orientation/aspect/rotation/size/width/height/mode/render. Dipakai buat diagnosa karena
     * dokumentasi publik RootEncoder gak selalu cocok persis sama versi library yang kepasang -
     * ini liat langsung API & STATE ASLI yang ada di runtime, gak nebak lagi.
     */
    private void logGlInterfaceState(String label) {
        try {
            java.lang.reflect.Method getGl = rtmpStream.getClass().getMethod("getGlInterface");
            getGl.setAccessible(true);
            Object glInterface = getGl.invoke(rtmpStream);
            if (glInterface == null) {
                Log.d(TAG, "reflect[glInterface/" + label + "]: getGlInterface() return null");
                return;
            }
            Log.d(TAG, "reflect[glInterface/" + label + "]: class=" + glInterface.getClass().getName());

            String[] keywords = {"orientation", "aspect", "rotation", "size", "width", "height", "mode", "render", "flip", "force"};

            for (java.lang.reflect.Method m : glInterface.getClass().getMethods()) {
                String lower = m.getName().toLowerCase();
                boolean mMatch = false;
                for (String k : keywords) {
                    if (lower.contains(k)) { mMatch = true; break; }
                }
                if (mMatch && m.getParameterCount() == 0) {
                    try {
                        m.setAccessible(true);
                        Object result = m.invoke(glInterface);
                        Log.d(TAG, "reflect[glInterface/" + label + "]: method " + m.getName() + "() = " + result);
                    } catch (Exception ignoredInner) {
                        Log.d(TAG, "reflect[glInterface/" + label + "]: method " + m.getName() + "() gagal dipanggil (butuh state lain)");
                    }
                }
            }

            for (java.lang.reflect.Field f : glInterface.getClass().getFields()) {
                String lower = f.getName().toLowerCase();
                boolean match = false;
                for (String k : keywords) {
                    if (lower.contains(k)) { match = true; break; }
                }
                if (match) {
                    try {
                        f.setAccessible(true);
                        Object result = f.get(glInterface);
                        Log.d(TAG, "reflect[glInterface/" + label + "]: field " + f.getName() + " = " + result);
                    } catch (Exception ignoredInner) {
                        Log.d(TAG, "reflect[glInterface/" + label + "]: field " + f.getName() + " gagal dibaca");
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "reflect[glInterface/" + label + "]: gagal total", e);
        }
    }


    public static final String ACTION_START = "com.example.tiktoklive.START";
    public static final String ACTION_STOP = "com.example.tiktoklive.STOP";
    public static final String ACTION_TEST_RECORD = "com.example.tiktoklive.TEST_RECORD";
    public static final String ACTION_SWITCH_SCENE = "com.example.tiktoklive.SWITCH_SCENE";
    public static final String ACTION_UPDATE_AUDIO_GAIN = "com.example.tiktoklive.UPDATE_AUDIO_GAIN";
    public static final String ACTION_STATUS_BROADCAST = "com.example.tiktoklive.STATUS_BROADCAST";
    public static final String EXTRA_STATUS_MSG = "statusMsg";

    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_DATA = "data";
    public static final String EXTRA_RTMP_URL = "rtmpUrl";
    public static final String EXTRA_WIDTH = "width";
    public static final String EXTRA_HEIGHT = "height";
    public static final String EXTRA_FPS = "fps";
    public static final String EXTRA_BITRATE = "bitrate";
    public static final String EXTRA_ENCODER_TYPE = "encoderType"; // 0 = hardware(auto), 1 = software
    public static final String EXTRA_TEST_DURATION_MS = "testDurationMs";
    public static final String EXTRA_MIC_GAIN = "micGain";
    public static final String EXTRA_SYSTEM_GAIN = "systemGain";
    public static final String EXTRA_GAME_UID = "gameUid"; // -1 = semua audio sistem

    public static final String EXTRA_SCENE_TYPE = "sceneType";
    public static final String EXTRA_SCENE_URI = "sceneUri";
    public static final String EXTRA_SCENE_JSON = "sceneJson";
    public static final String SCENE_SCREEN = "scene_screen";
    public static final String SCENE_IMAGE = "scene_image";     // dipertahankan buat kompatibilitas lama
    public static final String SCENE_VIDEO = "scene_video";     // dipertahankan buat kompatibilitas lama
    public static final String SCENE_COMPOSITE = "scene_composite"; // scene ala OBS: background + layer2

    // audioSourceIndex == ini artinya pakai AudioMixSource (volume mic & sistem terpisah)
    public static final int AUDIO_MODE_MANUAL_MIXER = 4;

    public static volatile boolean isServiceRunning = false;

    private static final String CHANNEL_ID = "tiktok_live_channel";
    private static final int NOTIFICATION_ID = 1;
    private static final long DEFAULT_TEST_DURATION_MS = 30000L;

    private RtmpStream rtmpStream;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isTestRecording = false;
    private boolean isStopping = false;

    // Disimpan supaya scene bisa gonta-ganti tanpa minta izin screen-capture berkali-kali,
    // dan supaya kembali ke scene "Layar" tinggal bikin ScreenSource baru dari sini.
    private MediaProjection savedMediaProjection;
    private int savedWidth = 720;
    private int savedHeight = 1280;
    private int savedFps = 30;

    private AudioMixSource audioMixSource;

    // FIX SMOOTHNESS: switchScene() dulu melakukan SEMUA kerja berat (parse JSON, decode
    // bitmap, MediaMetadataRetriever, sampai changeVideoSource() yang stop()+start() source)
    // langsung di onStartCommand() - yang SELALU jalan di MAIN THREAD (Service tidak dapat
    // thread sendiri secara default). Itu penyebab utama UI "berat"/patah setiap ganti scene.
    // Sekarang semua kerja berat itu dipindah ke single-thread executor ini, main thread cuma
    // kebagian decode Intent extras (murah) + submit tugas. single-thread (bukan pool) supaya
    // scene switch tetap diproses berurutan (tidak ada 2 switchScene() tumpang tindih).
    private final java.util.concurrent.ExecutorService sceneSwitchExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    // Referensi video source scene yang lagi aktif SEKARANG, dipakai buat ambil "snapshot" frame
    // terakhirnya sesaat sebelum diganti scene lain (bahan efek fade/cross-dissolve).
    private volatile com.pedro.encoder.input.sources.video.VideoSource currentSceneSource;

    @Override
    public void onCreate() {
        super.onCreate();
        isServiceRunning = true;
        rtmpStream = new RtmpStream(this, this);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            return START_NOT_STICKY;
        }

        String action = intent.getAction();

        if (ACTION_START.equals(action) || ACTION_TEST_RECORD.equals(action)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                }
                startForeground(NOTIFICATION_ID, buildNotification(action), type);
            } else {
                startForeground(NOTIFICATION_ID, buildNotification(action));
            }

            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1);
            Intent data = intent.getParcelableExtra(EXTRA_DATA);
            int width = intent.getIntExtra(EXTRA_WIDTH, 720);
            int height = intent.getIntExtra(EXTRA_HEIGHT, 1280);
            int fps = intent.getIntExtra(EXTRA_FPS, 30);
            int vBitrate = intent.getIntExtra(EXTRA_BITRATE, 2500 * 1024);
            int aBitrate = intent.getIntExtra("audioBitrate", 128 * 1024);
            int audioSourceIndex = intent.getIntExtra("audioSourceIndex", 0);
            int encoderType = intent.getIntExtra(EXTRA_ENCODER_TYPE, 0);
            float micGain = intent.getFloatExtra(EXTRA_MIC_GAIN, 1.0f);
            float systemGain = intent.getFloatExtra(EXTRA_SYSTEM_GAIN, 1.0f);
            int gameUid = intent.getIntExtra(EXTRA_GAME_UID, -1);

            String initialSceneType = intent.getStringExtra(EXTRA_SCENE_TYPE);
            String initialSceneJson = intent.getStringExtra(EXTRA_SCENE_JSON);

            Log.d(TAG, "onStartCommand: EXTRA_WIDTH/HEIGHT from intent = " + width + "x" + height
                    + " | sceneType=" + initialSceneType);

            // Jika ada scene JSON, kita bisa intip resolusi root-nya sebelum prepareVideo
            if (initialSceneJson != null) {
                try {
                    JSONObject o = new JSONObject(initialSceneJson);
                    int rootW = o.optInt("rootWidth", -1);
                    int rootH = o.optInt("rootHeight", -1);
                    Log.d(TAG, "onStartCommand: sceneJson rootWidth=" + rootW + " rootHeight=" + rootH
                            + " (raw json=" + initialSceneJson + ")");
                    if (rootW > 0 && rootH > 0) {
                        width = rootW;
                        height = rootH;
                    }

                    // Trigger prefetch untuk video background jika ada
                    String bgUri = o.optString("backgroundUri", null);
                    if ("VIDEO".equals(o.optString("backgroundType")) && bgUri != null) {
                        VideoCacheManager.getInstance().prefetch(this, Uri.parse(bgUri),
                                width, height, fps, 250L * 1024 * 1024, null);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "onStartCommand: gagal parse sceneJson buat intip rootWidth/Height", e);
                }
            }

            // Safety check: resolusi video wajib genap untuk encoder (H.264/H.265).
            // FIX: genap aja ternyata TIDAK CUKUP di sebagian hardware encoder. H.264 bekerja per
            // MACROBLOCK 16x16 piksel - kalau width/height tidak habis dibagi 16 (mis. 1528, cuma
            // habis dibagi 8), sebagian chip encoder diam-diam alokasi buffer internal dibulatkan
            // ke atas ke kelipatan 16 (mis. 1536), lalu crop-rect/stride yang dilaporkan balik ke
            // Surface kita jadi tidak konsisten dengan apa yang kita gambar di Canvas -> muncul
            // sebagai pita/band + bar hitam (PERSIS gejala yang muncul di scene composite kita,
            // yang render manual via Canvas->Surface, beda dari ScreenSource/VirtualDisplay yang
            // agaknya sudah handle alignment ini sendiri secara internal).
            // Makanya sekarang dibulatkan ke KELIPATAN 16 dulu (bukan cuma genap), searah ke bawah
            // biar tidak pernah melebihi resolusi asli layar.
            int originalWidth = width;
            int originalHeight = height;
            width -= (width % 16);
            height -= (height % 16);
            if (width <= 0) width = originalWidth - (originalWidth % 2);
            if (height <= 0) height = originalHeight - (originalHeight % 2);
            if (width != originalWidth || height != originalHeight) {
                Log.d(TAG, "onStartCommand: resolusi di-align ke kelipatan 16 utk hindari macroblock "
                        + "mismatch di hardware encoder: " + originalWidth + "x" + originalHeight
                        + " -> " + width + "x" + height);
            }

            savedWidth = width;
            savedHeight = height;
            savedFps = fps;

            Log.d(TAG, "onStartCommand: FINAL width/height dipakai buat prepareVideo() = "
                    + savedWidth + "x" + savedHeight + " (orientation="
                    + (savedWidth > savedHeight ? "LANDSCAPE" : "PORTRAIT") + ")");

            if (ACTION_START.equals(action)) {
                String rtmpUrl = intent.getStringExtra(EXTRA_RTMP_URL);
                startStreaming(resultCode, data, rtmpUrl, width, height, fps, vBitrate, aBitrate,
                        audioSourceIndex, encoderType, micGain, systemGain, gameUid, initialSceneType, initialSceneJson);
            } else {
                long durationMs = intent.getLongExtra(EXTRA_TEST_DURATION_MS, DEFAULT_TEST_DURATION_MS);
                startTestRecord(resultCode, data, width, height, fps, vBitrate, aBitrate, audioSourceIndex, encoderType, durationMs, micGain, systemGain, gameUid, initialSceneType, initialSceneJson);
            }
        } else if (ACTION_STOP.equals(action)) {
            stopEverything();
        } else if (ACTION_SWITCH_SCENE.equals(action)) {
            String scene = intent.getStringExtra(EXTRA_SCENE_TYPE);
            Uri uri = intent.getParcelableExtra(EXTRA_SCENE_URI);
            String sceneJson = intent.getStringExtra(EXTRA_SCENE_JSON);
            switchScene(scene, uri, sceneJson);
        } else if (ACTION_UPDATE_AUDIO_GAIN.equals(action)) {
            float micGain = intent.getFloatExtra(EXTRA_MIC_GAIN, 1.0f);
            float systemGain = intent.getFloatExtra(EXTRA_SYSTEM_GAIN, 1.0f);
            if (audioMixSource != null) {
                audioMixSource.setMicGain(micGain);
                audioMixSource.setInternalGain(systemGain);
            }
        }
        return START_STICKY;
    }

    // ==================== LIVE STREAMING ====================

    private void startStreaming(int resultCode, Intent data, String rtmpUrl, int width, int height, int fps,
                                int vBitrate, int aBitrate, int audioSourceIndex, int encoderType,
                                float micGain, float systemGain, int gameUid,
                                String initialSceneType, String initialSceneJson) {
        if (rtmpStream.isStreaming() || data == null || rtmpUrl == null || rtmpUrl.isEmpty()) {
            return;
        }

        applyEncoderType(encoderType);

        // KOREKSI: analisa sebelumnya SALAH. RtmpStream (extends StreamBase, BUKAN Camera2Base)
        // punya urutan parameter beda dari RtmpCamera2. Berdasarkan log RES-COMP setelah dicoba
        // (create() melaporkan fps=2.560.000 setelah argumen ditukar -> itu nilai vBitrate kita,
        // berarti urutan yg BENAR utk RtmpStream/StreamBase adalah:
        //   prepareVideo(width, height, bitrate, fps, iFrameInterval, rotation=0 default)
        // Jadi panggilan ASLI (width, height, vBitrate, fps, 2) itu SUDAH BENAR - "2" adalah
        // iFrameInterval (keyframe tiap 2 detik), BUKAN rotation. Dikembalikan ke bentuk semula.
        Log.d(TAG, "startStreaming: MEMANGGIL rtmpStream.prepareVideo(width=" + width
                + ", height=" + height + ", bitrate=" + vBitrate + ", fps=" + fps
                + ", iFrameInterval=2) <- dikembalikan ke urutan asli, ini yg benar utk RtmpStream");
        boolean videoOk = rtmpStream.prepareVideo(width, height, vBitrate, fps, 2);
        Log.d(TAG, "startStreaming: prepareVideo() returned videoOk=" + videoOk);
        logResolutionGetters(rtmpStream, "rtmpStream setelah prepareVideo");

        // FIX: Hapus paksaan AspectRatioMode.NONE di sini agar tidak konflik dengan applyGlProperties()
        // dan pastikan setRotation(0) agar tidak menebak orientasi dari sensor.
        try {
            // Kode lama dipindah ke applyGlProperties()
        } catch (Exception e) {
            Log.e(TAG, "startStreaming: gagal set GL properties", e);
        }

        boolean audioOk = true;
        if (audioSourceIndex != 3) { // 3 = Muted
            audioOk = rtmpStream.prepareAudio(44100, true, aBitrate);
        }

        if (!videoOk || !audioOk) {
            stopEverything();
            return;
        }

        MediaProjection mediaProjection = getMediaProjection(resultCode, data);
        if (mediaProjection == null) {
            stopEverything();
            return;
        }
        savedMediaProjection = mediaProjection;

        applyAudioSource(mediaProjection, audioSourceIndex, micGain, systemGain, gameUid);

        // Terapkan source video awal berdasarkan scene yang dipilih. Belum ada scene aktif
        // sebelumnya di titik ini, jadi tidak ada snapshot untuk fade (null = cut biasa).
        if (SCENE_COMPOSITE.equals(initialSceneType) && initialSceneJson != null) {
            try {
                applyCompositeScene(initialSceneJson, null);
            } catch (Exception e) {
                ScreenSource fallback = new ScreenSource(this, mediaProjection);
                rtmpStream.changeVideoSource(fallback);
                currentSceneSource = fallback;
            }
        } else {
            ScreenSource initial = new ScreenSource(this, mediaProjection);
            rtmpStream.changeVideoSource(initial);
            currentSceneSource = initial;
        }

        // FIX: Panggil properti GL SETELAH changeVideoSource agar tidak ter-reset
        applyGlProperties();

        rtmpStream.startStream(rtmpUrl);
    }

    private void applyGlProperties() {
        try {
            // getGlInterface() balikin tipe konkret GlStreamInterface (bukan interface GlInterface),
            // jadi semua method di bawah ini PUBLIC dan bisa dipanggil langsung - tidak butuh reflection.
            GlStreamInterface glInterface = rtmpStream.getGlInterface();

            // Penting: Reset rotasi ke 0 agar tidak mengikuti sensor HP saat render Composite
            glInterface.setRotation(0);

            // CATATAN PENTING: AspectRatioMode HANYA berlaku untuk jalur PREVIEW (drawScreenPreview).
            // Jalur ENCODER/stream (drawScreenEncoder) sama sekali tidak menerima parameter
            // AspectRatioMode - saat streamViewPort null, dia otomatis pakai
            // SizeCalculator.calculateViewPortEncoder(width, height, isPortrait), yang BISA
            // menghasilkan pillarbox/letterbox sendiri kalau isPortrait tidak cocok dengan rasio
            // width x height encoder kita. Makanya set AspectRatioMode.NONE saja TIDAK CUKUP untuk
            // menghilangkan pita di hasil STREAM - streamViewPort harus di-override manual ke
            // full-frame secara eksplisit seperti di bawah ini.
            glInterface.setAspectRatioMode(AspectRatioMode.NONE);
            glInterface.setStreamViewPort(new ViewPort(0, 0, savedWidth, savedHeight));
            glInterface.setPreviewViewPort(new ViewPort(0, 0, savedWidth, savedHeight));

            // Matikan handle orientasi otomatis (property Kotlin publik, jadi ini bukan reflection,
            // ini panggilan setter Java biasa hasil kompilasi `var autoHandleOrientation`).
            glInterface.setAutoHandleOrientation(false);

            // Method resmi di GlInterface, tidak perlu reflection.
            glInterface.setForceRender(true);

            Log.d(TAG, "applyGlProperties: Rotation=0, AspectRatioMode=NONE, StreamViewPort="
                    + savedWidth + "x" + savedHeight + ", ForceRender=true, AutoOrient=false");
        } catch (Exception e) {
            Log.e(TAG, "applyGlProperties: gagal", e);
        }
    }

    // ==================== SCENE SWITCHING ====================

    /**
     * Ganti video source on-the-fly. Screen recording tetap "siap" karena MediaProjection
     * disimpan (savedMediaProjection) - jadi balik ke scene Layar tidak minta izin lagi.
     *
     * FIX SMOOTHNESS: dulu SEMUA kerja di method ini (load bitmap dari URI, MediaMetadataRetriever
     * per layer video, parse JSON scene, sampai changeVideoSource() yang stop()+start() source)
     * dieksekusi LANGSUNG di sini - dan method ini selalu dipanggil dari onStartCommand(), yang
     * SELALU jalan di MAIN THREAD (Service tidak otomatis dapat thread sendiri). Itu penyebab
     * utama UI kerasa "berat"/patah setiap ganti scene: MediaMetadataRetriever saja bisa
     * 50-300ms PER PANGGILAN, ditambah changeVideoSource() yang nge-join() thread lama sampai
     * ~200-300ms - semua numpuk di main thread.
     *
     * Sekarang: bagian yang WAJIB tetap di caller thread (baca Intent extras - sudah dilakukan
     * oleh pemanggil, dan ambil snapshot frame scene aktif - operasi cepat, lihat
     * captureCurrentSceneSnapshot()) tetap sinkron. Sisanya (decode bitmap, retriever, JSON,
     * bikin decoder, DAN changeVideoSource() itu sendiri) dipindah ke sceneSwitchExecutor
     * (single background thread, urut - tidak akan ada 2 switch tumpang tindih).
     */
    private void switchScene(String scene, Uri uri, String sceneJson) {
        Log.d(TAG, "switchScene: scene=" + scene + " uri=" + uri
                + " sceneJson=" + sceneJson + " savedWidth/Height="
                + savedWidth + "x" + savedHeight);
        if (scene == null || savedMediaProjection == null) return;

        // Snapshot frame terakhir dari scene yang MASIH aktif sekarang, diambil SEBELUM di-stop().
        // Ini cuma satu kali bitmap copy (~beberapa ms), aman dilakukan di main thread.
        final Bitmap fadeSnapshot = captureCurrentSceneSnapshot();

        sceneSwitchExecutor.execute(() -> {
            try {
                if (SCENE_SCREEN.equals(scene)) {
                    ScreenSource source = new ScreenSource(this, savedMediaProjection);
                    // ScreenSource bawaan RootEncoder tidak mendukung snapshot/fade (lihat
                    // SceneCrossfadeSupport) - tetap "cut" langsung, tapi tidak lagi memblokir UI.
                    rtmpStream.changeVideoSource(source);
                    currentSceneSource = source;
                    updateInternalAudioMuteState(true);
                    applyGlProperties();

                } else if (SCENE_COMPOSITE.equals(scene) && sceneJson != null) {
                    applyCompositeScene(sceneJson, fadeSnapshot);

                } else if (SCENE_IMAGE.equals(scene) && uri != null) {
                    Bitmap bitmap = loadBitmapFromUri(uri, savedWidth, savedHeight);
                    if (bitmap != null) {
                        FakeSceneVideoSource source = new FakeSceneVideoSource(this, bitmap);
                        source.setResolution(savedWidth, savedHeight);
                        source.setFadeFromSnapshot(fadeSnapshot);
                        rtmpStream.changeVideoSource(source);
                        currentSceneSource = source;
                    } else if (fadeSnapshot != null) {
                        fadeSnapshot.recycle();
                    }
                    updateInternalAudioMuteState(false);
                    applyGlProperties();

                } else if (SCENE_VIDEO.equals(scene) && uri != null) {
                    FakeSceneVideoSource source = new FakeSceneVideoSource(this, uri);
                    source.setResolution(savedWidth, savedHeight);
                    source.setFadeFromSnapshot(fadeSnapshot);
                    rtmpStream.changeVideoSource(source);
                    currentSceneSource = source;
                    updateInternalAudioMuteState(false);
                    applyGlProperties();
                } else if (fadeSnapshot != null) {
                    fadeSnapshot.recycle();
                }

            } catch (Exception e) {
                Log.e(TAG, "Gagal ganti scene", e);
                if (fadeSnapshot != null && !fadeSnapshot.isRecycled()) fadeSnapshot.recycle();
                handler.post(() -> Toast.makeText(this,
                        "Gagal ganti scene: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    /** Ambil salinan frame terakhir dari source yang lagi aktif sekarang, kalau source itu
     *  mendukung SceneCrossfadeSupport (Composite/Fake). Dipakai sebagai bahan cross-dissolve
     *  buat scene BERIKUTNYA. Return null kalau tidak ada/tidak didukung - scene berikutnya
     *  akan "cut" langsung seperti biasa, tidak ada error. */
    private Bitmap captureCurrentSceneSnapshot() {
        Object src = currentSceneSource;
        if (src instanceof SceneCrossfadeSupport) {
            try {
                return ((SceneCrossfadeSupport) src).peekCurrentFrame();
            } catch (Exception e) {
                Log.w(TAG, "captureCurrentSceneSnapshot: gagal ambil snapshot, lanjut tanpa fade", e);
            }
        }
        return null;
    }

    /**
     * Parse JSON scene (background + layers) dari MainActivity, load semua bitmap yang dibutuhkan,
     * lalu render pakai CompositeSceneVideoSource. Ini jalur scene ala OBS yang baru.
     */
    private void applyCompositeScene(String sceneJson, Bitmap fadeSnapshot) throws Exception {
        JSONObject o = new JSONObject(sceneJson);
        String bgTypeStr = o.optString("backgroundType", "COLOR");
        String bgUriStr = o.isNull("backgroundUri") ? null : o.optString("backgroundUri", null);

        // Prefetch video background jika scene baru ini memilikinya
        if ("VIDEO".equals(bgTypeStr) && bgUriStr != null) {
            VideoCacheManager.getInstance().prefetch(this, Uri.parse(bgUriStr),
                    savedWidth, savedHeight, savedFps, 250L * 1024 * 1024, null);
        }

        // FIX: JANGAN pakai rootWidth/rootHeight dari JSON scene di sini. Itu cuma resolusi device
        // pas scene itu DIBUAT/DISIMPAN, bisa beda dari resolusi yang AKTUAL dipakai encoder di sesi
        // live sekarang (savedWidth/savedHeight, hasil prepareVideo()). Kalau dipaksa pakai rootW/H
        // dan CompositeSceneVideoSource.create() TIDAK terpanggil ulang saat changeVideoSource()
        // mid-stream (ini yg sering terjadi di RootEncoder), maka buffer SurfaceTexture jadi beda
        // ukuran dari encoder -> GL renderer nge-scale+letterbox composite kita jadi kotak kecil
        // di tengah layar (persis bug yang dilaporkan user). Jalur SCENE_IMAGE/SCENE_VIDEO lama di
        // switchScene() SUDAH benar pakai savedWidth/savedHeight - composite path ini disamakan.
        int rootW = savedWidth;
        int rootH = savedHeight;
        Log.d(TAG, "applyCompositeScene: JSON rootWidth/Height=" + o.optInt("rootWidth", -1) + "x"
                + o.optInt("rootHeight", -1) + " (DIABAIKAN) -> DIPAKAI savedWidth/Height (encoder aktual)="
                + rootW + "x" + rootH);

        CompositeSceneVideoSource.BackgroundType bgType;
        Bitmap backgroundImage = null;
        Uri backgroundVideoUri = null;

        switch (bgTypeStr) {
            case "IMAGE":
                bgType = CompositeSceneVideoSource.BackgroundType.IMAGE;
                if (bgUriStr != null) backgroundImage = loadBitmapPreserveAspect(Uri.parse(bgUriStr));
                break;
            case "VIDEO":
                bgType = CompositeSceneVideoSource.BackgroundType.VIDEO;
                if (bgUriStr != null) backgroundVideoUri = Uri.parse(bgUriStr);
                break;
            default:
                bgType = CompositeSceneVideoSource.BackgroundType.COLOR;
        }

        List<CompositeSceneVideoSource.Layer> layers = new ArrayList<>();
        JSONArray layerArr = o.optJSONArray("layers");
        if (layerArr != null) {
            for (int i = 0; i < layerArr.length(); i++) {
                JSONObject lo = layerArr.getJSONObject(i);
                String layerType = lo.optString("type", "IMAGE");
                Uri layerUri = Uri.parse(lo.getString("uri"));

                Bitmap bmp = null;
                if ("VIDEO".equals(layerType)) {
                    bmp = loadVideoFirstFrame(layerUri);
                } else if (!"SCREEN".equals(layerType)) {
                    bmp = loadBitmapPreserveAspect(layerUri);
                }

                if (bmp == null && !"SCREEN".equals(layerType)) continue;

                layers.add(new CompositeSceneVideoSource.Layer(
                        bmp,
                        lo.optString("uri", ""),
                        ame.project.nlstudio.scene.LayerType.valueOf(layerType),
                        (float) lo.getDouble("x"),
                        (float) lo.getDouble("y"),
                        (float) lo.getDouble("w"),
                        (float) lo.getDouble("h"),
                        lo.optInt("zIndex", 0)
                ));
            }
        }
        // urutin sesuai zIndex biar layer "belakang" digambar duluan, layer "depan" nutup di atasnya
        Collections.sort(layers, (a, b) -> Integer.compare(a.zIndex, b.zIndex));

        // Auto-mute internal audio jika tidak ada layer SCREEN
        boolean hasScreenLayer = false;
        for (CompositeSceneVideoSource.Layer layer : layers) {
            if (layer.type == ame.project.nlstudio.scene.LayerType.SCREEN) {
                hasScreenLayer = true;
                break;
            }
        }
        updateInternalAudioMuteState(hasScreenLayer);

        CompositeSceneVideoSource source = new CompositeSceneVideoSource(
                this, bgType, backgroundImage, backgroundVideoUri, layers, rootW, rootH);
        source.setMediaProjection(savedMediaProjection);
        source.setFadeFromSnapshot(fadeSnapshot);
        rtmpStream.changeVideoSource(source);
        currentSceneSource = source;
        applyGlProperties();
        logResolutionGetters(rtmpStream, "rtmpStream setelah changeVideoSource(composite)");
        logGlInterfaceState("setelah changeVideoSource(composite)");
    }

    /** Ambil 1 frame dari video layer buat dipakai sebagai bitmap overlay pas live (bukan motion,
     *  sama kayak jalur background VIDEO tapi utk layer individual). */
    private Bitmap loadVideoFirstFrame(Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(this, uri);
            return retriever.getFrameAtTime(0);
        } catch (Exception e) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    private Bitmap loadBitmapFromUri(Uri uri, int reqWidth, int reqHeight) {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            Bitmap original = BitmapFactory.decodeStream(is);
            if (original == null) return null;
            return Bitmap.createScaledBitmap(original, reqWidth, reqHeight, true);
        } catch (Exception e) {
            return null;
        }
    }

    /** Load bitmap TANPA maksa stretch ke resolusi live - aspect rasio aslinya dipertahankan,
     *  biar CompositeSceneVideoSource yang atur crop/fit-nya pas compositing. Dibatasi max dimensi
     *  biar gak makan memori berlebihan buat gambar yang resolusinya kegedean. */
    private Bitmap loadBitmapPreserveAspect(Uri uri) {
        if ("text".equals(uri.getScheme())) {
            // Ambil teks murni setelah "text:"
            return renderTextToBitmap(uri.getSchemeSpecificPart());
        }
        int maxDim = Math.max(savedWidth, savedHeight);
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            Bitmap original = BitmapFactory.decodeStream(is);
            if (original == null) return null;
            float scale = Math.min(1f, (float) maxDim / Math.max(original.getWidth(), original.getHeight()));
            if (scale >= 1f) return original;
            return Bitmap.createScaledBitmap(original,
                    Math.round(original.getWidth() * scale),
                    Math.round(original.getHeight() * scale), true);
        } catch (Exception e) {
            return null;
        }
    }

    private Bitmap renderTextToBitmap(String rawText) {
        String textToRender = rawText == null ? "" : rawText;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        paint.setTextSize(64f);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.LEFT);

        Rect bounds = new Rect();
        paint.getTextBounds(textToRender, 0, textToRender.length(), bounds);

        int width = Math.max(bounds.width() + 40, 100);
        int height = Math.max(bounds.height() + 40, 100);

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint bgPaint = new Paint();
        bgPaint.setColor(Color.parseColor("#80000000"));
        canvas.drawRect(0, 0, width, height, bgPaint);

        canvas.drawText(textToRender, 20f, height / 2f + bounds.height() / 2f - 5f, paint);
        return bitmap;
    }

    // ==================== TEST RECORD (LOKAL, TANPA LIVE) ====================

    private void startTestRecord(int resultCode, Intent data, int width, int height, int fps, int vBitrate, int aBitrate, int audioSourceIndex, int encoderType, long durationMs,
                                 float micGain, float systemGain, int gameUid,
                                 String initialSceneType, String initialSceneJson) {
        if (rtmpStream.isStreaming() || isTestRecording || data == null) {
            return;
        }

        applyEncoderType(encoderType);

        Log.d(TAG, "startTestRecord: prepareVideo(width=" + width + ", height=" + height
                + ", bitrate=" + vBitrate + ", fps=" + fps + ", iFrameInterval=2)");
        boolean videoOk = rtmpStream.prepareVideo(width, height, vBitrate, fps, 2);

        boolean audioOk = true;
        if (audioSourceIndex != 3) {
            audioOk = rtmpStream.prepareAudio(44100, true, aBitrate);
        }

        if (!videoOk || !audioOk) {
            stopEverything();
            return;
        }

        MediaProjection mediaProjection = getMediaProjection(resultCode, data);
        if (mediaProjection == null) {
            stopEverything();
            return;
        }
        savedMediaProjection = mediaProjection;

        applyAudioSource(mediaProjection, audioSourceIndex, micGain, systemGain, gameUid);

        if (SCENE_COMPOSITE.equals(initialSceneType) && initialSceneJson != null) {
            try {
                applyCompositeScene(initialSceneJson, null);
            } catch (Exception e) {
                ScreenSource fallback = new ScreenSource(this, mediaProjection);
                rtmpStream.changeVideoSource(fallback);
                currentSceneSource = fallback;
            }
        } else {
            ScreenSource initial = new ScreenSource(this, mediaProjection);
            rtmpStream.changeVideoSource(initial);
            currentSceneSource = initial;
        }

        applyGlProperties();

        String encoderLabel = (encoderType == 1) ? "SOFTWARE" : "HARDWARE";
        // 1. Simpan sementara di folder internal agar tidak kena blokir permission
        File tempFolder = new File(getExternalFilesDir(null), "temp_records");
        if (!tempFolder.exists()) tempFolder.mkdirs();
        File tempFile = new File(tempFolder, "temp_record.mp4");
        String outputPath = tempFile.getAbsolutePath();

        try {
            rtmpStream.startRecord(outputPath, status -> {});
            isTestRecording = true;
            Toast.makeText(this, "Merekam... (30 detik)", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Gagal mulai rekam: " + e.getMessage(), Toast.LENGTH_LONG).show();
            stopEverything();
            return;
        }

        handler.postDelayed(() -> {
            if (isTestRecording) {
                finishTestRecord(outputPath, encoderLabel, width, height);
            }
        }, durationMs);
    }

    private void finishTestRecord(String tempPath, String encoderLabel, int w, int h) {
        if (rtmpStream != null && rtmpStream.isRecording()) {
            rtmpStream.stopRecord();
        }
        isTestRecording = false;

        // 2. Export file dari folder internal ke Gallery (MediaStore)
        try {
            android.content.ContentValues values = new android.content.ContentValues();
            String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(new java.util.Date());
            String fileName = "NLStudio_" + encoderLabel + "_" + w + "x" + h + "_" + timestamp + ".mp4";

            values.put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, fileName);
            values.put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_MOVIES + "/NLStudio");

            android.net.Uri uri = getContentResolver().insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                try (java.io.InputStream is = new java.io.FileInputStream(tempPath);
                     java.io.OutputStream os = getContentResolver().openOutputStream(uri)) {
                    byte[] buffer = new byte[1024 * 1024];
                    int read;
                    while ((read = is.read(buffer)) != -1) {
                        os.write(buffer, 0, read);
                    }
                }
                stopEverything("Rekaman Selesai & Tersimpan ke Gallery");
                Toast.makeText(this, "Berhasil! Cek Gallery di folder Movies/NLStudio", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Gagal export ke gallery", e);
            stopEverything("Rekaman Selesai, Gagal Simpan ke Gallery");
            Toast.makeText(this, "Rekam selesai, tapi gagal pindah ke Gallery", Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== HELPER BERSAMA ====================

    private void applyEncoderType(int encoderType) {
        if (encoderType == 1) {
            rtmpStream.forceCodecType(CodecUtil.CodecType.SOFTWARE, CodecUtil.CodecType.FIRST_COMPATIBLE_FOUND);
        } else {
            rtmpStream.forceCodecType(CodecUtil.CodecType.HARDWARE, CodecUtil.CodecType.FIRST_COMPATIBLE_FOUND);
        }
    }

    private MediaProjection getMediaProjection(int resultCode, Intent data) {
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        MediaProjection mediaProjection = manager.getMediaProjection(resultCode, data);
        if (mediaProjection != null) {
            // FIX: Di Android 14+ (API 34), MediaProjection WAJIB punya callback yang terdaftar
            // SEBELUM dipakai buat createVirtualDisplay atau audio capture. Kalau tidak,
            // muncul "IllegalStateException: MediaProjection: must register a callback before start".
            mediaProjection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    Log.d(TAG, "MediaProjection stopped");
                }
            }, handler);
        }
        return mediaProjection;
    }

    private void updateInternalAudioMuteState(boolean enabled) {
        if (audioMixSource != null) {
            Log.d(TAG, "updateInternalAudioMuteState: " + enabled);
            audioMixSource.setInternalEnabled(enabled);
        }
    }

    private void applyAudioSource(MediaProjection mediaProjection, int audioSourceIndex,
                                  float micGain, float systemGain, int gameUid) {
        switch (audioSourceIndex) {
            case 0: // Internal + Mic
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    audioMixSource = new AudioMixSource(mediaProjection, -1);
                    audioMixSource.setMicGain(micGain);
                    audioMixSource.setInternalGain(systemGain);
                    rtmpStream.changeAudioSource(audioMixSource);
                } else {
                    rtmpStream.changeAudioSource(new MicrophoneSource());
                }
                break;
            case 1: // Internal Only
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    audioMixSource = new AudioMixSource(mediaProjection, -1);
                    audioMixSource.setMicEnabled(false);
                    audioMixSource.setMicGain(0f);
                    audioMixSource.setInternalGain(systemGain);
                    rtmpStream.changeAudioSource(audioMixSource);
                } else {
                    rtmpStream.changeAudioSource(new MicrophoneSource());
                }
                break;
            case 2: // Mic Only
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    audioMixSource = new AudioMixSource(mediaProjection, -1);
                    audioMixSource.setInternalEnabled(false);
                    audioMixSource.setInternalGain(0f);
                    audioMixSource.setMicGain(micGain);
                    rtmpStream.changeAudioSource(audioMixSource);
                } else {
                    rtmpStream.changeAudioSource(new MicrophoneSource());
                }
                break;
            case 3: // Muted
                break;
            case AUDIO_MODE_MANUAL_MIXER: // Mixer manual - volume mic & sistem/game terpisah
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    audioMixSource = new AudioMixSource(mediaProjection, gameUid);
                    audioMixSource.setMicGain(micGain);
                    audioMixSource.setInternalGain(systemGain);
                    rtmpStream.changeAudioSource(audioMixSource);
                } else {
                    Toast.makeText(this, "Mixer manual butuh Android 10+", Toast.LENGTH_LONG).show();
                    rtmpStream.changeAudioSource(new MicrophoneSource());
                }
                break;
        }
    }

    private void sendStatusBroadcast(String msg) {
        Intent intent = new Intent(ACTION_STATUS_BROADCAST);
        intent.putExtra(EXTRA_STATUS_MSG, msg);
        sendBroadcast(intent);
    }

    private void stopEverything() {
        stopEverything(null);
    }

    private void stopEverything(String finalMsg) {
        if (isStopping) return;
        isStopping = true;

        handler.removeCallbacksAndMessages(null);
        try {
            if (rtmpStream != null && rtmpStream.isRecording()) {
                rtmpStream.stopRecord();
            }
        } catch (Exception ignored) {
        }
        isTestRecording = false;
        try {
            if (rtmpStream != null && rtmpStream.isStreaming()) {
                rtmpStream.stopStream();
            }
        } catch (Exception ignored) {
        }
        if (audioMixSource != null) {
            audioMixSource.stop();
            audioMixSource = null;
        }
        currentSceneSource = null;
        if (savedMediaProjection != null) {
            try {
                savedMediaProjection.stop();
            } catch (Exception ignored) {
            }
            savedMediaProjection = null;
        }

        sendStatusBroadcast(finalMsg != null ? finalMsg : "Status: idle");

        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopEverything();
        isServiceRunning = false;
        sceneSwitchExecutor.shutdownNow();
        super.onDestroy();
    }

    private Notification buildNotification(String action) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "TikTok Live", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
        boolean isTest = ACTION_TEST_RECORD.equals(action);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(isTest ? "Test record aktif" : "Live streaming aktif")
                .setContentText(isTest ? "Merekam layar untuk uji kualitas encoder..." : "Sedang mengirim layar ke server RTMP...")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();
    }

    // ==== ConnectChecker callbacks (status koneksi RTMP) ====
    @Override
    public void onConnectionStarted(String url) {
    }

    @Override
    public void onConnectionSuccess() {
        sendStatusBroadcast("Live Streaming Aktif");
    }

    @Override
    public void onConnectionFailed(String reason) {
        sendStatusBroadcast("Koneksi Gagal: " + reason);
        stopEverything();
    }

    @Override
    public void onNewBitrate(long bitrate) {
    }

    @Override
    public void onDisconnect() {
    }

    @Override
    public void onAuthError() {
    }

    @Override
    public void onAuthSuccess() {
    }
}