package com.akash.fiverrsupport

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat

class FiverrLauncherService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var launchInterval = 20000L // Default 20 seconds

    // Screen wake-lock components
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
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
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // Get interval from intent, default to 20 seconds
                launchInterval = intent.getLongExtra(EXTRA_INTERVAL, 20000L)

                startForegroundServiceInternal()
                isRunning = true
                handler.post(launchRunnable)

                Log.d("nvm", "FiverrLauncherService started with interval: ${launchInterval}ms")

                // Create overlay and acquire wake lock
                createOverlay()
                acquireWakeLock()
            }
            ACTION_STOP -> {
                isRunning = false
                handler.removeCallbacks(launchRunnable)

                // Remove overlay and release wake lock
                removeOverlay()
                releaseWakeLock()

                stopForegroundServiceInternal()
                Log.d("nvm", "FiverrLauncherService stopped")
            }
            ACTION_UPDATE_INTERVAL -> {
                // Update interval without restarting service
                launchInterval = intent.getLongExtra(EXTRA_INTERVAL, 20000L)
                Log.d("nvm", "Interval updated to: ${launchInterval}ms")
                // Cancel current scheduled task and reschedule with new interval
                handler.removeCallbacks(launchRunnable)
                if (isRunning) {
                    handler.post(launchRunnable)
                }
            }
        }
        return START_STICKY
    }

    private fun createOverlay() {
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            overlayView = View(this)

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            }

            val params = WindowManager.LayoutParams(
                1, // 1 pixel width
                1, // 1 pixel height
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
            }

            windowManager?.addView(overlayView, params)
            Log.d("nvm", "Overlay window created with FLAG_KEEP_SCREEN_ON")
        } catch (e: Exception) {
            Log.e("nvm", "Error creating overlay: ${e.message}", e)
        }
    }

    private fun removeOverlay() {
        try {
            if (overlayView != null) {
                windowManager?.removeView(overlayView)
                overlayView = null
            }
        } catch (e: Exception) {
            Log.e("nvm", "Error removing overlay: ${e.message}")
        }
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK,
                "FiverrSupport:KeepScreenOnWakeLock"
            )
            wakeLock?.acquire(10 * 60 * 60 * 1000L) // 10 hour timeout
            Log.d("nvm", "WakeLock acquired")
        } catch (e: Exception) {
            Log.e("nvm", "Error acquiring wake lock: ${e.message}", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.release()
            wakeLock = null
        } catch (e: Exception) {
            Log.e("nvm", "Error releasing wake lock: ${e.message}")
        }
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

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Service continues running even when app is removed from recents
        // START_STICKY in onStartCommand ensures it restarts if killed
        Log.d("nvm", "App removed from recents, but service continues running")
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacks(launchRunnable)
        removeOverlay()
        releaseWakeLock()
        Log.d("nvm", "FiverrLauncherService destroyed")
    }

    companion object {
        const val CHANNEL_ID = "FiverrSupportChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_UPDATE_INTERVAL = "ACTION_UPDATE_INTERVAL"
        const val EXTRA_INTERVAL = "EXTRA_INTERVAL"
    }
}
