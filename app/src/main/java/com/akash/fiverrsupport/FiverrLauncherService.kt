package com.akash.fiverrsupport

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.akash.fiverrsupport.utils.isAppInForeground

/**
 * Simplified Fiverr Launcher Service
 * 
 * Flow:
 * 1. When screen turns OFF, start countdown timer
 * 2. After interval expires, wake screen
 * 3. Open Fiverr (or scroll if already open)
 * 4. Turn screen OFF again
 * 5. Repeat
 * 
 * This approach saves battery by keeping screen off most of the time.
 */
class FiverrLauncherService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var launchInterval = 30000L // Default 30 seconds
    private var screenOffTime = 0L // When screen turned off
    private var isScreenOn = true
    private var wakeLock: PowerManager.WakeLock? = null
    private var isPerformingAction = false // Flag to prevent multiple actions

    // Screen state receiver
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    screenOffTime = System.currentTimeMillis()
                    Log.d("nvm", "🔒 Screen OFF - starting countdown timer (${launchInterval / 1000}s)")
                    
                    // Start the countdown - after interval, we'll wake and do action
                    scheduleNextAction()
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    Log.d("nvm", "💡 Screen ON")
                    
                    // If we woke the screen for action, don't cancel the scheduled action
                    // The action runnable will handle turning screen off after action
                }
                Intent.ACTION_USER_PRESENT -> {
                    Log.d("nvm", "🔓 User unlocked device")
                    // User manually unlocked - they're using the phone
                    // Cancel any pending actions and wait for screen to turn off again
                    if (!isPerformingAction) {
                        cancelScheduledAction()
                        Log.d("nvm", "Cancelled pending action - user is active")
                    }
                }
            }
        }
    }

    // The main action runnable - wakes screen, does action, turns screen off
    private val actionRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) {
                Log.d("nvm", "Service not running, skipping action")
                return
            }

            Log.d("nvm", "⏰ Timer expired - executing action sequence")
            isPerformingAction = true

            // Step 1: Wake the screen
            wakeScreen()

            // Step 2: Wait a moment for screen to fully wake, then do Fiverr action
            handler.postDelayed({
                performFiverrAction {
                    // Step 3: After action completes, turn screen off
                    handler.postDelayed({
                        turnScreenOff()
                        isPerformingAction = false
                        
                        // The screen off receiver will schedule the next action
                    }, 2000) // Wait 2 seconds after action before turning off
                }
            }, 1000) // Wait 1 second for screen to wake
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
        // Handle null intent (service restarted by Android)
        if (intent == null) {
            Log.d("nvm", "Service restarted by Android - restoring state")
            val prefs = getSharedPreferences("FiverrSupportPrefs", MODE_PRIVATE)
            val wasEnabled = prefs.getBoolean("service_enabled", false)
            launchInterval = prefs.getLong("service_interval", 30000L)

            if (wasEnabled) {
                startForegroundServiceInternal()
                isRunning = true
                
                // If screen is off, schedule action
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

                // Save state
                val prefs = getSharedPreferences("FiverrSupportPrefs", MODE_PRIVATE)
                prefs.edit()
                    .putBoolean("service_enabled", true)
                    .putLong("service_interval", launchInterval)
                    .commit()

                startForegroundServiceInternal()
                isRunning = true

                Log.d("nvm", "✅ Service started with interval: ${launchInterval / 1000}s")
                Log.d("nvm", "📱 Waiting for screen to turn off to start automation...")

                // If screen is already off, start countdown
                if (!isScreenOn) {
                    screenOffTime = System.currentTimeMillis()
                    scheduleNextAction()
                }
            }
            ACTION_STOP -> {
                isRunning = false
                cancelScheduledAction()

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
                
                // Reschedule if screen is off
                if (!isScreenOn && isRunning) {
                    cancelScheduledAction()
                    scheduleNextAction()
                }
            }
        }
        return START_STICKY
    }

    private fun scheduleNextAction() {
        if (!isRunning) return
        
        cancelScheduledAction() // Cancel any existing scheduled action
        
        handler.postDelayed(actionRunnable, launchInterval)
        Log.d("nvm", "📅 Next action scheduled in ${launchInterval / 1000}s")
    }

    private fun cancelScheduledAction() {
        handler.removeCallbacks(actionRunnable)
        Log.d("nvm", "🚫 Cancelled scheduled action")
    }

    @SuppressLint("WakelockTimeout")
    private fun wakeScreen() {
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            
            @Suppress("DEPRECATION")
            wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or 
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
                "FiverrSupport:WakeScreen"
            )
            wakeLock?.acquire(10000) // Hold for 10 seconds max
            
            Log.d("nvm", "💡 Screen woken up")
        } catch (e: Exception) {
            Log.e("nvm", "Failed to wake screen: ${e.message}", e)
        }
    }

    private fun turnScreenOff() {
        try {
            // Release wake lock to allow screen to turn off
            wakeLock?.release()
            wakeLock = null
            
            // Use accessibility service to lock screen (if available)
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
                
                // Wait for app to load before completing
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
        wakeLock?.release()

        try {
            unregisterReceiver(screenStateReceiver)
        } catch (e: Exception) {
            Log.e("nvm", "Error unregistering receiver: ${e.message}")
        }

        // Schedule restart if was enabled
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
