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
 * Foreground service dedicated to continuous vibration while screen is OFF.
 * Uses manual one-shot pulses instead of repeating waveforms to avoid system throttling.
 */
class VibrationService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var vibrator: Vibrator? = null
    private var powerManager: PowerManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var running = false

    private val pulseRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            // Don't check screen state here - let FiverrLauncherService control when to stop
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
            // Schedule next pulse (400ms pulse + ~600ms gap = 1s cycle)
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("nvm", "VibrationService onCreate() called")

        try {
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
            // This is required for vibration to work on Android 10-14 when screen is off
            wakeLock = powerManager?.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "FiverrSupport:VibrationWakeLock"
            )
            wakeLock?.acquire(2 * 60 * 60 * 1000L) // 2h safety
            Log.d("nvm", "Screen wake lock acquired (SCREEN_DIM + ACQUIRE_CAUSES_WAKEUP) for vibration")
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
                val ch = NotificationChannel(CHANNEL_ID, "Continuous Vibration", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Keeps vibrating while screen is off"
                    enableVibration(false) // we handle pulses manually
                    setSound(null, null)
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Service Active: Continuous Vibration")
            .setContentText("Screen is off. Pulsing vibration to alert user.")
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

