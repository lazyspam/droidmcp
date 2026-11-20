package dev.droidmcp

import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.droidmcp.ui.AppState
import dev.droidmcp.ui.DroidApp
import dev.droidmcp.ui.DroidTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
        ActivityLog.listener = { runOnUiThread { if (!isDestroyed) state.refresh() } }
        state.refresh()
    }

    override fun onStop() {
        ActivityLog.listener = null
        super.onStop()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        state.refresh()
    }

    private fun start() {
        McpService.start(this)?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        // Root check in the background so the status is accurate right away.
        Thread { if (Shell.rootState == null) Shell.hasRoot(); runOnUiThread { state.refresh() } }.start()
    }

    private fun stop() {
        McpService.stop(this)
        state.refresh()
    }

    private fun restart() {
        McpService.stop(this)
        lifecycleScope.launch { delay(700); start() }
    }
}
