package com.akash.fiverrsupport

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground service that wakes screen when it turns OFF.
 * Keeps the screen awake to prevent auto-lock.
 */
class ScreenWakeService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var powerManager: PowerManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var running = false

    private val pulseRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            // Schedule next pulse to keep wake lock active
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("nvm", "ScreenWakeService onCreate() called")

        try {
            powerManager = getSystemService(POWER_SERVICE) as PowerManager
            Log.d("nvm", "PowerManager initialized, screen interactive: ${powerManager?.isInteractive}")

            acquirePartialWakeLock()
            createChannel()

            // Start foreground BEFORE any other work
            val notification = buildNotification()
            startForeground(NOTIFICATION_ID, notification)
            Log.d("nvm", "ScreenWakeService started as foreground with notification ID $NOTIFICATION_ID")

            running = true
            handler.post(pulseRunnable)
            Log.d("nvm", "ScreenWakeService started successfully, pulse loop initiated")
        } catch (e: Exception) {
            Log.e("nvm", "ScreenWakeService onCreate failed: ${e.message}", e)
            stopSelf()
        }
    }

    @Suppress("DEPRECATION")
    private fun acquirePartialWakeLock() {
        try {
            // CRITICAL: Use SCREEN_DIM_WAKE_LOCK + ACQUIRE_CAUSES_WAKEUP to turn screen on
            // This wakes the screen when it turns off, regardless of vibration setting
            wakeLock = powerManager?.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "FiverrSupport:ScreenWakeWakeLock"
            )
            wakeLock?.acquire(2 * 60 * 60 * 1000L) // 2h safety
            Log.d("nvm", "Screen wake lock acquired (SCREEN_DIM + ACQUIRE_CAUSES_WAKEUP) - screen will wake up")
        } catch (e: Exception) {
            Log.e("nvm", "Failed acquiring wake lock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try { wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(CHANNEL_ID, "Screen Wake Alert", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Wakes screen when it turns off"
                    enableVibration(false)
                    setSound(null, null)
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Service Active: Screen Wake Alert")
            .setContentText("Screen wake service is active")
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        handler.removeCallbacks(pulseRunnable)
        releaseWakeLock()
        Log.d("nvm", "ScreenWakeService destroyed")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // If killed, do not restart automatically (main service will manage)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "VibrationForegroundChannel"
        private const val NOTIFICATION_ID = 100
    }
}

