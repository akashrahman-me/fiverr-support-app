package com.akash.fiverrsupport

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.akash.fiverrsupport.ui.theme.FiverrSupportTheme
import androidx.core.net.toUri
import com.akash.fiverrsupport.ui.components.PermissionToggleItem
import androidx.core.content.edit
import com.akash.fiverrsupport.ui.components.GradientCard
import com.akash.fiverrsupport.ui.components.SectionHeader

fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    @Suppress("DEPRECATION")
    for (service in activityManager.getRunningServices(Int.MAX_VALUE)) {
        if (serviceClass.name == service.service.className) {
            return true
        }
    }
    return false
}

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted) {
            Toast.makeText(this, "Notification permission is required for the service", Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("BatteryLife")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Request battery optimization exemption
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = "package:$packageName".toUri()
            }
            try {
                startActivity(intent)
                Toast.makeText(
                    this,
                    "Please allow battery optimization exemption for better performance",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Could not request battery optimization", Toast.LENGTH_SHORT).show()
            }
        }

        // Request overlay permission for Android 10+ to launch apps from background
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:$packageName".toUri()
            )
            startActivity(intent)
            Toast.makeText(
                this,
                "Please grant overlay permission to launch apps from background",
                Toast.LENGTH_LONG
            ).show()
        }

        // Request WRITE_SETTINGS permission for brightness control
        if (!Settings.System.canWrite(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = "package:$packageName".toUri()
            }
            startActivity(intent)
            Toast.makeText(
                this,
                "Please grant permission to modify system settings for brightness control",
                Toast.LENGTH_LONG
            ).show()
        }

        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }


        setContent {
            FiverrSupportTheme {
                Root()
            }
        }
    }
}

