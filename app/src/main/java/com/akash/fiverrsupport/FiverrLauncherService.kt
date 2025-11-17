package com.akash.fiverrsupport

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.VibrationAttributes
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

class FiverrLauncherService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var isPaused = false // New: Track if service is paused due to user interaction
    private var launchInterval = 20000L // Default 20 seconds
    private var lastUserInteractionTime = 0L
    private val idleTimeout = 60000L // 1 minute = 60 seconds

    // Screen wake-lock components
    private var windowManager: WindowManager? = null
    private var overlayView: CircularTimerView? = null
    private var touchDetectorView: TouchDetectorView? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var vibrator: Vibrator? = null
    private var isScreenOn = true
    private var isVibrationServiceRunning = false // Track if we intentionally started vibration
    private var originalBrightness: Int = -1 // Store original brightness to restore later

    private var nextLaunchTime = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Initialize vibrator (modern API)
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getSystemService(Vibrator::class.java)
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }

        // Register screen state receiver
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT) // Fires when user unlocks device
        }
        registerReceiver(screenStateReceiver, filter)

        // Check current screen state
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        isScreenOn = powerManager.isInteractive

        Log.d("nvm", "Service created, screen is ${if (isScreenOn) "ON" else "OFF"}")
    }

    // Screen state receiver to detect screen on/off
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    Log.d("nvm", "Screen turned OFF - pausing service and starting vibration")

                    // Pause the service (like touch does) but without idle checker
                    // We'll resume after 3 seconds on unlock instead
                    if (!isPaused && isRunning) {
                        pauseService(startIdleChecker = false) // Don't start idle checker for screen lock
                        Log.d("nvm", "Service paused due to screen lock")
                    }

                    // Start vibration alert
                    startVibrationAlert()
                }
                Intent.ACTION_USER_PRESENT -> {
                    // USER_PRESENT fires when user unlocks the device (most reliable)
                    isScreenOn = true
                    val unlockTime = System.currentTimeMillis()
                    Log.d("nvm", "USER_PRESENT received - user unlocked device at $unlockTime")

                    // Stop vibration
                    if (isVibrationServiceRunning) {
                        Log.d("nvm", "Stopping vibration after unlock")
                        stopVibrationAlert()
                    }

                    // Resume service after 3 seconds (if paused and running)
                    if (isPaused && isRunning) {
                        Log.d("nvm", "Scheduling service resume for 3 seconds from now...")
                        handler.postDelayed({
                            if (isPaused && isRunning) {
                                val resumeTime = System.currentTimeMillis()
                                Log.d("nvm", "Auto-resuming service 3 seconds after unlock (delay was ${resumeTime - unlockTime}ms)")
                                resumeService()
                            } else {
                                Log.d("nvm", "Resume cancelled - service state changed (isPaused=$isPaused, isRunning=$isRunning)")
                            }
                        }, 3000) // Resume after 3 seconds
                    } else {
                        Log.d("nvm", "Resume not scheduled (isPaused=$isPaused, isRunning=$isRunning)")
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    val screenOnTime = System.currentTimeMillis()

                    // Use a short delay to let keyguard state settle
                    handler.postDelayed({
                        val keyguardManager = getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager
                        val isLocked = keyguardManager.isKeyguardLocked

                        Log.d("nvm", "Screen turned ON - isLocked: $isLocked, isVibrationServiceRunning: $isVibrationServiceRunning")

                        if (!isLocked && isVibrationServiceRunning) {
                            // Screen is ON and unlocked - stop vibration
                            Log.d("nvm", "Screen unlocked (via SCREEN_ON) - stopping vibration alert")
                            stopVibrationAlert()

                            // Resume service after 3 seconds (if paused and running)
                            if (isPaused && isRunning) {
                                Log.d("nvm", "Scheduling service resume for 3 seconds from now (via SCREEN_ON)...")
                                handler.postDelayed({
                                    if (isPaused && isRunning) {
                                        val resumeTime = System.currentTimeMillis()
                                        Log.d("nvm", "Auto-resuming service 3 seconds after unlock (delay was ${resumeTime - screenOnTime}ms)")
                                        resumeService()
                                    } else {
                                        Log.d("nvm", "Resume cancelled - service state changed (isPaused=$isPaused, isRunning=$isRunning)")
                                    }
                                }, 3000) // Resume after 3 seconds
                            }
                        } else if (isLocked) {
                            // Screen on but still locked (wake lock keeping it on for vibration)
                            Log.d("nvm", "Screen on but locked - vibration continues")
                        }
                    }, 500) // 500ms delay to ensure keyguard state is accurate
                }
            }
        }
    }

    private val launchRunnable = object : Runnable {
        override fun run() {
            if (isRunning && !isPaused) {
                launchFiverrApp()
                nextLaunchTime = System.currentTimeMillis() + launchInterval
            }
            if (isRunning) {
                handler.postDelayed(this, launchInterval)
            }
        }
    }

    // Check if user has been idle for 1 minute, then auto-resume
    private val idleCheckerRunnable = object : Runnable {
        override fun run() {
            if (isRunning && isPaused) {
                val idleTime = System.currentTimeMillis() - lastUserInteractionTime
                if (idleTime >= idleTimeout) {
                    Log.d("nvm", "User idle for 1 minute - auto-resuming service")
                    resumeService()
                }
            }
            if (isRunning) {
                handler.postDelayed(this, 1000) // Check every second
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle null intent when service is restarted by Android after being killed
        if (intent == null) {
            Log.d("nvm", "Service restarted by Android (null intent) - restoring from SharedPreferences")
            val prefs = getSharedPreferences("FiverrSupportPrefs", MODE_PRIVATE)
            val wasEnabled = prefs.getBoolean("service_enabled", false)
            val savedInterval = prefs.getLong("service_interval", 20000L)

            if (wasEnabled) {
                Log.d("nvm", "Restoring service with interval: ${savedInterval}ms")
                launchInterval = savedInterval
                startForegroundServiceInternal()
                isRunning = true
                handler.post(launchRunnable)
                createOverlay()
                acquireWakeLock()
                Log.d("nvm", "Service restored successfully")

                // Reduce brightness when service is restored (if screen is on)
                if (isScreenOn) {
                    Log.d("nvm", "Reducing brightness on service restore")
                    reduceBrightness()
                }

                // Check if screen is off and start vibration if needed
                if (!isScreenOn) {
                    Log.d("nvm", "Service restored with screen OFF - starting vibration alert")
                    startVibrationAlert()
                }
            } else {
                Log.d("nvm", "Service was disabled by user, not restoring")
                stopSelf()
            }
            return START_STICKY
        }

        when (intent.action) {
            ACTION_START -> {
                // Get interval from intent, default to 20 seconds
                launchInterval = intent.getLongExtra(EXTRA_INTERVAL, 20000L)

                // Save service state as enabled - use commit() for immediate write
                val prefs = getSharedPreferences("FiverrSupportPrefs", MODE_PRIVATE)
                prefs.edit()
                    .putBoolean("service_enabled", true)
                    .putLong("service_interval", launchInterval)
                    .commit() // Use commit() instead of apply() for immediate write

                Log.d("nvm", "Saved state: service_enabled=true, interval=${launchInterval}ms")

                startForegroundServiceInternal()
                isRunning = true
                handler.post(launchRunnable)

                Log.d("nvm", "FiverrLauncherService started with interval: ${launchInterval}ms")

                // Create overlay and acquire wake lock
                createOverlay()
                acquireWakeLock()

                // Reduce brightness when service starts (if screen is on)
                if (isScreenOn) {
                    Log.d("nvm", "Reducing brightness on service start")
                    reduceBrightness()
                }

                // Check if screen is off and start vibration if needed
                if (!isScreenOn) {
                    Log.d("nvm", "Service started with screen OFF - starting vibration alert")
                    startVibrationAlert()
                }
            }
            ACTION_STOP -> {
                isRunning = false
                handler.removeCallbacks(launchRunnable)

                // Save service state as disabled - use commit() for immediate write
                val prefs = getSharedPreferences("FiverrSupportPrefs", MODE_PRIVATE)
                prefs.edit()
                    .putBoolean("service_enabled", false)
                    .commit() // Use commit() instead of apply()

                Log.d("nvm", "Saved state: service_enabled=false")

                // Stop vibration, remove overlay and release wake lock
                stopVibrationAlert()
                removeOverlay()
                releaseWakeLock()

                stopForegroundServiceInternal()
                Log.d("nvm", "FiverrLauncherService stopped")
            }
            ACTION_UPDATE_INTERVAL -> {
                // Update interval without restarting service
                launchInterval = intent.getLongExtra(EXTRA_INTERVAL, 20000L)

                // Save updated interval - use commit() for immediate write
                val prefs = getSharedPreferences("FiverrSupportPrefs", MODE_PRIVATE)
                prefs.edit()
                    .putLong("service_interval", launchInterval)
                    .commit()

                Log.d("nvm", "Interval updated to: ${launchInterval}ms")
                // Cancel current scheduled task and reschedule with new interval
                handler.removeCallbacks(launchRunnable)
                if (isRunning) {
                    // Restart timer with new interval
                    overlayView?.stopTimer()
                    overlayView?.startTimer(launchInterval)
                    handler.post(launchRunnable)
                }
            }
        }
        return START_STICKY
    }

    private fun createOverlay() {
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            }

            // Create touch detector overlay (full screen, invisible)
            touchDetectorView = TouchDetectorView(this) { userTouched() }
            val touchParams = WindowManager.LayoutParams(
                1, // Very small, just 1 pixel
                1, // Very small, just 1 pixel
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
            }
            windowManager?.addView(touchDetectorView, touchParams)
            Log.d("nvm", "Touch detector overlay created")

            // Create circular timer overlay
            overlayView = CircularTimerView(this)
            val overlaySize = 120 // 120dp for circular timer
            val params = WindowManager.LayoutParams(
                overlaySize,
                overlaySize,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = 20 // 20px from right edge
                y = 100 // 100px from top
            }

            windowManager?.addView(overlayView, params)
            overlayView?.startTimer(launchInterval)
            Log.d("nvm", "Circular timer overlay created")
        } catch (e: Exception) {
            Log.e("nvm", "Error creating overlay: ${e.message}", e)
        }
    }

    private fun removeOverlay() {
        try {
            if (touchDetectorView != null) {
                windowManager?.removeView(touchDetectorView)
                touchDetectorView = null
            }
            if (overlayView != null) {
                overlayView?.stopTimer()
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

    // Called when user touches the screen
    private fun userTouched() {
        lastUserInteractionTime = System.currentTimeMillis()

        if (!isPaused) {
            pauseService()
        }
    }

    // Pause the service (stop opening Fiverr, turn timer red)
    private fun pauseService(startIdleChecker: Boolean = true) {
        isPaused = true
        overlayView?.setPaused(true) // Turn timer red

        // Restore brightness when paused by touch (not by screen lock)
        if (startIdleChecker) {
            restoreBrightness()
            handler.post(idleCheckerRunnable) // Start idle checker only for touch events
        }

        Log.d("nvm", "Service paused ${if (startIdleChecker) "with idle checker" else "without idle checker"}")
    }

    // Resume the service (start opening Fiverr, turn timer green)
    private fun resumeService() {
        isPaused = false
        overlayView?.setPaused(false) // Turn timer green
        overlayView?.resetTimer() // Reset timer to start fresh
        handler.removeCallbacks(idleCheckerRunnable) // Stop idle checker

        // Reduce brightness to 0 when service resumes
        reduceBrightness()

        Log.d("nvm", "Service resumed")
    }

    // Save current brightness and reduce to 0
    private fun reduceBrightness() {
        try {
            // Save original brightness only once
            if (originalBrightness == -1) {
                originalBrightness = Settings.System.getInt(
                    contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS
                )
                Log.d("nvm", "Original brightness saved: $originalBrightness")
            }

            // Method 1: Update Settings.System (for system-wide)
            Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                0
            )

            // Method 2: Update Window brightness (CRITICAL for Android 13+)
            // This makes the change take effect immediately
            overlayView?.let { view ->
                val layoutParams = view.layoutParams as? WindowManager.LayoutParams
                layoutParams?.screenBrightness = 0f // 0f = minimum brightness
                windowManager?.updateViewLayout(view, layoutParams)
                Log.d("nvm", "Window brightness reduced to 0")
            }

            // Also update touch detector view
            touchDetectorView?.let { view ->
                val layoutParams = view.layoutParams as? WindowManager.LayoutParams
                layoutParams?.screenBrightness = 0f
                windowManager?.updateViewLayout(view, layoutParams)
            }

            Log.d("nvm", "Brightness reduced to 0 (Settings + Window)")
        } catch (e: Exception) {
            Log.e("nvm", "Error reducing brightness: ${e.message}", e)
            Log.w("nvm", "WRITE_SETTINGS permission may be required")
        }
    }

    // Restore original brightness
    private fun restoreBrightness() {
        try {
            if (originalBrightness != -1) {
                // Method 1: Restore Settings.System
                Settings.System.putInt(
                    contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    originalBrightness
                )

                // Method 2: Restore Window brightness (CRITICAL for Android 13+)
                // Use BRIGHTNESS_OVERRIDE_NONE to let system control brightness
                overlayView?.let { view ->
                    val layoutParams = view.layoutParams as? WindowManager.LayoutParams
                    layoutParams?.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    windowManager?.updateViewLayout(view, layoutParams)
                    Log.d("nvm", "Window brightness restored to system default")
                }

                touchDetectorView?.let { view ->
                    val layoutParams = view.layoutParams as? WindowManager.LayoutParams
                    layoutParams?.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    windowManager?.updateViewLayout(view, layoutParams)
                }

                Log.d("nvm", "Brightness restored to: $originalBrightness (Settings + Window)")
                originalBrightness = -1 // Reset flag
            }
        } catch (e: Exception) {
            Log.e("nvm", "Error restoring brightness: ${e.message}", e)
        }
    }

    // Helper to start both vibration engines
    private fun startVibrationAlert() {
        try {
            Log.d("nvm", "startVibrationAlert() called - isRunning: $isRunning, isScreenOn: $isScreenOn")
            if (isRunning) {
                isVibrationServiceRunning = true // Set flag BEFORE starting service
                val serviceIntent = Intent(this, VibrationService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                Log.d("nvm", "Started VibrationService via ${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) "startForegroundService" else "startService"}")
            } else {
                Log.w("nvm", "Cannot start VibrationService - isRunning is false")
            }
        } catch (e: Exception) {
            Log.e("nvm", "Failed to start VibrationService: ${e.message}", e)
            isVibrationServiceRunning = false
        }
    }

    // Helper to stop both vibration engines
    private fun stopVibrationAlert() {
        try {
            isVibrationServiceRunning = false // Clear flag BEFORE stopping service
            stopService(Intent(this, VibrationService::class.java))
            Log.d("nvm", "Stopped VibrationService")
        } catch (e: Exception) {
            Log.e("nvm", "Failed to stop VibrationService: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Service continues running even when app is removed from recents
        // START_STICKY in onStartCommand ensures it restarts if killed
        Log.d("nvm", "App removed from recents, but service continues running")
    }

    @SuppressLint("ScheduleExactAlarm")
    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacks(launchRunnable)
        removeOverlay()
        releaseWakeLock()
        stopVibrationAlert()

        // Unregister screen state receiver
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (e: Exception) {
            Log.e("nvm", "Error unregistering receiver: ${e.message}")
        }

        // Check if service was enabled by user
        val prefs = getSharedPreferences("FiverrSupportPrefs", MODE_PRIVATE)
        val wasEnabled = prefs.getBoolean("service_enabled", false)

        if (wasEnabled) {
            Log.d("nvm", "Service destroyed but was enabled - scheduling restart")

            // Use AlarmManager for reliable restart on Android 13+
            try {
                val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
                val restartIntent = Intent(this, ServiceRestartReceiver::class.java).apply {
                    action = "com.akash.fiverrsupport.ACTION_RESTART_SERVICE"
                    putExtra("interval", prefs.getLong("service_interval", 20000L))
                }

                val pendingIntent = PendingIntent.getBroadcast(
                    this,
                    0,
                    restartIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                // Schedule restart after 2 seconds
                val triggerTime = System.currentTimeMillis() + 2000
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )

                Log.d("nvm", "Restart scheduled via AlarmManager")
            } catch (e: Exception) {
                Log.e("nvm", "Failed to schedule AlarmManager: ${e.message}")
            }
        }

        Log.d("nvm", "FiverrLauncherService destroyed")
    }

    companion object {
        const val CHANNEL_ID = "FiverrSupportChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_UPDATE_INTERVAL = "ACTION_UPDATE_INTERVAL"
        const val EXTRA_INTERVAL = "EXTRA_INTERVAL"
        const val VIBRATION_CHANNEL_ID = "FiverrSupportVibrationChannel"
        const val VIBRATION_NOTIFICATION_ID = 2
        const val VIBRATION_NOTIFICATION_ID_ALT = 3
    }
}

/**
 * Custom View that displays a beautiful circular countdown timer
 */
class CircularTimerView(context: android.content.Context) : View(context) {

    private val handler = Handler(Looper.getMainLooper())
    private var totalDuration = 20000L
    private var startTime = 0L
    private var pausedTime = 0L
    private var isRunning = false
    private var isPaused = false

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80000000") // Semi-transparent black
        style = Paint.Style.FILL
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4CAF50") // Green (active)
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
    }

    private val pausedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F44336") // Red (paused)
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val rectF = RectF()

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (isRunning && !isPaused) {
                invalidate() // Trigger redraw
                handler.postDelayed(this, 50) // Update every 50ms for smooth animation
            } else if (isPaused) {
                invalidate() // Still redraw to show paused state
                handler.postDelayed(this, 50)
            }
        }
    }

    fun startTimer(duration: Long) {
        totalDuration = duration
        startTime = System.currentTimeMillis()
        isRunning = true
        isPaused = false
        handler.post(updateRunnable)
    }

    fun stopTimer() {
        isRunning = false
        isPaused = false
        handler.removeCallbacks(updateRunnable)
    }

    fun setPaused(paused: Boolean) {
        if (paused && !isPaused) {
            // Entering pause - save current time
            pausedTime = System.currentTimeMillis() - startTime
            isPaused = true
            Log.d("nvm", "Timer paused at ${pausedTime}ms")
        } else if (!paused && isPaused) {
            // Exiting pause - not used here, use resetTimer instead
            isPaused = false
        }
        invalidate()
    }

    fun resetTimer() {
        startTime = System.currentTimeMillis()
        pausedTime = 0L
        isPaused = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()
        val centerX = width / 2
        val centerY = height / 2
        val radius = (Math.min(width, height) / 2) - 10

        // Draw background circle
        canvas.drawCircle(centerX, centerY, radius, backgroundPaint)

        // Calculate remaining time
        val elapsed = if (isPaused) {
            pausedTime // Use frozen time when paused
        } else {
            System.currentTimeMillis() - startTime
        }
        val remaining = (totalDuration - elapsed).coerceAtLeast(0)
        val progress = (remaining.toFloat() / totalDuration) * 360f

        // Draw progress arc
        rectF.set(
            centerX - radius + 5,
            centerY - radius + 5,
            centerX + radius - 5,
            centerY + radius - 5
        )

        // Use red paint if paused, green if active
        val paint = if (isPaused) pausedPaint else progressPaint
        canvas.drawArc(rectF, -90f, progress, false, paint)

        // Draw remaining time text
        val seconds = (remaining / 1000).toInt()
        val text = if (isPaused) "${seconds}s" else "${seconds}s"
        canvas.drawText(text, centerX, centerY + 8, textPaint)

        // Reset timer when it reaches 0 (only if not paused)
        if (remaining <= 0 && !isPaused) {
            startTime = System.currentTimeMillis()
        }
    }
}

/**
 * Invisible tiny overlay to detect user touches via FLAG_WATCH_OUTSIDE_TOUCH
 */
class TouchDetectorView(
    context: Context,
    private val onTouch: () -> Unit
) : View(context) {

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        // This will be called for outside touches due to FLAG_WATCH_OUTSIDE_TOUCH
        when (event?.action) {
            MotionEvent.ACTION_OUTSIDE -> {
                // User touched outside this tiny 1x1 view (anywhere on screen)
                onTouch()
            }
        }
        return false // Don't consume the touch event - let it pass through
    }
}
