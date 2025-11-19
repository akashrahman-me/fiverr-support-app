package com.akash.fiverrsupport

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

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

            // Step 1: Open recents screen
            val recentsOpened = performGlobalAction(GLOBAL_ACTION_RECENTS)

            if (!recentsOpened) {
                Log.w("nvm", "Failed to open recents screen")
                callback(false)
                return
            }

            Log.d("nvm", "Opened recents screen, waiting for UI to settle")

            // Step 2: Wait for recents UI to fully load
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    // Step 3: Find and dismiss Fiverr from recents
                    val rootNode = rootInActiveWindow
                    if (rootNode == null) {
                        Log.w("nvm", "No root node found in recents screen")
                        // Close recents and return
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        callback(false)
                        return@postDelayed
                    }

                    // Find Fiverr app card in recents
                    val fiverrCard = findFiverrInRecents(rootNode)

                    if (fiverrCard != null) {
                        Log.d("nvm", "Found Fiverr card in recents, performing swipe to dismiss")

                        // Get card bounds for swipe gesture
                        val rect = Rect()
                        fiverrCard.getBoundsInScreen(rect)

                        // Swipe up to dismiss (center of card, swipe up)
                        val startX = rect.centerX().toFloat()
                        val startY = rect.centerY().toFloat()
                        val endY = 0f // Swipe to top

                        performSwipeGesture(startX, startY, startX, endY) { success ->
                            // Don't recycle here - will be recycled at the end
                            if (success) {
                                Log.d("nvm", "Successfully cleared Fiverr from recents")
                                // Close recents screen
                                Handler(Looper.getMainLooper()).postDelayed({
                                    performGlobalAction(GLOBAL_ACTION_BACK)
                                }, 200)
                                callback(true)
                            } else {
                                Log.w("nvm", "Failed to swipe Fiverr card")
                                performGlobalAction(GLOBAL_ACTION_BACK)
                                callback(false)
                            }
                        }
                    } else {
                        Log.d("nvm", "Fiverr not found in recents (already cleared or not opened)")
                        // Close recents
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        callback(true) // Consider success if already not in recents
                    }

                } catch (e: Exception) {
                    Log.e("nvm", "Error finding Fiverr in recents: ${e.message}", e)
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    callback(false)
                }
            }, 800) // Wait for recents animation to complete

        } catch (e: Exception) {
            Log.e("nvm", "Error clearing Fiverr from recents: ${e.message}", e)
            callback(false)
        }
    }

    /**
     * Find Fiverr app card in recents screen
     */
    private fun findFiverrInRecents(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Check if this node or its children contain "Fiverr" or package name
        if (node.packageName?.toString()?.contains("fiverr", ignoreCase = true) == true) {
            // Found a node related to Fiverr
            // Try to find the parent card that can be dismissed
            var parent = node.parent
            var depth = 0
            while (parent != null && depth < 5) {
                if (parent.isClickable || parent.isDismissable) {
                    return parent
                }
                parent = parent.parent
                depth++
            }
            return node
        }

        // Recursively search children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFiverrInRecents(child)
            if (result != null) {
                child.recycle()
                return result
            }
            child.recycle()
        }

        return null
    }

    /**
     * Perform a swipe gesture
     */
    private fun performSwipeGesture(startX: Float, startY: Float, endX: Float, endY: Float, callback: (Boolean) -> Unit) {
        try {
            val path = Path()
            path.moveTo(startX, startY)
            path.lineTo(endX, endY)

            val gestureBuilder = GestureDescription.Builder()
            val gestureStroke = GestureDescription.StrokeDescription(path, 0, 250) // 250ms swipe
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
            Log.e("nvm", "Error performing swipe gesture: ${e.message}", e)
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
