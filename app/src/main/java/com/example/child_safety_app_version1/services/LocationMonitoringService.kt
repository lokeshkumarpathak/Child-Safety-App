package com.example.child_safety_app_version1.services

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.example.child_safety_app_version1.MainActivity
import com.example.child_safety_app_version1.utils.FcmNotificationSender
import com.example.child_safety_app_version1.utils.NotificationType
import com.example.child_safety_app_version1.utils.SafeZoneChecker
import com.example.child_safety_app_version1.utils.celltower.HybridLocationStrategy
import com.example.child_safety_app_version1.utils.celltower.HybridLocationResult
import com.example.child_safety_app_version1.utils.celltower.LocationMethod
import com.example.child_safety_app_version1.utils.celltower.NetworkConnectivityMonitor
import com.example.child_safety_app_version1.utils.media.SafeZoneMediaAlertService
import com.example.child_safety_app_version1.utils.sms.SmsLocationSender
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
        private const val LOCATION_CHECK_INTERVAL = 5 * 1000L // Check every 5 seconds
        private const val ALERT_THROTTLE_INTERVAL = 60 * 1000L // Send alert every 60 seconds

        // 🆕 Wake lock tag
        private const val WAKE_LOCK_TAG = "ChildSafety:LocationMonitoring"

        // 🆕 Restart tracking
        private var restartCount = 0
        private const val MAX_RESTART_ATTEMPTS = 5
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    // Hybrid location components
    private lateinit var hybridLocationStrategy: HybridLocationStrategy
    private lateinit var networkMonitor: NetworkConnectivityMonitor
    private lateinit var smsLocationSender: SmsLocationSender

    // 🆕 Wake lock to prevent service from sleeping
    private var wakeLock: PowerManager.WakeLock? = null

    private var isMonitoring = false
    private var wasInsideSafeZone: Boolean? = null
    private var lastOutsideAlertTime = 0L
    private var lastLocationDisabledNotificationTime = 0L
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var isFirstCheck = true
    private var wasLocationEnabled = true
    private var hasApplicableSafeZones: Boolean? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "════════════════════════════════════════")
        Log.d(TAG, "🚀 SERVICE CREATED (Restart count: $restartCount)")
        Log.d(TAG, "════════════════════════════════════════")

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Initialize hybrid location components
        hybridLocationStrategy = HybridLocationStrategy(this)
        networkMonitor = NetworkConnectivityMonitor(this)
        smsLocationSender = SmsLocationSender(this)

        acquireWakeLock()
        createNotificationChannel()
        setupLocationCallback()
    }

    // 🆕 Call this in onStartCommand after service starts
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "════════════════════════════════════════")
        Log.d(TAG, "🔄 SERVICE STARTED")
        Log.d(TAG, "   Intent: ${intent?.action}")
        Log.d(TAG, "   Restart count: $restartCount")
        Log.d(TAG, "════════════════════════════════════════")

        val notification = createForegroundNotification("Monitoring your safety zone")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        checkApplicableSafeZones()
        getImmediateLocationHybrid()
        startLocationMonitoring()
        setupLocationSettingsListener()

        // 🆕 Upload any pending media from previous crashes/offline periods
        uploadPendingMediaOnRestart()

        startServiceHeartbeat()

        return START_STICKY
    }

    /**
     * 🆕 Acquire partial wake lock to prevent Doze mode from killing service
     */
    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKE_LOCK_TAG
            ).apply {
                acquire(10 * 60 * 60 * 1000L) // 10 hours
            }
            Log.d(TAG, "✅ Wake lock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to acquire wake lock", e)
        }
    }

    /**
     * 🆕 Release wake lock
     */
    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "✅ Wake lock released")
                }
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error releasing wake lock", e)
        }
    }

    /**
     * 🆕 Heartbeat to keep service alive and detect if it's still working
     */
    private fun startServiceHeartbeat() {
        serviceScope.launch {
            var heartbeatCount = 0
            while (isActive && isMonitoring) {
                heartbeatCount++

                if (heartbeatCount % 12 == 0) { // Every 60 seconds (12 * 5 seconds)
                    Log.d(TAG, "💓 Service heartbeat: $heartbeatCount (${heartbeatCount * 5}s uptime)")

                    // Verify location monitoring is still active
                    if (!isMonitoring) {
                        Log.e(TAG, "⚠️ Monitoring stopped unexpectedly - restarting...")
                        startLocationMonitoring()
                    }
                }

                delay(5000) // 5 seconds
            }
        }
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    Log.d(TAG, "📍 GPS Location: ${location.latitude}, ${location.longitude}")

                    val hybridResult = HybridLocationResult.Success(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracy = location.accuracy,
                        method = LocationMethod.GPS
                    )

                    checkSafeZone(hybridResult)
                }
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                super.onLocationAvailability(availability)
                if (!availability.isLocationAvailable) {
                    Log.w(TAG, "⚠️ GPS not available - using cell tower fallback")
                    checkLocationSettings()
                }
            }
        }
    }

    private fun checkApplicableSafeZones() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            Log.e(TAG, "❌ No user logged in")
            return
        }

        serviceScope.launch {
            try {
                val hasSafeZones = SafeZoneChecker.hasApplicableSafeZones(childUid = uid)
                hasApplicableSafeZones = hasSafeZones

                if (!hasSafeZones) {
                    Log.w(TAG, "⚠️ No applicable safe zones defined")
                    updateForegroundNotification("No safe zones defined")
                } else {
                    Log.d(TAG, "✅ Safe zones found")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error checking safe zones", e)
            }
        }
    }

    private fun getImmediateLocationHybrid() {
        serviceScope.launch {
            try {
                Log.d(TAG, "🔍 Getting immediate location...")

                val result = hybridLocationStrategy.getCurrentLocation()

                when (result) {
                    is HybridLocationResult.Success -> {
                        Log.d(TAG, "✅ Got location via ${result.method}")
                        checkSafeZone(result)
                    }
                    is HybridLocationResult.Error -> {
                        Log.e(TAG, "❌ Failed: ${result.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Exception", e)
            }
        }
    }

    private fun startLocationMonitoring() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "❌ Location permission not granted")
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
        Log.d(TAG, "✅ Location monitoring started (GPS + Cell Tower + SMS)")
    }

    private fun setupLocationSettingsListener() {
        serviceScope.launch {
            while (isActive && isMonitoring) {
                delay(LOCATION_CHECK_INTERVAL)
                checkLocationSettings()
            }
        }
    }

    private fun checkLocationSettings() {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        val isLocationEnabled = isGpsEnabled || isNetworkEnabled

        networkMonitor.logNetworkChange()

        if (wasLocationEnabled && !isLocationEnabled) {
            Log.w(TAG, "⚠️ LOCATION DISABLED - Using cell tower + SMS")
            updateForegroundNotification("⚠️ Cell tower mode")
            sendLocationDisabledNotification(immediate = true)
            wasLocationEnabled = false
        }
        else if (!wasLocationEnabled && isLocationEnabled) {
            Log.d(TAG, "✅ Location re-enabled")
            updateForegroundNotification("Monitoring safety zone")
            wasLocationEnabled = true
            lastLocationDisabledNotificationTime = 0L
            hybridLocationStrategy.resetGpsFailureCounter()
        }
        else if (!isLocationEnabled) {
            sendLocationDisabledNotification(immediate = false)
            tryGetCellTowerLocation()
        }
    }

    private fun tryGetCellTowerLocation() {
        serviceScope.launch {
            try {
                val result = hybridLocationStrategy.forceCellTowerMode()

                when (result) {
                    is HybridLocationResult.Success -> {
                        Log.d(TAG, "✅ Cell tower location obtained")
                        checkSafeZone(result)
                    }
                    is HybridLocationResult.Error -> {
                        Log.w(TAG, "⚠️ Cell tower failed: ${result.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Exception", e)
            }
        }
    }

    private fun checkSafeZone(locationResult: HybridLocationResult.Success) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            Log.e(TAG, "❌ No user logged in")
            return
        }

        serviceScope.launch {
            try {
                val hasSafeZones = hasApplicableSafeZones
                    ?: SafeZoneChecker.hasApplicableSafeZones(childUid = uid)

                if (!hasSafeZones) {
                    Log.d(TAG, "ℹ️ No safe zones - skipping check")
                    updateForegroundNotification("No safe zones defined")
                    wasInsideSafeZone = null
                    return@launch
                }

                val isInSafeZone = SafeZoneChecker.isLocationInAnySafeZone(
                    childUid = uid,
                    latitude = locationResult.latitude,
                    longitude = locationResult.longitude
                )

                Log.d(TAG, "Safe zone check: $isInSafeZone (via ${locationResult.method})")

                when {
                    isInSafeZone -> {
                        val methodIcon = when (locationResult.method) {
                            LocationMethod.GPS -> "🛰️"
                            LocationMethod.CELL_TOWER -> "📡"
                            LocationMethod.CACHED_GPS -> "📦"
                            else -> ""
                        }
                        updateForegroundNotification("✅ Inside safe zone $methodIcon")

                        if (wasInsideSafeZone == false) {
                            Log.d(TAG, "🏠 Entered safe zone")
                        }

                        wasInsideSafeZone = true
                        isFirstCheck = false
                        lastOutsideAlertTime = 0L
                    }

                    !isInSafeZone -> {
                        Log.w(TAG, "⚠️ Outside safe zone")
                        updateForegroundNotification("⚠️ Outside safe zone")
                        sendOutsideAlertWithMethod(locationResult)
                        wasInsideSafeZone = false
                        isFirstCheck = false
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error checking safe zone", e)
            }
        }
    }

    // 🆕 REPLACE the sendOutsideAlertWithMethod function with this version:
    // 🆕 REPLACE the sendOutsideAlertWithMethod function with this version:
    private fun sendOutsideAlertWithMethod(result: HybridLocationResult.Success) {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastAlert = currentTime - lastOutsideAlertTime

        if (lastOutsideAlertTime == 0L || timeSinceLastAlert >= ALERT_THROTTLE_INTERVAL) {
            Log.d(TAG, "🚨 Sending alert (${result.method}, ${timeSinceLastAlert}ms ago)")

            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return

            serviceScope.launch {
                val hasInternet = networkMonitor.isInternetAvailable()
                val goodForApi = networkMonitor.isGoodEnoughForApiCalls()

                Log.d(TAG, "Network: internet=$hasInternet, goodForApi=$goodForApi")

                // 🆕 NEW: Capture and upload media (video + audio) when outside safe zone
                try {
                    val mediaSuccess = SafeZoneMediaAlertService.handleOutsideSafeZoneWithMedia(
                        context = this@LocationMonitoringService,
                        childUid = uid,
                        latitude = result.latitude,
                        longitude = result.longitude,
                        locationMethod = result.method.name,
                        accuracy = result.accuracy
                    )

                    if (mediaSuccess) {
                        Log.d(TAG, "✅ Media capture & upload succeeded")
                    } else {
                        Log.w(TAG, "⚠️ Media capture/upload failed - falling back to FCM/SMS")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Exception in media capture", e)
                    e.printStackTrace()
                }

                // 🆕 FALLBACK: Send location alert via FCM/SMS (original behavior)
                if (hasInternet && goodForApi) {
                    sendViaFcm(uid, result)
                } else {
                    sendViaSms(uid, result)
                }
            }

            lastOutsideAlertTime = currentTime
        } else {
            val timeUntilNext = (ALERT_THROTTLE_INTERVAL - timeSinceLastAlert) / 1000
            Log.d(TAG, "⏱️ Alert throttled - next in ${timeUntilNext}s")
        }
    }

    // 🆕 Add this function to upload pending media when service restarts
    private fun uploadPendingMediaOnRestart() {
        serviceScope.launch {
            try {
                Log.d(TAG, "🔄 Checking for pending media files on service restart...")
                SafeZoneMediaAlertService.uploadPendingMediaFiles(
                    context = this@LocationMonitoringService
                )
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error uploading pending media", e)
            }
        }
    }

    private suspend fun sendViaFcm(childUid: String, result: HybridLocationResult.Success) {
        try {
            val success = FcmNotificationSender.sendNotificationToParents(
                context = this@LocationMonitoringService,
                childUid = childUid,
                notificationType = NotificationType.OUTSIDE_SAFE_ZONE,
                latitude = result.latitude,
                longitude = result.longitude,
                locationMethod = result.method.name,
                accuracy = result.accuracy
            )

            if (success) {
                Log.d(TAG, "✅ FCM sent")
            } else {
                Log.e(TAG, "❌ FCM failed - trying SMS...")
                sendViaSms(childUid, result)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ FCM exception", e)
            sendViaSms(childUid, result)
        }
    }

    private suspend fun sendViaSms(childUid: String, result: HybridLocationResult.Success) {
        try {
            val success = smsLocationSender.sendLocationViaSms(
                latitude = result.latitude,
                longitude = result.longitude,
                childUid = childUid,
                locationMethod = result.method.name,
                accuracy = result.accuracy
            )

            if (success) {
                Log.d(TAG, "✅ SMS sent")
            } else {
                Log.e(TAG, "❌ SMS failed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ SMS exception", e)
        }
    }

    private fun sendLocationDisabledNotification(immediate: Boolean = false) {
        val currentTime = System.currentTimeMillis()

        if (!immediate && currentTime - lastLocationDisabledNotificationTime < 2 * 60 * 1000) {
            return
        }

        lastLocationDisabledNotificationTime = currentTime
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        serviceScope.launch {
            try {
                val success = FcmNotificationSender.sendNotificationToParents(
                    context = this@LocationMonitoringService,
                    childUid = uid,
                    notificationType = NotificationType.LOCATION_DISABLED
                )

                if (success) {
                    Log.d(TAG, "✅ Location disabled notification sent")
                } else {
                    Log.e(TAG, "❌ Failed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error", e)
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
            // 🆕 Add foreground service behavior for Android 12+
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateForegroundNotification(contentText: String) {
        val notification = createForegroundNotification(contentText)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * 🆕 Enhanced onDestroy with restart attempt
     */
    override fun onDestroy() {
        Log.d(TAG, "════════════════════════════════════════")
        Log.d(TAG, "⚠️ SERVICE DESTROYED")
        Log.d(TAG, "   Restart count: $restartCount")
        Log.d(TAG, "════════════════════════════════════════")

        isMonitoring = false
        fusedLocationClient.removeLocationUpdates(locationCallback)
        serviceScope.cancel()
        releaseWakeLock()

        // 🆕 Try to restart service if not intentionally stopped
        if (restartCount < MAX_RESTART_ATTEMPTS) {
            restartCount++
            Log.d(TAG, "🔄 Attempting restart ($restartCount/$MAX_RESTART_ATTEMPTS)")

            val restartIntent = Intent(applicationContext, LocationMonitoringService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(restartIntent)
            } else {
                applicationContext.startService(restartIntent)
            }
        } else {
            Log.e(TAG, "❌ Max restart attempts reached - service will not restart")
            restartCount = 0
        }

        super.onDestroy()
    }

    /**
     * 🆕 Handle task removal (when user swipes app away)
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "⚠️ TASK REMOVED - App swiped away")
        super.onTaskRemoved(rootIntent)

        // Restart service when task is removed
        val restartIntent = Intent(applicationContext, LocationMonitoringService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.startForegroundService(restartIntent)
        } else {
            applicationContext.startService(restartIntent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}