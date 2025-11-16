package com.example.child_safety_app_version1.utils.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * Sends location alerts via SMS when internet is unavailable
 */
class SmsLocationSender(private val context: Context) {

    companion object {
        private const val TAG = "SmsLocationSender"
        private const val MAX_SMS_LENGTH = 160
    }

    private val smsManager: SmsManager by lazy {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
    }

    /**
     * Send location alert to all parents via SMS
     */
    suspend fun sendLocationViaSms(
        latitude: Double,
        longitude: Double,
        childUid: String,
        locationMethod: String = "CELL_TOWER",
        accuracy: Float = 500f
    ): Boolean = withContext(Dispatchers.IO) {

        Log.d(TAG, "═══════════════════════════════════════════════════")
        Log.d(TAG, "SENDING LOCATION VIA SMS")
        Log.d(TAG, "═══════════════════════════════════════════════════")
        Log.d(TAG, "Child UID: ${childUid.take(10)}...")
        Log.d(TAG, "Location: $latitude, $longitude")
        Log.d(TAG, "Method: $locationMethod")
        Log.d(TAG, "Accuracy: ±${accuracy.toInt()}m")

        // Check SMS permission FIRST
        if (!hasSmsPermission()) {
            Log.e(TAG, "❌ CRITICAL: Missing SEND_SMS permission!")
            Log.e(TAG, "   Go to: Settings > Apps > Child Safety > Permissions")
            Log.e(TAG, "   Enable: SMS permission")
            return@withContext false
        } else {
            Log.d(TAG, "✅ SMS permission granted")
        }

        try {
            // 🆕 TRY CACHE FIRST (works offline)
            Log.d(TAG, "Attempting to use cached phone numbers...")
            var parentData = ParentPhoneCache.getCachedPhoneNumbers(context)

            if (parentData.isEmpty()) {
                Log.w(TAG, "⚠️ No cached phone numbers, trying Firestore...")

                // Try Firestore (will fail if offline)
                parentData = getParentPhoneNumbers(childUid)

                if (parentData.isEmpty()) {
                    Log.e(TAG, "❌ No parent phone numbers found (offline & no cache)")
                    Log.e(TAG, "   SOLUTION: Go online once to cache phone numbers")
                    return@withContext false
                }
            } else {
                Log.d(TAG, "✅ Using cached phone numbers")
            }

            Log.d(TAG, "✅ Found ${parentData.size} parent(s) with phone numbers")

            var successCount = 0

            parentData.forEach { (phoneNumber, childName) ->
                Log.d(TAG, "")
                Log.d(TAG, "📱 Sending to: ${phoneNumber.take(5)}...${phoneNumber.takeLast(4)}")
                Log.d(TAG, "   Child Name: $childName")

                val message = buildSmsMessage(
                    childName = childName,
                    latitude = latitude,
                    longitude = longitude,
                    locationMethod = locationMethod,
                    accuracy = accuracy
                )

                Log.d(TAG, "   Message length: ${message.length} chars")
                Log.d(TAG, "   Message preview: ${message.take(100)}...")

                val success = sendSms(phoneNumber, message)
                if (success) {
                    successCount++
                    Log.d(TAG, "   ✅ SMS sent successfully")
                } else {
                    Log.e(TAG, "   ❌ SMS failed to send")
                }
            }

            Log.d(TAG, "")
            Log.d(TAG, "═══════════════════════════════════════════════════")
            Log.d(TAG, "RESULT: $successCount/${parentData.size} SMS sent")
            Log.d(TAG, "═══════════════════════════════════════════════════")

            return@withContext successCount > 0

        } catch (e: Exception) {
            Log.e(TAG, "❌ EXCEPTION sending SMS", e)
            Log.e(TAG, "Exception type: ${e.javaClass.simpleName}")
            Log.e(TAG, "Exception message: ${e.message}")
            e.printStackTrace()
            return@withContext false
        }
    }

