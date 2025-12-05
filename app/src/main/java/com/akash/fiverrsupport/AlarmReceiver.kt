package com.akash.fiverrsupport

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * BroadcastReceiver that handles AlarmManager triggers for scheduled Fiverr actions.
 * This ensures the action fires reliably even in Doze mode on Android 13+.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == ACTION_EXECUTE_FIVERR_ACTION) {
            Log.d("nvm", "⏰ AlarmReceiver triggered - sending action to service")
            
            val serviceIntent = Intent(context, FiverrLauncherService::class.java).apply {
                action = FiverrLauncherService.ACTION_EXECUTE_SCHEDULED
            }
            
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                Log.e("nvm", "Failed to start service from AlarmReceiver: ${e.message}", e)
            }
        }
    }

    companion object {
        const val ACTION_EXECUTE_FIVERR_ACTION = "com.akash.fiverrsupport.ACTION_EXECUTE_FIVERR_ACTION"
        const val ALARM_REQUEST_CODE = 1001
    }
}
