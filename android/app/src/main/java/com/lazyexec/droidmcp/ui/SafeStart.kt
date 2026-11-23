package com.lazyexec.droidmcp.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import com.lazyexec.droidmcp.Mcp

/**
 * Starts a system screen (settings, a browser link). Some phones (OEM builds, Android Go, work profiles) don't
 * have every settings screen, and starting a missing one throws: the tap then does nothing instead of crashing the app.
 */
fun Context.startActivitySafely(intent: Intent) {
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Log.w(Mcp.TAG, "no screen for ${intent.action}", e)
    } catch (e: SecurityException) {
        Log.w(Mcp.TAG, "not allowed to open ${intent.action}", e)
    }
}
