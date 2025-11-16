package com.example.child_safety_app_version1.utils.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object MediaCaptureManager {
    private const val TAG = "MediaCaptureManager"
    private const val VIDEO_DURATION_MS = 15000L // 15 seconds
    private const val AUDIO_DURATION_MS = 15000L // 15 seconds

    // ✅ ADD: Mutex to prevent concurrent recording
    private val recordingMutex = Mutex()
    private var activeRecorder: MediaRecorder? = null

    /**
     * Capture 15 seconds of video + audio when child is outside safe zone
     */
    suspend fun captureVideoOutsideSafeZone(
        context: Context,
        childUid: String
    ): File? = recordingMutex.withLock {
        withContext(Dispatchers.IO) {
            var mediaRecorder: MediaRecorder? = null
            var videoFile: File? = null

            try {
                // ✅ Release any existing recorder
                releaseActiveRecorder()

                Log.d(TAG, "🎥 Starting 15-second video capture...")

                // Check permissions
                val cameraGranted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED

                val micGranted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED

                Log.d(TAG, "   Camera permission: $cameraGranted")
                Log.d(TAG, "   Microphone permission: $micGranted")

                if (!cameraGranted || !micGranted) {
                    Log.e(TAG, "❌ Missing permissions")
                    return@withContext null
                }

                // Check camera availability
                try {
                    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                    if (cameraManager == null || cameraManager.cameraIdList.isEmpty()) {
                        Log.e(TAG, "❌ No camera available")
                        return@withContext null
                    }
                    Log.d(TAG, "   Camera hardware: available")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error checking camera", e)
                    return@withContext null
                }

                videoFile = createVideoFile(context, childUid)
                if (videoFile == null) {
                    Log.e(TAG, "❌ Failed to create video file")
                    return@withContext null
                }

                Log.d(TAG, "📁 Video file path: ${videoFile.absolutePath}")

                // ✅ FIXED: Proper MediaRecorder setup
                mediaRecorder = MediaRecorder().apply {
                    try {
                        // Reset to clean state
                        reset()

                        // ✅ Set VIDEO source first, then AUDIO
                        setVideoSource(MediaRecorder.VideoSource.CAMERA)
                        setAudioSource(MediaRecorder.AudioSource.MIC) // Use MIC not CAMCORDER

                        // Set output format
                        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

                        // Set encoders
                        setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)

                        // Video settings - lower for compatibility
                        setVideoSize(640, 480)
                        setVideoFrameRate(15)
                        setVideoEncodingBitRate(500000)

                        // Audio settings
                        setAudioSamplingRate(44100)
                        setAudioEncodingBitRate(96000)

                        // Set output file
                        setOutputFile(videoFile.absolutePath)

                        // Set max duration
                        setMaxDuration(VIDEO_DURATION_MS.toInt())

                        // Prepare
                        prepare()
                        Log.d(TAG, "✅ MediaRecorder prepared successfully")

                        // Small delay before starting
                        Thread.sleep(100)

                        // Start recording
                        start()
                        Log.d(TAG, "✅ Video recording started: ${videoFile.name}")

                        // Store active recorder
                        activeRecorder = this

                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error setting up MediaRecorder", e)
                        release()
                        videoFile?.delete()
                        throw e
                    }
                }

                // Record for 15 seconds
                Thread.sleep(VIDEO_DURATION_MS)

                // Stop recording
                try {
                    mediaRecorder?.apply {
                        stop()
                        release()
                    }
                    activeRecorder = null

                    Log.d(TAG, "✅ Video recording completed: ${videoFile.length() / 1024}KB")
                    return@withContext videoFile

                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error stopping recorder", e)
                    videoFile?.delete()
                    return@withContext null
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Exception in captureVideoOutsideSafeZone", e)
                e.printStackTrace()

                try {
                    mediaRecorder?.release()
                    activeRecorder = null
                } catch (ex: Exception) {
                    Log.e(TAG, "Error releasing MediaRecorder", ex)
                }
                videoFile?.delete()

                return@withContext null
            }
        }
    }

    /**
     * Capture 15 seconds of audio only when child is outside safe zone
     */
    suspend fun captureAudioOutsideSafeZone(
        context: Context,
        childUid: String
    ): File? = recordingMutex.withLock {
        withContext(Dispatchers.IO) {
            var mediaRecorder: MediaRecorder? = null
            var audioFile: File? = null

            try {
                // ✅ Release any existing recorder
                releaseActiveRecorder()

                Log.d(TAG, "🎤 Starting 15-second audio capture...")

                val micGranted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED

                if (!micGranted) {
                    Log.e(TAG, "❌ Microphone permission not granted")
                    return@withContext null
                }

                audioFile = createAudioFile(context, childUid)
                if (audioFile == null) {
                    Log.e(TAG, "❌ Failed to create audio file")
                    return@withContext null
                }

                Log.d(TAG, "📁 Audio file path: ${audioFile.absolutePath}")

                mediaRecorder = MediaRecorder().apply {
                    try {
                        // ✅ Important: Use MIC source
                        setAudioSource(MediaRecorder.AudioSource.MIC)
                        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                        setAudioSamplingRate(44100)
                        setAudioEncodingBitRate(128000)
                        setOutputFile(audioFile.absolutePath)
                        setMaxDuration(AUDIO_DURATION_MS.toInt())

                        prepare()

                        // Small delay
                        Thread.sleep(100)

                        start()

                        // Store active recorder
                        activeRecorder = this

                        Log.d(TAG, "✅ Audio recording started: ${audioFile.name}")
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error setting up MediaRecorder", e)
                        release()
                        audioFile?.delete()
                        throw e
                    }
                }

                // Record for 15 seconds
                Thread.sleep(AUDIO_DURATION_MS)

                try {
                    mediaRecorder?.apply {
                        stop()
                        release()
                    }
                    activeRecorder = null

                    Log.d(TAG, "✅ Audio recording completed: ${audioFile.length() / 1024}KB")
                    return@withContext audioFile

                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error stopping recorder", e)
                    audioFile?.delete()
                    return@withContext null
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Exception in captureAudioOutsideSafeZone", e)

                try {
                    mediaRecorder?.release()
                    activeRecorder = null
                } catch (ex: Exception) {
                    Log.e(TAG, "Error releasing MediaRecorder", ex)
                }
                audioFile?.delete()

                return@withContext null
            }
        }
    }

    /**
     * ✅ NEW: Release active recorder safely
     */
    private fun releaseActiveRecorder() {
        try {
            activeRecorder?.let {
                try {
                    it.stop()
                } catch (e: Exception) {
                    // Ignore stop errors
                }
                it.release()
                Log.d(TAG, "✅ Released previous recorder")
            }
            activeRecorder = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing active recorder", e)
        }
    }

    private fun createVideoFile(context: Context, childUid: String): File? {
        return try {
            val mediaDir = context.getExternalFilesDir("SafeZoneMedia")
            if (mediaDir != null && !mediaDir.exists()) {
                mediaDir.mkdirs()
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "VIDEO_${childUid}_${timestamp}.mp4"

            File(mediaDir, fileName)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error creating video file", e)
            null
        }
    }

    private fun createAudioFile(context: Context, childUid: String): File? {
        return try {
            val mediaDir = context.getExternalFilesDir("SafeZoneMedia")
            if (mediaDir != null && !mediaDir.exists()) {
                mediaDir.mkdirs()
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "AUDIO_${childUid}_${timestamp}.m4a"

            File(mediaDir, fileName)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error creating audio file", e)
            null
        }
    }

    fun getOfflineMediaCacheDir(context: Context): File {
        val cacheDir = File(context.cacheDir, "offline_media")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        return cacheDir
    }

    fun getPendingMediaFiles(context: Context): List<File> {
        val cacheDir = getOfflineMediaCacheDir(context)
        return cacheDir.listFiles()?.toList() ?: emptyList()
    }

    fun moveToOfflineCache(context: Context, file: File): Boolean {
        return try {
            if (!file.exists()) {
                Log.w(TAG, "File does not exist: ${file.absolutePath}")
                return false
            }

            val cacheDir = getOfflineMediaCacheDir(context)
            val cachedFile = File(cacheDir, file.name)

            file.copyTo(cachedFile, overwrite = true)
            file.delete()

            Log.d(TAG, "✅ File moved to offline cache: ${cachedFile.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error moving file to cache", e)
            false
        }
    }

    fun deleteMediaFile(file: File): Boolean {
        return try {
            file.delete().also { success ->
                if (success) {
                    Log.d(TAG, "✅ Media file deleted: ${file.name}")
                } else {
                    Log.w(TAG, "⚠️ Failed to delete file: ${file.name}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error deleting file", e)
            false
        }
    }
}