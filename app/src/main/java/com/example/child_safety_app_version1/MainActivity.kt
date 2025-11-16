package com.example.child_safety_app_version1

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import com.example.child_safety_app_version1.navigation.NavGraph
import com.example.child_safety_app_version1.services.ComprehensiveAppBlockingService
import com.example.child_safety_app_version1.utils.NotificationChannelSetup
import com.example.child_safety_app_version1.utils.PermissionHelper
import com.google.firebase.FirebaseApp

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"

        // Shared state to trigger navigation to notifications screen
        var shouldOpenNotifications = mutableStateOf(false)

        // Shared state for SMS deep link location data
        var smsLocationData = mutableStateOf<SmsLocationData?>(null)
    }

    // Data class for SMS location info
    data class SmsLocationData(
        val latitude: Double,
        val longitude: Double,
        val childName: String,
        val locationMethod: String,
        val accuracy: Float,
        val timestamp: Long
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FirebaseApp.initializeApp(this)

        Log.d(TAG, "onCreate called")

        // Initialize notification channels
        NotificationChannelSetup.createNotificationChannels(this)

        // ✅ CHECK AND REQUEST RUNTIME PERMISSIONS
        checkAndRequestRuntimePermissions()

        // Handle notification click
        handleNotificationIntent(intent)

        // Handle SMS deep link
        handleSmsDeepLink(intent)

        setContent {
            NavGraph(shouldOpenNotifications = shouldOpenNotifications.value)
        }
    }

    /**
     * ✅ Check and request all runtime permissions (camera, mic, location, SMS)
     */
    private fun checkAndRequestRuntimePermissions() {
        // Log current permission status
        PermissionHelper.logPermissionStatus(this)

        // Check if all runtime permissions are granted
        if (PermissionHelper.hasAllRuntimePermissions(this)) {
            Log.d(TAG, "✅ All runtime permissions granted")

            // Verify camera hardware availability
            if (PermissionHelper.isCameraAvailable(this)) {
                Log.d(TAG, "✅ Camera hardware available")
            } else {
                Log.w(TAG, "⚠️ Camera hardware not available on this device")
            }

            onPermissionsReady()
        } else {
            Log.w(TAG, "⚠️ Some runtime permissions missing - requesting...")

            // Request all missing runtime permissions
            PermissionHelper.requestAllRuntimePermissions(this)
        }
    }

    /**
     * ✅ Handle permission request results
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        Log.d(TAG, "Permission request result: requestCode=$requestCode")

        when (requestCode) {
            PermissionHelper.ALL_RUNTIME_PERMISSIONS_REQUEST_CODE -> {
                handleAllRuntimePermissionsResult(permissions, grantResults)
            }
            PermissionHelper.MEDIA_PERMISSIONS_REQUEST_CODE -> {
                handleMediaPermissionsResult(permissions, grantResults)
            }
            PermissionHelper.CAMERA_PERMISSION_REQUEST_CODE -> {
                handleCameraPermissionResult(grantResults)
            }
            PermissionHelper.MICROPHONE_PERMISSION_REQUEST_CODE -> {
                handleMicrophonePermissionResult(grantResults)
            }
            PermissionHelper.LOCATION_PERMISSIONS_REQUEST_CODE -> {
                handleLocationPermissionsResult(grantResults)
            }
        }
    }

    /**
     * Handle result of requesting all runtime permissions
     */
    private fun handleAllRuntimePermissionsResult(
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        val deniedPermissions = mutableListOf<String>()

        permissions.forEachIndexed { index, permission ->
            if (grantResults[index] != PackageManager.PERMISSION_GRANTED) {
                val shortName = permission.substringAfterLast(".")
                deniedPermissions.add(shortName)
                Log.w(TAG, "  ❌ Denied: $shortName")
            } else {
                val shortName = permission.substringAfterLast(".")
                Log.d(TAG, "  ✅ Granted: $shortName")
            }
        }

        if (deniedPermissions.isEmpty()) {
            Log.d(TAG, "✅ All runtime permissions granted")
            Toast.makeText(this, "All permissions granted", Toast.LENGTH_SHORT).show()
            onPermissionsReady()
        } else {
            Log.e(TAG, "❌ Denied permissions: $deniedPermissions")

            // Check if critical permissions were denied
            val criticalDenied = deniedPermissions.any {
                it in listOf("CAMERA", "RECORD_AUDIO", "ACCESS_FINE_LOCATION")
            }

            if (criticalDenied) {
                Toast.makeText(
                    this,
                    "Critical permissions denied. Some features won't work.",
                    Toast.LENGTH_LONG
                ).show()

                // Offer to open settings
                showPermissionDeniedDialog(deniedPermissions)
            } else {
                // Non-critical permissions denied, proceed anyway
                onPermissionsReady()
            }
        }
    }

    /**
     * Handle media permissions (camera + microphone) result
     */
    private fun handleMediaPermissionsResult(
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        var cameraGranted = false
        var micGranted = false

        permissions.forEachIndexed { index, permission ->
            when (permission) {
                android.Manifest.permission.CAMERA -> {
                    cameraGranted = grantResults[index] == PackageManager.PERMISSION_GRANTED
                }
                android.Manifest.permission.RECORD_AUDIO -> {
                    micGranted = grantResults[index] == PackageManager.PERMISSION_GRANTED
                }
            }
        }

        Log.d(TAG, "Media permissions: Camera=$cameraGranted, Mic=$micGranted")

        if (cameraGranted && micGranted) {
            Toast.makeText(this, "Media permissions granted", Toast.LENGTH_SHORT).show()

            // Verify camera availability
            if (PermissionHelper.isCameraAvailable(this)) {
                Log.d(TAG, "✅ Camera ready for use")
            }
        } else {
            Toast.makeText(
                this,
                "Camera/Microphone permissions needed for video capture",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Handle camera permission result
     */
    private fun handleCameraPermissionResult(grantResults: IntArray) {
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "✅ Camera permission granted")

            if (PermissionHelper.isCameraAvailable(this)) {
                Toast.makeText(this, "Camera ready", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Camera not available", Toast.LENGTH_SHORT).show()
            }
        } else {
            Log.e(TAG, "❌ Camera permission denied")
            Toast.makeText(
                this,
                "Camera permission is required for video capture",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Handle microphone permission result
     */
    private fun handleMicrophonePermissionResult(grantResults: IntArray) {
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "✅ Microphone permission granted")
            Toast.makeText(this, "Microphone ready", Toast.LENGTH_SHORT).show()
        } else {
            Log.e(TAG, "❌ Microphone permission denied")
            Toast.makeText(
                this,
                "Microphone permission is required for audio/video capture",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Handle location permissions result
     */
    private fun handleLocationPermissionsResult(grantResults: IntArray) {
        val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }

        if (allGranted) {
            Log.d(TAG, "✅ Location permissions granted")
            Toast.makeText(this, "Location permissions granted", Toast.LENGTH_SHORT).show()
        } else {
            Log.e(TAG, "❌ Location permissions denied")
            Toast.makeText(
                this,
                "Location permissions are required for safety monitoring",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Show dialog when critical permissions are permanently denied
     */
    private fun showPermissionDeniedDialog(deniedPermissions: List<String>) {
        // TODO: Show AlertDialog with explanation
        // For now, just open app settings
        Log.d(TAG, "Opening app settings for denied permissions")
        PermissionHelper.openAppSettings(this)
    }

    /**
     * Called when all runtime permissions are ready
     * Start services here
     */
    private fun onPermissionsReady() {
        Log.d(TAG, "════════════════════════════════════════")
        Log.d(TAG, "✅ RUNTIME PERMISSIONS READY")
        Log.d(TAG, "════════════════════════════════════════")

        // Check if user is authenticated before starting services
        val currentUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
        if (currentUser != null) {
            Log.d(TAG, "User authenticated - starting services")
            startAppBlockingService()
        } else {
            Log.w(TAG, "User not authenticated yet - services will start after login")
        }
    }

    /**
     * Start the comprehensive app blocking service
     */
    private fun startAppBlockingService() {
        try {
            Log.d(TAG, "🚀 Starting app blocking service...")

            val currentUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
            if (currentUser == null) {
                Log.w(TAG, "⚠️ User not authenticated yet, deferring service start")
                return
            }

            val blockingServiceIntent = Intent(this, ComprehensiveAppBlockingService::class.java)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(blockingServiceIntent)
                Log.d(TAG, "✅ Blocking service started as foreground service")
            } else {
                startService(blockingServiceIntent)
                Log.d(TAG, "✅ Blocking service started")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error starting blocking service: ${e.message}", e)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        Log.d(TAG, "onNewIntent called")

        handleNotificationIntent(intent)
        handleSmsDeepLink(intent)
    }

    /**
     * Handle notification click to open notifications screen
     */
    private fun handleNotificationIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("open_notifications", false) == true) {
            Log.d(TAG, "Notification click detected - opening notifications screen")
            shouldOpenNotifications.value = true
        }
    }

    /**
     * Handle SMS deep link to view child location
     */
    private fun handleSmsDeepLink(intent: Intent?) {
        try {
            val data: Uri? = intent?.data

            if (data != null) {
                Log.d(TAG, "Deep link received: $data")

                if (data.scheme == "childsafety" && data.host == "location") {
                    Log.d(TAG, "Valid SMS location deep link detected")

                    val lat = data.getQueryParameter("lat")?.toDoubleOrNull()
                    val lon = data.getQueryParameter("lon")?.toDoubleOrNull()
                    val childName = data.getQueryParameter("name") ?: "Your Child"
                    val locationMethod = data.getQueryParameter("method") ?: "GPS"
                    val accuracy = data.getQueryParameter("accuracy")?.toFloatOrNull() ?: 10f
                    val timestamp = data.getQueryParameter("time")?.toLongOrNull()
                        ?: System.currentTimeMillis()

                    if (lat != null && lon != null) {
                        Log.d(TAG, "✅ Valid location data - triggering navigation")

                        smsLocationData.value = SmsLocationData(
                            latitude = lat,
                            longitude = lon,
                            childName = childName,
                            locationMethod = locationMethod,
                            accuracy = accuracy,
                            timestamp = timestamp
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling SMS deep link", e)
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume called")

        // Re-check permissions when returning to app
        if (!PermissionHelper.hasAllRuntimePermissions(this)) {
            Log.w(TAG, "⚠️ Some runtime permissions were revoked")
            // Optionally re-request
        }

        // Ensure blocking service is running if user is logged in
        val currentUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
        if (currentUser != null) {
            Log.d(TAG, "User logged in, ensuring blocking service is running...")
            com.example.child_safety_app_version1.utils.BlockingServiceManager
                .startBlockingService(this)
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause called")
    }
}