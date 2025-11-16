package com.example.child_safety_app_version1.utils.media

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.example.child_safety_app_version1.utils.FcmNotificationSender
import com.example.child_safety_app_version1.utils.NotificationType
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Orchestrates media capture, upload, and parent notification
 * when child goes outside safe zone
 *
 * Uses HTTP-based Supabase integration (no SDK required)
 */
object SafeZoneMediaAlertService {
    private const val TAG = "SafeZoneMediaAlert"
    private const val MEDIA_CAPTURE_INTERVAL_MS = 120000L // 2 minutes

    private var lastMediaCaptureTime = 0L
    private var isCapturingMedia = false

    /**
     * Called when child goes outside safe zone
     * Handles:
     * - 2-minute throttling
     * - 15-second video capture
     * - 15-second audio capture
     * - Upload to Supabase (or cache if offline)
     * - Parent notification
     */
    suspend fun handleOutsideSafeZoneWithMedia(
        context: Context,
        childUid: String,
        latitude: Double,
        longitude: Double,
        locationMethod: String,
        accuracy: Float
    ): Boolean = withContext(Dispatchers.Default) {

        // Check throttle - only capture media every 2 minutes
        val currentTime = System.currentTimeMillis()
        val timeSinceLastCapture = currentTime - lastMediaCaptureTime

        if (lastMediaCaptureTime != 0L && timeSinceLastCapture < MEDIA_CAPTURE_INTERVAL_MS) {
            val timeUntilNext = (MEDIA_CAPTURE_INTERVAL_MS - timeSinceLastCapture) / 1000
            Log.d(TAG, "⏱️ Media capture throttled - next in ${timeUntilNext}s")
            return@withContext false
        }

        if (isCapturingMedia) {
            Log.w(TAG, "⚠️ Already capturing media, skipping...")
            return@withContext false
        }

        isCapturingMedia = true
        lastMediaCaptureTime = currentTime

        try {
            Log.d(TAG, "════════════════════════════════════════")
            Log.d(TAG, "🚨 OUTSIDE SAFE ZONE - CAPTURING MEDIA")
            Log.d(TAG, "════════════════════════════════════════")
            Log.d(TAG, "Child UID: ${childUid.take(10)}...")
            Log.d(TAG, "Location: $latitude, $longitude")
            Log.d(TAG, "Method: $locationMethod")

            // Check internet connectivity
            val hasInternet = isInternetAvailable(context)
            Log.d(TAG, "Internet Available: $hasInternet")

            // Step 1: Capture video (15 seconds)
            Log.d(TAG, "")
            Log.d(TAG, "STEP 1: Capturing 15-second video...")
            Log.d(TAG, "────────────────────────────────────────")
            val videoFile = MediaCaptureManager.captureVideoOutsideSafeZone(context, childUid)

            var videoUploadPath: String? = null
            if (videoFile != null) {
                Log.d(TAG, "✅ Video captured: ${videoFile.name}")

                if (hasInternet) {
                    // Upload immediately if internet available
//                    videoUploadPath = SupabaseMediaUploader.uploadVideoToSupabase(
//                        context = context,
//                        videoFile = videoFile,
//                        childUid = childUid,
//                        latitude = latitude,
//                        longitude = longitude,
//                        locationMethod = locationMethod
//                    )

                    if (videoUploadPath != null) {
                        Log.d(TAG, "✅ Video uploaded to Supabase")
                        MediaCaptureManager.deleteMediaFile(videoFile)
                    } else {
                        Log.w(TAG, "⚠️ Video upload failed - moving to offline cache")
                        MediaCaptureManager.moveToOfflineCache(context, videoFile)
                    }
                } else {
                    // Save for later upload
                    Log.w(TAG, "⚠️ No internet - saving video for later upload")
                    MediaCaptureManager.moveToOfflineCache(context, videoFile)
                }
            } else {
                Log.e(TAG, "❌ Failed to capture video")
            }

            // Step 2: Capture audio (15 seconds)
            Log.d(TAG, "")
            Log.d(TAG, "STEP 2: Capturing 15-second audio...")
            Log.d(TAG, "────────────────────────────────────────")
            val audioFile = MediaCaptureManager.captureAudioOutsideSafeZone(context, childUid)

            var audioUploadPath: String? = null
            if (audioFile != null) {
                Log.d(TAG, "✅ Audio captured: ${audioFile.name}")

                if (hasInternet) {
                    audioUploadPath = SupabaseMediaUploader.uploadAudioToSupabase(
                        context = context,
                        audioFile = audioFile,
                        childUid = childUid,
                        latitude = latitude,
                        longitude = longitude,
                        locationMethod = locationMethod
                    )

                    if (audioUploadPath != null) {
                        Log.d(TAG, "✅ Audio uploaded to Supabase")
                        MediaCaptureManager.deleteMediaFile(audioFile)
                    } else {
                        Log.w(TAG, "⚠️ Audio upload failed - moving to offline cache")
                        MediaCaptureManager.moveToOfflineCache(context, audioFile)
                    }
                } else {
                    Log.w(TAG, "⚠️ No internet - saving audio for later upload")
                    MediaCaptureManager.moveToOfflineCache(context, audioFile)
                }
            } else {
                Log.e(TAG, "❌ Failed to capture audio")
            }

            // Step 3: Send FCM notification to parents with media info
            Log.d(TAG, "")
            Log.d(TAG, "STEP 3: Notifying parents via FCM...")
            Log.d(TAG, "────────────────────────────────────────")

            val notificationSuccess = FcmNotificationSender.sendNotificationToParents(
                context = context,
                childUid = childUid,
                notificationType = NotificationType.OUTSIDE_SAFE_ZONE,
                latitude = latitude,
                longitude = longitude,
                locationMethod = locationMethod,
                accuracy = accuracy,
                requestId = "media_alert_${System.currentTimeMillis()}"
            )

            if (notificationSuccess) {
                Log.d(TAG, "✅ Parent notification sent")
            } else {
                Log.w(TAG, "⚠️ Failed to send parent notification")
            }

            // Step 4: Upload pending media files if internet is available
            if (hasInternet && (videoUploadPath != null || audioUploadPath != null)) {
                Log.d(TAG, "")
                Log.d(TAG, "STEP 4: Attempting to upload pending media...")
                Log.d(TAG, "────────────────────────────────────────")
                uploadPendingMediaFiles(context)
            }

            Log.d(TAG, "")
            Log.d(TAG, "════════════════════════════════════════")
            Log.d(TAG, "✅ MEDIA ALERT PROCESS COMPLETED")
            Log.d(TAG, "════════════════════════════════════════")

            return@withContext videoUploadPath != null || audioUploadPath != null

        } catch (e: Exception) {
            Log.e(TAG, "════════════════════════════════════════")
            Log.e(TAG, "❌ CRITICAL ERROR in media capture")
            Log.e(TAG, "════════════════════════════════════════")
            Log.e(TAG, "Exception: ${e.javaClass.simpleName}")
            Log.e(TAG, "Message: ${e.message}")
            e.printStackTrace()
            return@withContext false

        } finally {
            isCapturingMedia = false
        }
    }

