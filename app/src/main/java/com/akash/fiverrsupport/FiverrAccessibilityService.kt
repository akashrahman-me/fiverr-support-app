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
        // We only need to swipe LEFT on the left column to dismiss it
        val leftColumnX = screenWidth * 0.25f
        val centerY = screenHeight * 0.5f
        
        // Swipe LEFT to dismiss the app in left column (Fiverr)
        performHorizontalSwipe(leftColumnX, centerY, -screenWidth * 0.4f) { swipeSuccess ->
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
     * Perform a horizontal swipe gesture.
     * @param startX Starting X position
     * @param startY Starting Y position  
     * @param deltaX Distance to swipe (negative = left, positive = right)
     */
    private fun performHorizontalSwipe(startX: Float, startY: Float, deltaX: Float, callback: (Boolean) -> Unit) {
        try {
            val path = Path()
            path.moveTo(startX, startY)
            path.lineTo(startX + deltaX, startY)
            
            val gestureBuilder = GestureDescription.Builder()
            val gestureStroke = GestureDescription.StrokeDescription(path, 0, 200) // 200ms duration
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
