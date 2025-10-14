package com.example.child_safety_app_version1.services

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.example.child_safety_app_version1.MainActivity
import com.example.child_safety_app_version1.utils.FcmNotificationSender
import com.example.child_safety_app_version1.utils.NotificationType
import com.example.child_safety_app_version1.utils.SafeZoneChecker
import com.google.android.gms.location.*
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.*

class LocationMonitoringService : Service() {

    companion object {
        private const val TAG = "LocationMonitorService"
        private const val CHANNEL_ID = "location_monitoring"
        private const val NOTIFICATION_ID = 1001
        private const val LOCATION_UPDATE_INTERVAL = 30 * 1000L // 30 seconds
        private const val LOCATION_FASTEST_INTERVAL = 15 * 1000L // 15 seconds
        private const val LOCATION_CHECK_INTERVAL = 5 * 1000L // Check every 5 seconds for location status
        private const val ALERT_THROTTLE_INTERVAL = 60 * 1000L // Send alert every 60 seconds when outside
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var isMonitoring = false
    private var wasInsideSafeZone: Boolean? = null // null means first check
    private var lastOutsideAlertTime = 0L // For throttling outside alerts
    private var lastLocationDisabledNotificationTime = 0L
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var isFirstCheck = true
    private var wasLocationEnabled = true // Track previous location state
    private var hasApplicableSafeZones: Boolean? = null // Track if child has any safe zones

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()

        setupLocationCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")

        val notification = createForegroundNotification("Monitoring your safety zone")
        startForeground(NOTIFICATION_ID, notification)

        // Check if child has applicable safe zones
        checkApplicableSafeZones()

        // Get immediate location check when service starts
        getImmediateLocation()

        startLocationMonitoring()
        setupLocationSettingsListener()

        return START_STICKY
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    Log.d(TAG, "Location update: ${location.latitude}, ${location.longitude}")
                    checkSafeZone(location)
                }
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                super.onLocationAvailability(availability)
                if (!availability.isLocationAvailable) {
                    Log.w(TAG, "Location not available - services may be disabled")
                    checkLocationSettings()
                }
            }
        }
    }

    /**
     * Check if the current child has any applicable safe zones defined
     */
    private fun checkApplicableSafeZones() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            Log.e(TAG, "No user logged in")
            return
        }

        serviceScope.launch {
            try {
                val hasSafeZones = SafeZoneChecker.hasApplicableSafeZones(childUid = uid)
                hasApplicableSafeZones = hasSafeZones

                if (!hasSafeZones) {
                    Log.w(TAG, "⚠️ Child has no applicable safe zones defined")
                    updateForegroundNotification("No safe zones defined for you")
                } else {
                    Log.d(TAG, "✓ Child has applicable safe zones")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking applicable safe zones", e)
            }
        }
    }

    /**
     * Gets immediate location when service starts (don't wait for first interval)
     */
    private fun getImmediateLocation() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Location permission not granted")
            return
        }

        Log.d(TAG, "Requesting immediate location...")

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                Log.d(TAG, "Got immediate location: ${location.latitude}, ${location.longitude}")
                checkSafeZone(location)
            } else {
                Log.w(TAG, "Last location is null, waiting for first update...")
            }
        }.addOnFailureListener { e ->
            Log.e(TAG, "Failed to get immediate location", e)
        }
    }

    private fun startLocationMonitoring() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Location permission not granted")
            stopSelf()
            return
        }

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            LOCATION_UPDATE_INTERVAL
        ).apply {
            setMinUpdateIntervalMillis(LOCATION_FASTEST_INTERVAL)
            setWaitForAccurateLocation(false)
        }.build()

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )

        isMonitoring = true
        Log.d(TAG, "Location monitoring started (updates every 30 seconds)")
    }

    private fun setupLocationSettingsListener() {
        serviceScope.launch {
            while (isActive && isMonitoring) {
                delay(LOCATION_CHECK_INTERVAL) // Check every 5 seconds
                checkLocationSettings()
            }
        }
    }

    private fun checkLocationSettings() {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        val isLocationEnabled = isGpsEnabled || isNetworkEnabled

        Log.d(TAG, "Location status check - GPS: $isGpsEnabled, Network: $isNetworkEnabled")

        // Detect location being turned OFF (state change)
        if (wasLocationEnabled && !isLocationEnabled) {
            Log.w(TAG, "⚠️ LOCATION TURNED OFF - Sending immediate notification!")
            updateForegroundNotification("⚠️ Location services disabled")
            sendLocationDisabledNotification(immediate = true)
            wasLocationEnabled = false
        }
        // Detect location being turned back ON
        else if (!wasLocationEnabled && isLocationEnabled) {
            Log.d(TAG, "✓ Location turned back on")
            updateForegroundNotification("Monitoring your safety zone")
            wasLocationEnabled = true
            // Reset notification timer when location is re-enabled
            lastLocationDisabledNotificationTime = 0L
        }
        // Location is still OFF - send periodic reminders
        else if (!isLocationEnabled) {
            Log.w(TAG, "Location still disabled")
            sendLocationDisabledNotification(immediate = false)
        }
    }

    private fun checkSafeZone(location: Location) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            Log.e(TAG, "No user logged in")
            return
        }

        serviceScope.launch {
            try {
                // Check if child has any safe zones assigned
                val hasSafeZones = hasApplicableSafeZones ?: SafeZoneChecker.hasApplicableSafeZones(childUid = uid)

                if (!hasSafeZones) {
                    Log.d(TAG, "Child has no applicable safe zones - skipping check")
                    updateForegroundNotification("No safe zones defined for you")
                    wasInsideSafeZone = null // Reset state
                    return@launch
                }

                val isInSafeZone = SafeZoneChecker.isLocationInAnySafeZone(
                    childUid = uid,
                    latitude = location.latitude,
                    longitude = location.longitude
                )

                Log.d(TAG, "Location check: isInSafeZone = $isInSafeZone, wasInside = $wasInsideSafeZone")

                // Handle based on current status
                when {
                    isInSafeZone -> {
                        // Child is INSIDE a safe zone
                        Log.d(TAG, "✓ Child is inside safe zone")
                        updateForegroundNotification("✓ Inside safe zone")

                        // If transitioning from outside to inside, log it
                        if (wasInsideSafeZone == false) {
                            Log.d(TAG, "Child entered safe zone from outside")
                        }

                        wasInsideSafeZone = true
                        isFirstCheck = false
                        // Reset alert throttle when entering safe zone
                        lastOutsideAlertTime = 0L
                    }

                    !isInSafeZone -> {
                        // Child is OUTSIDE all safe zones
                        Log.w(TAG, "⚠️ Child is outside safe zone")
                        updateForegroundNotification("⚠️ Outside safe zone")

                        // Send alert to parents
                        sendOutsideAlertIfNeeded(location)

                        wasInsideSafeZone = false
                        isFirstCheck = false
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error checking safe zone", e)
            }
        }
    }

    /**
     * Send alert to parents when child is outside safe zone
     * Throttled to avoid spam - sends immediately on first detection,
     * then every 60 seconds while child remains outside
     */
    private fun sendOutsideAlertIfNeeded(location: Location) {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastAlert = currentTime - lastOutsideAlertTime

        // Send immediately if first alert, otherwise throttle to 60 seconds
        if (lastOutsideAlertTime == 0L || timeSinceLastAlert >= ALERT_THROTTLE_INTERVAL) {
            Log.d(TAG, "Sending outside safe zone alert (time since last: ${timeSinceLastAlert}ms)")
            sendOutsideSafeZoneNotification(
                latitude = location.latitude,
                longitude = location.longitude
            )
            lastOutsideAlertTime = currentTime
        } else {
            val timeUntilNext = (ALERT_THROTTLE_INTERVAL - timeSinceLastAlert) / 1000
            Log.d(TAG, "Alert throttled - next alert in ${timeUntilNext}s")
        }
    }

    private fun sendLocationDisabledNotification(immediate: Boolean = false) {
        val currentTime = System.currentTimeMillis()

        // For immediate notifications (just turned off), send right away
        // For periodic reminders, throttle to once every 2 minutes
        if (!immediate && currentTime - lastLocationDisabledNotificationTime < 2 * 60 * 1000) {
            Log.d(TAG, "Location disabled notification throttled")
            return
        }

        lastLocationDisabledNotificationTime = currentTime

        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        Log.d(TAG, "Sending location disabled notification (immediate: $immediate)")

        serviceScope.launch {
            try {
                val success = FcmNotificationSender.sendNotificationToParents(
                    context = this@LocationMonitoringService,
                    childUid = uid,
                    notificationType = NotificationType.LOCATION_DISABLED
                )

                if (success) {
                    Log.d(TAG, "✓ Location disabled notification sent")
                } else {
                    Log.e(TAG, "✗ Failed to send location disabled notification")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending location disabled notification", e)
                e.printStackTrace()
            }
        }
    }

    private fun sendOutsideSafeZoneNotification(
        latitude: Double,
        longitude: Double
    ) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            Log.e(TAG, "Cannot send notification - no user UID")
            return
        }

        Log.d(TAG, "Sending outside safe zone notification")
        Log.d(TAG, "Child UID: ${uid.take(10)}...")
        Log.d(TAG, "Location: $latitude, $longitude")

        serviceScope.launch {
            try {
                val success = FcmNotificationSender.sendNotificationToParents(
                    context = this@LocationMonitoringService,
                    childUid = uid,
                    notificationType = NotificationType.OUTSIDE_SAFE_ZONE,
                    latitude = latitude,
                    longitude = longitude
                )

                if (success) {
                    Log.d(TAG, "✓ Outside safe zone notification sent with location")
                } else {
                    Log.e(TAG, "✗ Failed to send outside safe zone notification")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception while sending safe zone notification", e)
                e.printStackTrace()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Location Monitoring",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors your location for safety"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createForegroundNotification(contentText: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Child Safety Monitor")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateForegroundNotification(contentText: String) {
        val notification = createForegroundNotification(contentText)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")

        isMonitoring = false
        fusedLocationClient.removeLocationUpdates(locationCallback)
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}