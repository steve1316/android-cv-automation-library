package com.steve1316.automation_library.utils

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.steve1316.automation_library.data.SharedData

/**
 * Reopens the app after an in-app update replaced it, and deletes the downloaded APK. It ships disabled and [AppUpdateInstaller.install] enables it
 * right before an install, so other installs such as `adb install` during development never reopen the app.
 */
class AppUpdatedReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG: String = "${SharedData.loggerTag}AppUpdatedReceiver"
    }

    /**
     * Handles `ACTION_MY_PACKAGE_REPLACED`, which Android sends only to the app that was just updated.
     *
     * @param context The receiver's context.
     * @param intent The broadcast intent.
     */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        AppUpdateInstaller.clearLeftovers(context)
        // Disarm again so only the next in-app update reopens the app.
        context.packageManager.setComponentEnabledSetting(ComponentName(context, AppUpdatedReceiver::class.java), PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        try {
            // Android blocks background activity starts unless the app holds the overlay permission, which these bots need anyway.
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w(TAG, "Could not reopen the app after the update: ${e.message}")
        }
    }
}
