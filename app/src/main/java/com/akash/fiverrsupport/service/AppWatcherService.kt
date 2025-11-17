package com.akash.fiverrsupport.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Singleton holder for tracking the current foreground app package
 */
object ForegroundAppHolder {
    var currentPackage: String? = null
}

/**
 * Accessibility service that monitors foreground app changes
 * More efficient than UsageStatsManager for real-time detection
 */
class AppWatcherService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("nvm", "AppWatcherService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { packageName ->
                ForegroundAppHolder.currentPackage = packageName
                Log.d("nvm", "Foreground app changed to: $packageName")
            }
        }
    }

    override fun onInterrupt() {
        Log.d("nvm", "AppWatcherService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        ForegroundAppHolder.currentPackage = null
        Log.d("nvm", "AppWatcherService destroyed")
    }
}

