package com.example.child_safety_app_version1.utils.sms

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.net.URLDecoder

/**
 * Parses SMS messages containing child location information
 */
object SmsLocationParser {

    private const val TAG = "SmsLocationParser"

    // SMS message patterns
    private const val ALERT_MARKER = "🚨"
    private const val DEEP_LINK_SCHEME = "childsafety://"

    /**
     * Data class for parsed location information
     */
    data class ParsedLocationSms(
        val childName: String,
        val latitude: Double,
        val longitude: Double,
        val locationMethod: String,
        val accuracy: Float,
        val timestamp: Long,
        val deepLink: String,
        val rawMessage: String
    )

    /**
     * Check if SMS is a child safety location alert
     */
    fun isLocationAlertSms(messageBody: String?): Boolean {
        if (messageBody.isNullOrBlank()) return false

        return messageBody.contains(ALERT_MARKER) &&
                messageBody.contains(DEEP_LINK_SCHEME) &&
                (messageBody.contains("Outside Safe Zone") ||
                        messageBody.contains("Safe Zone Alert"))
    }

    /**
     * Parse location information from SMS
     * Returns null if SMS is not a valid location alert
     */
    fun parseLocationSms(messageBody: String): ParsedLocationSms? {
        try {
            Log.d(TAG, "═══════════════════════════════════════════════════")
            Log.d(TAG, "PARSING LOCATION SMS")
            Log.d(TAG, "═══════════════════════════════════════════════════")
            Log.d(TAG, "Message length: ${messageBody.length} chars")

            if (!isLocationAlertSms(messageBody)) {
                Log.d(TAG, "❌ Not a location alert SMS")
                return null
            }

            // Extract deep link from message
            val deepLinkStartIndex = messageBody.indexOf(DEEP_LINK_SCHEME)
            if (deepLinkStartIndex == -1) {
                Log.e(TAG, "❌ Deep link not found in message")
                return null
            }

            // Extract deep link (until end of line or message)
            val deepLinkEndIndex = messageBody.indexOf('\n', deepLinkStartIndex).let {
                if (it == -1) messageBody.length else it
            }

            val deepLink = messageBody.substring(deepLinkStartIndex, deepLinkEndIndex).trim()
            Log.d(TAG, "Deep Link: $deepLink")

            // Parse deep link
            val uri = Uri.parse(deepLink)

            if (uri.scheme != "childsafety" || uri.host != "location") {
                Log.e(TAG, "❌ Invalid deep link format")
                return null
            }

            // Extract parameters from deep link
            val lat = uri.getQueryParameter("lat")?.toDoubleOrNull()
            val lon = uri.getQueryParameter("lon")?.toDoubleOrNull()
            val encodedName = uri.getQueryParameter("name")
            val method = uri.getQueryParameter("method") ?: "UNKNOWN"
            val accuracy = uri.getQueryParameter("accuracy")?.toFloatOrNull() ?: 500f
            val timestamp = uri.getQueryParameter("time")?.toLongOrNull() ?: System.currentTimeMillis()

            if (lat == null || lon == null) {
                Log.e(TAG, "❌ Invalid coordinates in deep link")
                return null
            }

            val childName = if (encodedName != null) {
                URLDecoder.decode(encodedName, "UTF-8")
            } else {
                "Your Child"
            }

            Log.d(TAG, "✅ SMS Parsed Successfully:")
            Log.d(TAG, "   Child Name: $childName")
            Log.d(TAG, "   Location: $lat, $lon")
            Log.d(TAG, "   Method: $method")
            Log.d(TAG, "   Accuracy: ±${accuracy.toInt()}m")
            Log.d(TAG, "   Timestamp: $timestamp")
            Log.d(TAG, "═══════════════════════════════════════════════════")

            return ParsedLocationSms(
                childName = childName,
                latitude = lat,
                longitude = lon,
                locationMethod = method,
                accuracy = accuracy,
                timestamp = timestamp,
                deepLink = deepLink,
                rawMessage = messageBody
            )

        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception parsing SMS", e)
            e.printStackTrace()
            return null
        }
    }

    /**
     * Create intent to open location in app
     */
    fun createOpenLocationIntent(context: Context, parsedSms: ParsedLocationSms): Intent {
        return Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(parsedSms.deepLink)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            setPackage(context.packageName) // Ensure it opens in our app
        }
    }

    /**
     * Create intent with extras (alternative to deep link)
     */
    fun createOpenLocationIntentWithExtras(
        context: Context,
        parsedSms: ParsedLocationSms
    ): Intent {
        return Intent(context, context::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_location", true)
            putExtra("latitude", parsedSms.latitude)
            putExtra("longitude", parsedSms.longitude)
            putExtra("childName", parsedSms.childName)
            putExtra("locationMethod", parsedSms.locationMethod)
            putExtra("accuracy", parsedSms.accuracy)
            putExtra("timestamp", parsedSms.timestamp)
        }
    }

    /**
     * Extract child name from SMS (fallback if deep link fails)
     */
    fun extractChildNameFromSms(messageBody: String): String? {
        try {
            // Pattern: "🚨 [Child Name] Alert"
            val alertIndex = messageBody.indexOf(ALERT_MARKER)
            if (alertIndex == -1) return null

            val nameEndIndex = messageBody.indexOf("Alert", alertIndex)
            if (nameEndIndex == -1) return null

            val namePart = messageBody.substring(alertIndex + ALERT_MARKER.length, nameEndIndex).trim()

            return if (namePart.isNotBlank()) namePart else null

        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Format location SMS for display in notification
     */
    fun formatForNotification(parsedSms: ParsedLocationSms): String {
        val timeString = java.text.SimpleDateFormat(
            "h:mm a",
            java.util.Locale.getDefault()
        ).format(java.util.Date(parsedSms.timestamp))

        return buildString {
            append("${parsedSms.childName} - Outside Safe Zone\n")
            append("\n")
            append("Method: ")
            when (parsedSms.locationMethod) {
                "GPS" -> append("🛰️ GPS")
                "CELL_TOWER" -> append("📡 Cell Tower (±${parsedSms.accuracy.toInt()}m)")
                else -> append("Unknown")
            }
            append("\n")
            append("Time: $timeString\n")
            append("\n")
            append("Tap to view location")
        }
    }

    /**
     * Get human-readable accuracy description
     */
    fun getAccuracyDescription(accuracy: Float): String {
        return when {
            accuracy < 20 -> "High Precision (±${accuracy.toInt()}m)"
            accuracy < 100 -> "Good (±${accuracy.toInt()}m)"
            accuracy < 500 -> "Moderate (±${accuracy.toInt()}m)"
            accuracy < 1000 -> "Approximate (±${accuracy.toInt()}m)"
            else -> "Low Accuracy (±${(accuracy / 1000).toInt()}km)"
        }
    }

    /**
     * Get location method icon/emoji
     */
    fun getLocationMethodIcon(method: String): String {
        return when (method) {
            "GPS" -> "🛰️"
            "CELL_TOWER" -> "📡"
            "CACHED_GPS" -> "📦"
            else -> "❓"
        }
    }

    /**
     * Validate SMS sender (optional - for security)
     * Check if sender is a known child's phone number
     */
    suspend fun isFromKnownChild(context: Context, senderNumber: String): Boolean {
        // TODO: Implement Firestore check
        // Query parent's children collection and check if any child has this phone number
        // For now, return true to allow all SMS
        return true
    }
}