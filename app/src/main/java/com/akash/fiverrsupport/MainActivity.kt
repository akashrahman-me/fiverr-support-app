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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.akash.fiverrsupport.ui.components.ConfigSlider

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
fun Root() {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    val sharedPrefs = context.getSharedPreferences("FiverrSupportPrefs", Context.MODE_PRIVATE)

    // Interval options in seconds
    val intervalOptions = listOf(10, 30, 60, 120, 180, 300, 600, 1800)

    var isEnabled by remember {
        mutableStateOf(isServiceRunning(context, FiverrLauncherService::class.java))
    }

    val savedIntervalSeconds = (sharedPrefs.getLong("service_interval", 30000L) / 1000).toInt()
    val savedIntervalIndex = intervalOptions.indexOf(savedIntervalSeconds).let { if (it >= 0) it else 1 }
    var selectedIntervalIndex by remember { mutableStateOf(savedIntervalIndex) }

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
    var isAccessibilityEnabled by remember {
        mutableStateOf(com.akash.fiverrsupport.utils.isAccessibilityServiceEnabled(context))
    }
    var isOverlayEnabled by remember {
        mutableStateOf(Settings.canDrawOverlays(context))
    }

    // Update permission states when resuming
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isEnabled = isServiceRunning(context, FiverrLauncherService::class.java)
                isNotificationEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                } else true
                isBatteryOptimizationDisabled = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)
                isAccessibilityEnabled = com.akash.fiverrsupport.utils.isAccessibilityServiceEnabled(context)
                isOverlayEnabled = Settings.canDrawOverlays(context)
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
            serviceIntent.putExtra(FiverrLauncherService.EXTRA_INTERVAL, (intervalSeconds * 1000).toLong())
        }

        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    fun updateInterval(intervalSeconds: Int) {
        sharedPrefs.edit { putLong("service_interval", (intervalSeconds * 1000).toLong()) }
        if (isEnabled) {
            val serviceIntent = Intent(context, FiverrLauncherService::class.java)
            serviceIntent.action = FiverrLauncherService.ACTION_UPDATE_INTERVAL
            serviceIntent.putExtra(FiverrLauncherService.EXTRA_INTERVAL, (intervalSeconds * 1000).toLong())
            context.startService(serviceIntent)
        }
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
                    text = "Keeps Fiverr active when screen is off",
                    style = MaterialTheme.typography.bodyMedium,
                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.9f)
                )
            }
        }

        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            // Service Control Section
            SectionHeader(
                icon = Icons.Default.Build,
                title = "Service Control",
                subtitle = "Manage automation"
            )

            Spacer(modifier = Modifier.padding(12.dp))

            PermissionToggleItem(
                icon = Icons.Default.Build,
                title = "Enable Service",
                isEnabled = isEnabled,
                enabledText = "Automation active - turn off screen to start",
                disabledText = "Service is stopped",
                onToggle = {
                    isEnabled = !isEnabled
                    toggleService(isEnabled)
                }
            )

            Spacer(modifier = Modifier.padding(8.dp))

            val intervalSeconds = intervalOptions[selectedIntervalIndex]
            val intervalDisplay = when {
                intervalSeconds >= 60 -> "${intervalSeconds / 60}m"
                else -> "${intervalSeconds}s"
            }

            ConfigSlider(
                icon = Icons.Default.Settings,
                title = "Refresh Interval",
                currentValue = intervalDisplay,
                description = "Fiverr refreshes every $intervalDisplay while screen is off",
                sliderValue = selectedIntervalIndex.toFloat(),
                valueRange = 0f..(intervalOptions.size - 1).toFloat(),
                steps = intervalOptions.size - 2,
                onValueChange = {
                    selectedIntervalIndex = it.toInt()
                },
                onValueChangeFinished = {
                    val seconds = intervalOptions[selectedIntervalIndex]
                    updateInterval(seconds)
                },
                optionsText = "Available: 10s, 30s, 1m, 2m, 3m, 5m, 10m, 30m"
            )

            Spacer(modifier = Modifier.padding(16.dp))

            // Check if all required permissions are granted
            val allPermissionsGranted = isNotificationEnabled &&
                    isBatteryOptimizationDisabled &&
                    isAccessibilityEnabled &&
                    isOverlayEnabled

            // Only show permissions section if not all permissions are granted
            if (!allPermissionsGranted) {
                SectionHeader(
                    icon = Icons.Default.Settings,
                    title = "Required Permissions",
                    subtitle = "Enable all for optimal performance"
                )

                Spacer(modifier = Modifier.padding(12.dp))

                if (!isNotificationEnabled) {
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
                    Spacer(modifier = Modifier.padding(4.dp))
                }

                if (!isBatteryOptimizationDisabled) {
                    PermissionToggleItem(
                        icon = Icons.Default.Warning,
                        title = "Battery Optimization",
                        isEnabled = isBatteryOptimizationDisabled,
                        enabledText = "Exemption granted",
                        disabledText = "Required for reliable background operation",
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
                    Spacer(modifier = Modifier.padding(4.dp))
                }

                if (!isAccessibilityEnabled) {
                    PermissionToggleItem(
                        icon = Icons.Default.Star,
                        title = "Accessibility Service",
                        isEnabled = isAccessibilityEnabled,
                        enabledText = "Service enabled",
                        disabledText = "Required for gestures & screen lock",
                        onToggle = {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            context.startActivity(intent)
                            Toast.makeText(context, "Please enable Fiverr Support accessibility service", Toast.LENGTH_LONG).show()
                        }
                    )
                    Spacer(modifier = Modifier.padding(4.dp))
                }

                if (!isOverlayEnabled) {
                    PermissionToggleItem(
                        icon = Icons.Default.Info,
                        title = "Display Over Apps",
                        isEnabled = isOverlayEnabled,
                        enabledText = "Permission granted",
                        disabledText = "Required for floating status indicator",
                        onToggle = {
                            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                                data = "package:${context.packageName}".toUri()
                            }
                            try {
                                context.startActivity(intent)
                                Toast.makeText(context, "Please allow display over other apps", Toast.LENGTH_LONG).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "Could not open overlay settings", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.padding(16.dp))
            }

            Spacer(modifier = Modifier.padding(24.dp))
        }
    }
}

@Composable
private fun LocalContext() = androidx.compose.ui.platform.LocalContext.current
