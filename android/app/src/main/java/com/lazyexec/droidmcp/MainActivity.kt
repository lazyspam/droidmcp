package com.lazyexec.droidmcp

import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.lazyexec.droidmcp.ui.AppState
import com.lazyexec.droidmcp.ui.DroidApp
import com.lazyexec.droidmcp.ui.DroidTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var state: AppState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        state = AppState(this)
        state.refresh()
        setContent {
            DroidTheme {
                DroidApp(state, onStart = ::start, onStop = ::stop, onRestart = ::restart)
                com.lazyexec.droidmcp.ui.UpdateSheetHost()
            }
        }
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        // Keep the screens in sync with the service, but only while the app is visible.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) { state.refresh(); delay(1000) }
            }
        }
    }

    // onStart/onStop (not resume/pause) so split-screen and picture-in-picture keep updating.
    override fun onStart() {
        super.onStart()
        visible = true
        ActivityLog.listener = { runOnUiThread { if (!isDestroyed) state.refresh() } }
        state.refresh()
        // One GitHub request per app open (skipped within 5 minutes of the last one); no timers.
        Updater.autoCheck(this)
    }

    override fun onStop() {
        visible = false
        ActivityLog.listener = null
        super.onStop()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        state.refresh()
    }

    private fun start() {
        McpService.start(this)?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        // Root check off the main thread so the status is accurate right away; dropped if the screen goes away.
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { if (Shell.rootState == null) Shell.hasRoot() }
            state.refresh()
        }
    }

    private fun stop() {
        McpService.stop(this)
        state.refresh()
    }

    companion object {
        /** The app's screen is on show (between onStart and onStop). */
        @Volatile var visible = false
            private set
    }

    private fun restart() {
        McpService.stop(this)
        lifecycleScope.launch { delay(700); start() }
    }
}
