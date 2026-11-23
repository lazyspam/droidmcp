package com.lazyexec.droidmcp

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * With root, gives the app everything the manual setup steps would: accessibility, notification access,
 * runtime permissions, all-files access, overlay and the battery exemption. Checks first, so only what is
 * missing gets granted, and it only ever adds (never removes what the user or other apps set).
 */
object RootGrants {
    private val busy = AtomicBoolean(false)

    /** Runs in the background; a second call while one is running is skipped. */
    fun apply(ctx: Context) {
        val c = ctx.applicationContext
        if (!busy.compareAndSet(false, true)) return
        Thread {
            try {
                grantMissing(c)
            } catch (e: Exception) {
                Log.w(Mcp.TAG, "root grants", e)
            } finally {
                busy.set(false)
                ActivityLog.listener?.invoke()
            }
        }.start()
    }

    private fun grantMissing(c: Context) {
        val pkg = c.packageName
        val steps = linkedMapOf<String, String>() // name -> command

        val me = ComponentName(c, DroidAccessibility::class.java)
        val a11y = me.flattenToString()
        // Decided from the two settings alone, never from whether the service is running (right after a start it
        // may not have connected yet). The log line says which one was missing when it does grant.
        val listed = a11yListed(c, me)
        val a11yOn = Settings.Secure.getInt(c.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        if (!listed || !a11yOn) {
            Log.i(Mcp.TAG, "root grants: accessibility missing (in enabled list: $listed, accessibility_enabled: $a11yOn)")
            steps["accessibility"] = "cur=\$(settings get secure enabled_accessibility_services); " +
                "case \":\$cur:\" in *${Shell.q(":$a11y:")}*) ;; " +
                "*) if [ -z \"\$cur\" ] || [ \"\$cur\" = null ]; then new=${Shell.q(a11y)}; else new=\"\$cur:\"${Shell.q(a11y)}; fi; " +
                "settings put secure enabled_accessibility_services \"\$new\" || exit 1;; esac; " +
                "settings put secure accessibility_enabled 1"
        }
        if (!NotifListener.enabled(c)) {
            val comp = ComponentName(c, NotifListener::class.java).flattenToString()
            steps["notification access"] = "cmd notification allow_listener ${Shell.q(comp)}"
        }
        for (perm in missingRuntimePermissions(c)) {
            steps[perm.removePrefix("android.permission.")] = "pm grant ${Shell.q(pkg)} ${Shell.q(perm)}"
        }
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            steps["all files access"] = "appops set ${Shell.q(pkg)} MANAGE_EXTERNAL_STORAGE allow"
        }
        if (!Settings.canDrawOverlays(c)) {
            steps["overlay"] = "appops set ${Shell.q(pkg)} SYSTEM_ALERT_WINDOW allow"
        }
        if (!c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(pkg)) {
            steps["battery exemption"] = "dumpsys deviceidle whitelist +$pkg"
        }
        if (steps.isEmpty()) return

        // One su call for everything; each step reports on its own line and a failure doesn't stop the rest.
        val script = steps.entries.joinToString("\n") { (name, cmd) ->
            "if ( $cmd ) >/dev/null 2>&1; then echo ${Shell.q("ok $name")}; else echo ${Shell.q("fail $name")}; fi"
        }
        val out = Shell.run(script, root = true, timeoutSec = 60).out.lines()
        val ok = out.filter { it.startsWith("ok ") }.map { it.removePrefix("ok ") }
        val failed = out.filter { it.startsWith("fail ") }.map { it.removePrefix("fail ") }
        Log.i(Mcp.TAG, "root grants: ok=$ok failed=$failed")
        if (ok.isNotEmpty()) ActivityLog.add("root: granted ${ok.joinToString(", ")}")
        if (failed.isNotEmpty()) ActivityLog.add("✗ root could not grant ${failed.joinToString(", ")}; use the setup steps")
    }

    /**
     * Whether Settings.Secure ENABLED_ACCESSIBILITY_SERVICES lists [me]. The value is a colon-separated list of
     * flattened component names, long or short form ("pkg/.Cls"), so entries are compared as ComponentNames.
     */
    private fun a11yListed(c: Context, me: ComponentName): Boolean {
        val list = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return list.split(':').any { e -> e.isNotBlank() && ComponentName.unflattenFromString(e.trim()) == me }
    }

    /** Dangerous (runtime) permissions the manifest asks for that aren't granted yet. */
    private fun missingRuntimePermissions(c: Context): List<String> {
        val pm = c.packageManager
        val requested = try {
            pm.getPackageInfo(c.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        return requested.filter { perm ->
            c.checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED && try {
                val info = pm.getPermissionInfo(perm, 0)
                val base = if (Build.VERSION.SDK_INT >= 28) info.protection
                    else @Suppress("DEPRECATION") (info.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE)
                base == PermissionInfo.PROTECTION_DANGEROUS
            } catch (_: PackageManager.NameNotFoundException) {
                false // not known on this Android version (e.g. POST_NOTIFICATIONS before 13)
            }
        }
    }
}
