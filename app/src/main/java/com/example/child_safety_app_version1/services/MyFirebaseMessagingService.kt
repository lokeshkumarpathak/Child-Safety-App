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
import com.example.child_safety_app_version1.managers.UsageDataCollector
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MyFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "FCMService"
        private const val CHANNEL_ID = "child_safety_alerts"
        private const val CHANNEL_NAME = "Child Safety Alerts"
        private const val OUTSIDE_SAFE_ZONE_NOTIFICATION_ID = 2001
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        val childName = message.data["childName"] ?: "Your child"
        val requestId = message.data["requestId"] ?: ""

        Log.d(TAG, "Parsed Data:")
        Log.d(TAG, "  - Title: $title")
        Log.d(TAG, "  - Body: $body")
        Log.d(TAG, "  - Type: $notificationType")
        Log.d(TAG, "  - Child Name: $childName")
        Log.d(TAG, "  - Location: $latitude, $longitude")
        Log.d(TAG, "  - Parent UID: ${parentUid?.take(10)}...")
        Log.d(TAG, "  - Child UID: ${childUid.take(10)}...")
        Log.d(TAG, "  - Request ID: $requestId")

        // Handle different message types
        when (notificationType) {
            "USAGE_DATA_REQUEST" -> {
                Log.d(TAG, "========================================")
                Log.d(TAG, "🔥 USAGE DATA REQUEST RECEIVED")
                Log.d(TAG, "========================================")
                handleUsageDataRequest(childUid, requestId, parentUid)
            }
            "ALL_APPS_REQUEST" -> {
                Log.d(TAG, "========================================")
                Log.d(TAG, "📱 ALL APPS REQUEST RECEIVED")
                Log.d(TAG, "========================================")
                handleAllAppsRequest(childUid, requestId, parentUid)
            }

            "PAYMENT_THRESHOLD_EXCEEDED", "PAYMENT_TRANSACTION" -> {
                val targetParentUid = parentUid ?: FirebaseAuth.getInstance().currentUser?.uid

                if (targetParentUid == null) {
                    Log.e(TAG, "❌ CRITICAL: No parent UID available - cannot save notification")
                    return
                }

                Log.d(TAG, "Target parent UID: ${targetParentUid.take(10)}...")

                // Extract payment details from data
                val transactionId = message.data["transactionId"] ?: ""
                val amount = message.data["amount"] ?: "0"
                val merchant = message.data["merchant"] ?: "Unknown"
                val transactionType = message.data["transactionType"] ?: "DEBIT"

                val paymentBody = "$body\n₹$amount at $merchant"

                // Save to Firestore
                saveNotificationToFirestore(
                    parentUid = targetParentUid,
                    childUid = childUid,
                    childName = childName,
                    title = title,
                    body = paymentBody,
                    notificationType = notificationType,
                    latitude = null,
                    longitude = null
                )

                // Show system notification
                showNotification(
                    title,
                    paymentBody,
                    childName,
                    notificationType,
                    null,
                    null,
                    System.currentTimeMillis().toInt()
                )
            }


            else -> {
                // Handle other notification types as before
                val targetParentUid = parentUid ?: FirebaseAuth.getInstance().currentUser?.uid

                if (targetParentUid == null) {
                    Log.e(TAG, "❌ CRITICAL: No parent UID available - cannot save notification")
                    return
                }

                Log.d(TAG, "Target parent UID: ${targetParentUid.take(10)}...")

                // Save to Firestore
                saveNotificationToFirestore(
                    parentUid = targetParentUid,
                    childUid = childUid,
                    childName = childName,
                    title = title,
                    body = body,
                    notificationType = notificationType,
                    latitude = latitude,
                    longitude = longitude
                )

                // Show system notification
                when (notificationType) {
                    "OUTSIDE_SAFE_ZONE" -> {
                        showOrUpdateOutsideSafeZoneNotification(title, body, childName, latitude, longitude)
                    }
                    else -> {
                        showNotification(
                            title, body, childName, notificationType,
                            latitude, longitude, System.currentTimeMillis().toInt()
                        )
                    }
                }
            }
        }
    }

    /**
     * Handle usage data collection request from parent
     */
    private fun handleUsageDataRequest(childUid: String, requestId: String, parentUid: String?) {
        Log.d(TAG, "========================================")
        Log.d(TAG, "📊 HANDLING USAGE DATA REQUEST")
        Log.d(TAG, "Child UID: ${childUid.take(10)}...")
        Log.d(TAG, "Request ID: $requestId")
        Log.d(TAG, "Parent UID: ${parentUid?.take(10)}...")
        Log.d(TAG, "========================================")

        scope.launch {
            try {
                // Get current user UID
                val currentUserUid = FirebaseAuth.getInstance().currentUser?.uid
                if (currentUserUid == null) {
                    Log.e(TAG, "❌ Cannot collect data: User not logged in")
                    updateRequestStatus(childUid, requestId, "failed", "User not logged in")
                    return@launch
                }

                Log.d(TAG, "📱 Current user UID: ${currentUserUid.take(10)}...")
                Log.d(TAG, "✅ User is logged in, proceeding with data collection")

                // Trigger usage data collection
                Log.d(TAG, "🚀 Triggering UsageDataCollector...")
                val success = UsageDataCollector.collectAndUpload(
                    context = applicationContext,
                    childUid = currentUserUid,
                    requestId = requestId
                )

                Log.d(TAG, "========================================")
                if (success) {
                    Log.d(TAG, "✅ DATA COLLECTION SUCCESSFUL")
                    Log.d(TAG, "========================================")
                    UsageDataCollector.saveLastCollectionTime(applicationContext)

                    // Show local notification to child
                    showDataCollectionSuccessNotification()

                    // Send confirmation back to parent (optional)
                    if (parentUid != null) {
                        sendConfirmationToParent(currentUserUid, parentUid, requestId, true)
                    }
                } else {
                    Log.d(TAG, "❌ DATA COLLECTION FAILED")
                    Log.d(TAG, "========================================")

                    // Show error notification
                    showDataCollectionErrorNotification()

                    // Send failure confirmation to parent
                    if (parentUid != null) {
                        sendConfirmationToParent(currentUserUid, parentUid, requestId, false)
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "========================================")
                Log.e(TAG, "❌ CRITICAL ERROR IN USAGE DATA REQUEST")
                Log.e(TAG, "========================================")
                Log.e(TAG, "Exception: ${e.javaClass.simpleName}")
                Log.e(TAG, "Message: ${e.message}")
                e.printStackTrace()

                updateRequestStatus(childUid, requestId, "failed", e.message ?: "Unknown error")
                showDataCollectionErrorNotification()
            }
        }
    }

    /**
     * Update request status in Firestore
     */
    private suspend fun updateRequestStatus(
        childUid: String,
        requestId: String,
        status: String,
        errorMessage: String? = null
    ) {
        try {
            val db = FirebaseFirestore.getInstance()
            val updateData = hashMapOf<String, Any>(
                "status" to status,
                "completedAt" to System.currentTimeMillis()
            )

            if (errorMessage != null) {
                updateData["errorMessage"] = errorMessage
            }

            db.collection("users")
                .document(childUid)
                .collection("usageDataRequest")
                .document(requestId)
                .update(updateData)

            Log.d(TAG, "✅ Request status updated to: $status")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error updating request status", e)
        }
    }

    /**
     * Send confirmation back to parent
     */
    private fun sendConfirmationToParent(
        childUid: String,
        parentUid: String,
        requestId: String,
        success: Boolean
    ) {
        try {
            Log.d(TAG, "📤 Sending confirmation to parent...")

            val db = FirebaseFirestore.getInstance()
            val confirmationData = hashMapOf(
                "type" to "USAGE_DATA_COLLECTION_COMPLETE",
                "success" to success,
                "childUid" to childUid,
                "requestId" to requestId,
                "completedAt" to System.currentTimeMillis()
            )

            db.collection("users")
                .document(parentUid)
                .collection("dataCollectionConfirmations")
                .add(confirmationData)
                .addOnSuccessListener {
                    Log.d(TAG, "✅ Confirmation sent to parent")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "❌ Failed to send confirmation", e)
                }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error sending confirmation", e)
        }
    }

    /**
     * Show notification that data collection succeeded
     */
    private fun showDataCollectionSuccessNotification() {
        try {
            Log.d(TAG, "📲 Showing success notification")

            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            val pendingIntent = PendingIntent.getActivity(
                this,
                2002,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("✅ Usage Data Sent")
                .setContentText("Your app usage data has been collected and sent to your parent")
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(2002, notificationBuilder.build())

            Log.d(TAG, "✅ Success notification displayed")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error showing success notification", e)
        }
    }

    /**
     * Show notification that data collection failed
     */
    private fun showDataCollectionErrorNotification() {
        try {
            Log.d(TAG, "📲 Showing error notification")

            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            val pendingIntent = PendingIntent.getActivity(
                this,
                2003,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("❌ Data Collection Failed")
                .setContentText("Failed to collect app usage data. Please check permissions")
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(2003, notificationBuilder.build())

            Log.d(TAG, "✅ Error notification displayed")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error showing error notification", e)
        }
    }

    private fun showOrUpdateOutsideSafeZoneNotification(
        title: String,
        body: String,
        childName: String,
        latitude: Double?,
        longitude: Double?
    ) {
        try {
            Log.d(TAG, "🔱 Showing/updating OUTSIDE_SAFE_ZONE notification for $childName...")

            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("open_notifications", true)
                putExtra("notificationType", "OUTSIDE_SAFE_ZONE")
                putExtra("childName", childName)
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
                .setAutoCancel(false)
                .setOngoing(false)
                .setContentIntent(pendingIntent)
                .setOnlyAlertOnce(true)
                .setDefaults(NotificationCompat.DEFAULT_ALL)

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(OUTSIDE_SAFE_ZONE_NOTIFICATION_ID, notificationBuilder.build())

            Log.d(TAG, "✅ Notification displayed for $childName with ID: $OUTSIDE_SAFE_ZONE_NOTIFICATION_ID")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error displaying notification", e)
            e.printStackTrace()
        }
    }

    private fun showNotification(
        title: String,
        body: String,
        childName: String,
        notificationType: String,
        latitude: Double?,
        longitude: Double?,
        notificationId: Int
    ) {
        try {
            Log.d(TAG, "📲 Showing notification type: $notificationType for $childName")

            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("open_notifications", true)
                putExtra("notificationType", notificationType)
                putExtra("childName", childName)
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

            Log.d(TAG, "✅ Notification displayed for $childName with ID: $notificationId")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error displaying notification", e)
            e.printStackTrace()
        }
    }

    private fun saveNotificationToFirestore(
        parentUid: String,
        childUid: String,
        childName: String,
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
            Log.d(TAG, "  Child Name: $childName")
            Log.d(TAG, "  Type: $notificationType")

            val db = FirebaseFirestore.getInstance()

            if (notificationType == "OUTSIDE_SAFE_ZONE" && childUid.isNotEmpty()) {
                val documentId = "outside_safe_zone_$childUid"

                Log.d(TAG, "🔑 Using FIXED document ID: $documentId")
                Log.d(TAG, "📍 Location: $latitude, $longitude")

                val notificationData = hashMapOf<String, Any>(
                    "title" to title,
                    "body" to body,
                    "type" to notificationType,
                    "timestamp" to System.currentTimeMillis(),
                    "childUid" to childUid,
                    "childName" to childName
                )

                if (latitude != null) {
                    notificationData["latitude"] = latitude
                }
                if (longitude != null) {
                    notificationData["longitude"] = longitude
                }

                val docRef = db.collection("users")
                    .document(parentUid)
                    .collection("notifications")
                    .document(documentId)

                docRef.get()
                    .addOnSuccessListener { documentSnapshot ->
                        if (documentSnapshot.exists()) {
                            Log.d(TAG, "🔄 Updating EXISTING notification (preserving read status)")

                            val updateData = hashMapOf<String, Any>(
                                "body" to body,
                                "latitude" to (latitude ?: ""),
                                "longitude" to (longitude ?: ""),
                                "timestamp" to System.currentTimeMillis(),
                                "childName" to childName
                            )

                            docRef.update(updateData)
                                .addOnSuccessListener {
                                    Log.d(TAG, "✅ Successfully UPDATED notification $documentId for $childName")
                                }
                                .addOnFailureListener { e ->
                                    Log.e(TAG, "❌ Failed to UPDATE notification", e)
                                }
                        } else {
                            Log.d(TAG, "✨ Creating NEW notification for $childName")
                            notificationData["read"] = false

                            docRef.set(notificationData)
                                .addOnSuccessListener {
                                    Log.d(TAG, "✅ Successfully CREATED notification $documentId for $childName")
                                }
                                .addOnFailureListener { e ->
                                    Log.e(TAG, "❌ Failed to CREATE notification", e)
                                }
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "❌ Failed to check if notification exists", e)
                        notificationData["read"] = false
                        docRef.set(notificationData, SetOptions.merge())
                    }

            } else {
                Log.d(TAG, "🔄 Creating new notification with auto-generated ID for $childName")
                createNewNotification(parentUid, childUid, childName, title, body, notificationType, latitude, longitude)
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ CRITICAL ERROR saving notification to Firestore", e)
            e.printStackTrace()
        }
    }

    private fun createNewNotification(
        parentUid: String,
        childUid: String,
        childName: String,
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
            "childUid" to childUid,
            "childName" to childName
        )

        db.collection("users")
            .document(parentUid)
            .collection("notifications")
            .add(notificationData)
            .addOnSuccessListener { documentReference ->
                Log.d(TAG, "✅ Created new notification: ${documentReference.id} for $childName")
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

            Log.d(TAG, "🔔 Notification channel created: $CHANNEL_ID")
        }
    }
    /**
     * Handle all apps collection request from parent
     */
    private fun handleAllAppsRequest(childUid: String, requestId: String, parentUid: String?) {
        Log.d(TAG, "========================================")
        Log.d(TAG, "📱 HANDLING ALL APPS REQUEST")
        Log.d(TAG, "Child UID: ${childUid.take(10)}...")
        Log.d(TAG, "Request ID: $requestId")
        Log.d(TAG, "Parent UID: ${parentUid?.take(10)}...")
        Log.d(TAG, "========================================")

        scope.launch {
            try {
                // Get current user UID
                val currentUserUid = FirebaseAuth.getInstance().currentUser?.uid
                if (currentUserUid == null) {
                    Log.e(TAG, "❌ Cannot collect apps: User not logged in")
                    updateAllAppsRequestStatus(childUid, requestId, "failed", "User not logged in")
                    return@launch
                }

                Log.d(TAG, "📱 Current user UID: ${currentUserUid.take(10)}...")
                Log.d(TAG, "✅ User is logged in, proceeding with apps collection")

                // Trigger all apps collection
                Log.d(TAG, "🚀 Triggering UsageDataCollector for all apps...")
                val success = UsageDataCollector.collectAndUploadAllApps(
                    context = applicationContext,
                    childUid = currentUserUid,
                    requestId = requestId
                )

                Log.d(TAG, "========================================")
                if (success) {
                    Log.d(TAG, "✅ ALL APPS COLLECTION SUCCESSFUL")
                    Log.d(TAG, "========================================")

                    // Show local notification to child
                    showAllAppsCollectionSuccessNotification()

                } else {
                    Log.d(TAG, "❌ ALL APPS COLLECTION FAILED")
                    Log.d(TAG, "========================================")

                    // Show error notification
                    showAllAppsCollectionErrorNotification()
                }

            } catch (e: Exception) {
                Log.e(TAG, "========================================")
                Log.e(TAG, "❌ CRITICAL ERROR IN ALL APPS REQUEST")
                Log.e(TAG, "========================================")
                Log.e(TAG, "Exception: ${e.javaClass.simpleName}")
                Log.e(TAG, "Message: ${e.message}")
                e.printStackTrace()

                updateAllAppsRequestStatus(childUid, requestId, "failed", e.message ?: "Unknown error")
                showAllAppsCollectionErrorNotification()
            }
        }
    }

    /**
     * Update all apps request status in Firestore
     */
    private suspend fun updateAllAppsRequestStatus(
        childUid: String,
        requestId: String,
        status: String,
        errorMessage: String? = null
    ) {
        try {
            val db = FirebaseFirestore.getInstance()
            val updateData = hashMapOf<String, Any>(
                "status" to status,
                "completedAt" to System.currentTimeMillis()
            )

            if (errorMessage != null) {
                updateData["errorMessage"] = errorMessage
            }

            db.collection("users")
                .document(childUid)
                .collection("allAppsRequest")
                .document(requestId)
                .update(updateData)

            Log.d(TAG, "✅ All apps request status updated to: $status")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error updating all apps request status", e)
        }
    }

    /**
     * Show notification that all apps collection succeeded
     */
    private fun showAllAppsCollectionSuccessNotification() {
        try {
            Log.d(TAG, "📲 Showing all apps collection success notification")

            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            val pendingIntent = PendingIntent.getActivity(
                this,
                2004,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("✅ Apps List Sent")
                .setContentText("Your installed apps list has been sent to your parent")
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(2004, notificationBuilder.build())

            Log.d(TAG, "✅ Success notification displayed")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error showing success notification", e)
        }
    }

    /**
     * Show notification that all apps collection failed
     */
    private fun showAllAppsCollectionErrorNotification() {
        try {
            Log.d(TAG, "📲 Showing all apps collection error notification")

            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            val pendingIntent = PendingIntent.getActivity(
                this,
                2005,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("❌ Apps Collection Failed")
                .setContentText("Failed to collect installed apps list")
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(2005, notificationBuilder.build())

            Log.d(TAG, "✅ Error notification displayed")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error showing error notification", e)
        }
    }
}

