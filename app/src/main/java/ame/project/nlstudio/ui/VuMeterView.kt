package ame.project.nlstudio.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Bar level audio ala OBS: segmen hijau (aman) -> kuning (agak keras) -> merah (mepet clipping).
 * Ada sedikit "decay" (turunnya pelan-pelan) biar keliatan hidup, bukan lompat-lompat kaku.
 * Panggil setLevel(0f..1f) tiap ada data level baru dari AudioLevelBus.
 */
class VuMeterView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var displayedLevel = 0f
    private var targetLevel = 0f
    private val segmentCount = 24

    private val bgPaint = Paint().apply { color = Color.parseColor("#25272E") }
    private val fillPaint = Paint()

    fun setLevel(level: Float) {
        targetLevel = level.coerceIn(0f, 1f)
        // Gunakan interpolasi linear yang lebih halus
        displayedLevel = if (targetLevel > displayedLevel) {
            displayedLevel * 0.4f + targetLevel * 0.6f
        } else {
            displayedLevel * 0.85f + targetLevel * 0.15f
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        canvas.drawRoundRect(RectF(0f, 0f, w, h), h / 2f, h / 2f, bgPaint)

        val activeSegments = Math.round(displayedLevel * segmentCount)
        val segmentWidth = w / segmentCount
        for (i in 0 until segmentCount) {
            val left = i * segmentWidth
            val right = left + segmentWidth - 3f
            if (right <= left) continue
            fillPaint.color = if (i >= activeSegments) {
                Color.parseColor("#33353D") // segmen belum kepakai
            } else when {
                i < segmentCount * 0.7 -> Color.parseColor("#7FD8A6") // aman
                i < segmentCount * 0.9 -> Color.parseColor("#F5C242") // waspada
                else -> Color.parseColor("#FF5C5C")                   // mepet clipping
            }
            canvas.drawRoundRect(RectF(left, 0f, right, h), 2f, 2f, fillPaint)
        }
    }
}