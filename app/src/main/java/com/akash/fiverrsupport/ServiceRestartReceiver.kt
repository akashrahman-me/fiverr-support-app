package com.akash.fiverrsupport

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * BroadcastReceiver that automatically restarts the FiverrLauncherService
 * when it's killed by Android or when device boots up
 */
class ServiceRestartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        Log.d("nvm", "ServiceRestartReceiver received: ${intent.action}")

        val prefs = context.getSharedPreferences("FiverrSupportPrefs", Context.MODE_PRIVATE)
        val wasEnabled = prefs.getBoolean("service_enabled", false)

        when (intent.action) {
            ACTION_RESTART_SERVICE -> {
                // Service was killed, restart it if it was enabled
                if (wasEnabled) {
                    val interval = intent.getLongExtra("interval", prefs.getLong("service_interval", 20000L))
                    restartService(context, interval)
                }
            }
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                // Device booted or app updated, restart service if it was enabled
                if (wasEnabled) {
                    val interval = prefs.getLong("service_interval", 20000L)
                    restartService(context, interval)
                }
            }
        }
    }

    private fun restartService(context: Context, interval: Long) {
        try {
            Log.d("nvm", "Restarting FiverrLauncherService with interval: ${interval}ms")

            val serviceIntent = Intent(context, FiverrLauncherService::class.java).apply {
                action = FiverrLauncherService.ACTION_START
                putExtra(FiverrLauncherService.EXTRA_INTERVAL, interval)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }

            Log.d("nvm", "FiverrLauncherService restart initiated")
        } catch (e: Exception) {
            Log.e("nvm", "Error restarting service: ${e.message}", e)
        }
    }

    companion object {
        const val ACTION_RESTART_SERVICE = "com.akash.fiverrsupport.ACTION_RESTART_SERVICE"
    }
}

