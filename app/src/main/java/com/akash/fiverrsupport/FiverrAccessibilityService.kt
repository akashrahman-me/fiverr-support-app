package com.akash.fiverrsupport

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
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

    // Flag to track if automated gesture is currently running
    var isAutomatedGestureActive: Boolean = false

    fun setCallback(onTouch: (() -> Unit)?) {
        callback = onTouch
    }

    fun notifyTouch() {
        callback?.invoke()
    }
}

@SuppressLint("AccessibilityPolicy")
class FiverrAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("nvm", "FiverrAccessibilityService connected and ready")
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
            val packageName = event.packageName?.toString()
            Log.d("nvm", "User click detected via accessibility (TYPE_VIEW_CLICKED) - package: $packageName")
            TouchInteractionCallback.notifyTouch()
        }

        // Additional detection: Scrolling events (user swiping)
        else if (event?.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            val packageName = event.packageName?.toString()

            Log.d("nvm", "User scroll detected via accessibility (TYPE_VIEW_SCROLLED) - package: $packageName")

            // Only ignore Fiverr scroll if automated gesture is currently running
            if (packageName == "com.fiverr.fiverr" && TouchInteractionCallback.isAutomatedGestureActive) {
                Log.d("nvm", "Ignoring scroll from Fiverr - automated gesture is active (flag=true)")
            }
            // Detect all other scrolls including user scrolls in Fiverr (when flag is false)
            else {
                TouchInteractionCallback.notifyTouch()
            }
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

    /**
     * Clear Fiverr app from recent apps
     * Opens recents and dismisses Fiverr to ensure fresh start
     */
    fun clearFiverrFromRecents(callback: (Boolean) -> Unit) {
        try {
            Log.d("nvm", "Attempting to clear Fiverr from recents")

            // Method: Press back button to close Fiverr, then it will be in background
            // When we launch it again, it will start fresh
            val backPressed = performGlobalAction(GLOBAL_ACTION_BACK)

            if (backPressed) {
                Log.d("nvm", "Pressed back to close Fiverr app")
                callback(true)
            } else {
                Log.w("nvm", "Failed to press back button")
                callback(false)
            }
        } catch (e: Exception) {
            Log.e("nvm", "Error clearing Fiverr from recents: ${e.message}", e)
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
