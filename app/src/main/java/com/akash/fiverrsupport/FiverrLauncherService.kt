package com.akash.fiverrsupport

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Intent
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
    private var overlayView: CircularTimerView? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var nextLaunchTime = 0L

    private val launchRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                launchFiverrApp()
                nextLaunchTime = System.currentTimeMillis() + launchInterval
                handler.postDelayed(this, launchInterval)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
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

                // Remove overlay and release wake lock
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
            overlayView = CircularTimerView(this)

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            }

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

        // Check if service was enabled by user
        val prefs = getSharedPreferences("FiverrSupportPrefs", MODE_PRIVATE)
        val wasEnabled = prefs.getBoolean("service_enabled", false)

        if (wasEnabled) {
            Log.d("nvm", "Service destroyed but was enabled - scheduling restart")

            // Method 1: Use AlarmManager for reliable restart on Android 13+
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

            // Method 2: Use JobScheduler as backup (more reliable for process kills)
            try {
                val jobScheduler = getSystemService(JOB_SCHEDULER_SERVICE) as JobScheduler
                val componentName = ComponentName(
                    this.packageName,
                    "com.akash.fiverrsupport.ServiceRestartJobService"
                )
                val jobInfo = JobInfo.Builder(JOB_ID, componentName)
                    .setMinimumLatency(2000) // Wait 2 seconds
                    .setOverrideDeadline(5000) // Must run within 5 seconds
                    .setPersisted(true) // Survive reboots
                    .build()

                val result = jobScheduler.schedule(jobInfo)
                if (result == JobScheduler.RESULT_SUCCESS) {
                    Log.d("nvm", "Restart scheduled via JobScheduler (backup)")
                } else {
                    Log.e("nvm", "Failed to schedule JobScheduler")
                }
            } catch (e: Exception) {
                Log.e("nvm", "Failed to schedule JobScheduler: ${e.message}")
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
        const val JOB_ID = 1001
    }
}

/**
 * Custom View that displays a beautiful circular countdown timer
 */
class CircularTimerView(context: android.content.Context) : View(context) {

    private val handler = Handler(Looper.getMainLooper())
    private var totalDuration = 20000L
    private var startTime = 0L
    private var isRunning = false

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80000000") // Semi-transparent black
        style = Paint.Style.FILL
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4CAF50") // Green
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
            if (isRunning) {
                invalidate() // Trigger redraw
                handler.postDelayed(this, 50) // Update every 50ms for smooth animation
            }
        }
    }

    fun startTimer(duration: Long) {
        totalDuration = duration
        startTime = System.currentTimeMillis()
        isRunning = true
        handler.post(updateRunnable)
    }

    fun stopTimer() {
        isRunning = false
        handler.removeCallbacks(updateRunnable)
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
        val elapsed = System.currentTimeMillis() - startTime
        val remaining = (totalDuration - elapsed).coerceAtLeast(0)
        val progress = (remaining.toFloat() / totalDuration) * 360f

        // Draw progress arc
        rectF.set(
            centerX - radius + 5,
            centerY - radius + 5,
            centerX + radius - 5,
            centerY + radius - 5
        )

        // Draw arc from top (270 degrees) clockwise
        canvas.drawArc(rectF, -90f, progress, false, progressPaint)

        // Draw remaining time text
        val seconds = (remaining / 1000).toInt()
        val text = "${seconds}s"
        canvas.drawText(text, centerX, centerY + 8, textPaint)

        // Reset timer when it reaches 0
        if (remaining <= 0) {
            startTime = System.currentTimeMillis()
        }
    }
}

