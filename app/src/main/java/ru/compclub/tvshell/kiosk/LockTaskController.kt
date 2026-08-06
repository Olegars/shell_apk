package ru.compclub.tvshell.kiosk

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Lock Task (screen pinning / kiosk). Best with Device Owner:
 * adb shell dpm set-device-owner ru.compclub.tvshell/.kiosk.ShellDeviceAdminReceiver
 */
object LockTaskController {
    private const val TAG = "LockTask"

    fun adminComponent(context: Context): ComponentName =
        ComponentName(context, ShellDeviceAdminReceiver::class.java)

    fun isDeviceOwner(context: Context): Boolean {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return false
        return dpm.isDeviceOwnerApp(context.packageName)
    }

    fun prepareLockTaskPackages(context: Context, extraPackages: Array<String> = emptyArray()) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
        if (!dpm.isDeviceOwnerApp(context.packageName)) return
        runCatching {
            val pkgs = (listOf(context.packageName) + extraPackages.toList())
                .distinct()
                .toTypedArray()
            dpm.setLockTaskPackages(adminComponent(context), pkgs)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dpm.setLockTaskFeatures(
                    adminComponent(context),
                    DevicePolicyManager.LOCK_TASK_FEATURE_NONE,
                )
            }
            Log.i(TAG, "lock-task packages set (${pkgs.size}, owner)")
        }.onFailure { Log.w(TAG, "setLockTaskPackages: ${it.message}") }
    }

    fun start(activity: Activity, extraPackages: Array<String> = emptyArray()) {
        prepareLockTaskPackages(activity, extraPackages)
        val am = activity.getSystemService(ActivityManager::class.java) ?: return
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.lockTaskModeState
        } else {
            @Suppress("DEPRECATION")
            if (am.isInLockTaskMode) ActivityManager.LOCK_TASK_MODE_LOCKED
            else ActivityManager.LOCK_TASK_MODE_NONE
        }
        if (mode != ActivityManager.LOCK_TASK_MODE_NONE) return
        runCatching {
            activity.startLockTask()
            Log.i(TAG, "startLockTask ok (owner=${isDeviceOwner(activity)})")
        }.onFailure { Log.w(TAG, "startLockTask: ${it.message}") }
    }

    fun statusLine(context: Context): String {
        val owner = isDeviceOwner(context)
        return if (owner) {
            "Kiosk: Device Owner + Lock Task"
        } else {
            "Kiosk: HOME + Lock Task (без Device Owner). ADB:\n" +
                "dpm set-device-owner ru.compclub.tvshell/.kiosk.ShellDeviceAdminReceiver\n" +
                "cmd package set-home-activity ru.compclub.tvshell/.ui.LoginActivity"
        }
    }
}
