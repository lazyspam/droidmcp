package com.lazyexec.droidmcp

import android.app.Application
import android.util.Log
import java.io.File

/** Records any crash to a file so the app can show what went wrong on next launch. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ActivityLog.init(this)
        // With root, grant ourselves what the setup steps would ask the user for.
        Shell.onRootGranted = { RootGrants.apply(this) }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                crashFile(this).writeText("$ts on thread '${thread.name}':\n${Log.getStackTraceString(e)}")
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, e)
        }
    }

    companion object {
        fun crashFile(app: android.content.Context) = File(app.filesDir, "last_crash.txt")
    }
}
