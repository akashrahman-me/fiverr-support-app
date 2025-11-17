package com.akash.fiverrsupport

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class FiverrAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("nvm", "FiverrAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We'll use this later for automation tasks
//        event?.let {
//            Log.d("nvm", "Accessibility event: ${it.eventType}")
//        }
    }

    override fun onInterrupt() {
        Log.d("nvm", "FiverrAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("nvm", "FiverrAccessibilityService destroyed")
    }

    companion object {
        private var instance: FiverrAccessibilityService? = null

        fun isAccessibilityEnabled(): Boolean {
            return instance != null
        }

        fun getInstance(): FiverrAccessibilityService? {
            return instance
        }
    }

    init {
        instance = this
    }
}

