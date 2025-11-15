package com.example.child_safety_app_version1.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Utility to create notification channels for app blocking service
 * Call this in Application.onCreate() or MainActivity.onCreate()
 */
object NotificationChannelSetup {
    private const val TAG = "NotificationChannelSetup"

    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Log.d(TAG, "Creating notification channels...")

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                    as NotificationManager

            // Channel for app blocking service
            val blockingChannel = NotificationChannel(
                "app_blocking",
                "App Protection Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifications from app blocking service"
                enableVibration(false)
                setShowBadge(false)
            }

            notificationManager.createNotificationChannel(blockingChannel)
            Log.d(TAG, "✅ App blocking notification channel created")

            // Channel for blocked app alerts (if you want to notify parent)
            val alertChannel = NotificationChannel(
                "blocked_app_alerts",
                "Blocked App Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when child tries to access blocked apps"
                enableVibration(true)
                setShowBadge(true)
            }

            notificationManager.createNotificationChannel(alertChannel)
            Log.d(TAG, "✅ Blocked app alerts notification channel created")

        } else {
            Log.d(TAG, "Android version below O, notification channels not needed")
        }
    }
}