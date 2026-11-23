package com.lazyexec.droidmcp

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The activity "island", drawn as a chat head: a round bubble on the screen edge with a status ring,
 * and a chat balloon that pops out beside it saying what the AI is doing. While an AI acts, the droid's face
 * gives way to that client's logo (see [ClientLogos]); clients without one keep the droid.
 * Drag it anywhere; on release it snaps to the nearest edge. Tap it to open the app.
 * While the AI acts, touches pass through it so the AI's taps never land on it.
 * It hides itself during screenshots, so the AI never sees it.
 */
object Island {
    private val main = Handler(Looper.getMainLooper())
    private var bubble: FrameLayout? = null
    private var ring: View? = null
    private var logo: LogoView? = null
    private var clientView: android.widget.ImageView? = null
    /** Logo of the client whose action shows now, or null (the droid). */
    private var clientLogo: ClientLogos.Logo? = null
    private var balloon: TextView? = null
    /** Drawn before the balloon text while the current action shows (busy and done), e.g. the distro of linux_exec. */
    private var balloonIcon: Int? = null
    private var wm: WindowManager? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var balloonLp: WindowManager.LayoutParams? = null
    private var pulse: ObjectAnimator? = null

    /**
     * A tool call that is running now. Calls can overlap (several clients, or one client's parallel calls),
     * so each keeps its own text: the balloon shows the newest one still running, never a finished one's.
     * [thread]: the request thread that called [start]; its [finish] comes from the same thread.
     */
    private class Call(val thread: Long, val tool: String, val text: String, val icon: Int?, val logo: ClientLogos.Logo?)

    /** Running calls, oldest first. Main thread only. Kept across a rebuild of the windows (the calls still run). */
    private val calls = ArrayList<Call>()
    private var style = Prefs.IslandStyle()
    private var screenW = 0
    private var gapPx = 0
    private var appCtx: Context? = null
    private var overlayType = 0
    private var hiding = false
    private val collapse = Runnable { setState(State.IDLE, "") }

    private enum class State { IDLE, BUSY, OK, ERROR }

    private val onLeft get() = style.position == "left"

