package com.akash.fiverrsupport

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Singleton holder for tracking the current foreground app package
 */
object ForegroundAppHolder {
    var currentPackage: String? = null
}

/**
 * Callback for system-wide touch detection
 */
object TouchInteractionCallback {
    private var callback: (() -> Unit)? = null

    fun setCallback(onTouch: (() -> Unit)?) {
        callback = onTouch
    }

    fun notifyTouch() {
        callback?.invoke()
    }
}

class FiverrAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("nvm", "FiverrAccessibilityService connected and ready")

        // Log the current configuration
        val info = serviceInfo
        Log.d("nvm", "Accessibility event types: ${info?.eventTypes}")
        Log.d("nvm", "Accessibility flags: ${info?.flags}")
        Log.d("nvm", "Click detection enabled: ${(info?.eventTypes ?: 0) and AccessibilityEvent.TYPE_VIEW_CLICKED != 0}")
        Log.d("nvm", "Scroll detection enabled: ${(info?.eventTypes ?: 0) and AccessibilityEvent.TYPE_VIEW_SCROLLED != 0}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Track foreground app changes
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { packageName ->
                ForegroundAppHolder.currentPackage = packageName
                Log.d("nvm", "Foreground app changed to: $packageName")
            }
        }

        // Detect user interactions (clicks, touches) - Works on Android 13+
        // TYPE_VIEW_CLICKED captures all user taps/clicks including system UI
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            Log.d("nvm", "User click detected via accessibility (TYPE_VIEW_CLICKED)")
            TouchInteractionCallback.notifyTouch()
        }

        // Additional detection: Scrolling events (user swiping)
        else if (event?.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            Log.d("nvm", "User scroll detected via accessibility (TYPE_VIEW_SCROLLED)")
            TouchInteractionCallback.notifyTouch()
        }

        // Additional detection: Touch exploration (accessibility mode)
        else if (event?.eventType == AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START) {
            Log.d("nvm", "User touch exploration detected via accessibility")
            TouchInteractionCallback.notifyTouch()
        }
    }

    override fun onInterrupt() {
        Log.d("nvm", "FiverrAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d("nvm", "FiverrAccessibilityService destroyed")
    }

    /**
     * Perform a pull-down/scroll gesture in the Fiverr app
     * Simulates a swipe from top to bottom (pull to refresh)
     */
    fun performPullDownGesture(callback: (Boolean) -> Unit) {

        try {
            val displayMetrics = resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels

            // Create a path for pull-down gesture (swipe from top-center down)
            val path = Path()
            val startX = screenWidth / 2f
            val startY = screenHeight * 0.3f // Start at 30% from top
            val endY = screenHeight * 0.7f    // End at 70% from top

            path.moveTo(startX, startY)
            path.lineTo(startX, endY)

            // Create gesture description
            val gestureBuilder = GestureDescription.Builder()
            val gestureStroke = GestureDescription.StrokeDescription(path, 0, 300) // 300ms duration
            gestureBuilder.addStroke(gestureStroke)

            // Dispatch gesture
            val result = dispatchGesture(
                gestureBuilder.build(),
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        Log.d("nvm", "Pull-down gesture completed successfully")
                        callback(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        Log.w("nvm", "Pull-down gesture was cancelled")
                        callback(false)
                    }
                },
                null
            )

            if (!result) {
                Log.e("nvm", "Failed to dispatch pull-down gesture")
                callback(false)
            }
        } catch (e: Exception) {
            Log.e("nvm", "Error performing pull-down gesture: ${e.message}", e)
            callback(false)
        }
    }

    companion object {
        private var instance: FiverrAccessibilityService? = null

        fun getInstance(): FiverrAccessibilityService? {
            return instance
        }

        fun isAccessibilityEnabled(): Boolean {
            return instance != null
        }
    }
}
