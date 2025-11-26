package com.akash.fiverrsupport

import android.Manifest
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
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.VibrationAttributes
import android.os.Vibrator
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.akash.fiverrsupport.utils.isAppInForeground

class FiverrLauncherService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var isPaused = false // New: Track if service is paused due to user interaction
    private var launchInterval = 20000L // Default 20 seconds
    private var lastUserInteractionTime = 0L
    private var idleTimeout = 5000L // Default 5 seconds, configurable from MainActivity

    // Flag to ignore touch events during automated gestures
    private var isPerformingAutomatedGesture = false

    // Screen wake-lock components
    private var windowManager: WindowManager? = null
    private var overlayView: CircularTimerView? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var vibrator: Vibrator? = null
    private var isScreenOn = true
    private var isVibrationServiceRunning = false // Track if we intentionally started vibration

    private var nextLaunchTime = 0L

    // Media playback detection
    private var audioManager: AudioManager? = null

    // Call detection
    private var telephonyManager: TelephonyManager? = null
    private var telecomManager: TelecomManager? = null
    private var callStateListener: Any? = null // Can be PhoneStateListener or TelephonyCallback
    private var isInCall = false

    // Network connectivity detection
    private var connectivityManager: android.net.ConnectivityManager? = null
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private var hasInternet = true // Track internet connectivity
    private var isPausedByInternetLoss = false // Track if pause is due to internet loss

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Initialize vibrator (modern API)
        vibrator = getSystemService(Vibrator::class.java)

        // Initialize AudioManager for media playback detection
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        // Initialize TelephonyManager and TelecomManager for call detection
        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        telecomManager = getSystemService(TELECOM_SERVICE) as TelecomManager

        // Register call state listener
        registerCallStateListener()

        // Register network connectivity callback
        registerNetworkCallback()

        // Register touch interaction callback from accessibility service
        // Reset the automated gesture flag to ensure clean state
        TouchInteractionCallback.isAutomatedGestureActive = false
        isPerformingAutomatedGesture = false
        TouchInteractionCallback.setCallback { userTouched() }
        Log.d("nvm", "Registered touch interaction callback from accessibility service")

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
                    Log.d("nvm", "🔒 Screen turned OFF - isPaused=$isPaused, isRunning=$isRunning, lastUserInteractionTime=$lastUserInteractionTime")

                    // Pause the service (like touch does) but without idle checker
                    // We'll resume after unlock instead
                    if (!isPaused && isRunning) {
                        // Service was running - pause it and mark as screen-locked
                        lastUserInteractionTime = 0
                        Log.d("nvm", "Screen lock detected (service was running) - pausing service (lastUserInteractionTime=0, startIdleChecker=false)")
                        pauseService(startIdleChecker = false) // Don't start idle checker for screen lock
                        Log.d("nvm", "Service paused due to screen lock - ready for instant resume on unlock")

                        // Start vibration alert only if NOT paused by internet loss
                        startVibrationAlert()
                    } else if (isPaused && isPausedByInternetLoss) {
                        // Already paused by internet loss - don't vibrate
                        Log.d("nvm", "Screen turned OFF but already paused by internet loss - NOT starting vibration")
                    } else if (!isPaused && !isRunning) {
                        // Service is stopped - don't vibrate
                        Log.d("nvm", "Screen turned OFF but service is not running - NOT starting vibration")
                    } else if (isPaused && isRunning) {
                        // Service is already paused (by user touch) - convert to screen-lock pause
                        Log.d("nvm", "Screen turned OFF while already paused (was paused by touch) - converting to screen-lock pause")

                        // CRITICAL: Reset lastUserInteractionTime to 0 to mark as screen-locked
                        lastUserInteractionTime = 0

                        // Stop idle checker since we'll resume on unlock instead
                        handler.removeCallbacks(idleCheckerRunnable)

                        // Update pause reason in SharedPreferences
                        val prefs = getSharedPreferences("FiverrSupportPrefs", MODE_PRIVATE)
                        prefs.edit().putBoolean("paused_by_internet_loss", false).apply()

                        Log.d("nvm", "Converted to screen-lock pause - lastUserInteractionTime reset to 0, idle checker stopped")

                        // Start vibration
                        startVibrationAlert()
                    }
                }
                Intent.ACTION_USER_PRESENT -> {
                    // USER_PRESENT fires when user unlocks the device (most reliable)
                    isScreenOn = true
                    Log.d("nvm", "🔓 USER_PRESENT received - user unlocked device")
                    Log.d("nvm", "📊 State: isPaused=$isPaused, isRunning=$isRunning, lastUserInteractionTime=$lastUserInteractionTime")

                    // Stop vibration
                    if (isVibrationServiceRunning) {
                        Log.d("nvm", "Stopping vibration after unlock")
                        stopVibrationAlert()
                    }

                    // Check if user was paused by touch interaction (idle checker is running)
                    // If so, respect the 1-minute idle timeout instead of instant resume
                    if (isPaused && isRunning && lastUserInteractionTime > 0) {
                        val idleTime = System.currentTimeMillis() - lastUserInteractionTime
                        val mediaPlaying = isMediaPlaying()
                        val inCall = isInCall()

                        if (idleTime >= idleTimeout) {
                            if (!hasInternet) {
                                Log.d("nvm", "Idle timeout met but device is offline - NOT resuming (waiting for internet)")
                                // Idle checker will resume when internet comes back
                            } else if (mediaPlaying) {
                                Log.d("nvm", "Idle timeout met but media is playing - NOT resuming")
                            } else if (inCall) {
                                Log.d("nvm", "Idle timeout met but in a call - NOT resuming")
                            } else {
                                Log.d("nvm", "Auto-resuming service after unlock (idle timeout met: ${idleTime}ms, internet available)")
                                resumeService()
                            }
                        } else {
                            Log.d("nvm", "Not resuming yet - only ${idleTime}ms idle, need ${idleTimeout}ms (${idleTimeout - idleTime}ms remaining)")
                            // Don't resume - let idle checker continue
                        }
                    } else if (isPaused && isRunning && lastUserInteractionTime == 0L) {
                        // Service was paused by screen lock (not touch), so resume conditionally
                        val mediaPlaying = isMediaPlaying()
                        val inCall = isInCall()

                        Log.d("nvm", "Screen unlock detected (via USER_PRESENT) - checking conditions: hasInternet=$hasInternet, mediaPlaying=$mediaPlaying, inCall=$inCall")

                        when {
                            !hasInternet ->
                                Log.d("nvm", "Screen unlocked but no internet - NOT resuming (waiting for network)")
                            mediaPlaying ->
                                Log.d("nvm", "Screen unlocked but media is playing - NOT resuming until media stops")
                            inCall ->
                                Log.d("nvm", "Screen unlocked but call is active - NOT resuming until call ends")
                            else -> {
                                Log.d("nvm", "✅ Auto-resuming service INSTANTLY after screen unlock - all conditions satisfied!")
                                resumeService()
                            }
                        }
                    } else {
                        Log.d("nvm", "Resume not needed (isPaused=$isPaused, isRunning=$isRunning, lastUserInteractionTime=$lastUserInteractionTime)")
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    Log.d("nvm", "💡 Screen turned ON - waiting 500ms for keyguard state...")

                    // Use a short delay to let keyguard state settle
                    handler.postDelayed({
                        val keyguardManager = getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager
                        val isLocked = keyguardManager.isKeyguardLocked

                        Log.d("nvm", "💡 Screen ON settled - isLocked: $isLocked, isVibrationServiceRunning: $isVibrationServiceRunning")
                        Log.d("nvm", "📊 State: isPaused=$isPaused, isRunning=$isRunning, lastUserInteractionTime=$lastUserInteractionTime")

                        if (!isLocked) {
                            // Screen is ON and unlocked - stop vibration if running
                            if (isVibrationServiceRunning) {
                                Log.d("nvm", "Screen unlocked (via SCREEN_ON) - stopping vibration alert")
                                stopVibrationAlert()
                            }

                            // Check if user was paused by touch interaction
                            if (isPaused && isRunning && lastUserInteractionTime > 0) {
                                val idleTime = System.currentTimeMillis() - lastUserInteractionTime
                                val mediaPlaying = isMediaPlaying()
                                val inCall = isInCall()

                                if (idleTime >= idleTimeout) {
                                    if (mediaPlaying) {
                                        Log.d("nvm", "Idle timeout met but media is playing (via SCREEN_ON) - NOT resuming")
                                    } else if (inCall) {
                                        Log.d("nvm", "Idle timeout met but in a call (via SCREEN_ON) - NOT resuming")
                                    } else {
                                        Log.d("nvm", "Auto-resuming service after unlock (via SCREEN_ON, idle timeout met: ${idleTime}ms)")
                                        resumeService()
                                    }
                                } else {
                                    Log.d("nvm", "Not resuming yet (via SCREEN_ON) - only ${idleTime}ms idle, need ${idleTimeout}ms")
                                }
                            } else if (isPaused && isRunning && lastUserInteractionTime == 0L) {
                                // Service was paused by screen lock (not touch), so resume conditionally
                                val mediaPlaying = isMediaPlaying()
                                val inCall = isInCall()

                                Log.d("nvm", "Screen unlock detected (via SCREEN_ON) - checking conditions: hasInternet=$hasInternet, mediaPlaying=$mediaPlaying, inCall=$inCall")

                                when {
                                    !hasInternet ->
                                        Log.d("nvm", "Screen unlocked (via SCREEN_ON) but no internet - NOT resuming")
                                    mediaPlaying ->
                                        Log.d("nvm", "Screen unlocked (via SCREEN_ON) but media playing - NOT resuming until media stops")
                                    inCall ->
                                        Log.d("nvm", "Screen unlocked (via SCREEN_ON) but call active - NOT resuming until call ends")
                                    else -> {
                                        Log.d("nvm", "Auto-resuming service instantly after unlock (via SCREEN_ON, all conditions satisfied)")
                                        resumeService()
                                    }
                                }
                            } else {
                                Log.d("nvm", "Screen unlocked but resume not applicable (isPaused=$isPaused, isRunning=$isRunning, lastUserInteractionTime=$lastUserInteractionTime)")
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
            Log.d("nvm", "launchRunnable.run() called - isRunning=$isRunning, isPaused=$isPaused")

            if (isRunning && !isPaused) {
                Log.d("nvm", "⏰ Timer reached 0 - Verifying internet before executing action")

                // Verify actual internet connectivity before executing task
                verifyInternetConnectivity { hasActualInternet ->
                    if (hasActualInternet) {
                        Log.d("nvm", "✅ Internet verified - Executing action NOW")
                        handleFiverrAction()
                        nextLaunchTime = System.currentTimeMillis() + launchInterval

                        // Reset the timer to restart countdown after action
                        overlayView?.resetTimer()
                        Log.d("nvm", "Timer reset after Fiverr action - countdown restarted")

                        // Schedule next action with full interval
                        handler.postDelayed(this, launchInterval)
                        Log.d("nvm", "Next action scheduled in ${launchInterval}ms")
                    } else {
                        Log.w("nvm", "❌ Internet verification failed - pausing service")
                        // Update hasInternet flag
                        hasInternet = false

                        // Pause service due to internet loss
                        pauseService(startIdleChecker = false, shouldRestoreBrightness = false)

                        // Show notification
                        Toast.makeText(this@FiverrLauncherService, "No internet - service paused", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                Log.w("nvm", "⚠️ launchRunnable skipped execution - isRunning=$isRunning, isPaused=$isPaused")
                // DO NOT reschedule when paused - wait for resumeService() to schedule with correct remaining time
            }
        }
    }

    // Check if user has been idle for 1 minute, then auto-resume (unless media is playing or in call)
    // Note: Internet connectivity is handled separately by network callback, not here
    private val idleCheckerRunnable = object : Runnable {
        override fun run() {
            if (isRunning && isPaused) {
                // Do not resume while offline or if pause reason is internet loss
                if (isPausedByInternetLoss || !hasInternet) {
                    handler.postDelayed(this, 1000)
                    return
                }

                val idleTime = System.currentTimeMillis() - lastUserInteractionTime

                // Only check media and call state - internet is handled by network callback
                val mediaPlaying = isMediaPlaying()
                val inCall = isInCall()

                if (idleTime >= idleTimeout) {
                    if (mediaPlaying) {
                        Log.d("nvm", "User idle for 1 minute but media is playing - NOT resuming")
                    } else if (inCall) {
                        Log.d("nvm", "User idle for 1 minute but in a call - NOT resuming")
                    } else {
                        Log.d("nvm", "User idle for 1 minute, no media playing, no call - auto-resuming service")
                        resumeService()
                    }
                }
            }
            // Continue checking only if service is still running and paused
            if (isRunning && isPaused) {
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
            val savedIdleTimeout = prefs.getLong("idle_timeout", 5000L)
            val wasPaused = prefs.getBoolean("service_paused", false)
            val savedPausedTime = prefs.getLong("paused_time_ms", 0L)
            val wasPausedByInternetLoss = prefs.getBoolean("paused_by_internet_loss", false)

            if (wasEnabled) {
                Log.d("nvm", "Restoring service with interval: ${savedInterval}ms, idleTimeout: ${savedIdleTimeout}ms, wasPaused=$wasPaused, pausedTime=${savedPausedTime}ms")
                launchInterval = savedInterval
                idleTimeout = savedIdleTimeout
                startForegroundServiceInternal()
                isRunning = true

                createOverlay()
                acquireWakeLock()

                // If service was paused when process died, restore the paused state
                if (wasPaused && savedPausedTime > 0) {
                    isPaused = true
                    isPausedByInternetLoss = wasPausedByInternetLoss

                    // Start timer with full interval, then restore it to the paused position
                    overlayView?.startTimer(launchInterval)
                    overlayView?.restorePausedState(savedPausedTime)

                    Log.d("nvm", "Restored paused state: pausedAt=${savedPausedTime}ms, byInternetLoss=$isPausedByInternetLoss")

                    // If paused by internet loss, keep brightness low and don't schedule anything
                    if (isPausedByInternetLoss) {
                        Log.d("nvm", "Was paused by internet loss - keeping brightness low, NOT scheduling any callbacks (waiting for network)")
                        if (isScreenOn) {
                            reduceBrightness()
                        }
                        // DO NOT schedule launchRunnable or idle checker - wait for network callback to resume
                    } else {
                        // Paused by user touch - restore brightness and start idle checker
                        Log.d("nvm", "Was paused by user touch - starting idle checker, NOT scheduling launchRunnable yet")
                        if (isScreenOn) {
                            reduceBrightness()
                        }
                        // Set lastUserInteractionTime so idle checker works correctly
                        lastUserInteractionTime = System.currentTimeMillis()
                        handler.post(idleCheckerRunnable)
                        // DO NOT schedule launchRunnable yet - idle checker will call resumeService() which will schedule it
                    }
                } else {
                    // Service was NOT paused, start normally
                    handler.post(launchRunnable)
                    Log.d("nvm", "Service restored successfully (not paused)")

                    // Reduce brightness when service is restored (if screen is on)
                    if (isScreenOn) {
                        Log.d("nvm", "Reducing brightness on service restore")
                        reduceBrightness()
                    }
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

                // Get idle timeout from intent, default to 5 seconds
                idleTimeout = intent.getLongExtra(EXTRA_IDLE_TIMEOUT, 5000L)

                // Save service state as enabled - use commit() for immediate write
                val prefs = getSharedPreferences("FiverrSupportPrefs", MODE_PRIVATE)
                prefs.edit()
                    .putBoolean("service_enabled", true)
                    .putLong("service_interval", launchInterval)
                    .putLong("idle_timeout", idleTimeout)
                    .commit() // Use commit() instead of apply() for immediate write

                Log.d("nvm", "Saved state: service_enabled=true, interval=${launchInterval}ms, idleTimeout=${idleTimeout}ms")

                startForegroundServiceInternal()
                isRunning = true
                handler.post(launchRunnable)

                Log.d("nvm", "FiverrLauncherService started with interval: ${launchInterval}ms, idleTimeout: ${idleTimeout}ms")

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
                // User finished dragging slider - update interval and resume
                launchInterval = intent.getLongExtra(EXTRA_INTERVAL, 20000L)

                // Save updated interval - use commit() for immediate write
                val prefs = getSharedPreferences("FiverrSupportPrefs", MODE_PRIVATE)
                prefs.edit()
                    .putLong("service_interval", launchInterval)
                    .commit()

                Log.d("nvm", "Interval updated to: ${launchInterval}ms, rescheduling handler")
                // Cancel current scheduled task and reschedule with new interval
                handler.removeCallbacks(launchRunnable)
                if (isRunning && !isPaused) {
                    // If service was running (not paused), restart with new interval
                    overlayView?.stopTimer()
                    overlayView?.startTimer(launchInterval)
                    // Schedule next action with the new interval (not immediate)
                    handler.postDelayed(launchRunnable, launchInterval)
                    Log.d("nvm", "Handler rescheduled to run in ${launchInterval}ms")
                } else if (isRunning && isPaused) {
                    // Service is paused - just update the timer duration
                    overlayView?.stopTimer()
                    overlayView?.startTimer(launchInterval)
                    overlayView?.setPaused(true) // Keep it paused
                }
            }
            ACTION_UPDATE_IDLE_TIMEOUT -> {
                // Update idle timeout value
                idleTimeout = intent.getLongExtra(EXTRA_IDLE_TIMEOUT, 5000L)

                // Save updated idle timeout - use commit() for immediate write
                val prefs = getSharedPreferences("FiverrSupportPrefs", MODE_PRIVATE)
                prefs.edit()
                    .putLong("idle_timeout", idleTimeout)
                    .commit()

                Log.d("nvm", "Idle timeout updated to: ${idleTimeout}ms")
                // Note: If idle checker is already running, it will use the new value on next check
            }
            ACTION_PAUSE_FOR_SLIDER -> {
                // User is dragging slider - pause service temporarily
                launchInterval = intent.getLongExtra(EXTRA_INTERVAL, 20000L)

                Log.d("nvm", "Slider interaction detected - pausing service temporarily")
                if (isRunning && !isPaused) {
                    // Pause the service
                    lastUserInteractionTime = System.currentTimeMillis()
                    pauseService(startIdleChecker = false) // Don't start idle checker for slider

                    // Update timer with new interval but keep it paused
                    overlayView?.stopTimer()
                    overlayView?.startTimer(launchInterval)
                    overlayView?.setPaused(true)
                } else if (isRunning && isPaused) {
                    // Already paused, just update timer
                    overlayView?.stopTimer()
                    overlayView?.startTimer(launchInterval)
                    overlayView?.setPaused(true)
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

    /**
     * Verify actual internet connectivity by making a network request
     * Uses multiple fallback endpoints for reliability
     */
    private fun verifyInternetConnectivity(callback: (Boolean) -> Unit) {
        // Run network check in background thread
        Thread {
            var hasInternet = false

            // List of lightweight endpoints to check (in order of preference)
            val endpoints = listOf(
                "https://clients3.google.com/generate_204", // Google's connectivity check (204 No Content)
                "https://www.google.com",
                "https://www.cloudflare.com",
                "https://1.1.1.1" // Cloudflare DNS
            )

            for (endpoint in endpoints) {
                try {
                    val url = java.net.URL(endpoint)
                    val connection = url.openConnection() as java.net.HttpURLConnection
                    connection.connectTimeout = 5000 // 5 second timeout
                    connection.readTimeout = 5000
                    connection.requestMethod = "HEAD" // HEAD request is faster
                    connection.instanceFollowRedirects = false
                    connection.useCaches = false

                    val responseCode = connection.responseCode
                    connection.disconnect()

                    // Accept 200-299 range and 204 (No Content) as success
                    if (responseCode in 200..299 || responseCode == 204) {
                        hasInternet = true
                        Log.d("nvm", "✅ Internet verified via $endpoint (code: $responseCode)")
                        break
                    } else {
                        Log.d("nvm", "❌ Endpoint $endpoint returned code: $responseCode")
                    }
                } catch (e: Exception) {
                    Log.d("nvm", "❌ Failed to reach $endpoint: ${e.message}")
                    // Continue to next endpoint
                }
            }

            if (!hasInternet) {
                Log.w("nvm", "❌ All endpoints failed - no internet connectivity")
            }

            // Call callback on main thread
            handler.post {
                callback(hasInternet)
            }
        }.start()
    }

    /**
     * Main logic: Check if Fiverr is in foreground
     * - If YES: Perform pull-down scroll gesture (via accessibility)
     * - If NO: Launch/bring Fiverr to front
     */
    private fun handleFiverrAction() {
        val fiverrPackage = "com.fiverr.fiverr"

        Log.d("nvm", "========================================")
        Log.d("nvm", "Countdown reached 0 - Making decision NOW")
        Log.d("nvm", "Current timestamp: ${System.currentTimeMillis()}")

        // Check if Fiverr app is currently in foreground RIGHT NOW
        val isFiverrInFront = isAppInForeground(this, fiverrPackage)

        if (isFiverrInFront) {
            // Fiverr is already in front, perform scroll gesture
            Log.d("nvm", "✅ DECISION: Fiverr IS in foreground → Performing pull-down gesture")
            performScrollGesture()
        } else {
            // Fiverr is not in front, launch it
            Log.d("nvm", "✅ DECISION: Fiverr NOT in foreground → Launching app")
            launchFiverrApp()
        }
        Log.d("nvm", "========================================")
    }

    /**
     * Perform pull-down scroll gesture using Accessibility Service
     */
    private fun performScrollGesture() {
        val accessibilityService = FiverrAccessibilityService.getInstance()

        if (accessibilityService == null) {
            Log.w("nvm", "Accessibility service not available - cannot perform scroll gesture")
            Toast.makeText(this, "Enable accessibility service to perform auto-scroll", Toast.LENGTH_SHORT).show()
            return
        }

        // Set flag to ignore touch events during automated gesture
        isPerformingAutomatedGesture = true
        TouchInteractionCallback.isAutomatedGestureActive = true
        Log.d("nvm", "Starting automated gesture - touch detection disabled")

        accessibilityService.performPullDownGesture { success ->
            if (success) {
                Log.d("nvm", "Pull-down gesture executed successfully")
            } else {
                Log.e("nvm", "Failed to execute pull-down gesture")
            }

            // Re-enable touch detection after buffer (gesture + Fiverr's scroll events settle)
            handler.postDelayed({
                isPerformingAutomatedGesture = false
                TouchInteractionCallback.isAutomatedGestureActive = false
                Log.d("nvm", "Automated gesture completed - touch detection re-enabled")
            }, 1500) // 300ms gesture + 1200ms buffer for Fiverr scroll events to settle
        }
    }

    private fun launchFiverrApp() {
        try {
            // Set flag to ignore scroll events during app launch/loading
            isPerformingAutomatedGesture = true
            TouchInteractionCallback.isAutomatedGestureActive = true
            Log.d("nvm", "Starting app launch - touch detection disabled")

            val intent = packageManager.getLaunchIntentForPackage("com.fiverr.fiverr")
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                startActivity(intent)

                // Re-enable touch detection after buffer (app launch + loading scroll events settle)
                handler.postDelayed({
                    isPerformingAutomatedGesture = false
                    TouchInteractionCallback.isAutomatedGestureActive = false
                    Log.d("nvm", "App launch completed - touch detection re-enabled")
                }, 2500) // 2.5 seconds buffer for app to load and settle
            } else {
                Log.w("nvm", "Fiverr app package not found")
                // Re-enable immediately if launch failed
                isPerformingAutomatedGesture = false
                TouchInteractionCallback.isAutomatedGestureActive = false
            }
        } catch (e: Exception) {
            Log.e("nvm", "Error launching Fiverr app: ${e.message}")
            // Re-enable immediately on error
            isPerformingAutomatedGesture = false
            TouchInteractionCallback.isAutomatedGestureActive = false
        }
    }

    // Called when user touches the screen
    private fun userTouched() {
        // Ignore touch events during automated gestures
        if (isPerformingAutomatedGesture) {
            Log.d("nvm", "Touch detected during automated gesture - ignoring")
            return
        }

        // If we're paused due to internet loss (or currently offline), restore brightness but keep paused
        if (isPausedByInternetLoss || !hasInternet) {
            Log.d("nvm", "Touch detected while offline/internet-paused - restoring brightness but staying paused")
            restoreBrightness()
            return
        }

        Log.d("nvm", "User touch detected - pausing service")
        lastUserInteractionTime = System.currentTimeMillis()

        if (!isPaused) {
            pauseService()
        }
    }

    // Pause the service (stop opening Fiverr, turn timer red)
    private fun pauseService(startIdleChecker: Boolean = true, shouldRestoreBrightness: Boolean = true) {
        Log.d("nvm", "pauseService() called - startIdleChecker=$startIdleChecker, shouldRestoreBrightness=$shouldRestoreBrightness, lastUserInteractionTime=$lastUserInteractionTime")

        isPaused = true

        // Track if this pause is due to internet loss
        isPausedByInternetLoss = !startIdleChecker && !shouldRestoreBrightness

        overlayView?.setPaused(true) // Turn timer red

        // Remove pending launchRunnable callbacks to prevent them from rescheduling
        handler.removeCallbacks(launchRunnable)
        Log.d("nvm", "Removed pending launchRunnable callbacks when pausing")

        // Save paused state to SharedPreferences (survives process death)
        val pausedTimeMs = overlayView?.getPausedTime() ?: 0L
        val prefs = getSharedPreferences("FiverrSupportPrefs", MODE_PRIVATE)
        prefs.edit()
            .putBoolean("service_paused", true)
            .putLong("paused_time_ms", pausedTimeMs)
            .putBoolean("paused_by_internet_loss", isPausedByInternetLoss)
            .apply()
        Log.d("nvm", "Saved paused state: pausedTime=${pausedTimeMs}ms, byInternetLoss=$isPausedByInternetLoss")

        // Restore brightness only for user interaction (not for internet loss)
        if (shouldRestoreBrightness) {
            restoreBrightness()
        } else {
            Log.d("nvm", "Keeping brightness low (paused by internet loss)")
        }

        // Start idle checker only for touch events
        if (startIdleChecker) {
            handler.post(idleCheckerRunnable)
        }

        Log.d("nvm", "Service paused ${if (startIdleChecker) "with idle checker" else "without idle checker"} - isPausedByInternetLoss=$isPausedByInternetLoss")
    }

    // Resume the service (start opening Fiverr, turn timer green)
    private fun resumeService() {
        Log.d("nvm", "resumeService() called - BEFORE: isRunning=$isRunning, isPaused=$isPaused, isPausedByInternetLoss=$isPausedByInternetLoss")

        // Calculate remaining time from when it was paused
        val pausedTimeMs = overlayView?.getPausedTime() ?: 0L
        val remainingTime = (launchInterval - pausedTimeMs).coerceAtLeast(0)

        Log.d("nvm", "Service resuming - was paused at ${pausedTimeMs}ms, remaining time: ${remainingTime}ms")

        // IMPORTANT: Call resumeTimer() first - it will handle unpausing internally
        // Don't call setPaused(false) before resumeTimer() as it breaks the resume logic
        overlayView?.resumeTimer() // Resume from paused position (not reset!) and unpause

        isPaused = false
        isPausedByInternetLoss = false // Clear internet loss flag

        // Clear saved paused state from SharedPreferences
        val prefs = getSharedPreferences("FiverrSupportPrefs", MODE_PRIVATE)
        prefs.edit()
            .putBoolean("service_paused", false)
            .remove("paused_time_ms")
            .remove("paused_by_internet_loss")
            .apply()
        Log.d("nvm", "Cleared saved paused state from SharedPreferences")

        Log.d("nvm", "resumeService() - AFTER isPaused=false: isRunning=$isRunning, isPaused=$isPaused")

        handler.removeCallbacks(idleCheckerRunnable) // Stop idle checker

        // IMPORTANT: Remove ALL pending callbacks before rescheduling
        // This prevents old scheduled callbacks from interfering with the correct remaining time
        handler.removeCallbacks(launchRunnable)
        Log.d("nvm", "Removed all pending launchRunnable callbacks before rescheduling")

        // Schedule with the REMAINING time (not full interval)
        handler.postDelayed(launchRunnable, remainingTime)
        Log.d("nvm", "Service resumed - handler scheduled to run in ${remainingTime}ms (resumed from pause, not reset)")

        // Reduce brightness to 0 when service resumes
        reduceBrightness()
    }

    // Check if any media is currently playing (music, video, etc.)
    private fun isMediaPlaying(): Boolean {
        return try {
            audioManager?.isMusicActive == true
        } catch (e: Exception) {
            Log.e("nvm", "Error checking media playback: ${e.message}")
            false
        }
    }

    // Register call state listener (handles both regular calls and VoIP)
    @SuppressLint("MissingPermission")
    private fun registerCallStateListener() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ (API 31+): Use TelephonyCallback
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        handleCallStateChange(state)
                    }
                }
                telephonyManager?.registerTelephonyCallback(mainExecutor, callback)
                callStateListener = callback
                Log.d("nvm", "Registered TelephonyCallback for call detection (Android 12+)")
            } else {
                // Android 11 and below: Use PhoneStateListener
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        handleCallStateChange(state)
                    }
                }
                @Suppress("DEPRECATION")
                telephonyManager?.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                callStateListener = listener
                Log.d("nvm", "Registered PhoneStateListener for call detection (Android 11-)")
            }
        } catch (e: Exception) {
            Log.e("nvm", "Failed to register call state listener: ${e.message}", e)
        }
    }

    // Unregister call state listener
    @SuppressLint("MissingPermission")
    private fun unregisterCallStateListener() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                callStateListener?.let {
                    telephonyManager?.unregisterTelephonyCallback(it as TelephonyCallback)
                }
            } else {
                @Suppress("DEPRECATION")
                callStateListener?.let {
                    telephonyManager?.listen(it as PhoneStateListener, PhoneStateListener.LISTEN_NONE)
                }
            }
            Log.d("nvm", "Unregistered call state listener")
        } catch (e: Exception) {
            Log.e("nvm", "Error unregistering call state listener: ${e.message}")
        }
    }

    // Register network connectivity callback (Android 13+ modern API)
    private fun registerNetworkCallback() {
        try {
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager

            // Check initial connectivity state
            val activeNetwork = connectivityManager?.activeNetwork
            val capabilities = connectivityManager?.getNetworkCapabilities(activeNetwork)
            hasInternet = capabilities?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                         capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

            Log.d("nvm", "Initial network state: hasInternet = $hasInternet")

            // Create network callback
            networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    Log.d("nvm", "Network available: $network")
                    // Don't set hasInternet yet - wait for validation
                }

                override fun onCapabilitiesChanged(
                    network: android.net.Network,
                    capabilities: android.net.NetworkCapabilities
                ) {
                    val hasInternetCapability = capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    val isValidated = capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    val newHasInternet = hasInternetCapability && isValidated

                    if (newHasInternet != hasInternet) {
                        Log.d("nvm", "Network validation changed: newHasInternet=$newHasInternet, previousHasInternet=$hasInternet")

                        if (newHasInternet) {
                            Log.d("nvm", "Network claims internet restored - verifying with actual connectivity check")

                            // Verify actual internet connectivity before resuming
                            verifyInternetConnectivity { hasActualInternet ->
                                if (hasActualInternet) {
                                    hasInternet = true
                                    Log.d("nvm", "✅ Internet verified and restored - checking if should auto-resume")

                                    // Auto-resume ONLY if service was paused by internet loss
                                    // If paused by user interaction, let idle checker handle resume
                                    // ALSO: Don't resume if screen is off - wait for unlock
                                    if (isPaused && isRunning && isPausedByInternetLoss && isScreenOn) {
                                        Log.d("nvm", "Internet restored, screen ON, was paused by internet loss - auto-resuming service")
                                        resumeService()
                                    } else if (isPaused && isRunning && isPausedByInternetLoss && !isScreenOn) {
                                        Log.d("nvm", "Internet restored but screen is OFF - NOT resuming (will resume on unlock)")
                                    } else if (isPaused && isRunning && !isPausedByInternetLoss) {
                                        Log.d("nvm", "Internet restored but service was paused by user interaction - idle checker will handle resume")
                                    }
                                } else {
                                    Log.w("nvm", "❌ Network validation passed but actual internet check failed - keeping hasInternet=false")
                                    hasInternet = false
                                }
                            }
                        } else {
                            hasInternet = false
                            Log.d("nvm", "Internet lost - handling service pause")

                            // If service is already paused by user interaction, mark it as paused by internet loss too
                            if (isPaused && isRunning && !isPausedByInternetLoss) {
                                handler.post {
                                    Log.d("nvm", "Service already paused by user - marking as paused by internet loss (will wait for internet)")
                                    isPausedByInternetLoss = true

                                    // Update SharedPreferences
                                    val prefs = getSharedPreferences("FiverrSupportPrefs", MODE_PRIVATE)
                                    prefs.edit()
                                        .putBoolean("paused_by_internet_loss", true)
                                        .apply()
                                }
                                return@onCapabilitiesChanged
                            }

                            // Clear Fiverr from recents when going offline (only if not already paused)
                            val accessibilityService = FiverrAccessibilityService.getInstance()
                            if (accessibilityService != null && !isPaused && isRunning) {
                                // Clear Fiverr first (callback runs on main thread)
                                accessibilityService.clearFiverrFromRecents { success ->
                                    if (success) {
                                        Log.d("nvm", "Successfully cleared Fiverr from recents after internet loss")
                                    } else {
                                        Log.w("nvm", "Failed to clear Fiverr from recents (may not be running)")
                                    }

                                    // Then pause service (already on main thread)
                                    if (!isPaused && isRunning) {
                                        pauseService(startIdleChecker = false, shouldRestoreBrightness = false) // Keep brightness low, no idle checker
                                    }
                                }
                            } else {
                                // If accessibility not available or already paused, just pause
                                // Post to main thread since onCapabilitiesChanged runs on background thread
                                if (!isPaused && isRunning) {
                                    handler.post {
                                        pauseService(startIdleChecker = false, shouldRestoreBrightness = false) // Keep brightness low, no idle checker
                                    }
                                }
                            }
                        }
                    }
                }

                override fun onLost(network: android.net.Network) {
                    Log.d("nvm", "Network lost: $network")
                    hasInternet = false
                    Log.d("nvm", "Internet lost - handling service pause")

                    // If service is already paused by user interaction, mark it as paused by internet loss too
                    if (isPaused && isRunning && !isPausedByInternetLoss) {
                        handler.post {
                            Log.d("nvm", "Service already paused by user - marking as paused by internet loss (will wait for internet)")
                            isPausedByInternetLoss = true

                            // Update SharedPreferences
                            val prefs = getSharedPreferences("FiverrSupportPrefs", MODE_PRIVATE)
                            prefs.edit()
                                .putBoolean("paused_by_internet_loss", true)
                                .apply()
                        }
                        return
                    }

                    // Clear Fiverr from recents when going offline (only if not already paused)
                    val accessibilityService = FiverrAccessibilityService.getInstance()
                    if (accessibilityService != null && !isPaused && isRunning) {
                        // Clear Fiverr first (callback already runs on main thread)
                        accessibilityService.clearFiverrFromRecents { success ->
                            if (success) {
                                Log.d("nvm", "Successfully cleared Fiverr from recents after internet loss (onLost)")
                            } else {
                                Log.w("nvm", "Failed to clear Fiverr from recents (may not be running)")
                            }

                            // Then pause service (already on main thread via callback)
                            if (!isPaused && isRunning) {
                                pauseService(startIdleChecker = false, shouldRestoreBrightness = false) // Keep brightness low, no idle checker
                            }
                        }
                    } else {
                        // If accessibility not available or already paused, just pause
                        // Post to main thread since onLost runs on background thread
                        if (!isPaused && isRunning) {
                            handler.post {
                                pauseService(startIdleChecker = false, shouldRestoreBrightness = false) // Keep brightness low, no idle checker
                            }
                        }
                    }
                }
            }

            // Register the callback
            connectivityManager?.registerDefaultNetworkCallback(networkCallback!!)
            Log.d("nvm", "Network callback registered successfully")

        } catch (e: Exception) {
            Log.e("nvm", "Failed to register network callback: ${e.message}", e)
        }
    }

    // Unregister network callback
    private fun unregisterNetworkCallback() {
        try {
            networkCallback?.let {
                connectivityManager?.unregisterNetworkCallback(it)
                Log.d("nvm", "Network callback unregistered")
            }
        } catch (e: Exception) {
            Log.e("nvm", "Error unregistering network callback: ${e.message}")
        }
    }

    // Handle call state changes
    private fun handleCallStateChange(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_IDLE -> {
                // No call active
                if (isInCall) {
                    Log.d("nvm", "Call ended - isInCall = false")
                    isInCall = false
                    // Don't auto-resume here - let idle checker handle it
                }
            }
            TelephonyManager.CALL_STATE_RINGING, TelephonyManager.CALL_STATE_OFFHOOK -> {
                // Incoming call or call in progress
                if (!isInCall) {
                    Log.d("nvm", "Call started (state=$state) - pausing service")
                    isInCall = true

                    // Pause service if not already paused
                    if (!isPaused && isRunning) {
                        lastUserInteractionTime = System.currentTimeMillis()
                        pauseService()
                    }
                }
            }
        }
    }

    // Check if user is currently in a call (regular phone call or VoIP call)
    private fun isInCall(): Boolean {
        // Method 1: Check telephony call state
        val telephonyCallActive = try {
            telephonyManager?.callState != TelephonyManager.CALL_STATE_IDLE
        } catch (e: Exception) {
            false
        }

        // Method 2: Check TelecomManager for VoIP calls (WhatsApp, Messenger, etc.)
        val voipCallActive = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Check if we have READ_PHONE_STATE permission
                if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                    telecomManager?.isInCall == true
                } else {
                    false
                }
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }

        // Method 3: Check AudioManager mode (works for most VoIP apps)
        val audioModeInCall = try {
            val mode = audioManager?.mode
            mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
        } catch (e: Exception) {
            false
        }

        val inCall = telephonyCallActive || voipCallActive || audioModeInCall

        if (inCall && !isInCall) {
            Log.d("nvm", "Call detected: telephony=$telephonyCallActive, voip=$voipCallActive, audioMode=$audioModeInCall")
        }

        return inCall
    }

    // Lower brightness using window overlay (doesn't affect brightness slider)
    private fun reduceBrightness() {
        try {
            // Update Window brightness (CRITICAL for Android 13+)
            // This makes the screen dimmer without changing the system brightness slider
            overlayView?.let { view ->
                val layoutParams = view.layoutParams as? WindowManager.LayoutParams
                layoutParams?.screenBrightness = 0f // 0f = minimum brightness
                windowManager?.updateViewLayout(view, layoutParams)
                Log.d("nvm", "Window brightness reduced to 0 (slider unchanged)")
            }
        } catch (e: Exception) {
            Log.e("nvm", "Error reducing brightness: ${e.message}", e)
        }
    }

    // Restore brightness to system default (whatever the brightness slider is set to)
    private fun restoreBrightness() {
        try {
            // Restore Window brightness to system default
            // Use BRIGHTNESS_OVERRIDE_NONE to let system control brightness based on slider value
            overlayView?.let { view ->
                val layoutParams = view.layoutParams as? WindowManager.LayoutParams
                layoutParams?.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                windowManager?.updateViewLayout(view, layoutParams)
                Log.d("nvm", "Window brightness restored to system default (slider value)")
            }
        } catch (e: Exception) {
            Log.e("nvm", "Error restoring brightness: ${e.message}", e)
        }
    }

    // Helper to start both vibration engines
    private fun startVibrationAlert() {
        try {
            // Always start the service to wake screen (vibration is controlled inside the service)
            val prefs = getSharedPreferences("FiverrSupportPrefs", MODE_PRIVATE)
            val isVibrationEnabled = prefs.getBoolean("vibrate_on_screen_off", false)

            Log.d("nvm", "startVibrationAlert() called - isRunning: $isRunning, isScreenOn: $isScreenOn, vibrationEnabled: $isVibrationEnabled")
            Log.d("nvm", "Starting wake service (screen will wake up, vibration depends on user setting)")

            if (isRunning) {
                isVibrationServiceRunning = true // Set flag BEFORE starting service
                val serviceIntent = Intent(this, VibrationService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                Log.d("nvm", "Started VibrationService (wake + optional vibration) via ${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) "startForegroundService" else "startService"}")
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

        // Unregister touch interaction callback
        TouchInteractionCallback.setCallback(null)
        Log.d("nvm", "Unregistered touch interaction callback")

        // Unregister network callback
        unregisterNetworkCallback()

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
        const val ACTION_UPDATE_IDLE_TIMEOUT = "ACTION_UPDATE_IDLE_TIMEOUT"
        const val ACTION_PAUSE_FOR_SLIDER = "ACTION_PAUSE_FOR_SLIDER"
        const val EXTRA_INTERVAL = "EXTRA_INTERVAL"
        const val EXTRA_IDLE_TIMEOUT = "EXTRA_IDLE_TIMEOUT"
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
            // Exiting pause - not used here, use resumeTimer instead
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

    fun resumeTimer() {
        // Resume from where it was paused
        if (isPaused && pausedTime > 0) {
            startTime = System.currentTimeMillis() - pausedTime
            isPaused = false
            Log.d("nvm", "Timer resumed from ${pausedTime}ms (${(totalDuration - pausedTime) / 1000}s remaining)")
            // Restart the update runnable to continue animation
            handler.post(updateRunnable)
        } else {
            // If not paused or no saved time, just unpause
            isPaused = false
            Log.d("nvm", "Timer resumed but no saved pause time - just unpausing")
        }
        invalidate()
    }

    fun restorePausedState(elapsedTimeMs: Long) {
        // Restore the paused time from saved state (after process death)
        // This assumes totalDuration and isRunning are already set
        pausedTime = elapsedTimeMs
        startTime = System.currentTimeMillis() - elapsedTimeMs
        isPaused = true
        // Start the update runnable to show the paused red circle
        handler.post(updateRunnable)
        Log.d("nvm", "Timer state restored: pausedAt=${pausedTime}ms, remaining=${(totalDuration - pausedTime) / 1000}s")
        invalidate()
    }

    fun getPausedTime(): Long {
        return pausedTime
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

        // Draw remaining time text with appropriate suffix (s/m/h)
        val remainingSeconds = (remaining / 1000).toInt()
        val text = when {
            remainingSeconds >= 3600 -> "${remainingSeconds / 3600}h" // Hours
            remainingSeconds >= 60 -> "${remainingSeconds / 60}m"    // Minutes
            else -> "${remainingSeconds}s"                            // Seconds
        }
        canvas.drawText(text, centerX, centerY + 8, textPaint)
        }
    }
