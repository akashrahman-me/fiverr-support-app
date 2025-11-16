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
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat

class FiverrLauncherService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private val launchInterval = 5000L
    private var wakeLock: PowerManager.WakeLock? = null

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
        Log.d("nvm", "FiverrLauncherService created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundService()
                isRunning = true
                handler.post(launchRunnable)
            }

            ACTION_STOP -> {
                stopForegroundService()
            }
        }
        return START_STICKY
    }

    @Suppress("DEPRECATION")
    private fun startForegroundService() {
        // Acquire wake lock to keep screen on
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager

        // Use SCREEN_BRIGHT_WAKE_LOCK to keep screen on at full brightness
        // Combined with ACQUIRE_CAUSES_WAKEUP to turn on screen if off
        // Combined with ON_AFTER_RELEASE to keep screen on briefly after release
        wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
            PowerManager.ACQUIRE_CAUSES_WAKEUP or
            PowerManager.ON_AFTER_RELEASE,
            "FiverrSupport::ScreenWakeLock"
        )

        // Set reference counted to false so multiple acquire calls don't require multiple releases
        wakeLock?.setReferenceCounted(false)

        // Acquire without timeout for indefinite screen on
        // Note: This will keep screen on until explicitly released
        wakeLock?.acquire()

        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        Toast.makeText(this, "Fiverr Support Service Started - Screen will stay on", Toast.LENGTH_SHORT).show()
        Log.d("nvm", "WakeLock acquired - screen will stay on indefinitely")
    }

    private fun stopForegroundService() {
        isRunning = false
        handler.removeCallbacks(launchRunnable)

        // Release wake lock to allow screen to turn off
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d("nvm", "WakeLock released - screen can turn off now")
            }
        }
        wakeLock = null

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
            .setContentText("Service is running - Screen will stay on")
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
                Log.d("nvm", "Fiverr app launched successfully")
            } else {
                Log.w("nvm", "Fiverr app package not found")
            }
        } catch (e: Exception) {
            Log.e("nvm", "Error launching Fiverr app: ${e.message}")
            // Silently fail, service continues running
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacks(launchRunnable)

        // Release wake lock if still held
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null

        Log.d("nvm", "FiverrLauncherService destroyed")
    }

    companion object {
        const val CHANNEL_ID = "FiverrSupportChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
    }
}

