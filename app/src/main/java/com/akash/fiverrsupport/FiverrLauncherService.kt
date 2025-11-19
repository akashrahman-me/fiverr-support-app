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
    private val idleTimeout = 5000L

    // Flag to ignore touch events during automated gestures
    private var isPerformingAutomatedGesture = false

    // Screen wake-lock components
    private var windowManager: WindowManager? = null
    private var overlayView: CircularTimerView? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var vibrator: Vibrator? = null
    private var isScreenOn = true
    private var isVibrationServiceRunning = false // Track if we intentionally started vibration
    private var originalBrightness: Int = -1 // Store original brightness to restore later

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
                    Log.d("nvm", "Screen turned OFF - pausing service and starting vibration")

                    // Pause the service (like touch does) but without idle checker
                    // We'll resume after unlock instead
                    if (!isPaused && isRunning) {
                        // Reset lastUserInteractionTime to 0 to mark this as "paused by screen lock"
                        lastUserInteractionTime = 0
                        pauseService(startIdleChecker = false) // Don't start idle checker for screen lock
                        Log.d("nvm", "Service paused due to screen lock (lastUserInteractionTime reset to 0)")
                    }

                    // Start vibration alert
                    startVibrationAlert()
                }
                Intent.ACTION_USER_PRESENT -> {
                    // USER_PRESENT fires when user unlocks the device (most reliable)
                    isScreenOn = true
                    Log.d("nvm", "USER_PRESENT received - user unlocked device")

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
                            if (mediaPlaying) {
                                Log.d("nvm", "Idle timeout met but media is playing - NOT resuming")
                            } else if (inCall) {
                                Log.d("nvm", "Idle timeout met but in a call - NOT resuming")
                            } else {
                                Log.d("nvm", "Auto-resuming service after unlock (idle timeout met: ${idleTime}ms)")
                                resumeService()
                            }
                        } else {
                            Log.d("nvm", "Not resuming yet - only ${idleTime}ms idle, need ${idleTimeout}ms (${idleTimeout - idleTime}ms remaining)")
                            // Don't resume - let idle checker continue
                        }
                    } else if (isPaused && isRunning && lastUserInteractionTime == 0L) {
                        // Service was paused by screen lock (not touch), so resume instantly
                        // But still check for media playback and calls
                        val mediaPlaying = isMediaPlaying()
                        val inCall = isInCall()
                        if (mediaPlaying) {
                            Log.d("nvm", "Screen unlocked but media is playing - NOT resuming, starting idle checker")
                            // Start idle checker to wait for media to stop
                            lastUserInteractionTime = System.currentTimeMillis()
                            handler.post(idleCheckerRunnable)
                        } else if (inCall) {
                            Log.d("nvm", "Screen unlocked but in a call - NOT resuming, starting idle checker")
                            // Start idle checker to wait for call to end
                            lastUserInteractionTime = System.currentTimeMillis()
                            handler.post(idleCheckerRunnable)
                        } else {
                            Log.d("nvm", "Auto-resuming service instantly after unlock (paused by screen lock)")
                            resumeService()
                        }
                    } else {
                        Log.d("nvm", "Resume not needed (isPaused=$isPaused, isRunning=$isRunning)")
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true

                    // Use a short delay to let keyguard state settle
                    handler.postDelayed({
                        val keyguardManager = getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager
                        val isLocked = keyguardManager.isKeyguardLocked

                        Log.d("nvm", "Screen turned ON - isLocked: $isLocked, isVibrationServiceRunning: $isVibrationServiceRunning")

                        if (!isLocked && isVibrationServiceRunning) {
                            // Screen is ON and unlocked - stop vibration
                            Log.d("nvm", "Screen unlocked (via SCREEN_ON) - stopping vibration alert")
                            stopVibrationAlert()

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
                                // Service was paused by screen lock (not touch), check media and calls
                                val mediaPlaying = isMediaPlaying()
                                val inCall = isInCall()
                                if (mediaPlaying) {
                                    Log.d("nvm", "Screen unlocked (via SCREEN_ON) but media is playing - NOT resuming, starting idle checker")
                                    lastUserInteractionTime = System.currentTimeMillis()
                                    handler.post(idleCheckerRunnable)
                                } else if (inCall) {
                                    Log.d("nvm", "Screen unlocked (via SCREEN_ON) but in a call - NOT resuming, starting idle checker")
                                    lastUserInteractionTime = System.currentTimeMillis()
                                    handler.post(idleCheckerRunnable)
                                } else {
                                    Log.d("nvm", "Auto-resuming service instantly after unlock (via SCREEN_ON, paused by screen lock)")
                                    resumeService()
                                }
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
                Log.d("nvm", "⏰ Timer reached 0 - Executing action NOW")
                handleFiverrAction()
                nextLaunchTime = System.currentTimeMillis() + launchInterval

                // Reset the timer to restart countdown after action
                overlayView?.resetTimer()
                Log.d("nvm", "Timer reset after Fiverr action - countdown restarted")

                // Schedule next action with full interval
                handler.postDelayed(this, launchInterval)
                Log.d("nvm", "Next action scheduled in ${launchInterval}ms")
            } else {
                Log.w("nvm", "⚠️ launchRunnable skipped execution - isRunning=$isRunning, isPaused=$isPaused")
                // DO NOT reschedule when paused - wait for resumeService() to schedule with correct remaining time
            }
        }
    }

    // Check if user has been idle for 1 minute, then auto-resume (unless media is playing)
    private val idleCheckerRunnable = object : Runnable {
        override fun run() {
            if (isRunning && isPaused) {
                val idleTime = System.currentTimeMillis() - lastUserInteractionTime

                // Check if media is playing or user is in a call or no internet
                val mediaPlaying = isMediaPlaying()
                val inCall = isInCall()
                val noInternet = !hasInternet

                if (idleTime >= idleTimeout) {
                    if (mediaPlaying) {
                        Log.d("nvm", "User idle for 1 minute but media is playing - NOT resuming")
                    } else if (inCall) {
                        Log.d("nvm", "User idle for 1 minute but in a call - NOT resuming")
                    } else if (noInternet) {
                        Log.d("nvm", "User idle for 1 minute but no internet connection - NOT resuming")
                    } else {
                        Log.d("nvm", "User idle for 1 minute, no media playing, no call, internet available - auto-resuming service")
                        resumeService()
                    }
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

        Log.d("nvm", "User touch detected - pausing service")
        lastUserInteractionTime = System.currentTimeMillis()

        if (!isPaused) {
            pauseService()
        }
    }

    // Pause the service (stop opening Fiverr, turn timer red)
    private fun pauseService(startIdleChecker: Boolean = true, shouldRestoreBrightness: Boolean = true) {
        isPaused = true
        overlayView?.setPaused(true) // Turn timer red

        // Remove pending launchRunnable callbacks to prevent them from rescheduling
        handler.removeCallbacks(launchRunnable)
        Log.d("nvm", "Removed pending launchRunnable callbacks when pausing")

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

        Log.d("nvm", "Service paused ${if (startIdleChecker) "with idle checker" else "without idle checker"}")
    }

    // Resume the service (start opening Fiverr, turn timer green)
    private fun resumeService() {
        Log.d("nvm", "resumeService() called - BEFORE: isRunning=$isRunning, isPaused=$isPaused")

        // Calculate remaining time from when it was paused
        val pausedTimeMs = overlayView?.getPausedTime() ?: 0L
        val remainingTime = (launchInterval - pausedTimeMs).coerceAtLeast(0)

        Log.d("nvm", "Service resuming - was paused at ${pausedTimeMs}ms, remaining time: ${remainingTime}ms")

        // IMPORTANT: Call resumeTimer() first - it will handle unpausing internally
        // Don't call setPaused(false) before resumeTimer() as it breaks the resume logic
        overlayView?.resumeTimer() // Resume from paused position (not reset!) and unpause

        isPaused = false
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
                        hasInternet = newHasInternet
                        Log.d("nvm", "Network state changed: hasInternet = $hasInternet")

                        if (hasInternet) {
                            Log.d("nvm", "Internet connected - clearing Fiverr from recents for fresh start")

                            // Clear Fiverr from recents to ensure fresh start after reconnection
                            val accessibilityService = FiverrAccessibilityService.getInstance()
                            if (accessibilityService != null) {
                                // Only clear if Fiverr is currently in foreground
                                if (ForegroundAppHolder.currentPackage == "com.fiverr.fiverr") {
                                    handler.postDelayed({
                                        accessibilityService.clearFiverrFromRecents { success ->
                                            if (success) {
                                                Log.d("nvm", "Successfully cleared Fiverr from recents after internet reconnection")
                                                // Wait a moment before the idle checker will resume and relaunch Fiverr
                                            } else {
                                                Log.w("nvm", "Failed to clear Fiverr from recents")
                                            }
                                        }
                                    }, 500) // Small delay to ensure network is stable
                                } else {
                                    Log.d("nvm", "Fiverr not in foreground, will launch fresh when service resumes")
                                }
                            } else {
                                Log.w("nvm", "Accessibility service not available to clear Fiverr")
                            }
                            // Don't auto-resume here - let idle checker handle it
                        } else {
                            Log.d("nvm", "Internet lost - pausing service")
                            if (!isPaused && isRunning) {
                                lastUserInteractionTime = System.currentTimeMillis()
                                pauseService(startIdleChecker = true, shouldRestoreBrightness = false) // Keep brightness low
                            }
                        }
                    }
                }

                override fun onLost(network: android.net.Network) {
                    Log.d("nvm", "Network lost: $network")
                    hasInternet = false
                    Log.d("nvm", "Internet lost - pausing service")
                    if (!isPaused && isRunning) {
                        lastUserInteractionTime = System.currentTimeMillis()
                        pauseService(startIdleChecker = true, shouldRestoreBrightness = false) // Keep brightness low
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
        const val ACTION_PAUSE_FOR_SLIDER = "ACTION_PAUSE_FOR_SLIDER"
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

        // Draw remaining time text
        val seconds = (remaining / 1000).toInt()
        val text = "${seconds}s"
        canvas.drawText(text, centerX, centerY + 8, textPaint)
        }
    }

