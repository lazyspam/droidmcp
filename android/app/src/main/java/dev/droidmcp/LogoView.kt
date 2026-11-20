package dev.droidmcp

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/**
 * The DroidMCP droid, drawn live so it can react: eyes glance around while the AI works, blink now and
 * then when idle, and become ✕ ✕ on an error. The antenna tip shows the status colour.
 * Geometry matches res/drawable/ic_logo_mark.xml (108-unit canvas).
 */
class LogoView(ctx: Context) : View(ctx) {
    enum class Mood { IDLE, BUSY, OK, ERROR }

    var mood = Mood.IDLE
        set(v) { field = v; onMood(); invalidate() }
    var tipColor = Color.parseColor("#34C759")
        set(v) { field = v; invalidate() }

    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val eye = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0F1115") }
    private val stem = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }
    private val tip = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0F1115"); strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }
    private val r = RectF()

    private var look = 0f      // -1 … 1, eye offset sideways
    private var blink = 1f     // 1 open … 0.1 closed
    private var lookAnim: ValueAnimator? = null
    private val main = Handler(Looper.getMainLooper())
    private val blinker = object : Runnable {
        override fun run() {
            if (mood == Mood.IDLE || mood == Mood.OK) doBlink()
            main.postDelayed(this, 2600L + (Math.random() * 2600).toLong())
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); main.postDelayed(blinker, 1800) }
    override fun onDetachedFromWindow() { main.removeCallbacks(blinker); lookAnim?.cancel(); super.onDetachedFromWindow() }

    private fun doBlink() {
        ValueAnimator.ofFloat(1f, 0.1f, 1f).apply {
            duration = 220
            addUpdateListener { blink = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun onMood() {
        lookAnim?.cancel()
        if (mood == Mood.BUSY) {
            // Scanning glance: left, right, left… like reading the screen.
            lookAnim = ValueAnimator.ofFloat(-1f, 1f).apply {
                duration = 700; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { look = it.animatedValue as Float; invalidate() }
                start()
            }
        } else {
            look = 0f
        }
    }

    override fun onDraw(c: Canvas) {
        val s = minOf(width, height) / 108f
        c.save()
        c.translate((width - 108 * s) / 2, (height - 108 * s) / 2)
        c.scale(s, s)
        // Antenna.
        stem.strokeWidth = 4f
        c.drawLine(54f, 31f, 54f, 39f, stem)
        tip.color = tipColor
        c.drawCircle(54f, 24.5f, 4.5f, tip)
        // Head.
        r.set(30f, 39f, 78f, 77f)
        c.drawRoundRect(r, 14f, 14f, white)
        // Eyes.
        val dx = look * 3.5f
        if (mood == Mood.ERROR) {
            cross.strokeWidth = 3.2f
            for (cx in floatArrayOf(44f, 64f)) {
                c.drawLine(cx - 4, 52f, cx + 4, 60f, cross)
                c.drawLine(cx + 4, 52f, cx - 4, 60f, cross)
            }
        } else {
            val h = 12f * blink
            val top = 56f - h / 2
            for (x in floatArrayOf(40f, 60f)) {
                r.set(x + dx, top, x + 8 + dx, top + h)
                c.drawRoundRect(r, 4f, 4f, eye)
            }
        }
        c.restore()
    }
}
