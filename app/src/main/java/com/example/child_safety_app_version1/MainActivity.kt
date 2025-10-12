package com.example.child_safety_app_version1

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import com.example.child_safety_app_version1.navigation.NavGraph
import com.google.firebase.FirebaseApp

class MainActivity : ComponentActivity() {

    // Shared state to trigger navigation to notifications screen
    companion object {
        var shouldOpenNotifications = mutableStateOf(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FirebaseApp.initializeApp(this)

        // Check if we should open notifications screen from notification click
        handleNotificationIntent(intent)

        setContent {
            NavGraph(shouldOpenNotifications = shouldOpenNotifications.value)
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        // Handle notification click when app is already running
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra("open_notifications", false) == true) {
            shouldOpenNotifications.value = true
        }
    }
}