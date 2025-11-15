package com.example.child_safety_app_version1

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import com.example.child_safety_app_version1.navigation.NavGraph
import com.example.child_safety_app_version1.services.ComprehensiveAppBlockingService
import com.example.child_safety_app_version1.utils.NotificationChannelSetup
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

        // 🆕 Initialize notification channels
        NotificationChannelSetup.createNotificationChannels(this)

        // 🆕 START APP BLOCKING SERVICE
        startAppBlockingService()

        // Handle notification click
        handleNotificationIntent(intent)

        // Handle SMS deep link
        handleSmsDeepLink(intent)

        setContent {
            NavGraph(shouldOpenNotifications = shouldOpenNotifications.value)
        }
    }

    /**
     * 🆕 Start the comprehensive app blocking service
     */
    private fun startAppBlockingService() {
        try {
            Log.d(TAG, "🚀 Starting app blocking service...")

            // Only start if user is authenticated
            val currentUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
            if (currentUser == null) {
                Log.w(TAG, "⚠️ User not authenticated yet, deferring service start")
                // Service will be started after user logs in
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

        // Handle notification click when app is already running
        handleNotificationIntent(intent)

        // Handle SMS deep link when app is already running
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
     * Expected format: childsafety://location?lat=X&lon=Y&name=Child&method=GPS&accuracy=10&time=123456
     */
    private fun handleSmsDeepLink(intent: Intent?) {
        try {
            val data: Uri? = intent?.data

            if (data != null) {
                Log.d(TAG, "Deep link received: $data")
                Log.d(TAG, "  Scheme: ${data.scheme}")
                Log.d(TAG, "  Host: ${data.host}")

                // Check if this is our location deep link
                if (data.scheme == "childsafety" && data.host == "location") {
                    Log.d(TAG, "Valid SMS location deep link detected")

                    // Extract parameters
                    val lat = data.getQueryParameter("lat")?.toDoubleOrNull()
                    val lon = data.getQueryParameter("lon")?.toDoubleOrNull()
                    val childName = data.getQueryParameter("name") ?: "Your Child"
                    val locationMethod = data.getQueryParameter("method") ?: "GPS"
                    val accuracy = data.getQueryParameter("accuracy")?.toFloatOrNull() ?: 10f
                    val timestamp = data.getQueryParameter("time")?.toLongOrNull() ?: System.currentTimeMillis()

                    Log.d(TAG, "Extracted parameters:")
                    Log.d(TAG, "  Latitude: $lat")
                    Log.d(TAG, "  Longitude: $lon")
                    Log.d(TAG, "  Child Name: $childName")
                    Log.d(TAG, "  Method: $locationMethod")
                    Log.d(TAG, "  Accuracy: $accuracy")

                    // Validate required parameters
                    if (lat != null && lon != null) {
                        Log.d(TAG, "✅ Valid location data - triggering navigation")

                        // Update state to trigger navigation
                        smsLocationData.value = SmsLocationData(
                            latitude = lat,
                            longitude = lon,
                            childName = childName,
                            locationMethod = locationMethod,
                            accuracy = accuracy,
                            timestamp = timestamp
                        )
                    } else {
                        Log.e(TAG, "❌ Invalid location data - lat or lon is null")
                    }
                } else {
                    Log.d(TAG, "Not a location deep link, ignoring")
                }
            } else {
                Log.d(TAG, "No deep link data in intent")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling SMS deep link", e)
            e.printStackTrace()
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume called")

        // 🆕 Ensure blocking service is running when app resumes
        val currentUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
        if (currentUser != null) {
            Log.d(TAG, "User logged in, ensuring blocking service is running...")
            com.example.child_safety_app_version1.utils.BlockingServiceManager.startBlockingService(this)
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause called")
    }
}