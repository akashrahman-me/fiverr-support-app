package com.akash.fiverrsupport

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager

/**
 * Tiny always-on activity whose only job is to keep the screen awake.
 * It is launched by the service while "enabled".
 */
class AlwaysOnActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on and show even if device is locked
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        // Use a built-in minimal layout; we don't actually show anything meaningful
        setContentView(android.R.layout.simple_list_item_1)

        // Make window as small as possible
        window.setLayout(1, 1)
    }

    override fun onDestroy() {
        super.onDestroy()
        // When destroyed, flags go away and screen can turn off again per system timeout
    }
}

