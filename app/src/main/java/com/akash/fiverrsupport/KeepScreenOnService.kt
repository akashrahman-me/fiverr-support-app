package com.akash.fiverrsupport

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

class KeepScreenOnService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("nvm", "KeepScreenOnService created")
        createOverlay()
        acquireWakeLock()
    }

    private fun createOverlay() {
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

            // Create a tiny transparent view
            overlayView = View(this)

            // Layout params for overlay window
            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            }

            val params = WindowManager.LayoutParams(
                1, // width - 1 pixel
                1, // height - 1 pixel
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

            // Add the view to window manager
            windowManager?.addView(overlayView, params)
            Log.d("nvm", "Overlay window created with FLAG_KEEP_SCREEN_ON")

        } catch (e: Exception) {
            Log.e("nvm", "Error creating overlay: ${e.message}", e)
        }
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK,
                "FiverrSupport:KeepScreenOnWakeLock"
            )
            // Acquire with 10 hour timeout (will be renewed by the overlay)
            wakeLock?.acquire(10 * 60 * 60 * 1000L)
            Log.d("nvm", "WakeLock acquired")
        } catch (e: Exception) {
            Log.e("nvm", "Error acquiring wake lock: ${e.message}", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("nvm", "KeepScreenOnService started")
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d("nvm", "KeepScreenOnService destroyed")

        // Remove overlay
        try {
            if (overlayView != null) {
                windowManager?.removeView(overlayView)
                overlayView = null
            }
        } catch (e: Exception) {
            Log.e("nvm", "Error removing overlay: ${e.message}")
        }

        // Release wake lock
        try {
            wakeLock?.release()
            wakeLock = null
        } catch (e: Exception) {
            Log.e("nvm", "Error releasing wake lock: ${e.message}")
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

