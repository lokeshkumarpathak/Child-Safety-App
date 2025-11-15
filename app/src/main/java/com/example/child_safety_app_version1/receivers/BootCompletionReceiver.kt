package com.example.child_safety_app_version1.receivers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.child_safety_app_version1.MainActivity
import com.example.child_safety_app_version1.R
import com.example.child_safety_app_version1.services.ComprehensiveAppBlockingService

/**
 * ⭐ ENHANCED Boot Completion Receiver
 * - Restarts ComprehensiveAppBlockingService
 * - Checks if AccessibilityService is enabled
 * - Shows notification if accessibility service needs re-enabling
 */
class BootCompletionReceiver : BroadcastReceiver() {
    private val TAG = "BootCompletionReceiver"
    private val ACCESSIBILITY_SERVICE_NAME =
        "com.example.child_safety_app_version1/.services.AppBlockingAccessibilityService"

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return

        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "📱 Device boot completed - checking services")

            // 1. Restart ComprehensiveAppBlockingService
            restartBlockingService(context)

            // 2. Check if AccessibilityService is enabled
            checkAccessibilityService(context)
        }
    }

    /**
     * Restart the ComprehensiveAppBlockingService
     */
    private fun restartBlockingService(context: Context) {
        try {
            val blockingServiceIntent = Intent(context, ComprehensiveAppBlockingService::class.java)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(blockingServiceIntent)
                Log.d(TAG, "✅ Comprehensive blocking service started (foreground)")
            } else {
                context.startService(blockingServiceIntent)
                Log.d(TAG, "✅ Comprehensive blocking service started")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error starting comprehensive blocking service: ${e.message}", e)
        }
    }

    /**
     * Check if AccessibilityService is enabled, show notification if not
     */
    private fun checkAccessibilityService(context: Context) {
        val isEnabled = isAccessibilityServiceEnabled(context, ACCESSIBILITY_SERVICE_NAME)

        if (isEnabled) {
            Log.d(TAG, "✅ Accessibility service is enabled")
        } else {
            Log.w(TAG, "⚠️ Accessibility service is NOT enabled - showing notification")
            showAccessibilityReminderNotification(context)
        }
    }

    /**
     * Check if accessibility service is enabled
     */
    private fun isAccessibilityServiceEnabled(context: Context, serviceName: String): Boolean {
        return try {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )

            enabledServices?.contains(serviceName) == true
        } catch (e: Exception) {
            Log.e(TAG, "Error checking accessibility service: ${e.message}")
            false
        }
    }

    /**
     * Show notification to remind user to enable accessibility service
     */
    private fun showAccessibilityReminderNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create notification channel (required for Android 8.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "service_reminder",
                "Service Reminder",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Reminds to enable required services"
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Intent to open accessibility settings
        val accessibilityIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            accessibilityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Build notification
        val notification = NotificationCompat.Builder(context, "service_reminder")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("⚠️ Parental Control Service Disabled")
            .setContentText("Tap to re-enable accessibility service")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("The parental control service was disabled after device restart. Tap here to re-enable it in accessibility settings.")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(9999, notification)
        Log.d(TAG, "📢 Accessibility reminder notification shown")
    }
}