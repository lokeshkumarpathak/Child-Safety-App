package com.example.child_safety_app_version1.utils

import android.content.Context
import android.util.Log
import com.example.child_safety_app_version1.data.PaymentTransaction
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object FcmNotificationSender {
    private const val TAG = "FcmNotificationSender"
    private const val PROJECT_ID = "child-safety-c7fea"
    private const val FCM_ENDPOINT = "https://fcm.googleapis.com/v1/projects/$PROJECT_ID/messages:send"

    /**
     * Sends notification to all parents of the current child
     */
    suspend fun sendNotificationToParents(
        context: Context,
        childUid: String,
        notificationType: NotificationType,
        latitude: Double? = null,
        longitude: Double? = null,
        requestId: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "════════════════════════════════════════")
            Log.d(TAG, "🚀 STARTING NOTIFICATION SEND PROCESS")
            Log.d(TAG, "════════════════════════════════════════")
            Log.d(TAG, "📋 Input Parameters:")
            Log.d(TAG, "   Child UID: ${childUid.take(10)}... (full: $childUid)")
            Log.d(TAG, "   Notification Type: ${notificationType.name}")
            Log.d(TAG, "   Location: $latitude, $longitude")
            if (requestId != null) {
                Log.d(TAG, "   Request ID: $requestId")
            }
            Log.d(TAG, "════════════════════════════════════════")

            // Step 1: Get OAuth2 access token
            Log.d(TAG, "")
            Log.d(TAG, "STEP 1: Getting OAuth2 Access Token")
            Log.d(TAG, "────────────────────────────────────────")
            val accessToken = try {
                OAuth2TokenManager.getAccessToken(context)
            } catch (e: Exception) {
                Log.e(TAG, "❌ EXCEPTION getting access token", e)
                e.printStackTrace()
                null
            }

            if (accessToken == null) {
                Log.e(TAG, "❌ FAILED: Access token is NULL")
                Log.e(TAG, "   Possible causes:")
                Log.e(TAG, "   - Service account JSON file missing")
                Log.e(TAG, "   - OAuth2TokenManager not configured")
                Log.e(TAG, "   - Network error")
                return@withContext false
            }

            Log.d(TAG, "✅ SUCCESS: Access token obtained")
            Log.d(TAG, "   Token preview: ${accessToken.take(50)}...")
            Log.d(TAG, "   Token length: ${accessToken.length} characters")

            // Step 2: Get parent FCM tokens and child names from parents
            Log.d(TAG, "")
            Log.d(TAG, "STEP 2: Fetching Parent FCM Tokens and Child Names")
            Log.d(TAG, "────────────────────────────────────────")
            Log.d(TAG, "   Querying: /users/$childUid/parents")

            val parentData = try {
                getParentFcmTokensAndChildNames(childUid)
            } catch (e: Exception) {
                Log.e(TAG, "❌ EXCEPTION fetching parent data", e)
                e.printStackTrace()
                emptyMap()
            }

            Log.d(TAG, "")
            Log.d(TAG, "📊 Parent Data Result:")
            Log.d(TAG, "   Total parents found: ${parentData.size}")

            if (parentData.isEmpty()) {
                Log.e(TAG, "❌ FAILED: No parent data found")
                Log.e(TAG, "")
                Log.e(TAG, "🔍 TROUBLESHOOTING STEPS:")
                Log.e(TAG, "   1. Check if parents are linked to child:")
                Log.e(TAG, "      Path: /users/$childUid/parents")
                Log.e(TAG, "      Expected: Documents with 'parentId' field")
                Log.e(TAG, "")
                Log.e(TAG, "   2. Check if parent has FCM tokens:")
                Log.e(TAG, "      Path: /users/{parentId}/fcmTokens")
                Log.e(TAG, "      Expected: Documents with 'token' field")
                Log.e(TAG, "")
                Log.e(TAG, "   3. Ensure parent has opened app at least once")
                Log.e(TAG, "      (FCM token generated on first app launch)")
                return@withContext false
            }

            parentData.forEach { (parentUid, data) ->
                Log.d(TAG, "   Parent: ${parentUid.take(10)}...")
                Log.d(TAG, "      Child Name: ${data.childName}")
                Log.d(TAG, "      Tokens: ${data.tokens.size}")
                data.tokens.forEachIndexed { index, token ->
                    Log.d(TAG, "         Token ${index + 1}: ${token.take(30)}...")
                }
            }

            // Step 3: Send notifications
            Log.d(TAG, "")
            Log.d(TAG, "STEP 3: Sending FCM Messages")
            Log.d(TAG, "────────────────────────────────────────")
            var successCount = 0
            var failureCount = 0
            val totalMessages = parentData.values.sumOf { it.tokens.size }
            Log.d(TAG, "   Total messages to send: $totalMessages")
            Log.d(TAG, "")

            var messageNumber = 0
            for ((parentUid, data) in parentData) {
                val childName = data.childName
                Log.d(TAG, "   📤 Sending to parent: ${parentUid.take(10)}... (Child: $childName)")

                for (token in data.tokens) {
                    messageNumber++
                    Log.d(TAG, "")
                    Log.d(TAG, "   Message $messageNumber/$totalMessages")
                    Log.d(TAG, "   ─────────────────────")
                    Log.d(TAG, "   Target token: ${token.take(30)}...")

                    val success = try {
                        sendFcmMessage(
                            accessToken = accessToken,
                            fcmToken = token,
                            parentUid = parentUid,
                            childUid = childUid,
                            childName = childName,
                            title = notificationType.getTitle(childName),
                            body = notificationType.getBody(childName),
                            latitude = latitude,
                            longitude = longitude,
                            notificationType = notificationType.name,
                            requestId = requestId
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "   ❌ EXCEPTION sending message", e)
                        e.printStackTrace()
                        false
                    }

                    if (success) {
                        successCount++
                        Log.d(TAG, "   ✅ SUCCESS")
                    } else {
                        failureCount++
                        Log.e(TAG, "   ❌ FAILED")
                    }
                }
            }

            Log.d(TAG, "")
            Log.d(TAG, "════════════════════════════════════════")
            Log.d(TAG, "📊 FINAL RESULTS")
            Log.d(TAG, "════════════════════════════════════════")
            Log.d(TAG, "   ✅ Successful: $successCount")
            Log.d(TAG, "   ❌ Failed: $failureCount")
            Log.d(TAG, "   📈 Success Rate: ${if (totalMessages > 0) (successCount * 100 / totalMessages) else 0}%")
            Log.d(TAG, "════════════════════════════════════════")

            val overallSuccess = successCount > 0
            if (overallSuccess) {
                Log.d(TAG, "✅ OVERALL: SUCCESS (at least 1 message sent)")
            } else {
                Log.e(TAG, "❌ OVERALL: FAILED (no messages sent)")
            }

            return@withContext overallSuccess

        } catch (e: Exception) {
            Log.e(TAG, "════════════════════════════════════════")
            Log.e(TAG, "❌ CRITICAL ERROR in sendNotificationToParents")
            Log.e(TAG, "════════════════════════════════════════")
            Log.e(TAG, "Exception type: ${e.javaClass.simpleName}")
            Log.e(TAG, "Message: ${e.message}")
            e.printStackTrace()
            Log.e(TAG, "════════════════════════════════════════")
            return@withContext false
        }
    }

    /**
     * NEW: Send usage data request to child device via FCM
     */
    suspend fun sendUsageDataRequestToChild(
        context: Context,
        childUid: String,
        requestId: String,
        parentUid: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "════════════════════════════════════════")
            Log.d(TAG, "📊 SENDING USAGE DATA REQUEST TO CHILD")
            Log.d(TAG, "════════════════════════════════════════")
            Log.d(TAG, "   Child UID: ${childUid.take(10)}...")
            Log.d(TAG, "   Request ID: $requestId")
            Log.d(TAG, "   Parent UID: ${parentUid.take(10)}...")

            // Step 1: Get access token
            val accessToken = try {
                OAuth2TokenManager.getAccessToken(context)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to get access token", e)
                return@withContext false
            }

            if (accessToken == null) {
                Log.e(TAG, "❌ Access token is NULL")
                return@withContext false
            }

            // Step 2: Get child's FCM token
            Log.d(TAG, "   🔍 Fetching child's FCM token...")
            val childFcmToken = try {
                getChildFcmToken(childUid)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error fetching child FCM token", e)
                return@withContext false
            }

            if (childFcmToken == null) {
                Log.e(TAG, "❌ Child has no FCM token available")
                Log.e(TAG, "   Child may not have opened app yet")
                return@withContext false
            }

            Log.d(TAG, "   ✅ Child FCM token: ${childFcmToken.take(30)}...")

            // Step 3: Send message to child
            Log.d(TAG, "   📤 Sending message to child...")
            val success = try {
                sendFcmMessageToChild(
                    accessToken = accessToken,
                    fcmToken = childFcmToken,
                    childUid = childUid,
                    parentUid = parentUid,
                    requestId = requestId,
                    notificationType = NotificationType.USAGE_DATA_REQUEST
                )
            } catch (e: Exception) {
                Log.e(TAG, "❌ Exception sending message", e)
                e.printStackTrace()
                false
            }

            if (success) {
                Log.d(TAG, "   ✅ Usage data request sent successfully")
            } else {
                Log.e(TAG, "   ❌ Failed to send usage data request")
            }

            Log.d(TAG, "════════════════════════════════════════")
            return@withContext success

        } catch (e: Exception) {
            Log.e(TAG, "❌ CRITICAL ERROR in sendUsageDataRequestToChild", e)
            e.printStackTrace()
            return@withContext false
        }
    }

    /**
     * NEW: Send all apps request to child device via FCM
     */
    suspend fun sendAllAppsRequestToChild(
        context: Context,
        childUid: String,
        requestId: String,
        parentUid: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "════════════════════════════════════════")
            Log.d(TAG, "📱 SENDING ALL APPS REQUEST TO CHILD")
            Log.d(TAG, "════════════════════════════════════════")
            Log.d(TAG, "   Child UID: ${childUid.take(10)}...")
            Log.d(TAG, "   Request ID: $requestId")
            Log.d(TAG, "   Parent UID: ${parentUid.take(10)}...")

            // Step 1: Get access token
            val accessToken = try {
                OAuth2TokenManager.getAccessToken(context)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to get access token", e)
                return@withContext false
            }

            if (accessToken == null) {
                Log.e(TAG, "❌ Access token is NULL")
                return@withContext false
            }

            // Step 2: Get child's FCM token
            Log.d(TAG, "   🔍 Fetching child's FCM token...")
            val childFcmToken = try {
                getChildFcmToken(childUid)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error fetching child FCM token", e)
                return@withContext false
            }

            if (childFcmToken == null) {
                Log.e(TAG, "❌ Child has no FCM token available")
                Log.e(TAG, "   Child may not have opened app yet")
                return@withContext false
            }

            Log.d(TAG, "   ✅ Child FCM token: ${childFcmToken.take(30)}...")

            // Step 3: Send message to child
            Log.d(TAG, "   📤 Sending all apps request message to child...")
            val success = try {
                sendFcmMessageToChild(
                    accessToken = accessToken,
                    fcmToken = childFcmToken,
                    childUid = childUid,
                    parentUid = parentUid,
                    requestId = requestId,
                    notificationType = NotificationType.ALL_APPS_REQUEST
                )
            } catch (e: Exception) {
                Log.e(TAG, "❌ Exception sending message", e)
                e.printStackTrace()
                false
            }

            if (success) {
                Log.d(TAG, "   ✅ All apps request sent successfully")
            } else {
                Log.e(TAG, "   ❌ Failed to send all apps request")
            }

            Log.d(TAG, "════════════════════════════════════════")
            return@withContext success

        } catch (e: Exception) {
            Log.e(TAG, "❌ CRITICAL ERROR in sendAllAppsRequestToChild", e)
            e.printStackTrace()
            return@withContext false
        }
    }

    /**
     * NEW: Send mode activation notification to child
     */
    suspend fun sendModeActivationNotification(
        context: Context,
        childUid: String,
        newMode: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "════════════════════════════════════════")
            Log.d(TAG, "🔄 SENDING MODE ACTIVATION NOTIFICATION")
            Log.d(TAG, "════════════════════════════════════════")
            Log.d(TAG, "   Child UID: ${childUid.take(10)}...")
            Log.d(TAG, "   New Mode: $newMode")

            // Step 1: Get access token
            val accessToken = try {
                OAuth2TokenManager.getAccessToken(context)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to get access token", e)
                return@withContext false
            }

            if (accessToken == null) {
                Log.e(TAG, "❌ Access token is NULL")
                return@withContext false
            }

            // Step 2: Get child's FCM token
            Log.d(TAG, "   🔍 Fetching child's FCM token...")
            val childFcmToken = try {
                getChildFcmToken(childUid)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error fetching child FCM token", e)
                return@withContext false
            }

            if (childFcmToken == null) {
                Log.e(TAG, "❌ Child has no FCM token available")
                return@withContext false
            }

            Log.d(TAG, "   ✅ Child FCM token: ${childFcmToken.take(30)}...")

            // Step 3: Send mode activation message
            Log.d(TAG, "   📤 Sending mode activation message...")
            val success = try {
                sendFcmMessageToChild(
                    accessToken = accessToken,
                    fcmToken = childFcmToken,
                    childUid = childUid,
                    parentUid = "", // Not applicable for mode notifications
                    requestId = null,
                    notificationType = NotificationType.MODE_ACTIVATED,
                    modeInfo = newMode
                )
            } catch (e: Exception) {
                Log.e(TAG, "❌ Exception sending message", e)
                e.printStackTrace()
                false
            }

            if (success) {
                Log.d(TAG, "   ✅ Mode activation notification sent successfully")
            } else {
                Log.e(TAG, "   ❌ Failed to send mode activation notification")
            }

            Log.d(TAG, "════════════════════════════════════════")
            return@withContext success

        } catch (e: Exception) {
            Log.e(TAG, "❌ CRITICAL ERROR in sendModeActivationNotification", e)
            e.printStackTrace()
            return@withContext false
        }
    }

    /**
     * NEW: Get child's FCM token from Firestore
     */
    private suspend fun getChildFcmToken(childUid: String): String? {
        return try {
            val db = FirebaseFirestore.getInstance()
            val snapshot = db.collection("users")
                .document(childUid)
                .collection("fcmTokens")
                .limit(1)
                .get()
                .await()

            if (snapshot.isEmpty) {
                Log.w(TAG, "⚠️ No FCM tokens found for child")
                return null
            }

            val token = snapshot.documents.firstOrNull()?.getString("token")
            if (token.isNullOrEmpty()) {
                Log.w(TAG, "⚠️ FCM token is empty or null")
                return null
            }

            token
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error getting child FCM token", e)
            null
        }
    }

    /**
     * NEW: Send FCM message directly to child device
     */
    /**
     * ENHANCED VERSION with detailed debugging
     * Replace your existing sendFcmMessageToChild() with this version
     */
    private suspend fun sendFcmMessageToChild(
        accessToken: String,
        fcmToken: String,
        childUid: String,
        parentUid: String,
        requestId: String?,
        notificationType: NotificationType,
        modeInfo: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null

        try {
            Log.d(TAG, "      ════════════════════════════════════════")
            Log.d(TAG, "      🌐 DETAILED FCM REQUEST DEBUG")
            Log.d(TAG, "      ════════════════════════════════════════")
            Log.d(TAG, "      📋 Request Details:")
            Log.d(TAG, "         Endpoint: $FCM_ENDPOINT")
            Log.d(TAG, "         FCM Token: ${fcmToken.take(50)}...")
            Log.d(TAG, "         Access Token: ${accessToken.take(50)}...")
            Log.d(TAG, "         Child UID: ${childUid.take(15)}...")
            Log.d(TAG, "         Parent UID: ${parentUid.take(15)}...")
            Log.d(TAG, "         Request ID: $requestId")
            Log.d(TAG, "         Notification Type: ${notificationType.name}")

            val url = URL(FCM_ENDPOINT)
            connection = url.openConnection() as HttpURLConnection

            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Content-Type", "application/json; UTF-8")
            connection.doOutput = true
            connection.connectTimeout = 30000
            connection.readTimeout = 30000

            Log.d(TAG, "      ")
            Log.d(TAG, "      📦 Building JSON payload...")

            // Build JSON payload
            val message = JSONObject().apply {
                put("message", JSONObject().apply {
                    put("token", fcmToken)
                    put("notification", JSONObject().apply {
                        put("title", notificationType.getTitle(""))
                        put("body", notificationType.getBody(""))
                    })
                    put("data", JSONObject().apply {
                        put("notificationType", notificationType.name)
                        put("parentUid", parentUid)
                        put("childUid", childUid)
                        if (requestId != null) {
                            put("requestId", requestId)
                        }
                        if (modeInfo != null) {
                            put("modeInfo", modeInfo)
                        }
                    })
                    put("android", JSONObject().apply {
                        put("priority", "HIGH")
                        put("notification", JSONObject().apply {
                            put("sound", "default")
                            put("channel_id", "child_safety_alerts")
                        })
                    })
                })
            }

            Log.d(TAG, "      ✅ JSON payload built successfully")
            Log.d(TAG, "      ")
            Log.d(TAG, "      📄 Full Payload:")
            Log.d(TAG, message.toString(2).prependIndent("         "))
            Log.d(TAG, "      ")

            // Send request
            Log.d(TAG, "      📤 Sending HTTP POST request...")
            val writer = OutputStreamWriter(connection.outputStream)
            writer.write(message.toString())
            writer.flush()
            writer.close()
            Log.d(TAG, "      ✅ Request sent, waiting for response...")

            // Check response
            val responseCode = connection.responseCode
            Log.d(TAG, "      ")
            Log.d(TAG, "      📨 HTTP RESPONSE:")
            Log.d(TAG, "         Response Code: $responseCode")
            Log.d(TAG, "         Response Message: ${connection.responseMessage}")

            val success = responseCode == HttpURLConnection.HTTP_OK

            if (success) {
                // Success - read response body
                val responseBody = try {
                    connection.inputStream?.bufferedReader()?.readText()
                } catch (e: Exception) {
                    Log.w(TAG, "         ⚠️ Could not read response body", e)
                    "(unable to read)"
                }

                Log.d(TAG, "      ")
                Log.d(TAG, "      ✅ FCM SUCCESS - Message Sent!")
                Log.d(TAG, "      📄 Response Body:")
                Log.d(TAG, responseBody?.prependIndent("         ") ?: "         (empty)")
                Log.d(TAG, "      ════════════════════════════════════════")

            } else {
                // Error - read error stream
                val errorBody = try {
                    connection.errorStream?.bufferedReader()?.readText()
                } catch (e: Exception) {
                    Log.e(TAG, "         ⚠️ Could not read error stream", e)
                    "(unable to read error)"
                }

                Log.e(TAG, "      ")
                Log.e(TAG, "      ❌ FCM FAILED - Message Not Sent")
                Log.e(TAG, "      📄 Error Response Body:")
                Log.e(TAG, errorBody?.prependIndent("         ") ?: "         (empty)")
                Log.e(TAG, "      ")
                Log.e(TAG, "      🔍 TROUBLESHOOTING:")

                when (responseCode) {
                    400 -> {
                        Log.e(TAG, "         400 BAD REQUEST - Common causes:")
                        Log.e(TAG, "         - Invalid JSON payload structure")
                        Log.e(TAG, "         - Missing required fields")
                        Log.e(TAG, "         - Invalid FCM token format")
                        Log.e(TAG, "         - Check the error body above for details")
                    }
                    401 -> {
                        Log.e(TAG, "         401 UNAUTHORIZED - Common causes:")
                        Log.e(TAG, "         - Access token is invalid or expired")
                        Log.e(TAG, "         - Service account JSON is incorrect")
                        Log.e(TAG, "         - Token not generated correctly")
                        Log.e(TAG, "         - Access token preview: ${accessToken.take(30)}...")
                    }
                    403 -> {
                        Log.e(TAG, "         403 FORBIDDEN - Common causes:")
                        Log.e(TAG, "         - FCM API not enabled in Firebase project")
                        Log.e(TAG, "         - Wrong Firebase project ID")
                        Log.e(TAG, "         - Project ID: $PROJECT_ID")
                        Log.e(TAG, "         - Service account lacks FCM permissions")
                    }
                    404 -> {
                        Log.e(TAG, "         404 NOT FOUND - Common causes:")
                        Log.e(TAG, "         - Invalid FCM token (user uninstalled app)")
                        Log.e(TAG, "         - Token is old/expired")
                        Log.e(TAG, "         - Wrong endpoint URL")
                        Log.e(TAG, "         - Token: ${fcmToken.take(50)}...")
                    }
                    429 -> {
                        Log.e(TAG, "         429 TOO MANY REQUESTS - Common causes:")
                        Log.e(TAG, "         - Rate limit exceeded")
                        Log.e(TAG, "         - Too many messages sent too quickly")
                        Log.e(TAG, "         - Wait a few minutes and try again")
                    }
                    500, 502, 503, 504 -> {
                        Log.e(TAG, "         ${responseCode} SERVER ERROR - Common causes:")
                        Log.e(TAG, "         - Firebase/Google servers are down")
                        Log.e(TAG, "         - Temporary service issue")
                        Log.e(TAG, "         - Retry the request")
                    }
                    else -> {
                        Log.e(TAG, "         UNKNOWN ERROR CODE: $responseCode")
                        Log.e(TAG, "         Check Firebase Console for more details")
                    }
                }

                Log.e(TAG, "      ════════════════════════════════════════")
            }

            return@withContext success

        } catch (e: Exception) {
            Log.e(TAG, "      ════════════════════════════════════════")
            Log.e(TAG, "      ❌ EXCEPTION IN sendFcmMessageToChild")
            Log.e(TAG, "      ════════════════════════════════════════")
            Log.e(TAG, "      Exception Type: ${e.javaClass.simpleName}")
            Log.e(TAG, "      Exception Message: ${e.message}")
            Log.e(TAG, "      ")
            Log.e(TAG, "      Stack Trace:")
            e.printStackTrace()
            Log.e(TAG, "      ════════════════════════════════════════")
            return@withContext false
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Data class to hold parent's FCM tokens and the child's name as defined by that parent
     */
    private data class ParentData(
        val tokens: List<String>,
        val childName: String
    )

    /**
     * Retrieves FCM tokens for all parents and gets child name from each parent's children subcollection
     */
    private suspend fun getParentFcmTokensAndChildNames(childUid: String): Map<String, ParentData> {
        Log.d(TAG, "      🔍 Querying parents subcollection...")
        return try {
            val db = FirebaseFirestore.getInstance()

            // Query: /users/{childUid}/parents
            val parentsSnapshot = db.collection("users")
                .document(childUid)
                .collection("parents")
                .get()
                .await()

            Log.d(TAG, "      📋 Query result: ${parentsSnapshot.size()} parent document(s)")

            if (parentsSnapshot.isEmpty) {
                Log.e(TAG, "      ❌ No parent documents found")
                Log.e(TAG, "         Check Firebase Console:")
                Log.e(TAG, "         /users/$childUid/parents")
                return emptyMap()
            }

            val parentDataMap = mutableMapOf<String, ParentData>()

            parentsSnapshot.documents.forEachIndexed { index, parentDoc ->
                try {
                    Log.d(TAG, "")
                    Log.d(TAG, "      👤 Parent ${index + 1}/${parentsSnapshot.size()}")
                    Log.d(TAG, "         Document ID: ${parentDoc.id}")

                    val parentId = parentDoc.getString("parentId")
                    if (parentId.isNullOrEmpty()) {
                        Log.e(TAG, "         ❌ Missing 'parentId' field")
                        return@forEachIndexed
                    }

                    val parentEmail = parentDoc.getString("parentEmail") ?: "unknown"
                    Log.d(TAG, "         Parent UID: ${parentId.take(10)}...")
                    Log.d(TAG, "         Parent Email: $parentEmail")

                    // Get child name from parent's children subcollection
                    Log.d(TAG, "         🔍 Fetching child name from parent's children subcollection...")
                    val childName = getChildNameFromParent(parentId, childUid)
                    Log.d(TAG, "         👶 Child Name: $childName")

                    // Query: /users/{parentId}/fcmTokens
                    Log.d(TAG, "         🔍 Querying FCM tokens...")
                    val tokensSnapshot = db.collection("users")
                        .document(parentId)
                        .collection("fcmTokens")
                        .get()
                        .await()

                    Log.d(TAG, "         📋 Found ${tokensSnapshot.size()} token document(s)")

                    if (tokensSnapshot.isEmpty) {
                        Log.w(TAG, "         ⚠️ No FCM tokens found for this parent")
                        Log.w(TAG, "            Parent needs to open app to generate token")
                        return@forEachIndexed
                    }

                    val tokens = mutableListOf<String>()
                    tokensSnapshot.documents.forEachIndexed { tokenIndex, tokenDoc ->
                        val token = tokenDoc.getString("token")
                        if (!token.isNullOrEmpty()) {
                            tokens.add(token)
                            Log.d(TAG, "         ✅ Token ${tokenIndex + 1}: ${token.take(30)}...")
                        } else {
                            Log.w(TAG, "         ⚠️ Token ${tokenIndex + 1}: Empty or null")
                        }
                    }

                    if (tokens.isNotEmpty()) {
                        parentDataMap[parentId] = ParentData(tokens, childName)
                        Log.d(TAG, "         ✅ Added ${tokens.size} valid token(s) with child name: $childName")
                    } else {
                        Log.w(TAG, "         ⚠️ No valid tokens extracted")
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "         ❌ Error processing parent document", e)
                    e.printStackTrace()
                }
            }

            Log.d(TAG, "")
            Log.d(TAG, "      ✅ Total parents with tokens: ${parentDataMap.size}")
            parentDataMap

        } catch (e: Exception) {
            Log.e(TAG, "      ❌ CRITICAL ERROR in getParentFcmTokensAndChildNames", e)
            e.printStackTrace()
            emptyMap()
        }
    }

    /**
     * Gets the child's name from parent's children subcollection
     * Path: /users/{parentId}/children/{doc where childId == childUid}
     */
    private suspend fun getChildNameFromParent(parentId: String, childUid: String): String {
        return try {
            val db = FirebaseFirestore.getInstance()

            Log.d(TAG, "            🔍 Querying /users/$parentId/children for child $childUid")

            val childrenSnapshot = db.collection("users")
                .document(parentId)
                .collection("children")
                .whereEqualTo("childId", childUid)
                .get()
                .await()

            if (childrenSnapshot.isEmpty) {
                Log.w(TAG, "            ⚠️ No matching child document found in parent's children subcollection")
                return "Your child"
            }

            val childDoc = childrenSnapshot.documents.firstOrNull()
            val childName = childDoc?.getString("childName")

            if (childName.isNullOrEmpty()) {
                Log.w(TAG, "            ⚠️ Child name not found or empty, using default")
                return "Your child"
            }

            Log.d(TAG, "            ✅ Found child name: $childName")
            childName

        } catch (e: Exception) {
            Log.e(TAG, "            ❌ Error getting child name from parent's children subcollection", e)
            e.printStackTrace()
            "Your child"
        }
    }

    /**
     * Sends a single FCM message using HTTP v1 API to parent
     */
    private suspend fun sendFcmMessage(
        accessToken: String,
        fcmToken: String,
        parentUid: String,
        childUid: String,
        childName: String,
        title: String,
        body: String,
        latitude: Double?,
        longitude: Double?,
        notificationType: String,
        requestId: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null

        try {
            Log.d(TAG, "      🌐 Preparing HTTP request...")
            Log.d(TAG, "         Endpoint: $FCM_ENDPOINT")
            Log.d(TAG, "         Child Name: $childName")

            val url = URL(FCM_ENDPOINT)
            connection = url.openConnection() as HttpURLConnection

            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Content-Type", "application/json; UTF-8")
            connection.doOutput = true
            connection.connectTimeout = 30000
            connection.readTimeout = 30000

            // Build JSON payload
            val message = JSONObject().apply {
                put("message", JSONObject().apply {
                    put("token", fcmToken)
                    put("notification", JSONObject().apply {
                        put("title", title)
                        put("body", body)
                    })
                    put("data", JSONObject().apply {
                        put("notificationType", notificationType)
                        put("parentUid", parentUid)
                        put("childUid", childUid)
                        put("childName", childName)
                        if (requestId != null) {
                            put("requestId", requestId)
                        }
                        if (latitude != null && longitude != null) {
                            put("latitude", latitude.toString())
                            put("longitude", longitude.toString())
                        }
                    })
                    put("android", JSONObject().apply {
                        put("priority", "HIGH")
                        put("notification", JSONObject().apply {
                            put("sound", "default")
                            put("channel_id", "child_safety_alerts")
                        })
                    })
                })
            }

            Log.d(TAG, "      📦 Request payload:")
            Log.d(TAG, message.toString(2).prependIndent("         "))

            // Send request
            Log.d(TAG, "      📤 Sending request...")
            val writer = OutputStreamWriter(connection.outputStream)
            writer.write(message.toString())
            writer.flush()
            writer.close()

            // Check response
            val responseCode = connection.responseCode
            Log.d(TAG, "      📨 Response code: $responseCode")

            val success = responseCode == HttpURLConnection.HTTP_OK

            if (success) {
                val responseBody = connection.inputStream?.bufferedReader()?.readText()
                Log.d(TAG, "      ✅ FCM Response (success):")
                Log.d(TAG, responseBody?.prependIndent("         ") ?: "         (empty)")
            } else {
                val errorBody = connection.errorStream?.bufferedReader()?.readText()
                Log.e(TAG, "      ❌ FCM Response (error):")
                Log.e(TAG, errorBody?.prependIndent("         ") ?: "         (empty)")

                Log.e(TAG, "")
                Log.e(TAG, "      🔍 Common FCM Error Causes:")
                when (responseCode) {
                    400 -> Log.e(TAG, "         - Invalid JSON payload or missing required fields")
                    401 -> Log.e(TAG, "         - Invalid access token or expired")
                    403 -> Log.e(TAG, "         - FCM API not enabled or wrong project")
                    404 -> Log.e(TAG, "         - Invalid FCM token (user uninstalled app?)")
                    else -> Log.e(TAG, "         - Unknown error (check Firebase Console)")
                }
            }

            return@withContext success

        } catch (e: Exception) {
            Log.e(TAG, "      ❌ Exception sending FCM message", e)
            e.printStackTrace()
            return@withContext false
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Send payment notification to parents
     */
    suspend fun sendPaymentNotificationToParents(
        context: Context,
        childUid: String,
        transaction: PaymentTransaction,
        notificationType: NotificationType
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "════════════════════════════════════════")
            Log.d(TAG, "💳 SENDING PAYMENT NOTIFICATION TO PARENTS")
            Log.d(TAG, "════════════════════════════════════════")
            Log.d(TAG, "   Child UID: ${childUid.take(10)}...")
            Log.d(TAG, "   Transaction Amount: ₹${transaction.amount}")
            Log.d(TAG, "   Merchant: ${transaction.merchant}")
            Log.d(TAG, "   Type: ${notificationType.name}")

            // Get access token
            val accessToken = try {
                OAuth2TokenManager.getAccessToken(context)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to get access token", e)
                return@withContext false
            }

            if (accessToken == null) {
                Log.e(TAG, "❌ Access token is NULL")
                return@withContext false
            }

            // Get parent FCM tokens and child names
            val parentData = try {
                getParentFcmTokensAndChildNames(childUid)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Exception fetching parent data", e)
                emptyMap()
            }

            if (parentData.isEmpty()) {
                Log.e(TAG, "❌ No parent data found")
                return@withContext false
            }

            Log.d(TAG, "   Total parents found: ${parentData.size}")

            var successCount = 0
            var failureCount = 0

            for ((parentUid, data) in parentData) {
                val childName = data.childName
                Log.d(TAG, "   📤 Sending to parent: ${parentUid.take(10)}... (Child: $childName)")

                for (token in data.tokens) {
                    val success = try {
                        sendPaymentFcmMessage(
                            accessToken = accessToken,
                            fcmToken = token,
                            parentUid = parentUid,
                            childUid = childUid,
                            childName = childName,
                            transaction = transaction,
                            notificationType = notificationType
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "   ❌ Exception sending message", e)
                        false
                    }

                    if (success) {
                        successCount++
                        Log.d(TAG, "   ✅ SUCCESS")
                    } else {
                        failureCount++
                        Log.e(TAG, "   ❌ FAILED")
                    }
                }
            }

            Log.d(TAG, "")
            Log.d(TAG, "📊 RESULTS: ✅ $successCount successful, ❌ $failureCount failed")
            Log.d(TAG, "════════════════════════════════════════")

            return@withContext successCount > 0

        } catch (e: Exception) {
            Log.e(TAG, "❌ CRITICAL ERROR in sendPaymentNotificationToParents", e)
            e.printStackTrace()
            return@withContext false
        }
    }

    /**
     * Send FCM payment message to parent
     */
    private suspend fun sendPaymentFcmMessage(
        accessToken: String,
        fcmToken: String,
        parentUid: String,
        childUid: String,
        childName: String,
        transaction: PaymentTransaction,
        notificationType: NotificationType
    ): Boolean = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null

        try {
            val url = URL(FCM_ENDPOINT)
            connection = url.openConnection() as HttpURLConnection

            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Content-Type", "application/json; UTF-8")
            connection.doOutput = true
            connection.connectTimeout = 30000
            connection.readTimeout = 30000

            val title = notificationType.getTitle(childName)
            val body = "${notificationType.getBody(childName)}\n₹${transaction.amount} at ${transaction.merchant}"

            // Build JSON payload
            val message = JSONObject().apply {
                put("message", JSONObject().apply {
                    put("token", fcmToken)
                    put("notification", JSONObject().apply {
                        put("title", title)
                        put("body", body)
                    })
                    put("data", JSONObject().apply {
                        put("notificationType", notificationType.name)
                        put("parentUid", parentUid)
                        put("childUid", childUid)
                        put("childName", childName)
                        put("transactionId", transaction.id)
                        put("amount", transaction.amount.toString())
                        put("merchant", transaction.merchant)
                        put("transactionType", transaction.transactionType.name)
                        put("timestamp", transaction.timestamp.toString())
                    })
                    put("android", JSONObject().apply {
                        put("priority", "HIGH")
                        put("notification", JSONObject().apply {
                            put("sound", "default")
                            put("channel_id", "child_safety_alerts")
                        })
                    })
                })
            }

            // Send request
            val writer = OutputStreamWriter(connection.outputStream)
            writer.write(message.toString())
            writer.flush()
            writer.close()

            // Check response
            val responseCode = connection.responseCode
            val success = responseCode == HttpURLConnection.HTTP_OK

            if (!success) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText()
                Log.e(TAG, "      ❌ FCM Error Response: $errorBody")
            }

            return@withContext success

        } catch (e: Exception) {
            Log.e(TAG, "      ❌ Exception in sendPaymentFcmMessage", e)
            return@withContext false
        } finally {
            connection?.disconnect()
        }
    }
}

