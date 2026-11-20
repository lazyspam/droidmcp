package dev.droidmcp

import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
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
        // Keep the screens in sync with the service (status, permissions, activity).
        lifecycleScope.launch {
            while (true) { state.refresh(); delay(1000) }
        }
    }

    override fun onResume() {
        super.onResume()
        ActivityLog.listener = { runOnUiThread { state.refresh() } }
        state.refresh()
    }

    override fun onPause() {
        ActivityLog.listener = null
        super.onPause()
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
