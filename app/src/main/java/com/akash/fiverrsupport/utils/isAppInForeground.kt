package com.akash.fiverrsupport.utils

import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import com.akash.fiverrsupport.ForegroundAppHolder

/**
 * Check if a specific app is currently in the foreground
 * Uses ForegroundAppHolder (updated by FiverrAccessibilityService) for real-time detection
 * Falls back to UsageStatsManager if accessibility service is not running
 */
fun isAppInForeground(context: Context, packageName: String): Boolean {
    // Primary method: Check ForegroundAppHolder (updated by FiverrAccessibilityService)
    val currentPackage = ForegroundAppHolder.currentPackage
    if (currentPackage != null) {
        val result = currentPackage == packageName
        Log.d("nvm", "Foreground detection (Accessibility): currentApp='$currentPackage', target='$packageName', match=$result")
        return result
    }

    // Fallback method: Use UsageStatsManager (less accurate, requires recent usage)
    Log.d("nvm", "FiverrAccessibilityService not available, falling back to UsageStatsManager")
    return try {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return false
        val time = System.currentTimeMillis()
        val stats = usm.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            time - 1000 * 10,
            time
        )

        val recent = stats.maxByOrNull { it.lastTimeUsed }
        val result = recent?.packageName == packageName
        Log.d("nvm", "Foreground detection (UsageStats): currentApp='${recent?.packageName}', target='$packageName', match=$result")
        result
    } catch (e: Exception) {
        Log.e("nvm", "Error checking foreground app: ${e.message}")
        false
    }
}
