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
import android.os.VibrationEffect
import android.os.VibrationAttributes
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground service that wakes screen when it turns OFF, with optional vibration.
 * Always wakes the screen, but vibration can be disabled via settings.
 */
class VibrationService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var vibrator: Vibrator? = null
    private var powerManager: PowerManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var running = false
    private var vibrationEnabled = false // Controls whether to vibrate

    private val pulseRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            // Only vibrate if vibrationEnabled is true
            if (vibrationEnabled) {
                try {
                    vibrator?.let { vib ->
                        if (vib.hasVibrator()) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                // Android 13+: Use VibrationAttributes with USAGE_ALARM
                                val effect = VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE)
                                val attrs = VibrationAttributes.Builder()
                                    .setUsage(VibrationAttributes.USAGE_ALARM)
                                    .build()
                                vib.vibrate(effect, attrs)
                                Log.d("nvm", "Vibration pulse emitted (API 33+ with USAGE_ALARM)")
                            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                // Android 8-12: Use VibrationEffect
                                val effect = VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE)
                                vib.vibrate(effect)
                                Log.d("nvm", "Vibration pulse emitted (API 26+)")
                            } else {
                                // Android 7 and below
                                @Suppress("DEPRECATION")
                                vib.vibrate(400)
                                Log.d("nvm", "Vibration pulse emitted (Legacy)")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("nvm", "Vibration pulse error: ${e.message}", e)
                }
            }
            // Schedule next pulse (even if not vibrating, to keep wake lock active)
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("nvm", "VibrationService onCreate() called")

        try {
            // Read vibration setting from SharedPreferences
            val prefs = getSharedPreferences("FiverrSupportPrefs", MODE_PRIVATE)
            vibrationEnabled = prefs.getBoolean("vibrate_on_screen_off", false)
            Log.d("nvm", "Vibration enabled from settings: $vibrationEnabled")

            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                getSystemService(Vibrator::class.java)
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
            Log.d("nvm", "Vibrator initialized: ${vibrator != null}, hasVibrator: ${vibrator?.hasVibrator()}")

            powerManager = getSystemService(POWER_SERVICE) as PowerManager
            Log.d("nvm", "PowerManager initialized, screen interactive: ${powerManager?.isInteractive}")

            acquirePartialWakeLock()
            createChannel()

            // Start foreground BEFORE any other work
            val notification = buildNotification()
            startForeground(NOTIFICATION_ID, notification)
            Log.d("nvm", "VibrationService started as foreground with notification ID $NOTIFICATION_ID")

            running = true
            handler.post(pulseRunnable)
            Log.d("nvm", "VibrationService started successfully, pulse loop initiated")
        } catch (e: Exception) {
            Log.e("nvm", "VibrationService onCreate failed: ${e.message}", e)
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
            Log.d("nvm", "Screen wake lock acquired (SCREEN_DIM + ACQUIRE_CAUSES_WAKEUP) - screen will wake up, vibration: $vibrationEnabled")
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
                    description = "Wakes screen when it turns off, with optional vibration"
                    enableVibration(false) // we handle pulses manually
                    setSound(null, null)
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun buildNotification(): Notification {
        val contentText = if (vibrationEnabled) {
            "Screen woke up with vibration alert"
        } else {
            "Screen woke up (vibration disabled)"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Service Active: Screen Wake Alert")
            .setContentText(contentText)
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
        Log.d("nvm", "VibrationService destroyed")
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

