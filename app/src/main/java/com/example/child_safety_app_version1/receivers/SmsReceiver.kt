package com.example.child_safety_app_version1.receivers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.child_safety_app_version1.MainActivity
import com.example.child_safety_app_version1.utils.sms.SmsLocationParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver to handle incoming SMS messages
 * Detects child location alert SMS and shows notification
 */
class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
        private const val CHANNEL_ID = "sms_location_alerts"
        private const val NOTIFICATION_ID_BASE = 5000
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "═══════════════════════════════════════════════════")
        Log.d(TAG, "SMS RECEIVED")
        Log.d(TAG, "═══════════════════════════════════════════════════")

        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            Log.d(TAG, "Not an SMS received action, ignoring")
            return
        }

        try {
            // Extract SMS messages
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)

            if (messages.isNullOrEmpty()) {
                Log.w(TAG, "No messages in intent")
                return
            }

            Log.d(TAG, "Received ${messages.size} SMS message(s)")

            // Process each message
            messages.forEach { smsMessage ->
                processSmsMessage(context, smsMessage)
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error processing SMS", e)
            e.printStackTrace()
        }
    }

    /**
     * Process individual SMS message
     */
    private fun processSmsMessage(context: Context, smsMessage: SmsMessage) {
        val sender = smsMessage.originatingAddress ?: "Unknown"
        val messageBody = smsMessage.messageBody ?: ""

        Log.d(TAG, "")
        Log.d(TAG, "Processing SMS:")
        Log.d(TAG, "  From: ${sender.take(5)}...${sender.takeLast(4)}")
        Log.d(TAG, "  Body length: ${messageBody.length} chars")
        Log.d(TAG, "  Preview: ${messageBody.take(50)}...")

        // Check if this is a location alert SMS
        if (!SmsLocationParser.isLocationAlertSms(messageBody)) {
            Log.d(TAG, "  ℹ️ Not a location alert SMS, ignoring")
            return
        }

        Log.d(TAG, "  ✅ Location alert SMS detected!")

        // Parse SMS
        val parsedSms = SmsLocationParser.parseLocationSms(messageBody)

        if (parsedSms == null) {
            Log.e(TAG, "  ❌ Failed to parse location SMS")
            return
        }

        Log.d(TAG, "  ✅ SMS parsed successfully")
        Log.d(TAG, "     Child: ${parsedSms.childName}")
        Log.d(TAG, "     Location: ${parsedSms.latitude}, ${parsedSms.longitude}")

        // Validate sender (optional security check)
        CoroutineScope(Dispatchers.IO).launch {
            val isKnownChild = SmsLocationParser.isFromKnownChild(context, sender)

            if (!isKnownChild) {
                Log.w(TAG, "  ⚠️ SMS from unknown number, but showing anyway")
                // Note: We still show notification even from unknown numbers
                // because child might send from different number
            }
        }

        // Show notification
        showLocationAlertNotification(context, parsedSms, sender)
    }

    /**
     * Show notification for location alert SMS
     */
    private fun showLocationAlertNotification(
        context: Context,
        parsedSms: SmsLocationParser.ParsedLocationSms,
        sender: String
    ) {
        try {
            createNotificationChannel(context)

            // Create intent to open location in app
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                // Pass location data via intent extras
                putExtra("open_sms_location", true)
                putExtra("latitude", parsedSms.latitude)
                putExtra("longitude", parsedSms.longitude)
                putExtra("childName", parsedSms.childName)
                putExtra("locationMethod", parsedSms.locationMethod)
                putExtra("accuracy", parsedSms.accuracy)
                putExtra("timestamp", parsedSms.timestamp)
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                NOTIFICATION_ID_BASE + parsedSms.childName.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Format notification text
            val notificationText = SmsLocationParser.formatForNotification(parsedSms)

            // Build notification
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("⚠️ ${parsedSms.childName} - Location Alert")
                .setContentText("Outside Safe Zone")
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(notificationText)
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI)
                .setVibrate(longArrayOf(0, 500, 250, 500))
                .build()

            // Show notification
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notificationId = NOTIFICATION_ID_BASE + parsedSms.childName.hashCode()

            notificationManager.notify(notificationId, notification)

            Log.d(TAG, "  ✅ Notification shown (ID: $notificationId)")

        } catch (e: Exception) {
            Log.e(TAG, "  ❌ Error showing notification", e)
            e.printStackTrace()
        }
    }

    /**
     * Create notification channel for SMS location alerts
     */
    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SMS Location Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Location alerts received via SMS when offline"
                enableLights(true)
                enableVibration(true)
                setShowBadge(true)
            }

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}