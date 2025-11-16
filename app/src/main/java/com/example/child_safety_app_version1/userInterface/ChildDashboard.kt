package com.example.child_safety_app_version1.userInterface

import androidx.compose.foundation.border
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import android.os.PowerManager
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.example.child_safety_app_version1.data.AppMode
import com.example.child_safety_app_version1.managers.AppModeManager
import com.example.child_safety_app_version1.managers.UsageDataCollector
import com.example.child_safety_app_version1.services.LocationMonitoringService
import com.example.child_safety_app_version1.utils.BackgroundSurvivalHelper
import com.example.child_safety_app_version1.utils.FcmNotificationSender
import com.example.child_safety_app_version1.utils.FcmTokenManager
import com.example.child_safety_app_version1.utils.NotificationType
import com.example.child_safety_app_version1.utils.PermissionHelper
import com.example.child_safety_app_version1.utils.UsageStatsHelper
import com.example.child_safety_app_version1.utils.sms.ParentPhoneCache
import com.google.android.gms.location.LocationServices
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Locale

@Composable
fun ChildDashboard(navController: NavController) {
    val context = LocalContext.current
    val auth = FirebaseAuth.getInstance()
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var showBatteryOptDialog by remember { mutableStateOf(false) }
    var needsBatteryExemption by remember { mutableStateOf(false) }

    var isLoggingOut by remember { mutableStateOf(false) }
    var isSendingEmergency by remember { mutableStateOf(false) }
    var isLocationServiceRunning by remember { mutableStateOf(false) }
    var isLocationEnabled by remember { mutableStateOf(false) }
    var showLocationDisabledDialog by remember { mutableStateOf(false) }
    // 🆕 Media permissions dialog
    var showMediaPermissionsDialog by remember { mutableStateOf(false) }

    // ⭐ Mode state from AppModeManager
    val currentMode by AppModeManager.currentMode.collectAsState()
    var isCollectingUsageData by remember { mutableStateOf(false) }
    var missingPermissions by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Location tracking states
    var currentLocationName by remember { mutableStateOf("Fetching location...") }
    var currentLatitude by remember { mutableStateOf<Double?>(null) }
    var currentLongitude by remember { mutableStateOf<Double?>(null) }
    var isFetchingLocation by remember { mutableStateOf(false) }
    var lastUpdateTime by remember { mutableStateOf("Never") }

    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    // 🆕 MEDIA PERMISSION LAUNCHER
    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        val micGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        val audioSettingsGranted = permissions[Manifest.permission.MODIFY_AUDIO_SETTINGS] ?: false

        Log.d("ChildDashboard", "📹 Media Permission Results:")
        Log.d("ChildDashboard", "  Camera: $cameraGranted")
        Log.d("ChildDashboard", "  Microphone: $micGranted")
        Log.d("ChildDashboard", "  Audio Settings: $audioSettingsGranted")

        if (cameraGranted && micGranted && audioSettingsGranted) {
            Toast.makeText(context, "✅ Camera & Microphone permissions granted!", Toast.LENGTH_SHORT).show()

            // Refresh permission status
            val missing = mutableSetOf<String>()
            if (!PermissionHelper.hasUsageStatsPermission(context)) missing.add("Usage Stats")
            if (!PermissionHelper.hasAccessibilityPermission(context)) missing.add("Accessibility Service")
            if (!PermissionHelper.hasOverlayPermission(context)) missing.add("Overlay Permission")
            if (!PermissionHelper.hasCameraPermission(context)) missing.add("Camera")
            if (!PermissionHelper.hasMicrophonePermission(context)) missing.add("Microphone")
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                missing.add("SMS")
            }

            missingPermissions = missing
        } else {
            // Some permissions were denied - show dialog to open settings
            showMediaPermissionsDialog = true
            Log.w("ChildDashboard", "⚠️ Some media permissions denied, showing settings dialog")
        }
    }

    // ⭐ NEW: Initialize AppModeManager when dashboard is created
    LaunchedEffect(Unit) {
        AppModeManager.initialize(context)
    }

    // ⭐ NEW: Check permissions on lifecycle resume (fixes permission card not disappearing)
    // ⭐ NEW: Check permissions on lifecycle resume (fixes permission card not disappearing)
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                // Refresh permission status when returning from settings
                val missing = mutableSetOf<String>()

                if (!PermissionHelper.hasUsageStatsPermission(context)) {
                    missing.add("Usage Stats")
                }

                if (!PermissionHelper.hasAccessibilityPermission(context)) {
                    missing.add("Accessibility Service")
                }

                if (!PermissionHelper.hasOverlayPermission(context)) {
                    missing.add("Overlay Permission")
                }

                // 🆕 Add SMS permission check
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
                    missing.add("SMS")
                }

                // 🆕 Check battery optimization status
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                    needsBatteryExemption = !powerManager.isIgnoringBatteryOptimizations(context.packageName)
                }

                // 🆕 Check Camera & Microphone
                if (!PermissionHelper.hasCameraPermission(context)) {
                    missing.add("Camera")
                }

                if (!PermissionHelper.hasMicrophonePermission(context)) {
                    missing.add("Microphone")
                }

                missingPermissions = missing
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        // 🔧 FIX: Must return onDispose block
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Check for missing permissions on initial launch
    // Check for missing permissions on initial launch
    LaunchedEffect(Unit) {
        val missing = mutableSetOf<String>()

        if (!PermissionHelper.hasUsageStatsPermission(context)) {
            missing.add("Usage Stats")
        }

        if (!PermissionHelper.hasAccessibilityPermission(context)) {
            missing.add("Accessibility Service")
        }

        if (!PermissionHelper.hasOverlayPermission(context)) {
            missing.add("Overlay Permission")
        }

        // 🆕 Check Camera & Microphone
        if (!PermissionHelper.hasCameraPermission(context)) {
            missing.add("Camera")
        }

        if (!PermissionHelper.hasMicrophonePermission(context)) {
            missing.add("Microphone")
        }

        // Check SMS
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            missing.add("SMS")
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            missing.add("SMS")
        }

        missingPermissions = missing
    }

    // Check battery optimization on launch
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val packageName = context.packageName
            needsBatteryExemption = !powerManager.isIgnoringBatteryOptimizations(packageName)

            if (needsBatteryExemption) {
                delay(2000) // Wait 2 seconds before showing
                showBatteryOptDialog = true
            }
        }
    }

    // Check if device location is enabled
    fun checkLocationEnabled(): Boolean {
        val locationManager = context.getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    // Function to get current location
    fun fetchCurrentLocation() {
        if (!checkLocationEnabled()) {
            currentLocationName = "Device location is turned OFF"
            isLocationEnabled = false
            return
        }

        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            currentLocationName = "Location permission not granted"
            return
        }

        isFetchingLocation = true
        scope.launch {
            try {
                val location = fusedLocationClient.lastLocation.await()
                if (location != null) {
                    currentLatitude = location.latitude
                    currentLongitude = location.longitude

                    try {
                        val geocoder = Geocoder(context, Locale.getDefault())
                        val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)

                        if (addresses != null && addresses.isNotEmpty()) {
                            val address = addresses[0]
                            val locationParts = mutableListOf<String>()

                            address.featureName?.let { locationParts.add(it) }
                            address.locality?.let { locationParts.add(it) }
                            address.adminArea?.let { locationParts.add(it) }
                            address.countryName?.let { locationParts.add(it) }

                            currentLocationName = if (locationParts.isNotEmpty()) {
                                locationParts.joinToString(", ")
                            } else {
                                "Unknown location"
                            }
                        } else {
                            currentLocationName = "Location: ${String.format("%.4f", location.latitude)}, ${String.format("%.4f", location.longitude)}"
                        }
                    } catch (e: Exception) {
                        currentLocationName = "Location: ${String.format("%.4f", location.latitude)}, ${String.format("%.4f", location.longitude)}"
                    }

                    val currentTime = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                        .format(java.util.Date())
                    lastUpdateTime = currentTime
                } else {
                    currentLocationName = "Location not available"
                }
            } catch (e: Exception) {
                currentLocationName = "Error getting location: ${e.message}"
            } finally {
                isFetchingLocation = false
            }
        }
    }

    // Background location permission launcher (Android 10+)
    val backgroundLocationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(context, "Background location access granted!", Toast.LENGTH_SHORT).show()
            if (checkLocationEnabled()) {
                startLocationMonitoringService(context)
                isLocationServiceRunning = true
                fetchCurrentLocation()
            } else {
                showLocationDisabledDialog = true
            }
        } else {
            Toast.makeText(
                context,
                "Background location is required for safety monitoring",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // 🆕 SMS permission launcher
    val smsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val sendSmsGranted = permissions[Manifest.permission.SEND_SMS] ?: false
        val readSmsGranted = permissions[Manifest.permission.READ_SMS] ?: false
        val receiveSmsGranted = permissions[Manifest.permission.RECEIVE_SMS] ?: false

        if (sendSmsGranted && readSmsGranted && receiveSmsGranted) {
            Toast.makeText(context, "✅ SMS permissions granted", Toast.LENGTH_SHORT).show()

            // Refresh permission check
            val missing = mutableSetOf<String>()
            if (!PermissionHelper.hasUsageStatsPermission(context)) missing.add("Usage Stats")
            if (!PermissionHelper.hasAccessibilityPermission(context)) missing.add("Accessibility Service")
            if (!PermissionHelper.hasOverlayPermission(context)) missing.add("Overlay Permission")
            missingPermissions = missing
        } else {
            Toast.makeText(context, "⚠️ SMS permissions required for offline alerts", Toast.LENGTH_LONG).show()
        }
    }

    // Location permission launcher
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

        if (fineLocationGranted || coarseLocationGranted) {
            Toast.makeText(context, "Location permission granted", Toast.LENGTH_SHORT).show()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            } else {
                if (checkLocationEnabled()) {
                    startLocationMonitoringService(context)
                    isLocationServiceRunning = true
                    fetchCurrentLocation()
                } else {
                    showLocationDisabledDialog = true
                }
            }
        } else {
            Toast.makeText(
                context,
                "Location permission denied. App cannot function without it.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Check location status periodically
    LaunchedEffect(Unit) {
        while (true) {
            isLocationEnabled = checkLocationEnabled()
            if (!isLocationEnabled) {
                isLocationServiceRunning = false
                currentLocationName = "Device location is turned OFF"
            }
            kotlinx.coroutines.delay(3000)
        }
    }

    // Request permissions on launch
    LaunchedEffect(Unit) {
        if (!checkLocationEnabled()) {
            isLocationEnabled = false
            showLocationDisabledDialog = true
            return@LaunchedEffect
        }

        val fineLocationGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseLocationGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (fineLocationGranted || coarseLocationGranted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val backgroundGranted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

                if (!backgroundGranted) {
                    backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                } else {
                    startLocationMonitoringService(context)
                    isLocationServiceRunning = true
                    fetchCurrentLocation()
                }
            } else {
                startLocationMonitoringService(context)
                isLocationServiceRunning = true
                fetchCurrentLocation()
            }
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    // Auto-refresh location every 30 seconds
    LaunchedEffect(isLocationServiceRunning) {
        if (isLocationServiceRunning) {
            while (true) {
                kotlinx.coroutines.delay(30000)
                fetchCurrentLocation()
            }
        }
    }

    // Update parent phone cache AFTER location service starts (ensures internet)
    LaunchedEffect(isLocationServiceRunning) {
        if (isLocationServiceRunning) {
            delay(2000) // Wait 2 seconds for service to stabilize

            val uid = FirebaseAuth.getInstance().currentUser?.uid
            if (uid != null) {
                scope.launch {
                    try {
                        Log.d("ChildDashboard", "Location service running - updating phone cache...")
                        val success = ParentPhoneCache.updateCache(context, uid)

                        if (success) {
                            Log.d("ChildDashboard", "✅ Parent phone cache updated")
                            Toast.makeText(
                                context,
                                "✅ Offline SMS alerts ready",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            Log.w("ChildDashboard", "⚠️ Failed to cache phone numbers")
                        }
                    } catch (e: Exception) {
                        Log.e("ChildDashboard", "Error caching phone numbers", e)
                    }
                }
            }
        }
    }

    fun handleLogout() {
        isLoggingOut = true
        scope.launch {
            val currentUserUid = auth.currentUser?.uid
            stopLocationMonitoringService(context)

            if (currentUserUid != null) {
                FcmNotificationSender.sendNotificationToParents(
                    context = context,
                    childUid = currentUserUid,
                    notificationType = NotificationType.CHILD_LOGGED_OUT
                )
            }

            FcmTokenManager.performLogout(context) {
                navController.navigate("login") {
                    popUpTo("child_dashboard") { inclusive = true }
                }
            }
        }
    }

    fun handleEmergencyNotification() {
        isSendingEmergency = true
        scope.launch {
            try {
                val currentUserUid = auth.currentUser?.uid
                if (currentUserUid != null) {
                    val success = FcmNotificationSender.sendNotificationToParents(
                        context = context,
                        childUid = currentUserUid,
                        notificationType = NotificationType.EMERGENCY,
                        latitude = currentLatitude,
                        longitude = currentLongitude
                    )

                    if (success) {
                        Toast.makeText(context, "Emergency alert sent!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Failed to send alert", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(context, "User not logged in", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                isSendingEmergency = false
            }
        }
    }

    // Location Disabled Dialog
    if (showLocationDisabledDialog) {
        AlertDialog(
            onDismissRequest = { /* Cannot dismiss */ },
            icon = {
                Icon(
                    imageVector = Icons.Default.LocationOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(48.dp)
                )
            },
            title = {
                Text(
                    text = "Location is Turned OFF",
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Column {
                    Text(
                        text = "This app requires device location to be enabled for safety monitoring.",
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Please turn on location from your device settings to continue.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                        context.startActivity(intent)
                    }
                ) {
                    Icon(Icons.Default.Settings, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Open Settings")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        if (checkLocationEnabled()) {
                            showLocationDisabledDialog = false
                            isLocationEnabled = true
                            locationPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        } else {
                            Toast.makeText(
                                context,
                                "Please turn on location first",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                ) {
                    Text("Check Again")
                }
            }
        )
    }

    // Battery optimization dialog
    if (showBatteryOptDialog) {
        AlertDialog(
            onDismissRequest = { /* Cannot dismiss */ },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning, // or BatteryAlert if available
                    contentDescription = null,
                    tint = Color(0xFFFF9800),
                    modifier = Modifier.size(48.dp)
                )
            },
            title = {
                Text(
                    text = "Battery Optimization Required",
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Column {
                    Text(
                        text = "To prevent Android from killing the safety service, this app needs to be exempted from battery optimization.",
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "⚠️ Without this, the app will stop working after a few minutes.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            intent.data = Uri.parse("package:${context.packageName}")
                            context.startActivity(intent)
                        }
                        showBatteryOptDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF9800)
                    )
                ) {
                    Icon(Icons.Default.Settings, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Grant Permission")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        // Check again
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                            needsBatteryExemption = !powerManager.isIgnoringBatteryOptimizations(context.packageName)
                            showBatteryOptDialog = needsBatteryExemption

                            if (!needsBatteryExemption) {
                                Toast.makeText(context, "✅ Battery optimization disabled!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "⚠️ Please disable battery optimization", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                ) {
                    Text("Check Again")
                }
            }
        )
    }

    // 🆕 MEDIA PERMISSIONS DIALOG
    if (showMediaPermissionsDialog) {
        AlertDialog(
            onDismissRequest = { showMediaPermissionsDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Videocam,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(48.dp)
                )
            },
            title = {
                Text(
                    text = "Camera & Microphone Required",
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Column {
                    Text(
                        text = "To capture evidence when you leave the safe zone, the app needs camera and microphone access.",
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Please enable these permissions in app settings.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        // ✅ FIXED: Use openMediaPermissionsSettings instead of requestMediaPermissions
                        PermissionHelper.openMediaPermissionsSettings(context)
                        showMediaPermissionsDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Settings, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Open Settings")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showMediaPermissionsDialog = false
                    }
                ) {
                    Text("Not Now")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // ⭐ Mode Indicator Card - Dynamic based on current mode (NOW UPDATES AUTOMATICALLY)
        ModeIndicatorCard(
            currentMode = currentMode,
            isCollectingData = isCollectingUsageData
        )

        // After ModeIndicatorCard
        BackgroundSurvivalCard(
            onFixNow = {
                BackgroundSurvivalHelper.openBatteryOptimizationSettings(context)
            },
            onOpenManufacturerSettings = {
                BackgroundSurvivalHelper.openManufacturerSettings(context)
            }
        )

        // Battery optimization warning
        if (needsBatteryExemption) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                border = androidx.compose.foundation.BorderStroke(2.dp, Color(0xFFFF9800))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = Color(0xFFFF9800)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "⚠️ Service May Stop",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE65100)
                            )
                            Text(
                                text = "Battery optimization is enabled",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFEF6C00)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                intent.data = Uri.parse("package:${context.packageName}")
                                context.startActivity(intent)
                            }
                        },
                        modifier = Modifier.align(Alignment.End),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                    ) {
                        Text("Fix Now")
                    }
                }
            }
        }

        // Missing Permissions Card (NOW DISAPPEARS WHEN ALL PERMISSIONS GRANTED)
        if (missingPermissions.isNotEmpty()) {
            MissingPermissionsCard(
                missingPermissions = missingPermissions,
                onGrantPermissions = {
                    when {
                        missingPermissions.contains("Camera") || missingPermissions.contains("Microphone") -> {
                            Log.d("ChildDashboard", "Requesting camera & microphone permissions...")
                            mediaPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.CAMERA,
                                    Manifest.permission.RECORD_AUDIO,
                                    Manifest.permission.MODIFY_AUDIO_SETTINGS
                                )
                            )
                        }
                        missingPermissions.contains("SMS") -> {
                            Log.d("ChildDashboard", "Requesting SMS permissions...")
                            smsPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.SEND_SMS,
                                    Manifest.permission.READ_SMS,
                                    Manifest.permission.RECEIVE_SMS
                                )
                            )
                        }
                        !PermissionHelper.hasUsageStatsPermission(context) -> {
                            PermissionHelper.requestUsageStatsPermission(context)
                        }
                        !PermissionHelper.hasAccessibilityPermission(context) -> {
                            PermissionHelper.requestAccessibilityPermission(context)
                        }
                        !PermissionHelper.hasOverlayPermission(context) -> {
                            PermissionHelper.requestOverlayPermission(context)
                        }
                    }
                }
            )
        }

        // Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    !isLocationEnabled -> MaterialTheme.colorScheme.errorContainer
                    isLocationServiceRunning -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.errorContainer
                }
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = when {
                        !isLocationEnabled -> "⚠️ Device Location is OFF"
                        isLocationServiceRunning -> "✅ Safety Monitoring Active"
                        else -> "⚠️ Safety Monitoring Inactive"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = when {
                        !isLocationEnabled -> "Please enable location from device settings"
                        isLocationServiceRunning -> "Your location is being monitored for safety"
                        else -> "Grant location permission to enable monitoring"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )

                if (!isLocationEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Settings, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Enable Location")
                    }
                }
            }
        }

        // Current Location Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = "Location",
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Current Location",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    IconButton(
                        onClick = { fetchCurrentLocation() },
                        enabled = !isFetchingLocation && isLocationEnabled
                    ) {
                        if (isFetchingLocation) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = currentLocationName,
                    style = MaterialTheme.typography.bodyMedium
                )

                if (currentLatitude != null && currentLongitude != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Lat: ${String.format("%.6f", currentLatitude)}, Lon: ${String.format("%.6f", currentLongitude)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Last updated: $lastUpdateTime",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f)
                )
            }
        }

        HorizontalDivider()

        // Emergency notification button
        Button(
            onClick = { handleEmergencyNotification() },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            ),
            modifier = Modifier.fillMaxWidth(),
            enabled = !isSendingEmergency && isLocationEnabled
        ) {
            if (isSendingEmergency) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onError
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Sending...")
            } else {
                Text("🚨 Send Emergency Alert to Parent")
            }
        }

        Button(
            onClick = { navController.navigate("emergency_contacts") },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Emergency Contact Numbers")
        }

        if (!isLocationServiceRunning && isLocationEnabled) {
            OutlinedButton(
                onClick = {
                    locationPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Enable Location Monitoring")
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = "Your parents will be notified if you leave the safe zone",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
            textAlign = TextAlign.Center
        )

        // Logout Button
        Button(
            onClick = { handleLogout() },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            ),
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoggingOut
        ) {
            if (isLoggingOut) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Logging out...")
            } else {
                Text("Logout")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun ModeIndicatorCard(
    currentMode: AppMode,
    isCollectingData: Boolean
) {
    val backgroundColor = when (currentMode) {
        AppMode.NORMAL -> Color(0xFFC8E6C9)
        AppMode.STUDY -> Color(0xFFFFF3E0)
        AppMode.BEDTIME -> Color(0xFFF3E5F5)
    }

    val borderColor = when (currentMode) {
        AppMode.NORMAL -> Color(0xFF2E7D32)
        AppMode.STUDY -> Color(0xFFE65100)
        AppMode.BEDTIME -> Color(0xFF512DA8)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, borderColor, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = currentMode.icon,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = borderColor
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Current Mode: ${currentMode.displayName}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = borderColor
                    )
                    Text(
                        text = currentMode.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Black.copy(alpha = 0.7f)
                    )
                }
            }

            // ⭐ Mode-specific additional info
            when (currentMode) {
                AppMode.STUDY -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = borderColor.copy(alpha = 0.3f))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "📚 Only educational apps are accessible",
                        style = MaterialTheme.typography.bodySmall,
                        color = borderColor,
                        fontWeight = FontWeight.Medium
                    )
                }
                AppMode.BEDTIME -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = borderColor.copy(alpha = 0.3f))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "🌙 Only emergency calls and this app are available",
                        style = MaterialTheme.typography.bodySmall,
                        color = borderColor,
                        fontWeight = FontWeight.Medium
                    )
                }
                AppMode.NORMAL -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = borderColor.copy(alpha = 0.3f))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "✅ Standard device usage with parental controls",
                        style = MaterialTheme.typography.bodySmall,
                        color = borderColor,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            if (isCollectingData) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = borderColor
                    )
                    Text(
                        text = "📊 Collecting usage data...",
                        style = MaterialTheme.typography.bodySmall,
                        color = borderColor,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun MissingPermissionsCard(
    missingPermissions: Set<String>,
    onGrantPermissions: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFCDD2)),
        border = androidx.compose.foundation.BorderStroke(2.dp, Color(0xFFD32F2F))
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = Color(0xFFD32F2F)
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Missing Permissions",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFD32F2F)
                    )
                    Text(
                        text = missingPermissions.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF7F1814)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onGrantPermissions,
                modifier = Modifier
                    .align(Alignment.End)
                    .height(36.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFD32F2F)
                )
            ) {
                Text("Grant Permissions", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun startLocationMonitoringService(context: android.content.Context) {
    val intent = Intent(context, LocationMonitoringService::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
}

private fun stopLocationMonitoringService(context: android.content.Context) {
    val intent = Intent(context, LocationMonitoringService::class.java)
    context.stopService(intent)
}

@Composable
fun BackgroundSurvivalCard(
    onFixNow: () -> Unit,
    onOpenManufacturerSettings: () -> Unit
) {
    val context = LocalContext.current
    var backgroundStatus by remember { mutableStateOf<BackgroundSurvivalHelper.BackgroundStatus?>(null) }
    val manufacturer = BackgroundSurvivalHelper.getManufacturer()
    val isAggressive = BackgroundSurvivalHelper.isAggressiveManufacturer()

    // Check status on launch and periodically
    LaunchedEffect(Unit) {
        while (true) {
            backgroundStatus = BackgroundSurvivalHelper.checkBackgroundStatus(context)
            kotlinx.coroutines.delay(5000) // Check every 5 seconds
        }
    }

    backgroundStatus?.let { status ->
        if (!status.allRequirementsMet) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFFFEBEE)
                ),
                border = androidx.compose.foundation.BorderStroke(2.dp, Color(0xFFD32F2F))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Header
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = Color(0xFFD32F2F)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "⚠️ Background Running Required",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFD32F2F)
                            )
                            Text(
                                text = "App will stop working if not configured",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFC62828)
                            )
                        }
                    }

                    HorizontalDivider(color = Color(0xFFD32F2F).copy(alpha = 0.3f))

                    // Device info
                    Surface(
                        color = Color(0xFFFFF3E0),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "📱 Your Device: $manufacturer",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (isAggressive) {
                                Text(
                                    text = "⚡ Aggressive battery optimization detected",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFE65100)
                                )
                            }
                        }
                    }

                    // Status checklist
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusCheckItem(
                            label = "Battery Optimization",
                            isOk = status.batteryOptimizationDisabled
                        )
                        StatusCheckItem(
                            label = "Background Restrictions",
                            isOk = status.backgroundRestrictionDisabled
                        )
                    }

                    HorizontalDivider(color = Color(0xFFD32F2F).copy(alpha = 0.3f))

                    // Manufacturer-specific instructions
                    if (isAggressive) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "📋 Required Steps:",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFD32F2F)
                            )
                            BackgroundSurvivalHelper.getManufacturerInstructions().forEach { instruction ->
                                Row(
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "•",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFFD32F2F)
                                    )
                                    Text(
                                        text = instruction,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF5D4037)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Battery optimization button
                        if (!status.batteryOptimizationDisabled) {
                            Button(
                                onClick = onFixNow,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFD32F2F)
                                )
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "Fix Battery",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Optimization",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }

                        // Manufacturer settings button
                        if (isAggressive) {
                            Button(
                                onClick = onOpenManufacturerSettings,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFE65100)
                                )
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "Open $manufacturer",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Settings",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }

                    // Help text
                    Surface(
                        color = Color(0xFFFFF9C4),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = Color(0xFFFF6F00)
                            )
                            Text(
                                text = "Without these settings, the app will stop monitoring after a few minutes!",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFE65100),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCheckItem(
    label: String,
    isOk: Boolean
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = if (isOk) Icons.Default.CheckCircle else Icons.Default.Close,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (isOk) Color(0xFF4CAF50) else Color(0xFFD32F2F)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isOk) Color(0xFF2E7D32) else Color(0xFFD32F2F)
        )
        if (isOk) {
            Text(
                text = "✓ OK",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF4CAF50),
                fontWeight = FontWeight.Bold
            )
        } else {
            Text(
                text = "✗ Required",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFD32F2F),
                fontWeight = FontWeight.Bold
            )
        }
    }
}