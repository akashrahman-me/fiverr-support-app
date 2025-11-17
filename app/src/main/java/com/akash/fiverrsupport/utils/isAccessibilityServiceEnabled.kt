package com.akash.fiverrsupport.utils

import android.content.Context
import android.provider.Settings
import android.text.TextUtils

fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val service = "${context.packageName}/${context.packageName}.FiverrAccessibilityService"

    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    )

    return if (enabledServices.isNullOrEmpty()) {
        false
    } else {
        TextUtils.SimpleStringSplitter(':').apply {
            setString(enabledServices)
        }.any { it.equals(service, ignoreCase = true) }
    }
}

