package com.example.child_safety_app_version1.userInterface

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.example.child_safety_app_version1.services.LocationMonitoringService
import com.example.child_safety_app_version1.utils.FcmNotificationSender
import com.example.child_safety_app_version1.utils.FcmTokenManager
import com.example.child_safety_app_version1.utils.NotificationType
import com.google.android.gms.location.LocationServices
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Locale

@Composable
fun ChildDashboard(navController: NavController) {
    val context = LocalContext.current
    val auth = FirebaseAuth.getInstance()
    val scope = rememberCoroutineScope()
    var isLoggingOut by remember { mutableStateOf(false) }
    var isSendingEmergency by remember { mutableStateOf(false) }
    var isLocationServiceRunning by remember { mutableStateOf(false) }
    var isLocationEnabled by remember { mutableStateOf(false) }
    var showLocationDisabledDialog by remember { mutableStateOf(false) }

    // Location tracking states
    var currentLocationName by remember { mutableStateOf("Fetching location...") }
    var currentLatitude by remember { mutableStateOf<Double?>(null) }
    var currentLongitude by remember { mutableStateOf<Double?>(null) }
    var isFetchingLocation by remember { mutableStateOf(false) }
    var lastUpdateTime by remember { mutableStateOf("Never") }

    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

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

                    // Get location name using Geocoder
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
            // Now start the service
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

    // Location permission launcher
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

        if (fineLocationGranted || coarseLocationGranted) {
            Toast.makeText(context, "Location permission granted", Toast.LENGTH_SHORT).show()

            // Immediately request background location (Android 10+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            } else {
                // For Android 9 and below, just start the service
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
            kotlinx.coroutines.delay(3000) // Check every 3 seconds
        }
    }

    // Request permissions on launch
    LaunchedEffect(Unit) {
        // First check if location is enabled
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
            // Check background permission
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
            // Request location permissions
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
                            // Retry permission request
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

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
                        !isLocationEnabled -> "⚠ Device Location is OFF"
                        isLocationServiceRunning -> "✓ Safety Monitoring Active"
                        else -> "⚠ Safety Monitoring Inactive"
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

        // Restart monitoring button
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

        // Info text
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