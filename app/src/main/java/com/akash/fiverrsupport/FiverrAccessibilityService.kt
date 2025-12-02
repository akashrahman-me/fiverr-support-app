package com.akash.fiverrsupport

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Singleton holder for tracking the current foreground app package.
 * Used by isAppInForeground() utility to check if Fiverr is already open.
 */
object ForegroundAppHolder {
    var currentPackage: String? = null
}

/**
 * Accessibility Service for Fiverr Support App
 * 
 * Provides:
 * 1. Foreground app tracking (to detect if Fiverr is open)
 * 2. Pull-down gesture for scrolling/refreshing Fiverr
 * 3. Screen lock capability via GLOBAL_ACTION_LOCK_SCREEN
 */
class FiverrAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("nvm", "FiverrAccessibilityService connected and ready")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Track foreground app changes - used to detect if Fiverr is already open
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { packageName ->
                ForegroundAppHolder.currentPackage = packageName
            }
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
     * Perform a pull-down/scroll gesture in the Fiverr app.
     * Simulates a swipe from top to bottom (pull to refresh).
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
            val endY = screenHeight * 0.7f   // End at 70% from top

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

        fun getInstance(): FiverrAccessibilityService? = instance

        fun isAccessibilityEnabled(): Boolean = instance != null
    }
}
