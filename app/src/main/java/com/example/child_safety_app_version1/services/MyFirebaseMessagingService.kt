package com.example.child_safety_app_version1.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.child_safety_app_version1.MainActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "FCMService"
        private const val CHANNEL_ID = "child_safety_alerts"
        private const val CHANNEL_NAME = "Child Safety Alerts"
        private const val OUTSIDE_SAFE_ZONE_NOTIFICATION_ID = 2001
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "========================================")
        Log.d(TAG, "FCM SERVICE CREATED!!!")
        Log.d(TAG, "========================================")
        createNotificationChannel()
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "========================================")
        Log.d(TAG, "NEW TOKEN GENERATED: ${token.take(20)}...")
        Log.d(TAG, "========================================")
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        Log.d(TAG, "========================================")
        Log.d(TAG, "MESSAGE RECEIVED!!!")
        Log.d(TAG, "From: ${message.from}")
        Log.d(TAG, "MessageId: ${message.messageId}")
        Log.d(TAG, "Notification Title: ${message.notification?.title}")
        Log.d(TAG, "Notification Body: ${message.notification?.body}")
        Log.d(TAG, "Data Payload: ${message.data}")
        Log.d(TAG, "========================================")

        // Extract all data
        val title = message.notification?.title ?: message.data["title"] ?: "Child Safety Alert"
        val body = message.notification?.body ?: message.data["body"] ?: "Check your child's status"
        val notificationType = message.data["notificationType"] ?: "UNKNOWN"
        val latitude = message.data["latitude"]?.toDoubleOrNull()
        val longitude = message.data["longitude"]?.toDoubleOrNull()
        val parentUid = message.data["parentUid"]
        val childUid = message.data["childUid"] ?: ""

        Log.d(TAG, "Parsed Data:")
        Log.d(TAG, "  - Title: $title")
        Log.d(TAG, "  - Body: $body")
        Log.d(TAG, "  - Type: $notificationType")
        Log.d(TAG, "  - Location: $latitude, $longitude")
        Log.d(TAG, "  - Parent UID: ${parentUid?.take(10)}...")
        Log.d(TAG, "  - Child UID: ${childUid.take(10)}...")

        // Determine which parent to save to
        val targetParentUid = parentUid ?: FirebaseAuth.getInstance().currentUser?.uid

        if (targetParentUid == null) {
            Log.e(TAG, "❌ CRITICAL: No parent UID available - cannot save notification")
            return
        }

        Log.d(TAG, "Target parent UID: ${targetParentUid.take(10)}...")

        // Save to Firestore FIRST, then show notification
        saveNotificationToFirestore(
            parentUid = targetParentUid,
            childUid = childUid,
            title = title,
            body = body,
            notificationType = notificationType,
            latitude = latitude,
            longitude = longitude
        )

        // Show system notification
        when (notificationType) {
            "OUTSIDE_SAFE_ZONE" -> {
                showOrUpdateOutsideSafeZoneNotification(title, body, latitude, longitude)
            }
            else -> {
                showNotification(title, body, notificationType, latitude, longitude, System.currentTimeMillis().toInt())
            }
        }
    }

    private fun showOrUpdateOutsideSafeZoneNotification(
        title: String,
        body: String,
        latitude: Double?,
        longitude: Double?
    ) {
        try {
            Log.d(TAG, "📱 Showing/updating OUTSIDE_SAFE_ZONE notification...")

            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("open_notifications", true)
                putExtra("notificationType", "OUTSIDE_SAFE_ZONE")
                if (latitude != null && longitude != null) {
                    putExtra("latitude", latitude.toString())
                    putExtra("longitude", longitude.toString())
                }
            }

            val pendingIntent = PendingIntent.getActivity(
                this,
                OUTSIDE_SAFE_ZONE_NOTIFICATION_ID,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val locationText = if (latitude != null && longitude != null) {
                "\n📍 ${String.format("%.4f", latitude)}, ${String.format("%.4f", longitude)}"
            } else {
                ""
            }

            val fullBody = "$body$locationText\n🔄 Updates every 30 seconds"

            val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(fullBody))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(false) // Don't dismiss on tap - let user view in app
                .setOngoing(false)
                .setContentIntent(pendingIntent)
                .setOnlyAlertOnce(true) // Only sound/vibrate once
                .setDefaults(NotificationCompat.DEFAULT_ALL)

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(OUTSIDE_SAFE_ZONE_NOTIFICATION_ID, notificationBuilder.build())

            Log.d(TAG, "✅ Notification displayed with ID: $OUTSIDE_SAFE_ZONE_NOTIFICATION_ID")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error displaying notification", e)
            e.printStackTrace()
        }
    }

    private fun showNotification(
        title: String,
        body: String,
        notificationType: String,
        latitude: Double?,
        longitude: Double?,
        notificationId: Int
    ) {
        try {
            Log.d(TAG, "📱 Showing notification type: $notificationType")

            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("open_notifications", true)
                putExtra("notificationType", notificationType)
                if (latitude != null && longitude != null) {
                    putExtra("latitude", latitude.toString())
                    putExtra("longitude", longitude.toString())
                }
            }

            val pendingIntent = PendingIntent.getActivity(
                this,
                notificationId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(notificationId, notificationBuilder.build())

            Log.d(TAG, "✅ Notification displayed with ID: $notificationId")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error displaying notification", e)
            e.printStackTrace()
        }
    }

    /**
     * Saves notification to Firestore under parent's notifications subcollection
     * CRITICAL: Uses fixed document ID for OUTSIDE_SAFE_ZONE to enable dynamic updates
     */
    private fun saveNotificationToFirestore(
        parentUid: String,
        childUid: String,
        title: String,
        body: String,
        notificationType: String,
        latitude: Double?,
        longitude: Double?
    ) {
        try {
            Log.d(TAG, "💾 Starting Firestore save operation...")
            Log.d(TAG, "  Parent UID: ${parentUid.take(10)}...")
            Log.d(TAG, "  Child UID: ${childUid.take(10)}...")
            Log.d(TAG, "  Type: $notificationType")

            val db = FirebaseFirestore.getInstance()

            if (notificationType == "OUTSIDE_SAFE_ZONE" && childUid.isNotEmpty()) {
                // ⭐ CRITICAL: Fixed document ID enables updates instead of duplicates
                val documentId = "outside_safe_zone_$childUid"

                Log.d(TAG, "🔑 Using FIXED document ID: $documentId")
                Log.d(TAG, "📍 Location: $latitude, $longitude")

                val notificationData = hashMapOf<String, Any>(
                    "title" to title,
                    "body" to body,
                    "type" to notificationType,
                    "timestamp" to System.currentTimeMillis(),
                    "childUid" to childUid
                )

                // Add latitude/longitude as Double (not String) if available
                if (latitude != null) {
                    notificationData["latitude"] = latitude
                }
                if (longitude != null) {
                    notificationData["longitude"] = longitude
                }

                // Only set 'read' to false if this is a NEW notification
                // For updates, preserve existing 'read' status
                val docRef = db.collection("users")
                    .document(parentUid)
                    .collection("notifications")
                    .document(documentId)

                // Check if document exists first
                docRef.get()
                    .addOnSuccessListener { documentSnapshot ->
                        if (documentSnapshot.exists()) {
                            // Document exists - UPDATE without changing 'read' status
                            Log.d(TAG, "📝 Updating EXISTING notification (preserving read status)")

                            val updateData = hashMapOf<String, Any>(
                                "body" to body,
                                "latitude" to (latitude ?: ""),
                                "longitude" to (longitude ?: ""),
                                "timestamp" to System.currentTimeMillis()
                            )

                            docRef.update(updateData)
                                .addOnSuccessListener {
                                    Log.d(TAG, "✅ Successfully UPDATED notification $documentId")
                                }
                                .addOnFailureListener { e ->
                                    Log.e(TAG, "❌ Failed to UPDATE notification", e)
                                }
                        } else {
                            // Document doesn't exist - CREATE with read=false
                            Log.d(TAG, "✨ Creating NEW notification")
                            notificationData["read"] = false

                            docRef.set(notificationData)
                                .addOnSuccessListener {
                                    Log.d(TAG, "✅ Successfully CREATED notification $documentId")
                                }
                                .addOnFailureListener { e ->
                                    Log.e(TAG, "❌ Failed to CREATE notification", e)
                                }
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "❌ Failed to check if notification exists", e)
                        // Fallback: just set the data
                        notificationData["read"] = false
                        docRef.set(notificationData, SetOptions.merge())
                    }

            } else {
                // For other notification types, always create new document
                Log.d(TAG, "📄 Creating new notification with auto-generated ID")
                createNewNotification(parentUid, childUid, title, body, notificationType, latitude, longitude)
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ CRITICAL ERROR saving notification to Firestore", e)
            e.printStackTrace()
        }
    }

    /**
     * Creates a new notification document with auto-generated ID
     */
    private fun createNewNotification(
        parentUid: String,
        childUid: String,
        title: String,
        body: String,
        notificationType: String,
        latitude: Double?,
        longitude: Double?
    ) {
        val db = FirebaseFirestore.getInstance()

        val notificationData = hashMapOf(
            "title" to title,
            "body" to body,
            "type" to notificationType,
            "latitude" to (latitude ?: ""),
            "longitude" to (longitude ?: ""),
            "timestamp" to System.currentTimeMillis(),
            "read" to false,
            "childUid" to childUid
        )

        db.collection("users")
            .document(parentUid)
            .collection("notifications")
            .add(notificationData)
            .addOnSuccessListener { documentReference ->
                Log.d(TAG, "✅ Created new notification: ${documentReference.id}")
                Log.d(TAG, "   Type: $notificationType")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "❌ Failed to create notification", e)
                e.printStackTrace()
            }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for child safety alerts"
                enableLights(true)
                enableVibration(true)
                setShowBadge(true)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)

            Log.d(TAG, "📢 Notification channel created: $CHANNEL_ID")
        }
    }
}