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
        
        // Detect user touch/interaction events for inactivity monitoring
        // OPTIMIZATION: Check if monitoring is actually active before doing anything.
        // This avoids performance overhead of processing high-volume events (like scroll)
        // when we don't need them. FiverrLauncherService.isMonitoringActive is a static volatile flag.
        if (FiverrLauncherService.isMonitoringActive) {
            when (event?.eventType) {
                AccessibilityEvent.TYPE_VIEW_CLICKED,
                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
                AccessibilityEvent.TYPE_VIEW_SCROLLED,
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
                AccessibilityEvent.TYPE_GESTURE_DETECTION_START -> {
                    // User is actively interacting - notify FiverrLauncherService
                    notifyUserActivity()
                }
            }
        }
    }
    
    /**
     * Notify FiverrLauncherService that user activity was detected.
     * This stops the inactivity monitoring.
     */
    private fun notifyUserActivity() {
        try {
            val intent = android.content.Intent(this, FiverrLauncherService::class.java).apply {
                action = FiverrLauncherService.ACTION_USER_ACTIVITY
            }
            startService(intent)
        } catch (e: Exception) {
            // Service might not be running - ignore
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

    /**
     * Clear Fiverr from recent apps.
     * Opens the recents view and swipes to dismiss Fiverr.
     * 
     * For MIUI 2-column grid:
     * - Left column: swipe LEFT to dismiss
     * - Right column: swipe RIGHT to dismiss
     * 
     * We'll try both directions to ensure it gets cleared regardless of position.
     */
    fun clearFiverrFromRecents(callback: (Boolean) -> Unit) {
        try {
            Log.d("nvm", "🗑️ Opening recents to clear Fiverr...")
            
            // Step 1: Open recent apps
            val recentsOpened = performGlobalAction(GLOBAL_ACTION_RECENTS)
            if (!recentsOpened) {
                Log.e("nvm", "Failed to open recents")
                callback(false)
                return
            }
            
            // Step 2: Wait for recents to open, then perform swipe gestures
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                performClearSwipeGestures(callback)
            }, 800) // Wait 800ms for recents to fully open
            
        } catch (e: Exception) {
            Log.e("nvm", "Error clearing Fiverr from recents: ${e.message}", e)
            callback(false)
        }
    }
    
    /**
     * Perform swipe gesture to clear the last opened app from recents.
     * 
     * MIUI 2-column grid layout:
     * - Last opened app appears in the LEFT column
     * - Swipe LEFT to dismiss apps in the left column
     */
    private fun performClearSwipeGestures(callback: (Boolean) -> Unit) {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        
        // In MIUI 2-column grid, the last opened app (Fiverr) is in the LEFT column
        // Start from center of left column (25% of width) and swipe LEFT
        // End position must be > 0 to avoid "Path bounds must not be negative" error
        val startX = screenWidth * 0.35f  // Start a bit more to the right
        val endX = 10f  // End near left edge (but not negative)
        val centerY = screenHeight * 0.5f
        
        // Swipe LEFT to dismiss the app in left column (Fiverr)
        performHorizontalSwipe(startX, centerY, endX, centerY) { swipeSuccess ->
            if (swipeSuccess) {
                Log.d("nvm", "✅ Left column swipe completed - Fiverr dismissed")
            } else {
                Log.w("nvm", "Left column swipe failed")
            }
            
            // Go back to home after clearing
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                performGlobalAction(GLOBAL_ACTION_HOME)
                Log.d("nvm", "Returned to home screen")
                callback(swipeSuccess)
            }, 500)
        }
    }
    
    /**
     * Perform a swipe gesture from start to end coordinates.
     * @param startX Starting X position
     * @param startY Starting Y position  
     * @param endX Ending X position (must be >= 0)
     * @param endY Ending Y position (must be >= 0)
     */
    private fun performHorizontalSwipe(startX: Float, startY: Float, endX: Float, endY: Float, callback: (Boolean) -> Unit) {
        try {
            // Ensure all coordinates are positive
            val safeStartX = maxOf(10f, startX)
            val safeStartY = maxOf(10f, startY)
            val safeEndX = maxOf(10f, endX)
            val safeEndY = maxOf(10f, endY)
            
            val path = Path()
            path.moveTo(safeStartX, safeStartY)
            path.lineTo(safeEndX, safeEndY)
            
            val gestureBuilder = GestureDescription.Builder()
            val gestureStroke = GestureDescription.StrokeDescription(path, 0, 250) // 250ms duration for smoother swipe
            gestureBuilder.addStroke(gestureStroke)
            
            val result = dispatchGesture(
                gestureBuilder.build(),
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        callback(true)
                    }
                    
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        callback(false)
                    }
                },
                null
            )
            
            if (!result) {
                callback(false)
            }
        } catch (e: Exception) {
            Log.e("nvm", "Error performing horizontal swipe: ${e.message}", e)
            callback(false)
        }
    }


    companion object {
        private var instance: FiverrAccessibilityService? = null

        fun getInstance(): FiverrAccessibilityService? = instance

        fun isAccessibilityEnabled(): Boolean = instance != null
    }
}
