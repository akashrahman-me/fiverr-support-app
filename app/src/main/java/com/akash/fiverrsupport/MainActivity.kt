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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.akash.fiverrsupport.ui.theme.FiverrSupportTheme
import androidx.core.net.toUri
import com.akash.fiverrsupport.ui.components.PermissionToggleItem
import com.akash.fiverrsupport.utils.isAccessibilityServiceEnabled

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

        // Check if accessibility service is enabled
        if (!isAccessibilityServiceEnabled(this)) {
            Toast.makeText(
                this,
                "Please enable Accessibility Service for automation features",
                Toast.LENGTH_LONG
            ).show()
        }

        setContent {
            FiverrSupportTheme {
                Root()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Root(modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Fiverr Support",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { innerPadding ->
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current

        // Load saved interval from SharedPreferences
        val sharedPrefs = context.getSharedPreferences("FiverrSupportPrefs", Context.MODE_PRIVATE)

        // Check if service is actually running
        var isEnabled by remember {
            mutableStateOf(isServiceRunning(context, FiverrLauncherService::class.java))
        }
        var launchIntervalSeconds by remember {
            mutableFloatStateOf(sharedPrefs.getFloat("launch_interval", 20f)) // Default 20 seconds
        }
        var isAccessibilityEnabled by remember {
            mutableStateOf(isAccessibilityServiceEnabled(context))
        }
        var isOverlayEnabled by remember {
            mutableStateOf(Settings.canDrawOverlays(context))
        }
        var isNotificationEnabled by remember {
            mutableStateOf(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                } else {
                    true // Always true for older versions
                }
            )
        }
        var isBatteryOptimizationDisabled by remember {
            mutableStateOf(
                (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .isIgnoringBatteryOptimizations(context.packageName)
            )
        }

        // Update all permission states when activity resumes
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    // Check if service is actually running
                    isEnabled = isServiceRunning(context, FiverrLauncherService::class.java)

                    isAccessibilityEnabled = isAccessibilityServiceEnabled(context)
                    isOverlayEnabled = Settings.canDrawOverlays(context)
                    isNotificationEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED
                    } else {
                        true
                    }
                    isBatteryOptimizationDisabled =
                        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                            .isIgnoringBatteryOptimizations(context.packageName)
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }

        // Force check service state when composable first loads
        LaunchedEffect(Unit) {
            isEnabled = isServiceRunning(context, FiverrLauncherService::class.java)
            Log.d("nvm", "Initial service check: isEnabled = $isEnabled")
        }

        // Start or stop the foreground service based on the switch state
        fun toggleService(enabled: Boolean) {
            val serviceIntent = Intent(context, FiverrLauncherService::class.java)
            serviceIntent.action = if (enabled) FiverrLauncherService.ACTION_START else FiverrLauncherService.ACTION_STOP

            // Pass interval when starting the service
            if (enabled) {
                serviceIntent.putExtra(FiverrLauncherService.EXTRA_INTERVAL, (launchIntervalSeconds * 1000).toLong())
            }

            if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }

        // Update interval while service is running
        fun updateInterval(intervalSeconds: Float) {
            // Save to SharedPreferences
            sharedPrefs.edit().putFloat("launch_interval", intervalSeconds).apply()

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
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            // Permissions Section Header
            Text(
                text = "Required Permissions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Accessibility Service toggle
            PermissionToggleItem(
                icon = Icons.Default.Settings,
                title = "Accessibility Service",
                isEnabled = isAccessibilityEnabled,
                enabledText = "Service is enabled",
                disabledText = "Service is disabled",
                onToggle = {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    context.startActivity(intent)
                    Toast.makeText(
                        context,
                        "Please enable 'Fiverr Support' in Accessibility",
                        Toast.LENGTH_LONG
                    ).show()
                }
            )

            Spacer(modifier = Modifier.padding(4.dp))

            // Display Overlay Permission toggle
            PermissionToggleItem(
                icon = Icons.Default.Star,
                title = "Display Overlay Permission",
                isEnabled = isOverlayEnabled,
                enabledText = "Permission granted",
                disabledText = "Permission required",
                onToggle = {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        "package:${context.packageName}".toUri()
                    )
                    context.startActivity(intent)
                    Toast.makeText(
                        context,
                        "Please grant overlay permission",
                        Toast.LENGTH_LONG
                    ).show()
                }
            )

            Spacer(modifier = Modifier.padding(4.dp))

            // Notification Permission toggle
            PermissionToggleItem(
                icon = Icons.Default.Notifications,
                title = "Notification Permission",
                isEnabled = isNotificationEnabled,
                enabledText = "Permission granted",
                disabledText = "Permission required",
                onToggle = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        }
                        context.startActivity(intent)
                        Toast.makeText(
                            context,
                            "Please enable notifications",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            )

            Spacer(modifier = Modifier.padding(4.dp))

            // Battery Optimization toggle
            PermissionToggleItem(
                icon = Icons.Default.Warning,
                title = "Battery Optimization",
                isEnabled = isBatteryOptimizationDisabled,
                enabledText = "Exemption granted",
                disabledText = "Exemption required",
                onToggle = {
                    val intent =
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = "package:${context.packageName}".toUri()
                        }
                    try {
                        context.startActivity(intent)
                        Toast.makeText(
                            context,
                            "Please allow battery optimization exemption",
                            Toast.LENGTH_LONG
                        ).show()
                    } catch (e: Exception) {
                        Toast.makeText(
                            context,
                            "Could not open battery settings",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            )

            Spacer(modifier = Modifier.padding(4.dp))


            // Divider between permissions and features
            Spacer(modifier = Modifier.padding(12.dp))
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )
            Spacer(modifier = Modifier.padding(12.dp))

            // App Features Section Header
            Text(
                text = "App Features",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Service toggle
            PermissionToggleItem(
                icon = Icons.Default.Build,
                title = "Enable Support Service",
                isEnabled = isEnabled,
                enabledText = "Service is running",
                disabledText = "Service is disabled",
                onToggle = {
                    isEnabled = !isEnabled
                    toggleService(isEnabled)
                }
            )

            Spacer(modifier = Modifier.padding(8.dp))

            // Launch Interval Slider
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Build,
                        contentDescription = "Launch Interval",
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = "Launch Interval: ${launchIntervalSeconds.toInt()} seconds",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Fiverr app will open every ${launchIntervalSeconds.toInt()} seconds",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Slider(
                    value = launchIntervalSeconds,
                    onValueChange = {
                        launchIntervalSeconds = it
                        updateInterval(it)
                    },
                    valueRange = 3f..30f,
                    steps = 26, // 3, 4, 5, ... 30 (28 values total, so 26 steps between)
                    enabled = true
                )
                Text(
                    text = "Range: 3-30 seconds",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