@SuppressLint("BatteryLife")
@Composable
fun Root(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    val sharedPrefs = context.getSharedPreferences("FiverrSupportPrefs", Context.MODE_PRIVATE)

        val intervalOptions = listOf(5, 10, 30, 60, 180, 300, 600, 1800)
        val idleTimeoutOptions = listOf(5, 10, 30, 60, 120, 300)

        var isEnabled by remember {
            mutableStateOf(isServiceRunning(context, FiverrLauncherService::class.java))
        }

        val savedIntervalSeconds = (sharedPrefs.getLong("service_interval", 30000L) / 1000).toInt()
        val savedIntervalIndex = intervalOptions.indexOf(savedIntervalSeconds).let { if (it >= 0) it else 2 }
        var selectedIntervalIndex by remember { mutableStateOf(savedIntervalIndex) }

        val savedIdleTimeoutSeconds = (sharedPrefs.getLong("idle_timeout", 5000L) / 1000).toInt()
        val savedIdleTimeoutIndex = idleTimeoutOptions.indexOf(savedIdleTimeoutSeconds).let { if (it >= 0) it else 0 }
        var selectedIdleTimeoutIndex by remember { mutableStateOf(savedIdleTimeoutIndex) }

        var isOverlayEnabled by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
        var isNotificationEnabled by remember {
            mutableStateOf(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                } else true
            )
        }
        var isBatteryOptimizationDisabled by remember {
            mutableStateOf((context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName))
        }
        var isWriteSettingsEnabled by remember { mutableStateOf(Settings.System.canWrite(context)) }
        var isPhoneStatePermissionEnabled by remember {
            mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED)
        }
        var isAccessibilityEnabled by remember {
            mutableStateOf(com.akash.fiverrsupport.utils.isAccessibilityServiceEnabled(context))
        }

        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    isEnabled = isServiceRunning(context, FiverrLauncherService::class.java)
                    isOverlayEnabled = Settings.canDrawOverlays(context)
                    isNotificationEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                    } else true
                    isBatteryOptimizationDisabled = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)
                    isWriteSettingsEnabled = Settings.System.canWrite(context)
                    isPhoneStatePermissionEnabled = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
                    isAccessibilityEnabled = com.akash.fiverrsupport.utils.isAccessibilityServiceEnabled(context)
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        LaunchedEffect(Unit) {
            isEnabled = isServiceRunning(context, FiverrLauncherService::class.java)
            Log.d("nvm", "Initial service check: isEnabled = $isEnabled")
        }

        fun toggleService(enabled: Boolean) {
            val serviceIntent = Intent(context, FiverrLauncherService::class.java)
            serviceIntent.action = if (enabled) FiverrLauncherService.ACTION_START else FiverrLauncherService.ACTION_STOP

            if (enabled) {
                val intervalSeconds = intervalOptions[selectedIntervalIndex]
                val timeoutSeconds = idleTimeoutOptions[selectedIdleTimeoutIndex]
                serviceIntent.putExtra(FiverrLauncherService.EXTRA_INTERVAL, (intervalSeconds * 1000).toLong())
                serviceIntent.putExtra(FiverrLauncherService.EXTRA_IDLE_TIMEOUT, (timeoutSeconds * 1000).toLong())
            }

            if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }

        fun updateInterval(intervalSeconds: Int, isUserInteracting: Boolean = false) {
            sharedPrefs.edit { putLong("service_interval", (intervalSeconds * 1000).toLong()) }
            if (isEnabled) {
                val serviceIntent = Intent(context, FiverrLauncherService::class.java)
                serviceIntent.action = if (isUserInteracting) FiverrLauncherService.ACTION_PAUSE_FOR_SLIDER else FiverrLauncherService.ACTION_UPDATE_INTERVAL
                serviceIntent.putExtra(FiverrLauncherService.EXTRA_INTERVAL, (intervalSeconds * 1000).toLong())
                context.startService(serviceIntent)
            }
        }

        fun updateIdleTimeout(timeoutSeconds: Int) {
            sharedPrefs.edit { putLong("idle_timeout", (timeoutSeconds * 1000).toLong()) }
        }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
    ) {
            // Gradient Header
            GradientCard(
                modifier = Modifier.fillMaxWidth(),
                gradient = androidx.compose.ui.graphics.Brush.horizontalGradient(
                    colors = listOf(
                        com.akash.fiverrsupport.ui.theme.GradientStart.copy(0.1f),
                        com.akash.fiverrsupport.ui.theme.GradientMiddle.copy(0.1f),
                        com.akash.fiverrsupport.ui.theme.GradientEnd.copy(0.1f)
                    )
                )
            ) {
                Column(
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(24.dp)
                ) {
                    Text(
                        text = "Fiverr Support",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = androidx.compose.ui.graphics.Color.White
                    )
                    Spacer(modifier = Modifier.padding(4.dp))
                    Text(
                        text = "Automated workflow assistant for Fiverr",
                        style = MaterialTheme.typography.bodyMedium,
                        color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.9f)
                    )
                }
            }

            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                // Features Section
                com.akash.fiverrsupport.ui.components.SectionHeader(
                    icon = Icons.Default.Build,
                    title = "Service Control",
                    subtitle = "Manage automation features"
                )

                Spacer(modifier = Modifier.padding(12.dp))

                PermissionToggleItem(
                    icon = Icons.Default.Build,
                    title = "Enable Service",
                    isEnabled = isEnabled,
                    enabledText = "Service is running",
                    disabledText = "Service is stopped",
                    onToggle = {
                        isEnabled = !isEnabled
                        toggleService(isEnabled)
                    }
                )

                Spacer(modifier = Modifier.padding(4.dp))

                val launchIntervalSeconds = intervalOptions[selectedIntervalIndex]
                val launchIntervalDisplay = when {
                    launchIntervalSeconds >= 60 -> "${launchIntervalSeconds / 60}m"
                    else -> "${launchIntervalSeconds}s"
                }

                com.akash.fiverrsupport.ui.components.ConfigSlider(
                    icon = Icons.Default.Build,
                    title = "Launch Interval",
                    currentValue = launchIntervalDisplay,
                    description = "App opens every $launchIntervalDisplay",
                    sliderValue = selectedIntervalIndex.toFloat(),
                    valueRange = 0f..(intervalOptions.size - 1).toFloat(),
                    steps = intervalOptions.size - 2,
                    onValueChange = {
                        selectedIntervalIndex = it.toInt()
                        val intervalSeconds = intervalOptions[selectedIntervalIndex]
                        updateInterval(intervalSeconds, isUserInteracting = true)
                    },
                    onValueChangeFinished = {
                        val intervalSeconds = intervalOptions[selectedIntervalIndex]
                        updateInterval(intervalSeconds, isUserInteracting = false)
                    },
                    optionsText = "Available: 5s, 10s, 30s, 1m, 3m, 5m, 10m, 30m"
                )

                Spacer(modifier = Modifier.padding(4.dp))

                val idleTimeoutSeconds = idleTimeoutOptions[selectedIdleTimeoutIndex]
                val idleTimeoutDisplay = when {
                    idleTimeoutSeconds >= 60 -> "${idleTimeoutSeconds / 60}m"
                    else -> "${idleTimeoutSeconds}s"
                }

                com.akash.fiverrsupport.ui.components.ConfigSlider(
                    icon = Icons.Default.Settings,
                    title = "Idle Timeout",
                    currentValue = idleTimeoutDisplay,
                    description = "Auto-resume after $idleTimeoutDisplay inactivity",
                    sliderValue = selectedIdleTimeoutIndex.toFloat(),
                    valueRange = 0f..(idleTimeoutOptions.size - 1).toFloat(),
                    steps = idleTimeoutOptions.size - 2,
                    onValueChange = {
                        selectedIdleTimeoutIndex = it.toInt()
                        val timeoutSeconds = idleTimeoutOptions[selectedIdleTimeoutIndex]
                        updateIdleTimeout(timeoutSeconds)
                    },
                    optionsText = "Available: 5s, 10s, 30s, 1m, 2m, 5m"
                )

                Spacer(modifier = Modifier.padding(16.dp))

                // Permissions Section
                SectionHeader(
                    icon = Icons.Default.Settings,
                    title = "Required Permissions",
                    subtitle = "Enable all permissions for optimal performance"
                )

                Spacer(modifier = Modifier.padding(12.dp))

                PermissionToggleItem(
                    icon = Icons.Default.Star,
                    title = "Display Overlay",
                    isEnabled = isOverlayEnabled,
                    enabledText = "Permission granted",
                    disabledText = "Required for background operations",
                    onToggle = {
                        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri())
                        context.startActivity(intent)
                        Toast.makeText(context, "Please grant overlay permission", Toast.LENGTH_LONG).show()
                    }
                )

                PermissionToggleItem(
                    icon = Icons.Default.Notifications,
                    title = "Notifications",
                    isEnabled = isNotificationEnabled,
                    enabledText = "Permission granted",
                    disabledText = "Required for service status",
                    onToggle = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            }
                            context.startActivity(intent)
                            Toast.makeText(context, "Please enable notifications", Toast.LENGTH_LONG).show()
                        }
                    }
                )

                PermissionToggleItem(
                    icon = Icons.Default.Warning,
                    title = "Battery Optimization",
                    isEnabled = isBatteryOptimizationDisabled,
                    enabledText = "Exemption granted",
                    disabledText = "Required for background operation",
                    onToggle = {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = "package:${context.packageName}".toUri()
                        }
                        try {
                            context.startActivity(intent)
                            Toast.makeText(context, "Please allow battery optimization exemption", Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, "Could not open battery settings", Toast.LENGTH_SHORT).show()
                        }
                    }
                )

                PermissionToggleItem(
                    icon = Icons.Default.Settings,
                    title = "System Settings",
                    isEnabled = isWriteSettingsEnabled,
                    enabledText = "Permission granted",
                    disabledText = "Required for brightness control",
                    onToggle = {
                        val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                            data = "package:${context.packageName}".toUri()
                        }
                        context.startActivity(intent)
                        Toast.makeText(context, "Please grant permission to modify settings", Toast.LENGTH_LONG).show()
                    }
                )

                PermissionToggleItem(
                    icon = Icons.Default.Build,
                    title = "Phone State",
                    isEnabled = isPhoneStatePermissionEnabled,
                    enabledText = "Permission granted",
                    disabledText = "Required for call detection",
                    onToggle = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = "package:${context.packageName}".toUri()
                        }
                        context.startActivity(intent)
                        Toast.makeText(context, "Please grant Phone permission", Toast.LENGTH_LONG).show()
                    }
                )

                PermissionToggleItem(
                    icon = Icons.Default.Star,
                    title = "Accessibility Service",
                    isEnabled = isAccessibilityEnabled,
                    enabledText = "Service enabled",
                    disabledText = "Required for gestures & app detection",
                    onToggle = {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        context.startActivity(intent)
                        Toast.makeText(context, "Please enable accessibility service", Toast.LENGTH_LONG).show()
                    }
                )

                Spacer(modifier = Modifier.padding(16.dp))
            }
        }
}
