package fr.mamieturbo.kiosk

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.view.View
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import fr.mamieturbo.ui.MainActivity

class KioskManager(private val activity: Activity) {
    private val dpm = activity.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin = ComponentName(activity, MamieTurboDeviceAdminReceiver::class.java)

    /** Applies policies only after ADB provisioning made SpeechKiosk Device Owner. */
    fun provisionDedicatedDevice(): Boolean {
        if (!dpm.isDeviceOwnerApp(activity.packageName)) return false
        dpm.setLockTaskPackages(admin, arrayOf(activity.packageName))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Power remains usable; Home, Recents, notifications and keyguard stay unavailable.
            dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS)
        }
        runCatching { dpm.setKeyguardDisabled(admin, true) }
        val homeFilter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        dpm.addPersistentPreferredActivity(
            admin,
            homeFilter,
            ComponentName(activity, MainActivity::class.java)
        )
        return true
    }

    fun applyImmersiveMode(enabled: Boolean) {
        if (!enabled) return
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        WindowInsetsControllerCompat(activity.window, activity.window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        @Suppress("DEPRECATION")
        activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    fun enterLockTaskIfPermitted(): Boolean {
        return if (dpm.isLockTaskPermitted(activity.packageName)) {
            if (!isLocked()) activity.startLockTask()
            true
        } else false
    }

    fun exitLockTask() { runCatching { activity.stopLockTask() } }
    fun isDeviceOwner(): Boolean = dpm.isDeviceOwnerApp(activity.packageName)

    /** Turns the display back off after an OEM wakes it because power was connected. */
    fun returnDeviceToSleep(): Boolean {
        if (!dpm.isDeviceOwnerApp(activity.packageName) || !dpm.isAdminActive(admin)) return false
        return runCatching { dpm.lockNow(); true }.getOrDefault(false)
    }

    private fun isLocked(): Boolean {
        val manager = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_LOCKED
    }
}
