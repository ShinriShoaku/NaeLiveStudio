package ame.project.nlstudio.OBS;

import android.graphics.Bitmap;

/**
 * Diimplementasikan oleh video source yang mendukung transisi cross-dissolve antar scene
 * (CompositeSceneVideoSource, FakeSceneVideoSource). StreamService memakai interface ini supaya
 * bisa mengambil "snapshot" frame terakhir dari scene yang SEDANG aktif sebelum diganti scene
 * lain, lalu mewariskannya ke scene BARU supaya draw-loop scene baru bisa fade-in dari situ
 * (cross-dissolve), alih-alih langsung "cut" hitam/patah seperti sebelumnya.
 *
 * ScreenSource (bawaan RootEncoder) TIDAK mengimplementasikan ini - scene "Layar" tetap "cut"
 * seperti biasa (screen recording tidak menyimpan frame buffer software, jadi tidak ada snapshot
 * murah yang bisa diambil tanpa nge-capture ImageReader ekstra).
 */
public interface SceneCrossfadeSupport {

    /** Dipanggil StreamService SEBELUM start(), mewariskan frame terakhir scene sebelumnya
     *  supaya scene ini bisa fade-in dari situ. Boleh dipanggil dengan null (tidak ada fade). */
    void setFadeFromSnapshot(Bitmap snapshot);

    /** Salinan frame yang sedang tampil sekarang. Bisa null kalau belum sempat menggambar frame
     *  apapun (mis. baru saja start()). Caller bertanggung jawab me-recycle bitmap hasilnya. */
    Bitmap peekCurrentFrame();
}