package com.kuman.mini

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin

/**
 * KUMAN's face: a canvas-drawn robot character with antenna, ears,
 * gradient body and selectable eye styles. The pupils follow the
 * front-camera face tracking target.
 */
class FaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Mood { IDLE, THINKING, SPEAKING, LISTENING, ERROR }

    enum class EyeStyle(val label: String) {
        ROUND("동공 눈"),
        DOT("점안이"),
        VISOR("사이버 바이저"),
        CAT("고양이 눈"),
        ANIME("애니메이션 눈")
    }

    var mood: Mood = Mood.IDLE
        set(value) {
            field = value
            invalidate()
        }

    var eyeStyle: EyeStyle = EyeStyle.ROUND
        set(value) {
            field = value
            invalidate()
        }

    /** Fill the whole screen with the character (landscape mode). */
    var fullscreen = false
        set(value) {
            field = value
            invalidate()
        }

    /** Body accent color; outline/cheeks/antenna tip derive from it. */
    var accentColor: Int = Color.parseColor("#FFB74D")
        set(value) {
            field = value
            facePaint.color = value
            outlinePaint.color = shade(value, 0.72f)
            cheekPaint.color = withAlpha(value, 90)
            invalidate()
        }

    /**
     * Where the eyes should look, normalized -1..1 (x: right, y: down).
     */
    var lookTargetX = 0f
    var lookTargetY = 0f
    private var lookX = 0f
    private var lookY = 0f

    // ------------------------------------------------------------------
    // Paints
    // ------------------------------------------------------------------

    private fun shade(color: Int, factor: Float): Int = Color.rgb(
        (Color.red(color) * factor).toInt().coerceIn(0, 255),
        (Color.green(color) * factor).toInt().coerceIn(0, 255),
        (Color.blue(color) * factor).toInt().coerceIn(0, 255)
    )

    private fun lighten(color: Int, factor: Float): Int = Color.rgb(
        (Color.red(color) + (255 - Color.red(color)) * factor).toInt().coerceIn(0, 255),
        (Color.green(color) + (255 - Color.green(color)) * factor).toInt().coerceIn(0, 255),
        (Color.blue(color) + (255 - Color.blue(color)) * factor).toInt().coerceIn(0, 255)
    )

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = shade(accentColor, 0.72f)
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val earPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val antennaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = shade(accentColor, 0.6f)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val tipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val tipGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val pupilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val slitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val eyeOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#212121")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val visorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#10202B")
        style = Paint.Style.FILL
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val mouthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4E342E")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val cheekPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = withAlpha(accentColor, 90)
        style = Paint.Style.FILL
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val lashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#212121")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val lowerLashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#212121")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        alpha = 140
    }
    private val irisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val animeClipPath = Path()

    private var blinkStart = -1L
    private var nextBlink = SystemClock.uptimeMillis() + 2500
    private val faceRect = RectF()
    private val visorRect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val r = minOf(w, h) / 2f * (if (fullscreen) 0.90f else 0.82f)

        // --------------------------------------------------------------
        // Ground shadow
        // --------------------------------------------------------------
        shadowPaint.shader = RadialGradient(
            cx, cy + r * 1.02f, r * 0.9f,
            intArrayOf(Color.argb(70, 0, 0, 0), Color.TRANSPARENT),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawOval(
            RectF(cx - r * 0.9f, cy + r * 0.75f, cx + r * 0.9f, cy + r * 1.15f),
            shadowPaint
        )

        // --------------------------------------------------------------
        // Ears
        // --------------------------------------------------------------
        val earR = r * 0.11f
        val earY = cy - r * 0.05f
        earPaint.color = shade(accentColor, 0.85f)
        canvas.drawCircle(cx - r * 0.92f, earY, earR, earPaint)
        canvas.drawCircle(cx + r * 0.92f, earY, earR, earPaint)
        earPaint.color = shade(accentColor, 0.6f)
        canvas.drawCircle(cx - r * 0.92f, earY, earR * 0.45f, earPaint)
        canvas.drawCircle(cx + r * 0.92f, earY, earR * 0.45f, earPaint)

        // --------------------------------------------------------------
        // Antenna with glowing tip (tip pulses while speaking/thinking)
        // --------------------------------------------------------------
        val antBaseY = cy - r * 0.78f
        val antTipY = maxOf(
            antBaseY - r * (if (fullscreen) 0.30f else 0.26f),
            h * 0.03f
        )
        antennaPaint.strokeWidth = r * 0.035f
        canvas.drawLine(cx, antBaseY, cx, antTipY + r * 0.08f, antennaPaint)
        val tipR = r * 0.075f
        val nowMs = SystemClock.uptimeMillis()
        val glowPulse = if (mood == Mood.SPEAKING || mood == Mood.THINKING) {
            0.55f + 0.35f * sin(nowMs / 140.0).toFloat()
        } else 0.35f
        tipPaint.color = lighten(accentColor, 0.35f)
        tipGlowPaint.color = withAlpha(lighten(accentColor, 0.35f), (glowPulse * 255).toInt())
        tipGlowPaint.shader = RadialGradient(
            cx, antTipY, tipR * 2.6f,
            intArrayOf(withAlpha(lighten(accentColor, 0.35f), (glowPulse * 255).toInt()), Color.TRANSPARENT),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, antTipY, tipR * 2.6f, tipGlowPaint)
        canvas.drawCircle(cx, antTipY, tipR, tipPaint)
        tipGlowPaint.shader = null

        // --------------------------------------------------------------
        // Body: rounded rect with vertical gradient
        // --------------------------------------------------------------
        val inset = r * 0.10f
        faceRect.set(cx - r + inset, cy - r + inset * 0.6f, cx + r - inset, cy + r - inset)
        facePaint.shader = LinearGradient(
            0f, faceRect.top, 0f, faceRect.bottom,
            intArrayOf(lighten(accentColor, 0.28f), accentColor, shade(accentColor, 0.88f)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(faceRect, r * 0.32f, r * 0.32f, facePaint)
        facePaint.shader = null
        canvas.drawRoundRect(faceRect, r * 0.32f, r * 0.32f, outlinePaint)

        // Top highlight
        val hlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, faceRect.top, 0f, faceRect.top + r * 0.5f,
                intArrayOf(Color.argb(70, 255, 255, 255), Color.TRANSPARENT),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(faceRect, r * 0.32f, r * 0.32f, hlPaint)

        // --------------------------------------------------------------
        // Cheeks
        // --------------------------------------------------------------
        canvas.drawCircle(cx - r * 0.5f, cy + r * 0.2f, r * 0.1f, cheekPaint)
        canvas.drawCircle(cx + r * 0.5f, cy + r * 0.2f, r * 0.1f, cheekPaint)

        // --------------------------------------------------------------
        // Eyes
        // --------------------------------------------------------------
        lookX += (lookTargetX - lookX) * 0.18f
        lookY += (lookTargetY - lookY) * 0.18f

        // Blink scheduling
        val now = SystemClock.uptimeMillis()
        if (now >= nextBlink) {
            blinkStart = now
            nextBlink = now + 1800 + (Math.random() * 2800).toLong()
        }
        val blinkPhase = if (blinkStart >= 0) (now - blinkStart) / 150f else -1f
        val eyeScaleY = when {
            blinkPhase < 0 || blinkPhase > 1f -> {
                if (blinkPhase > 1f) blinkStart = -1L
                1f
            }
            else -> kotlin.math.abs(kotlin.math.cos(blinkPhase * Math.PI.toFloat()))
        }.coerceAtLeast(0.08f)

        val eyeY = cy - r * 0.08f
        val eyeDX = r * 0.32f
        val eyeRX = r * 0.105f
        val eyeRY = r * 0.15f * eyeScaleY
        val pupilR = eyeRX * 0.55f
        val maxOX = eyeRX * 0.4f
        val maxOY = eyeRY * 0.4f
        val glowDot = lighten(accentColor, 0.55f)

        when (eyeStyle) {
            EyeStyle.ROUND -> {
                for (side in intArrayOf(-1, 1)) {
                    val ex = cx + side * eyeDX
                    canvas.drawOval(RectF(ex - eyeRX, eyeY - eyeRY, ex + eyeRX, eyeY + eyeRY), eyePaint)
                    canvas.drawOval(RectF(ex - eyeRX, eyeY - eyeRY, ex + eyeRX, eyeY + eyeRY), eyeOutlinePaint)
                    canvas.drawCircle(ex + lookX * maxOX, eyeY + lookY * maxOY, pupilR * eyeScaleY, pupilPaint)
                }
            }
            EyeStyle.DOT -> {
                pupilPaint.color = Color.BLACK
                for (side in intArrayOf(-1, 1)) {
                    val ex = cx + side * eyeDX
                    canvas.drawOval(
                        RectF(ex - eyeRX * 0.7f, eyeY - eyeRY * 1.2f, ex + eyeRX * 0.7f, eyeY + eyeRY * 1.2f),
                        pupilPaint
                    )
                }
            }
            EyeStyle.VISOR -> {
                // Dark visor band + two glowing eyes that track
                visorRect.set(
                    cx - eyeDX - eyeRX * 1.5f, eyeY - eyeRY * 1.5f,
                    cx + eyeDX + eyeRX * 1.5f, eyeY + eyeRY * 1.4f
                )
                canvas.drawRoundRect(visorRect, eyeRY * 1.4f, eyeRY * 1.4f, visorPaint)
                glowPaint.color = withAlpha(glowDot, 70)
                for (side in intArrayOf(-1, 1)) {
                    val ex = cx + side * eyeDX + lookX * maxOX
                    val ey = eyeY + lookY * maxOY
                    canvas.drawCircle(ex, ey, pupilR * 1.9f, glowPaint)
                    glowPaint.color = glowDot
                    canvas.drawCircle(ex, ey, pupilR, glowPaint)
                    glowPaint.color = withAlpha(glowDot, 70)
                }
            }
            EyeStyle.CAT -> {
                for (side in intArrayOf(-1, 1)) {
                    val ex = cx + side * eyeDX
                    // white sclera
                    canvas.drawOval(RectF(ex - eyeRX, eyeY - eyeRY, ex + eyeRX, eyeY + eyeRY), eyePaint)
                    canvas.drawOval(RectF(ex - eyeRX, eyeY - eyeRY, ex + eyeRX, eyeY + eyeRY), eyeOutlinePaint)
                    // vertical slit pupil tracking the target
                    slitPaint.color = Color.BLACK
                    val slitH = eyeRY * 1.9f * eyeScaleY
                    val slitW = eyeRX * 0.34f
                    canvas.drawOval(
                        RectF(
                            ex + lookX * maxOX - slitW,
                            eyeY + lookY * maxOY * 0.6f - slitH / 2f,
                            ex + lookX * maxOX + slitW,
                            eyeY + lookY * maxOY * 0.6f + slitH / 2f
                        ),
                        slitPaint
                    )
                }
            }
            EyeStyle.ANIME -> drawAnimeEyes(
                canvas, cx, eyeY, eyeDX, eyeRX, eyeRY, eyeScaleY,
                lookX * maxOX, lookY * maxOY
            )
        }

        // --------------------------------------------------------------
        // Mouth per mood
        // --------------------------------------------------------------
        val mouthY = cy + r * 0.26f
        mouthPaint.strokeWidth = r * 0.065f
        when (mood) {
            Mood.IDLE -> {
                mouthPaint.style = Paint.Style.STROKE
                canvas.drawLine(cx - r * 0.2f, mouthY - r * 0.05f, cx + r * 0.2f, mouthY - r * 0.05f, mouthPaint)
                canvas.drawLine(cx - r * 0.2f, mouthY - r * 0.05f, cx - r * 0.12f, mouthY + r * 0.06f, mouthPaint)
                canvas.drawLine(cx + r * 0.2f, mouthY - r * 0.05f, cx + r * 0.12f, mouthY + r * 0.06f, mouthPaint)
            }
            Mood.THINKING -> {
                mouthPaint.style = Paint.Style.STROKE
                val t = (nowMs % 1200) / 1200f
                val dx = sin(t * Math.PI.toFloat() * 2) * r * 0.08f
                canvas.drawLine(cx - r * 0.2f + dx, mouthY, cx + r * 0.2f + dx, mouthY, mouthPaint)
            }
            Mood.LISTENING -> {
                mouthPaint.style = Paint.Style.FILL
                val t = (nowMs % 600) / 600f
                val open = r * (0.10f + 0.05f * sin(t * Math.PI.toFloat() * 2))
                canvas.drawOval(
                    RectF(cx - r * 0.12f, mouthY - open, cx + r * 0.12f, mouthY + open),
                    mouthPaint
                )
            }
            Mood.SPEAKING -> {
                val t = (nowMs % 400) / 400f
                val open = (sin(t * Math.PI.toFloat() * 2) * 0.5f + 0.5f) * r * 0.18f + r * 0.03f
                mouthPaint.style = Paint.Style.FILL
                canvas.drawOval(
                    RectF(cx - r * 0.22f, mouthY - open, cx + r * 0.22f, mouthY + open),
                    mouthPaint
                )
            }
            Mood.ERROR -> {
                mouthPaint.style = Paint.Style.STROKE
                canvas.drawLine(cx - r * 0.18f, mouthY - r * 0.08f, cx + r * 0.18f, mouthY + r * 0.08f, mouthPaint)
                canvas.drawLine(cx - r * 0.18f, mouthY + r * 0.08f, cx + r * 0.18f, mouthY - r * 0.08f, mouthPaint)
            }
        }

        val looking = kotlin.math.abs(lookTargetX - lookX) > 0.01f ||
            kotlin.math.abs(lookTargetY - lookY) > 0.01f
        if (mood == Mood.SPEAKING || mood == Mood.THINKING || mood == Mood.LISTENING ||
            eyeScaleY < 1f || looking
        ) {
            postInvalidateOnAnimation()
        }
    }

    /**
     * Anime-style eyes: tall sclera, radial-gradient iris in the accent
     * color, black pupil that tracks the look target, dual highlights and
     * thick upper lash line.
     */
    private fun drawAnimeEyes(
        canvas: Canvas,
        cx: Float,
        eyeY: Float,
        eyeDX: Float,
        eyeRX: Float,
        eyeRY: Float,
        eyeScaleY: Float,
        offX: Float,
        offY: Float
    ) {
        val animeRY = eyeRY * 1.45f
        val pupilCX = offX
        val pupilCY = offY

        lashPaint.strokeWidth = (eyeRX * 0.7f).coerceAtLeast(5f)
        lowerLashPaint.strokeWidth = lashPaint.strokeWidth * 0.35f

        for (side in intArrayOf(-1, 1)) {
            val ex = cx + side * eyeDX
            val pcx = ex + pupilCX
            val pcy = eyeY + pupilCY

            val bounds = RectF(
                ex - eyeRX * 1.05f, eyeY - animeRY,
                ex + eyeRX * 1.05f, eyeY + animeRY
            )

            // Sclera
            canvas.drawOval(bounds, eyePaint)
            canvas.drawOval(bounds, eyeOutlinePaint)

            // Iris + pupil + highlights, clipped to the eye shape
            canvas.save()
            animeClipPath.reset()
            animeClipPath.addOval(bounds, Path.Direction.CW)
            canvas.clipPath(animeClipPath)

            // Iris: radial gradient centered near the pupil
            irisPaint.shader = RadialGradient(
                pcx, pcy + animeRY * 0.12f, eyeRX * 1.2f,
                intArrayOf(
                    lighten(accentColor, 0.55f),
                    accentColor,
                    shade(accentColor, 0.45f)
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(pcx, pcy + animeRY * 0.1f, eyeRX * 1.15f, irisPaint)
            irisPaint.shader = null

            // Pupil (vertical oval)
            pupilPaint.color = Color.BLACK
            canvas.drawOval(
                RectF(
                    pcx - eyeRX * 0.42f, pcy - animeRY * 0.95f,
                    pcx + eyeRX * 0.42f, pcy + animeRY * 0.95f
                ),
                pupilPaint
            )

            // Big top highlight + small bottom highlight
            highlightPaint.color = Color.argb(235, 255, 255, 255)
            canvas.drawOval(
                RectF(
                    ex - eyeRX * 0.75f + pupilCX * 0.4f, eyeY - animeRY * 0.95f,
                    ex - eyeRX * 0.15f + pupilCX * 0.4f, eyeY - animeRY * 0.35f
                ),
                highlightPaint
            )
            highlightPaint.color = Color.argb(170, 255, 255, 255)
            canvas.drawCircle(
                ex + eyeRX * 0.45f + pupilCX * 0.4f,
                eyeY + animeRY * 0.55f,
                eyeRX * 0.16f,
                highlightPaint
            )
            canvas.restore()

            // Upper lash (thick ∩ arc) + faint lower lash
            canvas.drawArc(bounds, 180f, 180f, false, lashPaint)
            canvas.drawArc(bounds, 0f, 180f, false, lowerLashPaint)
        }
    }
}