    /**
     * Build SMS message with deep link AND Google Maps link
     */
    private fun buildSmsMessage(
        childName: String,
        latitude: Double,
        longitude: Double,
        locationMethod: String,
        accuracy: Float
    ): String {
        val timestamp = System.currentTimeMillis()
        val timeString = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestamp))

        // Format coordinates (6 decimal places)
        val latStr = String.format("%.6f", latitude)
        val lonStr = String.format("%.6f", longitude)

        // 🆕 Google Maps link (works for EVERYONE - with or without app)
        val googleMapsLink = "https://maps.google.com/?q=$latitude,$longitude"

        // Deep link (only works if app installed)
        val deepLink = buildDeepLink(latitude, longitude, childName, locationMethod, accuracy, timestamp)

        // Build compact message
        return buildString {
            append("🚨 $childName Alert\n")
            append("\n")
            append("Status: Outside Safe Zone\n")
            append("Method: ")
            when (locationMethod) {
                "GPS" -> append("🛰️ GPS\n")
                "CELL_TOWER" -> append("📡 Cell Tower (±${accuracy.toInt()}m)\n")
                else -> append("Unknown\n")
            }
            append("Time: $timeString\n")
            append("Coords: $latStr, $lonStr\n")
            append("\n")

            // Priority: Google Maps link (universal)
            append("🗺️ View on Maps:\n")
            append(googleMapsLink)
            append("\n\n")

            // Secondary: App link (for app users)
            append("📱 View in App:\n")
            append(deepLink)
        }
    }

    /**
     * Build deep link for opening location in app
     * Format: childsafety://location?lat=X&lon=Y&name=Child&method=GPS&accuracy=10&time=123456
     */
    private fun buildDeepLink(
        latitude: Double,
        longitude: Double,
        childName: String,
        locationMethod: String,
        accuracy: Float,
        timestamp: Long
    ): String {
        // URL encode child name
        val encodedName = java.net.URLEncoder.encode(childName, "UTF-8")

        return "childsafety://location?" +
                "lat=$latitude&" +
                "lon=$longitude&" +
                "name=$encodedName&" +
                "method=$locationMethod&" +
                "accuracy=$accuracy&" +
                "time=$timestamp"
    }

    /**
     * Send SMS message (handles long messages by splitting)
     */
    private fun sendSms(phoneNumber: String, message: String): Boolean {
        return try {
            // Split message if too long
            val parts = if (message.length > MAX_SMS_LENGTH) {
                smsManager.divideMessage(message)
            } else {
                arrayListOf(message)
            }

            Log.d(TAG, "      Message parts: ${parts.size}")

            // Send message (single or multipart)
            if (parts.size == 1) {
                smsManager.sendTextMessage(
                    phoneNumber,
                    null, // Service center address (use default)
                    message,
                    null, // Sent intent
                    null  // Delivery intent
                )
            } else {
                smsManager.sendMultipartTextMessage(
                    phoneNumber,
                    null,
                    parts,
                    null,
                    null
                )
            }

            true

        } catch (e: SecurityException) {
            Log.e(TAG, "      SecurityException - missing permission", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "      Exception sending SMS", e)
            e.printStackTrace()
            false
        }
    }

    /**
     * Get parent phone numbers and child names from Firestore
     * Returns map of phone number to child name
     */
    private suspend fun getParentPhoneNumbers(childUid: String): Map<String, String> {
        return try {
            val db = FirebaseFirestore.getInstance()

            Log.d(TAG, "   🔍 Step 1: Querying /users/$childUid/parents")

            // Get all parents of this child
            val parentsSnapshot = db.collection("users")
                .document(childUid)
                .collection("parents")
                .get()
                .await()

            Log.d(TAG, "   📋 Found ${parentsSnapshot.size()} parent document(s)")

            if (parentsSnapshot.isEmpty) {
                Log.e(TAG, "   ❌ No parents found for child")
                Log.e(TAG, "      Check Firestore: /users/$childUid/parents")
                return emptyMap()
            }

            val parentPhones = mutableMapOf<String, String>()

            for (parentDoc in parentsSnapshot.documents) {
                val parentId = parentDoc.getString("parentId")
                val parentEmail = parentDoc.getString("parentEmail") ?: "unknown"

                if (parentId == null) {
                    Log.w(TAG, "   ⚠️ Parent document missing 'parentId' field")
                    continue
                }

                Log.d(TAG, "   👤 Parent: ${parentId.take(10)}... ($parentEmail)")

                // Get child name from parent's children subcollection
                Log.d(TAG, "      🔍 Getting child name from parent's children...")
                val childName = getChildNameFromParent(parentId, childUid)
                Log.d(TAG, "      👶 Child Name: $childName")

                // Get parent's phone number
                Log.d(TAG, "      🔍 Getting parent's phone from /users/$parentId")
                val parentSnapshot = db.collection("users")
                    .document(parentId)
                    .get()
                    .await()

                if (!parentSnapshot.exists()) {
                    Log.e(TAG, "      ❌ Parent document doesn't exist!")
                    continue
                }

                val phoneNumber = parentSnapshot.getString("phone")

                Log.d(TAG, "      📱 Phone field value: ${phoneNumber ?: "NULL"}")

                if (phoneNumber != null && phoneNumber.isNotBlank()) {
                    parentPhones[phoneNumber] = childName
                    Log.d(TAG, "      ✅ Phone: ${phoneNumber.take(5)}...${phoneNumber.takeLast(4)}")
                } else {
                    Log.e(TAG, "      ❌ No phone number in profile!")
                    Log.e(TAG, "         Parent needs to add phone number in Settings")
                }
            }

            Log.d(TAG, "   📊 Total parents with phone numbers: ${parentPhones.size}")
            parentPhones

        } catch (e: Exception) {
            Log.e(TAG, "   ❌ Exception getting parent phone numbers", e)
            Log.e(TAG, "   Exception: ${e.javaClass.simpleName}")
            Log.e(TAG, "   Message: ${e.message}")
            e.printStackTrace()
            emptyMap()
        }
    }

    /**
     * Get child's name from parent's children subcollection
     */
    private suspend fun getChildNameFromParent(parentId: String, childUid: String): String {
        return try {
            val db = FirebaseFirestore.getInstance()

            val childrenSnapshot = db.collection("users")
                .document(parentId)
                .collection("children")
                .whereEqualTo("childId", childUid)
                .get()
                .await()

            val childDoc = childrenSnapshot.documents.firstOrNull()
            childDoc?.getString("childName") ?: "Your Child"

        } catch (e: Exception) {
            Log.e(TAG, "Error getting child name", e)
            "Your Child"
        }
    }

    /**
     * Check if app has SMS permission
     */
    private fun hasSmsPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Get estimated SMS cost (for user information)
     */
    fun getEstimatedSmsCost(parentCount: Int): String {
        return when {
            parentCount == 0 -> "No parents to notify"
            parentCount == 1 -> "~1 SMS per alert"
            else -> "~$parentCount SMS per alert"
        }
    }
}