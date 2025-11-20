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
     * For Android 13+, use a combination of BACK button and HOME to effectively clear the app
     */
    fun clearFiverrFromRecents(callback: (Boolean) -> Unit) {
        try {
            Log.d("nvm", "Attempting to clear Fiverr from recents (Android 13+ compatible)")

            // Set automated gesture flag to ignore touch events during clearing process
            TouchInteractionCallback.isAutomatedGestureActive = true
            Log.d("nvm", "Set automated gesture flag for clearing Fiverr from recents")

            // Check if Fiverr is currently in foreground
            val currentPackage = ForegroundAppHolder.currentPackage
            val isFiverrInForeground = currentPackage == "com.fiverr.fiverr"

            if (isFiverrInForeground) {
                Log.d("nvm", "Fiverr is in foreground - pressing HOME to send it to background")

                // Press HOME button to send Fiverr to background
                val homePressed = performGlobalAction(GLOBAL_ACTION_HOME)

                if (homePressed) {
                    Log.d("nvm", "Successfully sent Fiverr to background via HOME")

                    // Wait a bit for the transition, then clear from recents
                    Handler(Looper.getMainLooper()).postDelayed({
                        clearFromRecentsViaSwipe(callback)
                    }, 500)
                } else {
                    Log.w("nvm", "Failed to press HOME button")
                    // Clear flag on failure
                    TouchInteractionCallback.isAutomatedGestureActive = false
                    callback(false)
                }
            } else {
                Log.d("nvm", "Fiverr not in foreground - attempting to clear from recents directly")
                clearFromRecentsViaSwipe(callback)
            }

        } catch (e: Exception) {
            Log.e("nvm", "Error clearing Fiverr from recents: ${e.message}", e)
            // Clear flag on error
            TouchInteractionCallback.isAutomatedGestureActive = false
            callback(false)
        }
    }

    /**
     * Actually clear from recents using swipe gesture
     */
    private fun clearFromRecentsViaSwipe(callback: (Boolean) -> Unit) {
        try {
            // Step 1: Open recents screen
            val recentsOpened = performGlobalAction(GLOBAL_ACTION_RECENTS)

            if (!recentsOpened) {
                Log.w("nvm", "Failed to open recents screen")
                // Clear flag on failure
                TouchInteractionCallback.isAutomatedGestureActive = false
                callback(false)
                return
            }

            Log.d("nvm", "Opened recents screen, waiting for UI to settle")

            // Step 2: Wait for recents UI to fully load (longer delay for Android 13)
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    // Step 3: Find and dismiss Fiverr from recents
                    val rootNode = rootInActiveWindow
                    if (rootNode == null) {
                        Log.w("nvm", "No root node found in recents screen")
                        // Close recents and return
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        // Clear flag on failure
                        TouchInteractionCallback.isAutomatedGestureActive = false
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

                        Log.d("nvm", "Fiverr card bounds: left=${rect.left}, top=${rect.top}, right=${rect.right}, bottom=${rect.bottom}")

                        // Get screen width to determine which column the card is in
                        val displayMetrics = resources.displayMetrics
                        val screenWidth = displayMetrics.widthPixels
                        val screenCenterX = screenWidth / 2

                        // Determine if card is in left or right column
                        val cardCenterX = rect.centerX()
                        val isLeftColumn = cardCenterX < screenCenterX

                        Log.d("nvm", "Screen width: $screenWidth, Screen center: $screenCenterX, Card center: $cardCenterX, Is left column: $isLeftColumn")

                        // MIUI recents: swipe left for left column, swipe right for right column
                        val startX = rect.centerX().toFloat()
                        val startY = rect.centerY().toFloat()
                        val endX = if (isLeftColumn) {
                            // Left column - swipe left (to the edge)
                            0f
                        } else {
                            // Right column - swipe right (to the edge)
                            screenWidth.toFloat()
                        }
                        val endY = startY // Same Y position (horizontal swipe)

                        Log.d("nvm", "Performing ${if (isLeftColumn) "LEFT" else "RIGHT"} swipe: ($startX, $startY) -> ($endX, $endY)")

                        performSwipeGesture(startX, startY, endX, endY) { success ->
                            if (success) {
                                Log.d("nvm", "Successfully swiped Fiverr card")
                                // Wait a bit for dismissal animation, then close recents
                                Handler(Looper.getMainLooper()).postDelayed({
                                    performGlobalAction(GLOBAL_ACTION_BACK)
                                    // Clear automated gesture flag after recents is closed
                                    Handler(Looper.getMainLooper()).postDelayed({
                                        TouchInteractionCallback.isAutomatedGestureActive = false
                                        Log.d("nvm", "Cleared automated gesture flag after clearing Fiverr from recents")
                                    }, 200)
                                    callback(true)
                                }, 300)
                            } else {
                                Log.w("nvm", "Failed to swipe Fiverr card")
                                performGlobalAction(GLOBAL_ACTION_BACK)
                                // Clear flag after closing recents
                                Handler(Looper.getMainLooper()).postDelayed({
                                    TouchInteractionCallback.isAutomatedGestureActive = false
                                    Log.d("nvm", "Cleared automated gesture flag after failed swipe")
                                }, 200)
                                callback(false)
                            }
                        }
                    } else {
                        Log.d("nvm", "Fiverr not found in recents (already cleared or not opened)")
                        // Close recents
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        // Clear flag after closing recents
                        Handler(Looper.getMainLooper()).postDelayed({
                            TouchInteractionCallback.isAutomatedGestureActive = false
                            Log.d("nvm", "Cleared automated gesture flag - Fiverr not in recents")
                        }, 200)
                        callback(true) // Consider success if already not in recents
                    }

                } catch (e: Exception) {
                    Log.e("nvm", "Error finding Fiverr in recents: ${e.message}", e)
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    // Clear flag on error
                    TouchInteractionCallback.isAutomatedGestureActive = false
                    callback(false)
                }
            }, 1200) // Longer delay for Android 13 animation

            // Safety timeout: clear flag after 5 seconds no matter what (in case callbacks fail)
            Handler(Looper.getMainLooper()).postDelayed({
                if (TouchInteractionCallback.isAutomatedGestureActive) {
                    Log.w("nvm", "Safety timeout: clearing automated gesture flag after 5 seconds")
                    TouchInteractionCallback.isAutomatedGestureActive = false
                }
            }, 5000)

        } catch (e: Exception) {
            Log.e("nvm", "Error in clearFromRecentsViaSwipe: ${e.message}", e)
            // Clear flag on error
            TouchInteractionCallback.isAutomatedGestureActive = false
            callback(false)
        }
    }

    /**
     * Find Fiverr app card in recents screen (Android 13+ compatible)
     */
    private fun findFiverrInRecents(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Method 1: Check package name
        if (node.packageName?.toString()?.contains("fiverr", ignoreCase = true) == true) {
            Log.d("nvm", "Found node with Fiverr package: ${node.packageName}, class: ${node.className}")
            // Try to find the parent card that can be dismissed
            var parent = node.parent
            var depth = 0
            while (parent != null && depth < 10) { // Increased depth for Android 13
                if (parent.isClickable || parent.isDismissable) {
                    Log.d("nvm", "Found dismissable parent at depth $depth")
                    return parent
                }
                parent = parent.parent
                depth++
            }
            return node
        }

        // Method 2: Check text content (app name might be "Fiverr")
        node.text?.let { text ->
            if (text.toString().contains("Fiverr", ignoreCase = true)) {
                Log.d("nvm", "Found node with 'Fiverr' text: $text")
                var parent = node.parent
                var depth = 0
                while (parent != null && depth < 10) {
                    if (parent.isClickable || parent.isDismissable) {
                        Log.d("nvm", "Found dismissable parent at depth $depth (via text)")
                        return parent
                    }
                    parent = parent.parent
                    depth++
                }
                return node
            }
        }

        // Method 3: Check content description
        node.contentDescription?.let { desc ->
            if (desc.toString().contains("Fiverr", ignoreCase = true)) {
                Log.d("nvm", "Found node with 'Fiverr' in content description: $desc")
                var parent = node.parent
                var depth = 0
                while (parent != null && depth < 10) {
                    if (parent.isClickable || parent.isDismissable) {
                        Log.d("nvm", "Found dismissable parent at depth $depth (via contentDescription)")
                        return parent
                    }
                    parent = parent.parent
                    depth++
                }
                return node
            }
        }

        // Recursively search children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFiverrInRecents(child)
            if (result != null) {
                return result
            }
        }

        return null
    }

    /**
     * Perform a swipe gesture (optimized for Android 13)
     */
    private fun performSwipeGesture(startX: Float, startY: Float, endX: Float, endY: Float, callback: (Boolean) -> Unit) {
        try {
            Log.d("nvm", "Performing swipe: ($startX, $startY) -> ($endX, $endY)")

            val path = Path()
            path.moveTo(startX, startY)
            path.lineTo(endX, endY)

            val gestureBuilder = GestureDescription.Builder()
            // Faster swipe for better dismissal on Android 13 (150ms instead of 250ms)
            val gestureStroke = GestureDescription.StrokeDescription(path, 0, 150)
            gestureBuilder.addStroke(gestureStroke)

            val result = dispatchGesture(
                gestureBuilder.build(),
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        Log.d("nvm", "Swipe gesture completed")
                        callback(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        Log.w("nvm", "Swipe gesture cancelled")
                        callback(false)
                    }
                },
                null
            )

            if (!result) {
                Log.w("nvm", "Failed to dispatch swipe gesture")
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
