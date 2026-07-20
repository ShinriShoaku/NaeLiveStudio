package ame.project.nlstudio.ui

import ame.project.nlstudio.scene.AnimationEffect
import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*
import kotlin.random.Random

class QuickAnimationView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private val renderer: MyRenderer

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderOnTop(true)

        renderer = MyRenderer(this)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun startAnimation(effect: AnimationEffect) {
        queueEvent {
            renderer.trigger(effect)
        }
        requestRender()
    }

    private object Shape {
        const val CIRCLE = 0f
        const val HEART = 1f
        const val STAR = 2f
        const val DIAMOND = 3f
    }

    private class Particle {
        var active = false
        var effect = AnimationEffect.BURST
        var birthTime = 0L
        var life = 1f
        var x0 = 0f
        var y0 = 0f
        var vx = 0f
        var vy = 0f
        var r = 1f
        var g = 1f
        var b = 1f
        var baseSize = 20f
        var shape = Shape.CIRCLE
        var seed = 0f
        var seed2 = 0f
        var curX = 0f
        var curY = 0f
        var curSize = 0f
        var curAlpha = 0f
    }

    private class MyRenderer(private val view: GLSurfaceView) : Renderer {
        private var program: Int = 0
        private var positionHandle: Int = 0
        private var colorHandle: Int = 0
        private var pointSizeHandle: Int = 0
        private var shapeHandle: Int = 0

        private val poolSize = 2500
        private val pool = Array(poolSize) { Particle() }
        private var cursor = 0

        private val floatsPerVertex = 8
        private val strideBytes = floatsPerVertex * 4
        private val vertexBuffer: FloatBuffer =
            ByteBuffer.allocateDirect(poolSize * floatsPerVertex * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()

        private val vertexShaderCode = """
            attribute vec4 vPosition;
            attribute vec4 vColor;
            attribute float vPointSize;
            attribute float vShape;
            varying vec4 fColor;
            varying float fShape;
            void main() {
                gl_Position = vPosition;
                gl_PointSize = vPointSize;
                fColor = vColor;
                fShape = vShape;
            }
        """.trimIndent()

        private val fragmentShaderCode = """
            precision mediump float;
            varying vec4 fColor;
            varying float fShape;

            float heartDist(vec2 uv) {
                vec2 p = (uv - 0.5) * 2.2;
                p.y = -p.y + 0.35;
                float r = length(p);
                float a = abs(atan(p.x, p.y)) / 3.14159265;
                float d = (13.0 - 10.0 * a - 8.0 * a * a + 8.0 * a * a * a) / (5.0 - 2.0 * a);
                return r - d * 0.35;
            }

            void main() {
                vec2 c = gl_PointCoord - vec2(0.5);
                float dist = length(c);
                float alphaMul;

                if (fShape < 0.5) {
                    if (dist > 0.5) discard;
                    alphaMul = 1.0 - dist * 2.0;
                } else if (fShape < 1.5) {
                    float hd = heartDist(gl_PointCoord);
                    if (hd > 0.0) discard;
                    alphaMul = clamp(1.0 - hd * 3.0, 0.0, 1.0);
                } else if (fShape < 2.5) {
                    float angle = atan(c.y, c.x);
                    float r = 0.20 + 0.12 * cos(angle * 5.0);
                    if (dist > r) discard;
                    alphaMul = 1.0 - dist / max(r, 0.001) * 0.7;
                } else {
                    float d2 = abs(c.x) + abs(c.y);
                    if (d2 > 0.42) discard;
                    alphaMul = 1.0 - d2 / 0.42;
                }
                gl_FragColor = vec4(fColor.rgb, fColor.a * clamp(alphaMul, 0.0, 1.0));
            }
        """.trimIndent()

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)

            val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
            val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
            program = GLES20.glCreateProgram().apply {
                GLES20.glAttachShader(this, vertexShader)
                GLES20.glAttachShader(this, fragmentShader)
                GLES20.glLinkProgram(this)
            }
            GLES20.glUseProgram(program)
            positionHandle = GLES20.glGetAttribLocation(program, "vPosition")
            colorHandle = GLES20.glGetAttribLocation(program, "vColor")
            pointSizeHandle = GLES20.glGetAttribLocation(program, "vPointSize")
            shapeHandle = GLES20.glGetAttribLocation(program, "vShape")
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val now = System.currentTimeMillis()
            vertexBuffer.clear()
            var count = 0
            for (p in pool) {
                if (!p.active) continue
                val elapsed = (now - p.birthTime) / 1000f
                if (elapsed >= p.life) {
                    p.active = false
                    continue
                }
                computeParticleState(p, elapsed)
                vertexBuffer.put(p.curX)
                vertexBuffer.put(p.curY)
                vertexBuffer.put(p.curSize)
                vertexBuffer.put(p.r)
                vertexBuffer.put(p.g)
                vertexBuffer.put(p.b)
                vertexBuffer.put(p.curAlpha)
                vertexBuffer.put(p.shape)
                count++
            }
            if (count > 0) {
                vertexBuffer.position(0)
                GLES20.glUseProgram(program)
                vertexBuffer.position(0)
                GLES20.glEnableVertexAttribArray(positionHandle)
                GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, strideBytes, vertexBuffer)
                vertexBuffer.position(2)
                GLES20.glEnableVertexAttribArray(pointSizeHandle)
                GLES20.glVertexAttribPointer(pointSizeHandle, 1, GLES20.GL_FLOAT, false, strideBytes, vertexBuffer)
                vertexBuffer.position(3)
                GLES20.glEnableVertexAttribArray(colorHandle)
                GLES20.glVertexAttribPointer(colorHandle, 4, GLES20.GL_FLOAT, false, strideBytes, vertexBuffer)
                vertexBuffer.position(7)
                GLES20.glEnableVertexAttribArray(shapeHandle)
                GLES20.glVertexAttribPointer(shapeHandle, 1, GLES20.GL_FLOAT, false, strideBytes, vertexBuffer)
                GLES20.glDrawArrays(GLES20.GL_POINTS, 0, count)
                GLES20.glDisableVertexAttribArray(positionHandle)
                GLES20.glDisableVertexAttribArray(pointSizeHandle)
                GLES20.glDisableVertexAttribArray(colorHandle)
                GLES20.glDisableVertexAttribArray(shapeHandle)
                view.requestRender()
            }
        }

        fun trigger(effect: AnimationEffect) {
            val count = spawnCountFor(effect)
            repeat(count) {
                val idx = findFreeSlot() ?: return@repeat
                initParticle(pool[idx], effect)
            }
        }

        private fun findFreeSlot(): Int? {
            val start = cursor
            var idx = start
            do {
                if (!pool[idx].active) {
                    cursor = (idx + 1) % pool.size
                    return idx
                }
                idx = (idx + 1) % pool.size
            } while (idx != start)
            return null
        }

        private fun spawnCountFor(effect: AnimationEffect): Int = when (effect) {
            AnimationEffect.BURST -> 40
            AnimationEffect.HEARTS -> 25
            AnimationEffect.SNOW -> 60
            AnimationEffect.RAINBOW -> 45
            AnimationEffect.CONFETTI -> 55
            AnimationEffect.FIREWORKS -> 35
            AnimationEffect.SPARKLE -> 30
            AnimationEffect.BUBBLES -> 25
            AnimationEffect.GLITTER -> 45
            AnimationEffect.SPIRAL -> 40
            AnimationEffect.PETALS -> 35
            AnimationEffect.LEAVES -> 30
            AnimationEffect.STARDUST -> 50
            AnimationEffect.METEOR -> 15
        }

        private fun assignHue(p: Particle, h: Float) {
            p.r = (abs(h * 6f - 3f) - 1f).coerceIn(0f, 1f)
            p.g = (2f - abs(h * 6f - 2f)).coerceIn(0f, 1f)
            p.b = (2f - abs(h * 6f - 4f)).coerceIn(0f, 1f)
        }

        private fun fadeInOut(elapsed: Float, life: Float, fadeInDur: Float, fadeOutFrac: Float): Float {
            val fadeIn = (elapsed / fadeInDur).coerceIn(0f, 1f)
            val fadeOut = ((life - elapsed) / (life * fadeOutFrac)).coerceIn(0f, 1f)
            return min(fadeIn, fadeOut)
        }

        private fun initParticle(p: Particle, effect: AnimationEffect) {
            p.effect = effect
            p.active = true
            p.birthTime = System.currentTimeMillis()
            p.seed = Random.nextFloat()
            p.seed2 = Random.nextFloat() * 2f - 1f
            when (effect) {
                AnimationEffect.BURST -> {
                    p.x0 = 0f; p.y0 = 0f
                    val angle = Random.nextFloat() * 2f * PI.toFloat()
                    val speed = Random.nextFloat() * 1.2f + 0.6f
                    p.vx = cos(angle) * speed
                    p.vy = sin(angle) * speed
                    p.r = Random.nextFloat() * 0.5f + 0.5f
                    p.g = Random.nextFloat() * 0.5f + 0.5f
                    p.b = Random.nextFloat() * 0.5f + 0.5f
                    p.baseSize = Random.nextFloat() * 27f + 18f
                    p.life = Random.nextFloat() * 0.5f + 0.8f
                    p.shape = Shape.CIRCLE
                }
                AnimationEffect.HEARTS -> {
                    p.x0 = Random.nextFloat() * 0.6f - 0.3f
                    p.y0 = -0.9f
                    p.vx = Random.nextFloat() * 0.3f - 0.15f
                    p.vy = Random.nextFloat() * 0.4f + 0.5f
                    p.r = 1f; p.g = Random.nextFloat() * 0.35f; p.b = Random.nextFloat() * 0.25f + 0.35f
                    p.baseSize = Random.nextFloat() * 24f + 24f
                    p.life = Random.nextFloat() * 1f + 2f
                    p.shape = Shape.HEART
                }
                AnimationEffect.SNOW -> {
                    p.x0 = Random.nextFloat() * 2f - 1f
                    p.y0 = 1.1f
                    p.vx = Random.nextFloat() * 0.1f - 0.05f
                    p.vy = -(Random.nextFloat() * 0.2f + 0.15f)
                    p.r = 1f; p.g = 1f; p.b = 1f
                    p.baseSize = Random.nextFloat() * 10f + 6f
                    p.life = Random.nextFloat() * 1.5f + 3f
                    p.shape = Shape.CIRCLE
                }
                AnimationEffect.RAINBOW -> {
                    p.x0 = 0f; p.y0 = 0f
                    val angle = Random.nextFloat() * 2f * PI.toFloat()
                    val speed = Random.nextFloat() * 0.8f + 0.8f
                    p.vx = cos(angle) * speed
                    p.vy = sin(angle) * speed
                    assignHue(p, Random.nextFloat())
                    p.baseSize = Random.nextFloat() * 16f + 20f
                    p.life = Random.nextFloat() * 0.6f + 1f
                    p.shape = Shape.CIRCLE
                }
                AnimationEffect.CONFETTI -> {
                    p.x0 = Random.nextFloat() * 2f - 1f
                    p.y0 = 1.1f
                    p.vx = Random.nextFloat() * 0.6f - 0.3f
                    p.vy = -(Random.nextFloat() * 0.5f + 0.5f)
                    assignHue(p, Random.nextFloat())
                    p.baseSize = Random.nextFloat() * 10f + 10f
                    p.life = Random.nextFloat() * 1f + 1.8f
                    p.shape = Shape.DIAMOND
                }
                AnimationEffect.FIREWORKS -> {
                    p.x0 = Random.nextFloat() * 1.2f - 0.6f
                    p.y0 = Random.nextFloat() * 0.5f + 0.2f
                    val angle = Random.nextFloat() * 2f * PI.toFloat()
                    val speed = Random.nextFloat() * 0.8f + 0.7f
                    p.vx = cos(angle) * speed
                    p.vy = sin(angle) * speed
                    p.r = Random.nextFloat() * 0.4f + 0.6f; p.g = Random.nextFloat() * 0.4f + 0.6f; p.b = Random.nextFloat() * 0.4f + 0.6f
                    p.baseSize = Random.nextFloat() * 14f + 16f
                    p.life = Random.nextFloat() * 0.5f + 0.9f
                    p.shape = Shape.STAR
                }
                AnimationEffect.SPARKLE -> {
                    p.x0 = Random.nextFloat() * 1.6f - 0.8f; p.y0 = Random.nextFloat() * 1.6f - 0.8f
                    p.vx = 0f; p.vy = Random.nextFloat() * 0.06f + 0.02f
                    p.r = Random.nextFloat() * 0.3f + 0.7f; p.g = Random.nextFloat() * 0.3f + 0.7f; p.b = Random.nextFloat() * 0.3f + 0.7f
                    p.baseSize = Random.nextFloat() * 12f + 14f
                    p.life = Random.nextFloat() * 0.8f + 1f
                    p.shape = if (Random.nextBoolean()) Shape.STAR else Shape.DIAMOND
                }
                AnimationEffect.BUBBLES -> {
                    p.x0 = Random.nextFloat() * 1.8f - 0.9f; p.y0 = -1.1f
                    p.vx = Random.nextFloat() * 0.1f - 0.05f; p.vy = Random.nextFloat() * 0.2f + 0.15f
                    p.r = Random.nextFloat() * 0.3f + 0.4f; p.g = Random.nextFloat() * 0.3f + 0.7f; p.b = 1f
                    p.baseSize = Random.nextFloat() * 20f + 14f
                    p.life = Random.nextFloat() * 1.5f + 2.5f
                    p.shape = Shape.CIRCLE
                }
                AnimationEffect.GLITTER -> {
                    p.x0 = 0f; p.y0 = 0f
                    val angle = Random.nextFloat() * 2f * PI.toFloat()
                    val speed = Random.nextFloat() * 0.6f + 0.4f
                    p.vx = cos(angle) * speed; p.vy = sin(angle) * speed
                    if (Random.nextBoolean()) { p.r = 1f; p.g = 0.84f; p.b = 0.2f } else { p.r = 0.85f; p.g = 0.9f; p.b = 0.95f }
                    p.baseSize = Random.nextFloat() * 10f + 8f
                    p.life = Random.nextFloat() * 0.5f + 0.7f
                    p.shape = Shape.DIAMOND
                }
                AnimationEffect.SPIRAL -> {
                    p.x0 = 0f; p.y0 = 0f
                    p.vx = Random.nextFloat() * 0.4f + 0.4f
                    assignHue(p, Random.nextFloat())
                    p.baseSize = Random.nextFloat() * 12f + 14f
                    p.life = Random.nextFloat() * 0.6f + 1.4f
                    p.shape = Shape.CIRCLE
                }
                AnimationEffect.PETALS -> {
                    p.x0 = Random.nextFloat() * 2f - 1f; p.y0 = 1.1f
                    p.vx = Random.nextFloat() * 0.3f - 0.15f; p.vy = -(Random.nextFloat() * 0.2f + 0.2f)
                    p.r = Random.nextFloat() * 0.3f + 0.7f; p.g = Random.nextFloat() * 0.3f + 0.3f; p.b = Random.nextFloat() * 0.3f + 0.6f
                    p.baseSize = Random.nextFloat() * 10f + 10f
                    p.life = Random.nextFloat() * 1f + 2.5f
                    p.shape = Shape.HEART
                }
                AnimationEffect.LEAVES -> {
                    p.x0 = Random.nextFloat() * 2f - 1f; p.y0 = 1.1f
                    p.vx = Random.nextFloat() * 0.4f - 0.2f; p.vy = -(Random.nextFloat() * 0.3f + 0.2f)
                    p.r = Random.nextFloat() * 0.3f + 0.1f; p.g = Random.nextFloat() * 0.4f + 0.4f; p.b = Random.nextFloat() * 0.2f
                    p.baseSize = Random.nextFloat() * 12f + 12f
                    p.life = Random.nextFloat() * 1.5f + 3f
                    p.shape = Shape.DIAMOND
                }
                AnimationEffect.STARDUST -> {
                    p.x0 = Random.nextFloat() * 2f - 1f; p.y0 = Random.nextFloat() * 2f - 1f
                    p.vx = Random.nextFloat() * 0.1f - 0.05f; p.vy = Random.nextFloat() * 0.1f - 0.05f
                    p.r = 1f; p.g = 1f; p.b = 0.5f
                    p.baseSize = Random.nextFloat() * 8f + 4f
                    p.life = Random.nextFloat() * 1f + 1f
                    p.shape = Shape.STAR
                }
                AnimationEffect.METEOR -> {
                    p.x0 = Random.nextFloat() * 1.5f + 0.5f; p.y0 = 1.1f
                    p.vx = -1.2f - Random.nextFloat() * 0.8f; p.vy = -0.8f - Random.nextFloat() * 0.6f
                    p.r = 0.8f; p.g = 0.9f; p.b = 1f
                    p.baseSize = Random.nextFloat() * 15f + 15f
                    p.life = 0.8f + Random.nextFloat() * 0.4f
                    p.shape = Shape.STAR
                }
            }
        }

        private fun computeParticleState(p: Particle, elapsed: Float) {
            val t = (elapsed / p.life).coerceIn(0f, 1f)
            when (p.effect) {
                AnimationEffect.BURST -> {
                    val damp = (1f - 0.5f * elapsed).coerceAtLeast(0f)
                    p.curX = p.x0 + p.vx * elapsed * damp
                    p.curY = p.y0 + p.vy * elapsed * damp - 0.4f * elapsed * elapsed
                    p.curAlpha = 1f - t
                    p.curSize = p.baseSize * (1f - 0.2f * t)
                }
                AnimationEffect.HEARTS -> {
                    val wobble = sin(elapsed * 3f + p.seed * 6.283f) * 0.08f
                    p.curX = p.x0 + p.vx * elapsed + wobble
                    p.curY = p.y0 + p.vy * elapsed
                    p.curAlpha = fadeInOut(elapsed, p.life, 0.15f, 0.3f)
                    p.curSize = p.baseSize
                }
                AnimationEffect.SNOW -> {
                    val drift = sin(elapsed * 2f + p.seed * 6.283f) * 0.06f
                    p.curX = p.x0 + p.vx * elapsed + drift
                    p.curY = p.y0 + p.vy * elapsed
                    p.curAlpha = fadeInOut(elapsed, p.life, 0.2f, 0.2f)
                    p.curSize = p.baseSize
                }
                AnimationEffect.RAINBOW -> {
                    p.curX = p.x0 + p.vx * elapsed
                    p.curY = p.y0 + p.vy * elapsed - 0.15f * elapsed * elapsed
                    p.curAlpha = 1f - t
                    p.curSize = p.baseSize * (1f - 0.15f * t)
                }
                AnimationEffect.CONFETTI -> {
                    val freq = 6f + p.seed * 4f
                    val flutter = sin(elapsed * freq + p.seed2 * 6.283f) * 0.15f
                    p.curX = p.x0 + p.vx * elapsed + flutter
                    p.curY = p.y0 + p.vy * elapsed - 0.05f * elapsed * elapsed
                    p.curAlpha = fadeInOut(elapsed, p.life, 0.1f, 0.3f)
                    p.curSize = p.baseSize * (0.7f + 0.3f * abs(sin(elapsed * freq)))
                }
                AnimationEffect.FIREWORKS -> {
                    val damp = (1f - 0.6f * elapsed).coerceAtLeast(0f)
                    p.curX = p.x0 + p.vx * elapsed * damp
                    p.curY = p.y0 + p.vy * elapsed * damp - 0.3f * elapsed * elapsed
                    p.curAlpha = (1f - t) * (1f - t)
                    p.curSize = p.baseSize * (1f - 0.3f * t)
                }
                AnimationEffect.SPARKLE -> {
                    val twinkle = 0.5f + 0.5f * sin(elapsed * (8f + p.seed * 6f) + p.seed * 6.283f)
                    p.curX = p.x0 + sin(elapsed * 1.5f + p.seed * 6.283f) * 0.03f
                    p.curY = p.y0 + p.vy * elapsed
                    p.curAlpha = twinkle * (1f - t)
                    p.curSize = p.baseSize * (0.6f + 0.4f * twinkle)
                }
                AnimationEffect.BUBBLES -> {
                    val sway = sin(elapsed * 2f + p.seed * 6.283f) * 0.08f
                    p.curX = p.x0 + p.vx * elapsed + sway
                    p.curY = p.y0 + p.vy * elapsed
                    p.curAlpha = fadeInOut(elapsed, p.life, 0.2f, 0.25f) * 0.7f
                    p.curSize = p.baseSize
                }
                AnimationEffect.GLITTER -> {
                    p.curX = p.x0 + p.vx * elapsed
                    p.curY = p.y0 + p.vy * elapsed - 0.1f * elapsed * elapsed
                    val twinkle = 0.4f + 0.6f * abs(sin(elapsed * 12f + p.seed * 6.283f))
                    p.curAlpha = twinkle * (1f - t)
                    p.curSize = p.baseSize * twinkle
                }
                AnimationEffect.SPIRAL -> {
                    val angle = p.seed * 2f * PI.toFloat() + p.seed2 * 8f * elapsed
                    val radius = p.vx * elapsed
                    p.curX = radius * cos(angle)
                    p.curY = radius * sin(angle)
                    p.curAlpha = 1f - t
                    p.curSize = p.baseSize * (1f - 0.2f * t)
                }
                AnimationEffect.PETALS -> {
                    val sway = sin(elapsed * (2f + p.seed * 2f) + p.seed2 * 6.283f) * 0.12f
                    p.curX = p.x0 + p.vx * elapsed + sway
                    p.curY = p.y0 + p.vy * elapsed
                    p.curAlpha = fadeInOut(elapsed, p.life, 0.15f, 0.3f)
                    p.curSize = p.baseSize
                }
                AnimationEffect.LEAVES -> {
                    val sway = sin(elapsed * 1.5f + p.seed * 6.283f) * 0.2f
                    p.curX = p.x0 + p.vx * elapsed + sway
                    p.curY = p.y0 + p.vy * elapsed
                    p.curAlpha = fadeInOut(elapsed, p.life, 0.2f, 0.3f)
                    p.curSize = p.baseSize * (0.8f + 0.4f * abs(sin(elapsed * 2f)))
                }
                AnimationEffect.STARDUST -> {
                    val twinkle = 0.5f + 0.5f * sin(elapsed * 10f + p.seed * 100f)
                    p.curX = p.x0 + p.vx * elapsed
                    p.curY = p.y0 + p.vy * elapsed
                    p.curAlpha = twinkle * fadeInOut(elapsed, p.life, 0.1f, 0.1f)
                    p.curSize = p.baseSize * twinkle
                }
                AnimationEffect.METEOR -> {
                    p.curX = p.x0 + p.vx * elapsed
                    p.curY = p.y0 + p.vy * elapsed
                    p.curAlpha = fadeInOut(elapsed, p.life, 0.05f, 0.5f)
                    p.curSize = p.baseSize * (1f + elapsed * 2f)
                }
            }
        }

        private fun loadShader(type: Int, shaderCode: String): Int {
            return GLES20.glCreateShader(type).also { shader ->
                GLES20.glShaderSource(shader, shaderCode)
                GLES20.glCompileShader(shader)
            }
        }
    }
}
