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
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var isMonitoring = false
    private var wasInsideSafeZone: Boolean? = null // null means first check
    private var lastNotificationTime = 0L
    private var lastLocationDisabledNotificationTime = 0L
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var isFirstCheck = true
    private var wasLocationEnabled = true // Track previous location state

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
                val isInSafeZone = SafeZoneChecker.isLocationInAnySafeZone(
                    childUid = uid,
                    latitude = location.latitude,
                    longitude = location.longitude
                )

                Log.d(TAG, "Location check: isInSafeZone = $isInSafeZone, wasInside = $wasInsideSafeZone, isFirstCheck = $isFirstCheck")

                // First time check - immediate notification if outside
                if (wasInsideSafeZone == null || isFirstCheck) {
                    Log.d(TAG, "First location check")
                    wasInsideSafeZone = isInSafeZone
                    isFirstCheck = false

                    if (!isInSafeZone) {
                        // Child is outside safe zone on startup - send immediate notification
                        Log.w(TAG, "Child is OUTSIDE safe zone on first check! Sending immediate notification...")
                        sendOutsideSafeZoneNotification(
                            latitude = location.latitude,
                            longitude = location.longitude,
                            immediate = true
                        )
                        updateForegroundNotification("⚠️ Outside safe zone")
                    } else {
                        Log.d(TAG, "Child is inside safe zone")
                        updateForegroundNotification("✓ Inside safe zone")
                    }
                    return@launch
                }

                // Detect zone boundary crossing
                when {
                    wasInsideSafeZone == true && !isInSafeZone -> {
                        // Child LEFT the safe zone
                        Log.w(TAG, "Child left safe zone!")
                        sendOutsideSafeZoneNotification(location.latitude, location.longitude)
                        updateForegroundNotification("⚠️ Outside safe zone")
                    }
                    wasInsideSafeZone == false && isInSafeZone -> {
                        // Child ENTERED the safe zone
                        Log.d(TAG, "Child entered safe zone")
                        updateForegroundNotification("✓ Inside safe zone")
                        // Reset notification throttle when entering safe zone
                        lastNotificationTime = 0L
                    }
                    wasInsideSafeZone == false && !isInSafeZone -> {
                        // Still outside - send periodic location updates every 30 seconds
                        Log.d(TAG, "Child still outside safe zone - sending location update")
                        sendOutsideSafeZoneNotification(location.latitude, location.longitude)
                    }
                }

                wasInsideSafeZone = isInSafeZone

            } catch (e: Exception) {
                Log.e(TAG, "Error checking safe zone", e)
            }
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
        longitude: Double,
        immediate: Boolean = false
    ) {
        val currentTime = System.currentTimeMillis()

        // For immediate notifications (first check), skip throttle
        // For regular updates, throttle to once every 30 seconds
        if (!immediate && currentTime - lastNotificationTime < 30 * 1000) {
            Log.d(TAG, "Notification throttled - too soon since last notification")
            return
        }
        lastNotificationTime = currentTime

        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            Log.e(TAG, "Cannot send notification - no user UID")
            return
        }

        Log.d(TAG, "Preparing to send outside safe zone notification...")
        Log.d(TAG, "Child UID: ${uid.take(10)}...")
        Log.d(TAG, "Location: $latitude, $longitude")
        Log.d(TAG, "Immediate: $immediate")

        serviceScope.launch {
            try {
                Log.d(TAG, "Calling FcmNotificationSender.sendNotificationToParents...")

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