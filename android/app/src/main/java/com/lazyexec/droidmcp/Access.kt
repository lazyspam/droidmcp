package com.lazyexec.droidmcp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.app.Notification
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * No-root phone control through Android's accessibility API: screenshots (Android 11+), the on-screen
 * element tree, gestures, typing and global keys. The user turns it on once in Settings → Accessibility.
 */
class DroidAccessibility : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        ActivityLog.add("accessibility service connected")
        if (Status.running) Island.reattach(this)
        ActivityLog.listener?.invoke()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        // The bubble's window belonged to this service and is gone now: rebuild it as a normal overlay.
        if (Status.running) Island.reattach(applicationContext)
        ActivityLog.listener?.invoke()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun screenshot(): Bitmap {
        if (Build.VERSION.SDK_INT < 30) throw ToolError("Screenshots without root need Android 11 or newer.")
        val latch = CountDownLatch(1)
        val result = AtomicReference<Bitmap?>()
        val error = AtomicReference<String?>()
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(shot: ScreenshotResult) {
                try {
                    val hw = Bitmap.wrapHardwareBuffer(shot.hardwareBuffer, shot.colorSpace)
                    result.set(hw?.copy(Bitmap.Config.ARGB_8888, false))
                    hw?.recycle()
                    shot.hardwareBuffer.close()
                } catch (e: Exception) {
                    error.set("could not read the screenshot: ${e.message}")
                }
                latch.countDown()
            }

            override fun onFailure(code: Int) {
                error.set(when (code) {
                    ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "screenshots are rate-limited by Android; wait a second and retry"
                    ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "the app on screen blocks screenshots (secure window)"
                    ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "accessibility screenshot access is missing"
                    else -> "Android error code $code"
                })
                latch.countDown()
            }
        })
        if (!latch.await(10, TimeUnit.SECONDS)) throw ToolError("Screenshot timed out.")
        error.get()?.let { throw ToolError("Screenshot failed: $it.") }
        return result.get() ?: throw ToolError("Screenshot failed: empty image.")
    }

    fun gesture(path: Path, durationMs: Long) {
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(1, 60_000)))
            .build()
        val latch = CountDownLatch(1)
        var ok = false
        val sent = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { ok = true; latch.countDown() }
            override fun onCancelled(d: GestureDescription?) { latch.countDown() }
        }, null)
        if (!sent) throw ToolError("Android refused the gesture.")
        if (!latch.await(durationMs + 5000, TimeUnit.MILLISECONDS)) throw ToolError("Gesture timed out.")
        if (!ok) throw ToolError("The gesture was cancelled (another touch or gesture interrupted it).")
    }

    fun global(action: Int): Boolean = performGlobalAction(action)

    /** Types into the focused editable field: appends at the end, Enter for newlines. */
    fun type(text: String) {
        val parts = text.split("\n")
        parts.forEachIndexed { i, part ->
            val node = focusedInput() ?: throw ToolError("No text field is focused. Tap one first.")
            if (part.isNotEmpty()) {
                val current = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, current + part)
                }
                if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                    throw ToolError("The focused field didn't accept text.")
                }
            }
            if (i < parts.lastIndex) {
                val enter = if (Build.VERSION.SDK_INT >= 30) {
                    node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                } else false
                if (!enter) throw ToolError("Couldn't press Enter in this field (needs Android 11+ or root).")
            }
        }
    }

    private fun focusedInput(): AccessibilityNodeInfo? {
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { return it }
        for (w in windows) w.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { return it }
        return null
    }

    /** Same compact format as the root (uiautomator) version. */
    fun tree(all: Boolean): String {
        val sb = StringBuilder()
        // Leave out our own island overlay.
        val roots = windows.mapNotNull { w -> w.root?.let { w to it } }.filter { it.second.packageName != packageName }
        if (roots.isEmpty()) {
            val r = rootInActiveWindow ?: return "No windows are readable right now."
            dump(r, 0, all, sb)
            return sb.toString().ifEmpty { "No elements found." }
        }
        for ((w, root) in roots) {
            sb.append("window: ${w.title ?: root.packageName ?: "?"} (${typeName(w.type)})\n")
            dump(root, 1, all, sb)
        }
        return sb.toString()
    }

    private fun typeName(t: Int) = when (t) {
        android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION -> "app"
        android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "keyboard"
        android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
        else -> "other"
    }

    private fun dump(n: AccessibilityNodeInfo, depth: Int, all: Boolean, sb: StringBuilder) {
        if (sb.length > 200_000) return
        val t = n.text?.toString().orEmpty()
        val d = n.contentDescription?.toString().orEmpty()
        val id = n.viewIdResourceName.orEmpty().substringAfter(":id/")
        val cls = n.className?.toString().orEmpty().substringAfterLast('.')
        val flags = buildList {
            if (n.isClickable) add("clickable")
            if (n.isLongClickable) add("long-clickable")
            if (n.isScrollable) add("scrollable")
            if (n.isChecked) add("checked")
            if (n.isFocused) add("focused")
            if (n.isSelected) add("selected")
            if (n.isEditable) add("editable")
        }
        val show = all || t.isNotEmpty() || d.isNotEmpty() || id.isNotEmpty() || flags.isNotEmpty()
        if (show && n.isVisibleToUser) {
            val r = Rect().also { n.getBoundsInScreen(it) }
            sb.append("  ".repeat(depth.coerceAtMost(12))).append(cls)
            if (t.isNotEmpty()) sb.append(" text=\"").append(t.take(200)).append('"')
            if (d.isNotEmpty()) sb.append(" desc=\"").append(d.take(200)).append('"')
            if (id.isNotEmpty()) sb.append(" id=").append(id)
            if (flags.isNotEmpty()) sb.append(" [").append(flags.joinToString(",")).append(']')
            sb.append(" @(").append(r.centerX()).append(',').append(r.centerY()).append(")\n")
        }
        for (i in 0 until n.childCount) n.getChild(i)?.let { dump(it, if (show) depth + 1 else depth, all, sb) }
    }

    companion object {
        @Volatile var instance: DroidAccessibility? = null

        fun enabled(c: Context): Boolean {
            val list = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = ComponentName(c, DroidAccessibility::class.java)
            return list.split(':').any { ComponentName.unflattenFromString(it) == me }
        }

        /** The running service, or a ToolError telling the user how to turn it on. */
        fun require(what: String): DroidAccessibility = instance ?: throw ToolError(
            "$what needs root or DroidMCP's accessibility service. On the phone: Settings → Accessibility → " +
                "DroidMCP → turn on. (On Android 13+, if the switch is greyed out: App info → ⋮ → Allow restricted settings.)"
        )
    }
}

/** No-root notification reading. The user allows it once in Settings → Notification access. */
class NotifListener : NotificationListenerService() {
    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { instance = null }

    fun read(): String {
        val sb = StringBuilder()
        for (sbn in activeNotifications ?: emptyArray()) {
            val e = sbn.notification.extras
            val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
            if (title.isEmpty() && text.isEmpty()) continue
            sb.append("[${sbn.packageName}] $title: $text".take(1000)).append('\n')
        }
        return sb.toString().ifEmpty { "No notifications." }
    }

    companion object {
        @Volatile var instance: NotifListener? = null

        fun enabled(c: Context): Boolean {
            val list = Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(c, NotifListener::class.java)
            return list.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