    fun attach(ctx: Context) = main.post {
        appCtx = ctx.applicationContext
        if (bubble != null || !Prefs.island(ctx)) return@post
        style = Prefs.islandStyle(ctx)
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
            detachNow()
        }
    }

    fun detach() = main.post { detachNow() }

    private fun detachNow() {
        for (v in listOf<View?>(balloon, bubble)) try { if (v != null) wm?.removeView(v) } catch (_: Exception) {}
        pulse?.cancel()
        // [calls] stays: a rebuild (accessibility service (re)connecting, a style change) doesn't end the running calls.
        bubble = null; ring = null; logo = null; clientView = null; balloon = null; wm = null; bubbleLp = null; balloonLp = null; hiding = false
    }

    fun reattach(ctx: Context) { detach(); attach(ctx) }

    /** Plays a sample action so the user can see their settings. */
    fun preview(ctx: Context) {
        if (bubble == null) attach(ctx)
        main.postDelayed({
            if (calls.isNotEmpty()) return@postDelayed // a real call is showing; don't cover it
            main.removeCallbacks(collapse); balloonIcon = null; clientLogo = null; setState(State.BUSY, "Tap 540, 1200")
            main.postDelayed({
                if (calls.isNotEmpty()) return@postDelayed
                setState(State.OK, "Tap 540, 1200"); main.postDelayed(collapse, 1600)
            }, 1800)
        }, 150)
    }

    /** [client]: the acting client's name from its clientInfo, for its logo. */
    fun start(tool: String, args: JSONObject, client: String = "") {
        val text = describe(tool, args)
        val thread = Thread.currentThread().id
        main.post {
            val ctx = appCtx ?: balloon?.context
            calls += Call(thread, tool, text, ctx?.let { icon(it, tool, args) }, ClientLogos.of(client))
            settle?.let { main.removeCallbacks(it); settle = null }
            main.removeCallbacks(collapse)
            showNewest()
        }
    }

    private var settle: Runnable? = null

    fun finish(tool: String, ok: Boolean) {
        val thread = Thread.currentThread().id
        main.post {
            // This request's own call; by tool name only if it somehow came from another thread.
            val i = calls.indexOfLast { it.thread == thread && it.tool == tool }.takeIf { it >= 0 }
                ?: calls.indexOfLast { it.tool == tool }.takeIf { it >= 0 }
                ?: return@post
            val done = calls.removeAt(i)
            if (calls.isNotEmpty()) {
                // Others still run: show the newest of them, not the one that just ended.
                showNewest()
                return@post
            }
            // Wait a moment before showing "done": AI actions often come back-to-back, and flipping
            // busy → ✓ → busy within a few hundred ms reads as a blink.
            settle?.let { main.removeCallbacks(it) }
            settle = Runnable {
                settle = null
                if (calls.isNotEmpty()) return@Runnable
                balloonIcon = done.icon; clientLogo = done.logo
                if (ok) setState(State.OK, done.text) else setState(State.ERROR, "$tool failed")
                main.postDelayed(collapse, if (ok) 1400 else 3500)
            }.also { main.postDelayed(it, if (ok) 350 else 0) }
        }
    }

    /** Shows the newest running call as busy (main thread). */
    private fun showNewest() {
        val c = calls.lastOrNull() ?: return
        balloonIcon = c.icon; clientLogo = c.logo
        setState(State.BUSY, c.text)
    }

    /** Where the bubble (and its balloon, if showing) sit on screen, in pixels, for telling the AI to ignore them. */
    fun overlayRects(): List<android.graphics.Rect> {
        val out = ArrayList<android.graphics.Rect>()
        val latch = CountDownLatch(1)
        main.post {
            for (v in listOfNotNull(bubble, balloon)) {
                if (v.visibility != View.VISIBLE || !v.isAttachedToWindow) continue
                val loc = IntArray(2).also { v.getLocationOnScreen(it) }
                out += android.graphics.Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
            }
            latch.countDown()
        }
        latch.await(1, TimeUnit.SECONDS)
        return out
    }

    /** Runs [block] with the island invisible, so screenshots don't include it. */
    fun <T> hiddenDuring(block: () -> T): T {
        val views = listOfNotNull(bubble, balloon)
        if (views.isEmpty()) return block()
        val was = views.map { it.visibility }
        setVis(views, List(views.size) { View.INVISIBLE })
        try {
            Thread.sleep(80)
            return block()
        } finally {
            setVis(views, was)
        }
    }

    private fun setVis(views: List<View>, vis: List<Int>) {
        val latch = CountDownLatch(1)
        main.post { views.forEachIndexed { i, v -> v.visibility = vis[i] }; latch.countDown() }
        latch.await(1, TimeUnit.SECONDS)
    }

    private fun dp(ctx: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

    private fun build(ctx: Context, type: Int) {
        val dm = ctx.resources.displayMetrics
        screenW = dm.widthPixels
        gapPx = dp(ctx, 8f)
        val size = dp(ctx, when (style.size) { 0 -> 42f; 2 -> 60f; else -> 50f })
        val alpha = 255 // whole-window opacity is applied via LayoutParams.alpha (see applyOpacity)
        overlayType = type
        val w = ctx.getSystemService(WindowManager::class.java)

        // Balloon: its own window that never takes touches.
        val b = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, when (style.size) { 0 -> 12f; 2 -> 15f; else -> 13.5f })
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            maxWidth = (dm.widthPixels * 0.62).toInt()
            setPadding(dp(ctx, 14f), dp(ctx, 9f), dp(ctx, 14f), dp(ctx, 9f))
            background = GradientDrawable().apply { setColor(Color.argb(alpha, 44, 44, 46)) }
            visibility = View.GONE
        }
        val bLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; title = "DroidMCP island text" }

        // Bubble: dark circle, status ring, the droid.
        val bub = FrameLayout(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(alpha, 28, 28, 30)) }
        }
        val r = View(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(ctx, 3f), Color.parseColor("#34C759")) }
        }
        bub.addView(r, FrameLayout.LayoutParams(size, size))
        val face = LogoView(ctx)
        bub.addView(face, FrameLayout.LayoutParams((size * 0.92f).toInt(), (size * 0.92f).toInt(), Gravity.CENTER))
        val clientImg = android.widget.ImageView(ctx).apply { visibility = View.GONE }
        bub.addView(clientImg, FrameLayout.LayoutParams((size * 0.5f).toInt(), (size * 0.5f).toInt(), Gravity.CENTER))
        val edge = dp(ctx, 6f)
        val uLp = WindowManager.LayoutParams(
            size, size, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (onLeft) edge else screenW - size - edge
            y = if (style.y >= 0) style.y else (dm.heightPixels * 0.22).toInt()
            title = "DroidMCP island"
        }

        w.addView(b, bLp)
        w.addView(bub, uLp)
        wm = w; bubble = bub; ring = r; logo = face; clientView = clientImg; balloon = b; bubbleLp = uLp; balloonLp = bLp
        applyOpacityNow()
        enableDrag(bub, uLp, w, ctx, size, edge)
        // Rebuilt while calls run (e.g. the accessibility service connected mid-call): keep showing them.
        if (calls.isNotEmpty()) showNewest() else setState(State.IDLE, "")
    }

    /** Keeps the balloon beside the bubble, opening towards the middle of the screen. */
    private fun placeBalloon() {
        val b = balloon ?: return; val bl = balloonLp ?: return; val ul = bubbleLp ?: return; val w = wm ?: return
        val left = ul.x + ul.width / 2 < screenW / 2
        val big = gapPx * 2.25f; val small = gapPx * 0.6f
        (b.background as GradientDrawable).cornerRadii =
            if (left) floatArrayOf(small, small, big, big, big, big, big, big) else floatArrayOf(big, big, small, small, big, big, big, big)
        b.measure(View.MeasureSpec.makeMeasureSpec(screenW, View.MeasureSpec.AT_MOST), View.MeasureSpec.UNSPECIFIED)
        bl.x = if (left) ul.x + ul.width + gapPx else (ul.x - gapPx - b.measuredWidth).coerceAtLeast(0)
        bl.y = ul.y + gapPx / 2
        try { w.updateViewLayout(b, bl) } catch (_: Exception) {}
    }

    /** Drag anywhere; on release glide to the nearest edge (like a chat head). A tap opens the app. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun enableDrag(v: View, p: WindowManager.LayoutParams, w: WindowManager, ctx: Context, size: Int, edge: Int) {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        val slop = dp(ctx, 8f)
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; moved = false
                    view.animate().scaleX(1.12f).scaleY(1.12f).setDuration(120).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!moved && dx * dx + dy * dy > slop * slop) moved = true
                    if (moved) {
                        p.x = (startX + dx).toInt(); p.y = (startY + dy).toInt().coerceAtLeast(0)
                        w.updateViewLayout(view, p); placeBalloon()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    if (!moved) {
                        if (e.actionMasked == MotionEvent.ACTION_UP) openApp(ctx)
                        return@setOnTouchListener true
                    }
                    val toLeft = p.x + size / 2 < screenW / 2
                    val target = if (toLeft) edge else screenW - size - edge
                    ValueAnimator.ofInt(p.x, target).apply {
                        duration = 260; interpolator = OvershootInterpolator(1.2f)
                        addUpdateListener { a ->
                            if (bubble == null) return@addUpdateListener
                            p.x = a.animatedValue as Int
                            try { w.updateViewLayout(view, p) } catch (_: Exception) {}
                            placeBalloon()
                        }
                        start()
                    }
                    style = style.copy(position = if (toLeft) "left" else "right", y = p.y)
                    Prefs.setIslandStyle(ctx, style)
                    true
                }
                else -> false
            }
        }
    }

    private fun openApp(ctx: Context) {
        try {
            ctx.startActivity(android.content.Intent(ctx, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }

    /** Live opacity change from the settings slider (no rebuild). */
    fun setOpacity(o: Float) = main.post {
        style = style.copy(opacity = o)
        applyOpacityNow()
    }

    /**
     * Fades both windows as a whole. Android 12+ drops touches that pass through an app overlay more than
     * 80% opaque, so pass-through windows drawn as app overlays are capped at 0.8. Accessibility overlays are trusted.
     */
    private fun applyOpacityNow() {
        val o = style.opacity.coerceIn(0.2f, 1f)
        val cap = if (overlayType == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) 0.8f else 1f
        val w = wm ?: return
        balloonLp?.let { lp -> balloon?.let { v -> lp.alpha = minOf(o, cap); try { w.updateViewLayout(v, lp) } catch (_: Exception) {} } }
        // Same alpha in every state: switching between the touch-through cap and full opacity on every action blinked.
        bubbleLp?.let { lp -> bubble?.let { v ->
            val a = minOf(o, cap)
            if (lp.alpha != a) { lp.alpha = a; try { w.updateViewLayout(v, lp) } catch (_: Exception) {} }
        } }
    }

    /** While the AI acts, let touches pass through the bubble so its taps can never land on it. */
    private fun setTouchable(on: Boolean) {
        val p = bubbleLp ?: return
        val f = if (on) p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                else p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (f != p.flags) { p.flags = f; bubble?.let { v -> try { wm?.updateViewLayout(v, p) } catch (_: Exception) {} } }
    }

    private fun setState(s: State, text: String) {
        val r = ring ?: return
        val b = balloon ?: return
        val color = when (s) {
            State.IDLE, State.OK -> "#34C759"
            State.BUSY -> "#0A84FF"
            State.ERROR -> "#FF453A"
        }
        (r.background as GradientDrawable).setStroke(gapPx * 3 / 8, Color.parseColor(color))
        pulse?.cancel(); r.alpha = 1f
        logo?.let {
            it.tipColor = Color.parseColor(color)
            it.mood = when (s) {
                State.IDLE -> LogoView.Mood.IDLE
                State.BUSY -> LogoView.Mood.BUSY
                State.OK -> LogoView.Mood.OK
                State.ERROR -> LogoView.Mood.ERROR
            }
        }
        showClientLogo(if (s == State.BUSY || s == State.OK) clientLogo else null)
        if (s == State.BUSY) {
            // Slow, shallow "breathing" instead of a strobe.
            pulse = ObjectAnimator.ofFloat(r, "alpha", 1f, 0.55f).apply {
                duration = 1100; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
                interpolator = android.view.animation.AccelerateDecelerateInterpolator(); start()
            }
        }
        setTouchable(s != State.BUSY)
        bubble?.visibility = if (s == State.IDLE && style.hideWhenIdle) View.INVISIBLE else View.VISIBLE

        val showText = when (s) {
            State.IDLE -> ""
            State.OK -> "✓  $text"
            State.ERROR -> "✕  $text"
            State.BUSY -> text
        }
        (b.background as GradientDrawable).setColor(
            if (s == State.ERROR) Color.rgb(120, 28, 24) else Color.rgb(44, 44, 46))
        if (showText.isEmpty()) {
            if (b.visibility == View.VISIBLE && !hiding) {
                hiding = true
                b.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setDuration(160).withEndAction {
                    // Only finish hiding if nothing new arrived during the fade.
                    if (hiding) b.visibility = View.GONE
                    hiding = false
                }.start()
            }
        } else {
            b.text = showText
            setBalloonIcon(b, if (s == State.ERROR) null else balloonIcon)
            placeBalloon()
            if (hiding) {
                // A new action arrived mid-fade: cancel the fade and snap back to fully shown.
                hiding = false
                b.animate().cancel()
                b.alpha = 1f; b.scaleX = 1f; b.scaleY = 1f; b.visibility = View.VISIBLE
            } else if (b.visibility != View.VISIBLE) {
                val left = (bubbleLp?.x ?: 0) < screenW / 2
                b.pivotX = if (left) 0f else b.measuredWidth.toFloat()
                b.pivotY = 0f
                b.alpha = 0f; b.scaleX = 0.7f; b.scaleY = 0.7f; b.visibility = View.VISIBLE
                b.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(240).setInterpolator(OvershootInterpolator(1.6f)).start()
            }
        }
    }

    /** The client's logo in place of the droid, or the droid again for null. The ring keeps showing the state. */
    private fun showClientLogo(l: ClientLogos.Logo?) {
        val v = clientView ?: return
        if (l == null) {
            v.visibility = View.GONE; logo?.visibility = View.VISIBLE
            return
        }
        v.setImageResource(l.res)
        // The bubble is always dark: single-colour logos in white, brand-coloured ones as they are.
        if (l.tinted) v.setColorFilter(Color.WHITE) else v.clearColorFilter()
        v.visibility = View.VISIBLE; logo?.visibility = View.INVISIBLE
    }

    private fun setBalloonIcon(b: TextView, res: Int?) {
        val d = res?.let { b.context.getDrawable(it) }?.apply {
            val px = (b.textSize * 1.25f).toInt()
            setBounds(0, 0, px, px)
        }
        b.compoundDrawablePadding = dp(b.context, 6f)
        b.setCompoundDrawablesRelative(d, null, null, null)
    }

    /** Icon drawn before a tool call's description, or null: linux_exec shows the distro it runs in. */
    @androidx.annotation.DrawableRes
    fun icon(ctx: Context, tool: String, a: JSONObject): Int? = when (tool) {
        "linux_exec" -> when (Distro.of(a.optString("distro")) ?: Prefs.defaultDistro(ctx)) {
            Distro.ALPINE -> R.drawable.ic_session_alpine
            Distro.UBUNTU -> R.drawable.ic_session_ubuntu
        }
        else -> null
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
            "linux_exec" -> "${a.optString("distro").takeIf { it.isNotBlank() }?.let { "$it: " } ?: ""}${s("command")}"
            "linux_setup" -> "Setting up Linux"
            "terminal_read" -> "Reading the terminal"
            "terminal_input" -> if (a.optBoolean("ctrl_c")) "Ctrl+C" else "Typing in the terminal"
            "device_info" -> "Checking the device"
            else -> tool
        }
    }
}
