package dev.jarvis.companion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.sin

/**
 * The JARVIS core display.
 *
 * It is drawn as an arc reactor: three rotating rings around a glowing core,
 * with a live audio ribbon across the middle. The ribbon is fed by the real
 * microphone level while listening, and by a synthetic envelope while the
 * assistant is speaking, so the display always reflects the current state.
 */
class VoiceWaveformView(context: Context) : View(context) {
    enum class State { IDLE, LISTENING, THINKING, SPEAKING, ERROR, OFFLINE }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)

    private var phase = 0f
    private var state = State.IDLE
    private var speakingEnvelope = 0f

    private val levels = FloatArray(WAVE_POINTS)
    private var writeIndex = 0

    private val reducedMotion = try {
        android.provider.Settings.Global.getFloat(
            context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    } catch (_: Exception) {
        false
    }

    /** Current microphone level, 0 to 1. */
    var level: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            pushLevel(field)
            invalidate()
        }

    fun setState(next: State) {
        if (state != next) {
            state = next
            if (next != State.LISTENING && next != State.SPEAKING) {
                levels.fill(0f)
            }
            invalidate()
        }
    }

    /** Adds one sample to the rolling ribbon. */
    private fun pushLevel(value: Float) {
        levels[writeIndex] = value
        writeIndex = (writeIndex + 1) % WAVE_POINTS
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val base = minOf(width, height) * 0.30f
        val moving = state != State.ERROR && state != State.OFFLINE

        if (!reducedMotion && moving) {
            phase += when (state) {
                State.IDLE -> 0.006f
                State.THINKING -> 0.030f
                State.SPEAKING -> 0.024f
                else -> 0.014f
            }
            if (state == State.SPEAKING) {
                // A soft envelope so the rings breathe while the voice plays.
                speakingEnvelope = 0.5f + 0.5f * sin(phase * 3.1f)
            } else if (state == State.LISTENING) {
                speakingEnvelope = 0f
            }
        }

        val tint = when (state) {
            State.ERROR, State.OFFLINE -> Color.rgb(120, 142, 158)
            State.LISTENING -> Color.rgb(79, 216, 255)
            State.SPEAKING -> Color.rgb(89, 227, 160)
            State.THINKING -> Color.rgb(122, 162, 255)
            else -> Color.rgb(52, 120, 190)
        }
        val red = Color.red(tint)
        val green = Color.green(tint)
        val blue = Color.blue(tint)

        // Ambient glow behind the core.
        glow.shader = RadialGradient(
            cx,
            cy,
            base * 1.9f,
            intArrayOf(
                Color.argb(56, red, green, blue),
                Color.argb(16, red, green, blue),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, base * 1.9f, glow)
        glow.shader = null

        // Three rotating arc rings.
        drawArcRing(canvas, cx, cy, base * 1.34f, 0.55f, phase, red, green, blue, 210, 2.2f)
        drawArcRing(canvas, cx, cy, base * 1.14f, 0.40f, -phase * 1.7f, red, green, blue, 150, 3.0f)
        drawArcRing(canvas, cx, cy, base * 0.96f, 0.70f, phase * 0.6f + 1.2f, red, green, blue, 110, 1.6f)

        // Soft outer halo.
        fill.shader = null
        fill.style = Paint.Style.FILL
        fill.color = Color.argb(22, red, green, blue)
        canvas.drawCircle(cx, cy, base * 1.2f, fill)

        // The core.
        val coreRadius = base * (0.40f + if (state == State.LISTENING) level * 0.10f else 0f)
        glow.shader = RadialGradient(
            cx,
            cy,
            coreRadius * 1.9f,
            intArrayOf(
                Color.argb(190, red, green, blue),
                Color.argb(70, red, green, blue),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, coreRadius * 1.9f, glow)
        glow.shader = null
        fill.color = Color.argb(215, red, green, blue)
        canvas.drawCircle(cx, cy, coreRadius, fill)
        fill.color = Color.argb(150, 240, 250, 255)
        canvas.drawCircle(cx - coreRadius * 0.26f, cy - coreRadius * 0.3f, coreRadius * 0.30f, fill)

        // The live audio ribbon across the middle.
        drawWaveRibbon(canvas, cx, cy, base, red, green, blue)

        if (!reducedMotion && moving) {
            postInvalidateDelayed(if (state == State.IDLE) 110 else 40)
        }
    }

    /** Draws one rotating dashed arc ring. */
    private fun drawArcRing(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        sweepFraction: Float,
        rotation: Float,
        red: Int,
        green: Int,
        blue: Int,
        alpha: Int,
        width: Float,
    ) {
        val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        stroke.strokeWidth = width
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.shader = LinearGradient(
            cx - radius,
            cy - radius,
            cx + radius,
            cy + radius,
            intArrayOf(
                Color.argb(0, red, green, blue),
                Color.argb(alpha, red, green, blue),
                Color.argb(alpha / 3, red, green, blue),
                Color.argb(0, red, green, blue),
            ),
            floatArrayOf(0f, 0.35f, 0.7f, 1f),
            Shader.TileMode.CLAMP,
        )
        val sweep = 360f * sweepFraction
        canvas.drawArc(rect, rotation * 57.2958f, sweep, false, stroke)
        canvas.drawArc(rect, rotation * 57.2958f + 180f, sweep, false, stroke)
        stroke.shader = null
    }

    /** Draws the rolling audio ribbon from the sampled levels. */
    private fun drawWaveRibbon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        base: Float,
        red: Int,
        green: Int,
        blue: Int,
    ) {
        val ribbonWidth = minOf(this.width * 0.92f, base * 6.4f)
        val ribbonHeight = base * 0.92f
        val lower = Path()
        val upper = Path()
        val count = WAVE_POINTS
        for (index in 0 until count) {
            val sample = levels[(writeIndex + index) % count]
            val normalised = when (state) {
                State.LISTENING -> sample
                State.SPEAKING -> {
                    val wobble = 0.45f + 0.55f * sin(phase * 6f + index * 0.42f)
                    (0.35f + speakingEnvelope * 0.65f) * wobble
                }
                else -> 0f
            }
            val x = cx - ribbonWidth / 2f + ribbonWidth * index / (count - 1)
            val offset = normalised * ribbonHeight * 0.5f
            if (index == 0) {
                lower.moveTo(x, cy + offset)
                upper.moveTo(x, cy - offset)
            } else {
                lower.lineTo(x, cy + offset)
                upper.lineTo(x, cy - offset)
            }
        }
        val ribbon = Path()
        ribbon.addPath(upper)
        ribbon.lineTo(cx + ribbonWidth / 2f, cy)
        ribbon.lineTo(cx - ribbonWidth / 2f, cy)
        ribbon.close()

        fill.style = Paint.Style.FILL
        fill.shader = LinearGradient(
            cx - ribbonWidth / 2f,
            0f,
            cx + ribbonWidth / 2f,
            0f,
            intArrayOf(
                Color.argb(0, red, green, blue),
                Color.argb(120, red, green, blue),
                Color.argb(30, red, green, blue),
                Color.argb(120, red, green, blue),
                Color.argb(0, red, green, blue),
            ),
            floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(ribbon, fill)
        fill.shader = null

        stroke.strokeWidth = 1.8f
        stroke.color = Color.argb(200, red, green, blue)
        stroke.shader = null
        canvas.drawPath(lower, stroke)
    }

    companion object {
        private const val WAVE_POINTS = 96
    }
}
