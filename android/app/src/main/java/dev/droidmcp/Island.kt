package dev.droidmcp

import android.animation.LayoutTransition
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A small "dynamic island" pill at the top of the screen that shows what the AI is doing.
 * Idle: a tiny dot. During a tool call: expands with the action. Errors flash red.
 * It never takes touches (so it can't block the AI's taps) and hides itself during screenshots.
 */
object Island {
    private val main = Handler(Looper.getMainLooper())
    private var root: LinearLayout? = null
    private var dot: View? = null
    private var label: TextView? = null
    private var wm: WindowManager? = null
    private var pulse: ObjectAnimator? = null
    private var active = 0
    private val collapse = Runnable { setState(State.IDLE, "") }

    private enum class State { IDLE, BUSY, OK, ERROR }

    /** Shows the island if enabled and allowed (accessibility overlay preferred, else "display over other apps"). */
    fun attach(ctx: Context) = main.post {
        if (root != null || !Prefs.island(ctx)) return@post
        val a11y = DroidAccessibility.instance
        val (context, type) = when {
            a11y != null -> a11y to WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            Settings.canDrawOverlays(ctx) -> ctx.applicationContext to WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else -> return@post
        }
        try {
            build(context, type)
        } catch (e: Exception) {
            Log.w(Mcp.TAG, "island attach failed", e)
            root = null
        }
    }

    fun detach() = main.post {
        val v = root ?: return@post
        try { wm?.removeView(v) } catch (_: Exception) {}
        pulse?.cancel()
        root = null; dot = null; label = null; wm = null; active = 0
    }

    /** Re-attach (e.g. after the accessibility service connects, so it can switch to the better overlay type). */
    fun reattach(ctx: Context) { detach(); attach(ctx) }

    fun start(tool: String, args: JSONObject) {
        val text = describe(tool, args)
        main.post {
            active++
            main.removeCallbacks(collapse)
            setState(State.BUSY, text)
        }
    }

    fun finish(tool: String, ok: Boolean) = main.post {
        active = (active - 1).coerceAtLeast(0)
        if (active > 0) return@post
        if (ok) setState(State.OK, label?.text?.toString().orEmpty()) else setState(State.ERROR, "$tool failed")
        main.postDelayed(collapse, if (ok) 1500 else 3500)
    }

    /** Runs [block] with the island invisible, so screenshots don't include it. */
    fun <T> hiddenDuring(block: () -> T): T {
        val v = root ?: return block()
        setVisible(v, false)
        try {
            Thread.sleep(80) // let a frame render without the island
            return block()
        } finally {
            setVisible(v, true)
        }
    }

    private fun setVisible(v: View, visible: Boolean) {
        val latch = CountDownLatch(1)
        main.post { v.visibility = if (visible) View.VISIBLE else View.INVISIBLE; latch.countDown() }
        latch.await(1, TimeUnit.SECONDS)
    }

    private fun build(ctx: Context, type: Int) {
        val dm = ctx.resources.displayMetrics
        fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, dm).toInt()

        val pill = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), dp(5f), dp(10f), dp(5f))
            background = GradientDrawable().apply { cornerRadius = dp(20f).toFloat(); setColor(Color.argb(235, 0, 0, 0)) }
            layoutTransition = LayoutTransition().apply { enableTransitionType(LayoutTransition.CHANGING); setDuration(220) }
            minimumWidth = dp(34f)
        }
        val d = View(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#34C759")) }
        }
        pill.addView(d, LinearLayout.LayoutParams(dp(8f), dp(8f)))
        val t = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = (dm.widthPixels * 0.7).toInt()
            visibility = View.GONE
        }
        pill.addView(t, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = dp(8f) })

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(6f)
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            title = "DroidMCP island"
        }
        val w = ctx.getSystemService(WindowManager::class.java)
        w.addView(pill, lp)
        wm = w; root = pill; dot = d; label = t
        setState(State.IDLE, "")
    }

    private fun setState(s: State, text: String) {
        val d = dot ?: return
        val t = label ?: return
        val color = when (s) {
            State.IDLE -> "#34C759"
            State.BUSY -> "#0A84FF"
            State.OK -> "#34C759"
            State.ERROR -> "#FF453A"
        }
        (d.background as GradientDrawable).setColor(Color.parseColor(color))
        pulse?.cancel(); d.alpha = 1f
        if (s == State.BUSY) {
            pulse = ObjectAnimator.ofFloat(d, "alpha", 1f, 0.25f).apply {
                duration = 500; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; start()
            }
        }
        if (s == State.IDLE || text.isEmpty()) {
            t.visibility = View.GONE
        } else {
            t.text = text
            t.visibility = View.VISIBLE
        }
    }

    /** Short human description of a tool call. */
    fun describe(tool: String, a: JSONObject): String {
        fun s(k: String, n: Int = 40) = a.optString(k).replace('\n', ' ').let { if (it.length > n) it.take(n) + "…" else it }
        return when (tool) {
            "tap" -> "Tap ${a.opt("x")}, ${a.opt("y")}"
            "long_press" -> "Long-press ${a.opt("x")}, ${a.opt("y")}"
            "swipe" -> "Swipe ${a.opt("x1")},${a.opt("y1")} → ${a.opt("x2")},${a.opt("y2")}"
            "type_text" -> "Type \"${s("text", 30)}\""
            "key" -> "Press ${s("key")}"
            "screenshot" -> "Looking at the screen"
            "ui_tree" -> "Reading the screen"
            "launch_app" -> "Open ${s("package")}"
            "open_url" -> "Open ${s("url")}"
            "list_apps" -> "Listing apps"
            "notifications" -> "Reading notifications"
            "sms_list" -> "Reading SMS"
            "send_sms" -> "Sending SMS to ${s("to")}"
            "read_file" -> "Read ${s("path")}"
            "write_file" -> "Write ${s("path")}"
            "shell" -> "$ ${s("command")}"
            "linux_exec" -> "🐧 ${s("command")}"
            "linux_setup" -> "Setting up Linux"
            "device_info" -> "Checking the device"
            else -> tool
        }
    }
}