    /**
     * Upload all pending media files from offline cache when internet becomes available
     */
    suspend fun uploadPendingMediaFiles(context: Context) = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "📤 Checking for pending media files...")

            val pendingFiles = MediaCaptureManager.getPendingMediaFiles(context)

            if (pendingFiles.isEmpty()) {
                Log.d(TAG, "✅ No pending files to upload")
                return@withContext
            }

            Log.d(TAG, "Found ${pendingFiles.size} pending file(s)")

            val childUid = FirebaseAuth.getInstance().currentUser?.uid ?: return@withContext

            for (file in pendingFiles) {
                try {
                    Log.d(TAG, "📤 Uploading: ${file.name}")

                    val isVideo = file.name.contains("VIDEO_")
                    val isAudio = file.name.contains("AUDIO_")

                    if (isVideo) {
//                        val remotePath = SupabaseMediaUploader.uploadVideoToSupabase(
//                            context = context,
//                            videoFile = file,
//                            childUid = childUid,
//                            latitude = 0.0,
//                            longitude = 0.0,
//                            locationMethod = "CACHED"
//                        )

//                        if (remotePath != null) {
//                            MediaCaptureManager.deleteMediaFile(file)
//                            Log.d(TAG, "✅ Uploaded pending video")
//                        }
                    } else if (isAudio) {
                        val remotePath = SupabaseMediaUploader.uploadAudioToSupabase(
                            context = context,
                            audioFile = file,
                            childUid = childUid,
                            latitude = 0.0,
                            longitude = 0.0,
                            locationMethod = "CACHED"
                        )

                        if (remotePath != null) {
                            MediaCaptureManager.deleteMediaFile(file)
                            Log.d(TAG, "✅ Uploaded pending audio")
                        }
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error uploading file: ${file.name}", e)
                }
            }

            Log.d(TAG, "✅ Pending media upload process completed")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in uploadPendingMediaFiles", e)
        }
    }

    /**
     * Check if device has internet connectivity
     */
    private fun isInternetAvailable(context: Context): Boolean {
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking internet connectivity", e)
            return false
        }
    }
}