/**
 * Enum for different notification types
 */
enum class NotificationType {
    OUTSIDE_SAFE_ZONE {
        override fun getTitle(childName: String) = "⚠️ $childName - Safe Zone Alert"
        override fun getBody(childName: String) = "$childName has left the safe zone"
    },
    LOCATION_DISABLED {
        override fun getTitle(childName: String) = "⚠️ $childName - Location Disabled"
        override fun getBody(childName: String) = "$childName has turned off location services"
    },
    CHILD_LOGGED_OUT {
        override fun getTitle(childName: String) = "⚠️ $childName - App Logged Out"
        override fun getBody(childName: String) = "$childName has logged out of the safety app"
    },
    EMERGENCY {
        override fun getTitle(childName: String) = "🚨 $childName - EMERGENCY ALERT"
        override fun getBody(childName: String) = "$childName has triggered an emergency alert!"
    },
    USAGE_DATA_REQUEST {
        override fun getTitle(childName: String) = "📊 Usage Data Request"
        override fun getBody(childName: String) = "Collecting app usage data..."
    },
    ALL_APPS_REQUEST {
        override fun getTitle(childName: String) = "📱 Apps List Request"
        override fun getBody(childName: String) = "Collecting installed apps list..."
    },
    MODE_ACTIVATED {
        override fun getTitle(childName: String) = "📚 Mode Changed"
        override fun getBody(childName: String) = "Device mode has been updated"
    },
    // Add these to your existing NotificationType enum in FcmNotificationSender.kt

    PAYMENT_THRESHOLD_EXCEEDED {
        override fun getTitle(childName: String) = "💳 $childName - Payment Alert"
        override fun getBody(childName: String) = "$childName made a payment exceeding the set threshold"
    },
    PAYMENT_TRANSACTION {
        override fun getTitle(childName: String) = "💰 $childName - Payment Made"
        override fun getBody(childName: String) = "$childName made a payment transaction"
    };

    abstract fun getTitle(childName: String): String
    abstract fun getBody(childName: String): String
}

