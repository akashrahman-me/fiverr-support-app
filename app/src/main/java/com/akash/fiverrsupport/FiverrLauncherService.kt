package com.akash.fiverrsupport

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat

class FiverrLauncherService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private val launchInterval = 5000L

    private val launchRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                launchFiverrApp()
                handler.postDelayed(this, launchInterval)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundServiceInternal()
                isRunning = true
                handler.post(launchRunnable)

                Log.d("nvm", "FiverrLauncherService started")

                // Start the KeepScreenOnService to maintain screen awake with overlay
                try {
                    val keepScreenIntent = Intent(this, KeepScreenOnService::class.java)
                    startService(keepScreenIntent)
                    Log.d("nvm", "KeepScreenOnService started successfully")
                } catch (e: Exception) {
                    Log.e("nvm", "Failed to start KeepScreenOnService: ${e.message}", e)
                }
            }
            ACTION_STOP -> {
                isRunning = false
                handler.removeCallbacks(launchRunnable)
                stopForegroundServiceInternal()

                Log.d("nvm", "FiverrLauncherService stopped")

                // Stop the KeepScreenOnService
                try {
                    val keepScreenIntent = Intent(this, KeepScreenOnService::class.java)
                    stopService(keepScreenIntent)
                    Log.d("nvm", "KeepScreenOnService stopped")
                } catch (e: Exception) {
                    Log.e("nvm", "Failed to stop KeepScreenOnService: ${e.message}", e)
                }
            }
        }
        return START_STICKY
    }

    private fun startForegroundServiceInternal() {
        startForeground(NOTIFICATION_ID, createNotification())
        Toast.makeText(this, "Fiverr Support Service Started", Toast.LENGTH_SHORT).show()
    }

    private fun stopForegroundServiceInternal() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Toast.makeText(this, "Fiverr Support Service Stopped", Toast.LENGTH_SHORT).show()
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Fiverr Support")
            .setContentText("Service running - keeping screen awake & opening Fiverr")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Fiverr Support Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when Fiverr support service is running"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun launchFiverrApp() {
        try {
            val intent = packageManager.getLaunchIntentForPackage("com.fiverr.fiverr")
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                startActivity(intent)
            } else {
                Log.w("nvm", "Fiverr app package not found")
            }
        } catch (e: Exception) {
            Log.e("nvm", "Error launching Fiverr app: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "FiverrSupportChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
    }
}
