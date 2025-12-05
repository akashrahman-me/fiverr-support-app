package com.akash.fiverrsupport

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.akash.fiverrsupport.utils.isAppInForeground
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Simplified Fiverr Launcher Service with floating status indicator
 *
 * Flow:
 * 1. When screen turns OFF, start countdown timer
 * 2. After interval expires, wake screen (with low brightness)
 * 3. Open Fiverr (or scroll if already open)
 * 4. Turn screen OFF again (brightness resets automatically)
 * 5. Repeat
 */
class FiverrLauncherService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var launchInterval = 30000L // Default 30 seconds
    private var screenOffTime = 0L // When screen turned off
    private var isScreenOn = true
    private var wakeLock: PowerManager.WakeLock? = null
    private var isPerformingAction = false
    
    // Low brightness for automated actions
    private var originalBrightness: Int = -1
    private var originalBrightnessMode: Int = -1

    // Floating status overlay
    private var windowManager: WindowManager? = null
    private var statusOverlay: StatusOverlayView? = null
    private var nextActionTime = 0L // When next action will happen

    // Screen state receiver
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    screenOffTime = System.currentTimeMillis()
                    Log.d("nvm", "🔒 Screen OFF - starting countdown timer (${launchInterval / 1000}s)")

                    scheduleNextAction()
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    Log.d("nvm", "💡 Screen ON")
                    
                    // Cancel scheduled action when user turns on screen
                    if (!isPerformingAction) {
                        cancelScheduledAction()
                        Log.d("nvm", "Cancelled pending action - user turned on screen")
                    }
                }
                Intent.ACTION_USER_PRESENT -> {
                    Log.d("nvm", "🔓 User unlocked device")
                    if (!isPerformingAction) {
                        cancelScheduledAction()
                        Log.d("nvm", "Cancelled pending action - user is active")
                    }
                }
            }
        }
    }

    // The main action runnable
    private val actionRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) {
                Log.d("nvm", "Service not running, skipping action")
                return
            }

            Log.d("nvm", "⏰ Timer expired - checking internet before executing")
            
            // Check internet connectivity first
            checkInternetConnectivity { hasInternet ->
                if (!hasInternet) {
                    Log.d("nvm", "📵 No internet - skipping action, will retry at next interval")
                    // Schedule next action without waking screen
                    if (!isScreenOn) {
                        scheduleNextAction()
                    }
                    return@checkInternetConnectivity
                }
                
                Log.d("nvm", "📶 Internet available - executing action sequence")
                isPerformingAction = true

                // Step 1: Set low brightness BEFORE waking screen (screen is still off)
                setLowBrightness()

                // Step 2: Wake the screen (will wake with low brightness already set)
                wakeScreen()

                // Step 3: Wait for screen to fully wake, then do Fiverr action
                handler.postDelayed({
                    performFiverrAction {
                        // Step 4: After action completes, turn screen off first, then restore brightness
                        handler.postDelayed({
                            turnScreenOff()
                            
                            // Restore brightness AFTER screen is off (user won't see the change)
                            handler.postDelayed({
                                restoreBrightness()
                                isPerformingAction = false
                                // Screen off receiver will schedule next action
                            }, 500)
                        }, 2000)
                    }
                }, 1000)
            }
        }
    }



    override fun onCreate() {
        super.onCreate()
        Log.d("nvm", "FiverrLauncherService onCreate")

        createNotificationChannel()

        // Register screen state receiver
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenStateReceiver, filter)

        // Check current screen state
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        isScreenOn = powerManager.isInteractive

        Log.d("nvm", "Service created, screen is ${if (isScreenOn) "ON" else "OFF"}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            Log.d("nvm", "Service restarted by Android - restoring state")
            val prefs = getSharedPreferences("FiverrSupportPrefs", MODE_PRIVATE)
            val wasEnabled = prefs.getBoolean("service_enabled", false)
            launchInterval = prefs.getLong("service_interval", 30000L)

            if (wasEnabled) {
                startForegroundServiceInternal()
                isRunning = true
                createStatusOverlay()

                if (!isScreenOn) {
                    screenOffTime = System.currentTimeMillis()
                    scheduleNextAction()
                }
                Log.d("nvm", "Service restored with interval: ${launchInterval / 1000}s")
            } else {
                stopSelf()
            }
            return START_STICKY
        }

        when (intent.action) {
            ACTION_START -> {
                launchInterval = intent.getLongExtra(EXTRA_INTERVAL, 30000L)

                val prefs = getSharedPreferences("FiverrSupportPrefs", MODE_PRIVATE)
                prefs.edit()
                    .putBoolean("service_enabled", true)
                    .putLong("service_interval", launchInterval)
                    .commit()

                startForegroundServiceInternal()
                isRunning = true
                createStatusOverlay()

                Log.d("nvm", "✅ Service started with interval: ${launchInterval / 1000}s")

                if (!isScreenOn) {
                    screenOffTime = System.currentTimeMillis()
                    scheduleNextAction()
                }
            }
            ACTION_STOP -> {
                isRunning = false
                cancelScheduledAction()
                removeStatusOverlay()

                val prefs = getSharedPreferences("FiverrSupportPrefs", MODE_PRIVATE)
                prefs.edit()
                    .putBoolean("service_enabled", false)
                    .commit()

                stopForegroundServiceInternal()
                Log.d("nvm", "🛑 Service stopped")
            }
            ACTION_UPDATE_INTERVAL -> {
                launchInterval = intent.getLongExtra(EXTRA_INTERVAL, 30000L)

                val prefs = getSharedPreferences("FiverrSupportPrefs", MODE_PRIVATE)
                prefs.edit()
                    .putLong("service_interval", launchInterval)
                    .commit()

                Log.d("nvm", "⏱️ Interval updated to: ${launchInterval / 1000}s")

                if (!isScreenOn && isRunning) {
                    cancelScheduledAction()
                    scheduleNextAction()
                }
            }
        }
        return START_STICKY
    }

    private fun createStatusOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Log.w("nvm", "No overlay permission - status indicator disabled")
            return
        }

        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            statusOverlay = StatusOverlayView(this)

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = 16
                y = 100
            }

            windowManager?.addView(statusOverlay, params)
            Log.d("nvm", "Status overlay created")
        } catch (e: Exception) {
            Log.e("nvm", "Failed to create status overlay: ${e.message}", e)
        }
    }

    private fun removeStatusOverlay() {
        try {
            statusOverlay?.let {
                windowManager?.removeView(it)
            }
            statusOverlay = null
        } catch (e: Exception) {
            Log.e("nvm", "Error removing status overlay: ${e.message}")
        }
    }

    /**
     * Sets screen brightness to minimum (native system setting).
     * This actually reduces LCD backlight power, saving battery.
     */
    private fun setLowBrightness() {
        if (!Settings.System.canWrite(this)) {
            Log.w("nvm", "No WRITE_SETTINGS permission - low brightness disabled")
            return
        }

        try {
            // Save current brightness settings
            originalBrightnessMode = Settings.System.getInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            )
            originalBrightness = Settings.System.getInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                128
            )

            // Disable auto brightness
            Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            
            // Set brightness to minimum (0-255, we use 1 as minimum)
            Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                1
            )
            
            Log.d("nvm", "🔅 Screen brightness set to minimum")
        } catch (e: Exception) {
            Log.e("nvm", "Failed to set low brightness: ${e.message}", e)
        }
    }

    private fun restoreBrightness() {
        if (originalBrightness == -1) return
        
        try {
            if (Settings.System.canWrite(this)) {
                Settings.System.putInt(
                    contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    originalBrightnessMode
                )
                Settings.System.putInt(
                    contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    originalBrightness
                )
                Log.d("nvm", "🔆 Screen brightness restored to $originalBrightness")
            }
            
            originalBrightness = -1
            originalBrightnessMode = -1
        } catch (e: Exception) {
            Log.e("nvm", "Error restoring brightness: ${e.message}")
        }
    }

    private fun scheduleNextAction() {
        if (!isRunning) return

        cancelScheduledAction()
        nextActionTime = System.currentTimeMillis() + launchInterval
        handler.postDelayed(actionRunnable, launchInterval)
        Log.d("nvm", "📅 Next action scheduled in ${launchInterval / 1000}s")
    }

    private fun cancelScheduledAction() {
        handler.removeCallbacks(actionRunnable)
        nextActionTime = 0
        Log.d("nvm", "🚫 Cancelled scheduled action")
    }

    /**
     * Check actual internet connectivity by making HTTP request to reliable endpoints.
     * This handles cases where device is connected to WiFi but WiFi has no internet.
     */
    private fun checkInternetConnectivity(callback: (Boolean) -> Unit) {
        // First do a quick network capability check
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        
        val hasNetworkConnection = capabilities?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } ?: false
        
        if (!hasNetworkConnection) {
            Log.d("nvm", "No network connection detected")
            handler.post { callback(false) }
            return
        }
        
        // Network says it's connected - verify with actual HTTP request
        thread {
            val hasInternet = tryHttpConnection("https://connectivitycheck.gstatic.com/generate_204") ||
                              tryHttpConnection("https://www.cloudflare.com/cdn-cgi/trace") ||
                              tryHttpConnection("https://clients3.google.com/generate_204")
            
            handler.post { callback(hasInternet) }
        }
    }
    
    private fun tryHttpConnection(urlString: String): Boolean {
        return try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.requestMethod = "HEAD"
            connection.useCaches = false
            
            val responseCode = connection.responseCode
            connection.disconnect()
            
            Log.d("nvm", "Internet check $urlString: $responseCode")
            responseCode in 200..399
        } catch (e: Exception) {
            Log.d("nvm", "Internet check failed for $urlString: ${e.message}")
            false
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun wakeScreen() {
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager

            // Use SCREEN_DIM_WAKE_LOCK to avoid forcing full brightness on wake
            @Suppress("DEPRECATION")
            wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                "FiverrSupport:WakeScreen"
            )
            wakeLock?.acquire(10000)

            Log.d("nvm", "💡 Screen woken up (dim mode)")
        } catch (e: Exception) {
            Log.e("nvm", "Failed to wake screen: ${e.message}", e)
        }
    }

    private fun turnScreenOff() {
        try {
            wakeLock?.release()
            wakeLock = null

            val accessibilityService = FiverrAccessibilityService.getInstance()
            if (accessibilityService != null) {
                accessibilityService.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                Log.d("nvm", "🔒 Screen locked via accessibility")
            } else {
                Log.w("nvm", "Accessibility not available - screen will turn off via timeout")
            }
        } catch (e: Exception) {
            Log.e("nvm", "Error turning screen off: ${e.message}", e)
        }
    }

    private fun performFiverrAction(onComplete: () -> Unit) {
        val fiverrPackage = "com.fiverr.fiverr"
        val isFiverrInFront = isAppInForeground(this, fiverrPackage)

        Log.d("nvm", "========================================")
        if (isFiverrInFront) {
            Log.d("nvm", "✅ Fiverr IS in foreground → Performing pull-down scroll")
            performScrollGesture(onComplete)
        } else {
            Log.d("nvm", "📱 Fiverr NOT in foreground → Launching app")
            launchFiverrApp(onComplete)
        }
        Log.d("nvm", "========================================")
    }

    private fun performScrollGesture(onComplete: () -> Unit) {
        val accessibilityService = FiverrAccessibilityService.getInstance()

        if (accessibilityService == null) {
            Log.w("nvm", "Accessibility service not available")
            onComplete()
            return
        }

        accessibilityService.performPullDownGesture { success ->
            if (success) {
                Log.d("nvm", "✅ Pull-down gesture executed")
            } else {
                Log.e("nvm", "❌ Failed to execute gesture")
            }
            onComplete()
        }
    }

    private fun launchFiverrApp(onComplete: () -> Unit) {
        try {
            val intent = packageManager.getLaunchIntentForPackage("com.fiverr.fiverr")
            if (intent != null) {
                intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
                startActivity(intent)
                Log.d("nvm", "✅ Fiverr launched")

                handler.postDelayed({
                    onComplete()
                }, 2000)
            } else {
                Log.w("nvm", "Fiverr app not found")
                onComplete()
            }
        } catch (e: Exception) {
            Log.e("nvm", "Error launching Fiverr: ${e.message}", e)
            onComplete()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Fiverr Support Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Fiverr active periodically"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Fiverr Support")
            .setContentText("Automation active - interval: ${launchInterval / 1000}s")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundServiceInternal() {
        startForeground(NOTIFICATION_ID, createNotification())
        Toast.makeText(this, "Fiverr Support Started", Toast.LENGTH_SHORT).show()
    }

    private fun stopForegroundServiceInternal() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Toast.makeText(this, "Fiverr Support Stopped", Toast.LENGTH_SHORT).show()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d("nvm", "App removed from recents, service continues")
    }

    @SuppressLint("ScheduleExactAlarm")
    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        cancelScheduledAction()
        restoreBrightness()
        removeStatusOverlay()
        wakeLock?.release()

        try {
            unregisterReceiver(screenStateReceiver)
        } catch (e: Exception) {
            Log.e("nvm", "Error unregistering receiver: ${e.message}")
        }

        val prefs = getSharedPreferences("FiverrSupportPrefs", MODE_PRIVATE)
        val wasEnabled = prefs.getBoolean("service_enabled", false)

        if (wasEnabled) {
            Log.d("nvm", "Service destroyed but was enabled - scheduling restart")
            try {
                val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
                val restartIntent = Intent(this, ServiceRestartReceiver::class.java).apply {
                    action = "com.akash.fiverrsupport.ACTION_RESTART_SERVICE"
                    putExtra("interval", launchInterval)
                }

                val pendingIntent = PendingIntent.getBroadcast(
                    this, 0, restartIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 2000,
                    pendingIntent
                )
            } catch (e: Exception) {
                Log.e("nvm", "Failed to schedule restart: ${e.message}")
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
    }
}

/**
 * Simple floating circle indicator - green outline when service is running
 */
class StatusOverlayView(context: Context) : View(context) {

    private val density = context.resources.displayMetrics.density
    private val size = (24 * density).toInt() // 24dp circle
    private val strokeWidth = 2 * density      // 4dp outline

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        this.strokeWidth = this@StatusOverlayView.strokeWidth
        color = Color.parseColor("#4CAF50") // Green
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val radius = (size / 2f) - (strokeWidth / 2f)
        canvas.drawCircle(size / 2f, size / 2f, radius, paint)
    }
}
