package ame.project.nlstudio.scene

/**
 * Tipe background scene:
 * - SCREEN : rekam layar HP asli (jalur khusus, TIDAK dicompose dengan layer lain).
 * - COLOR  : background polos hitam, dipakai kalau cuma mau nampilin layer2 (logo/icon) doang.
 * - IMAGE  : gambar statis yang nutup penuh canvas (di-crop biar full, kayak background OBS).
 * - VIDEO  : video yang di-loop, nutup penuh canvas.
 */
enum class BackgroundType { SCREEN, COLOR, IMAGE, VIDEO }

enum class LayerType {
    IMAGE, ICON, TEXT, VIDEO, SCREEN, VOICE_ANIM,
    TIKTOK_CHAT, TIKTOK_GIFT, TIKTOK_JOIN,
    MUSIC_CURRENT, MUSIC_QUEUE, EFFECT, MUSIC
}

enum class AnimationEffect {
    BURST, HEARTS, SNOW, RAINBOW, CONFETTI, FIREWORKS,
    SPARKLE, BUBBLES, GLITTER, SPIRAL, PETALS, LEAVES, STARDUST, METEOR
}

/**
 * Satu item overlay (gambar/icon) di atas background. Posisi & ukuran disimpan dalam RASIO 0..1
 * terhadap canvas/live (bukan pixel absolut), jadi otomatis nyesuain ke resolusi live berapapun,
 * dan gampang digambar ulang tiap kali resolusi live berubah.
 */
data class SceneLayer(
    val id: String,
    val type: LayerType,
    val uri: String,
    var x: Float = 0.3f,
    var y: Float = 0.3f,
    var w: Float = 0.35f,
    var h: Float = 0.35f,
    var zIndex: Int = 0
)

data class Scene(
    val id: String,
    var name: String,
    val backgroundType: BackgroundType,
    var backgroundUri: String? = null,           // null utk SCREEN & COLOR
    val layers: MutableList<SceneLayer> = mutableListOf(),
    val thumbnailPath: String? = null,             // hasil composite background+layers, dipakai buat preview list
    var rootWidth: Int = 1080,
    var rootHeight: Int = 1920,
    var internalAudioEnabled: Boolean = true
)
