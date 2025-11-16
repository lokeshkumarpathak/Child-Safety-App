package com.example.child_safety_app_version1.utils.media

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File

object SupabaseMediaUploader {

    private const val TAG = "SupabaseMediaUploader"

    private const val SUPABASE_URL = "https://qcqdpnsetwljcflwlxex.supabase.co"
    private const val BUCKET_NAME = "child_safety_media"

    // KEY THAT MUST BE USED FOR ALL UPLOADS + FETCHES
    private const val SERVICE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InFjcWRwbnNldHdsamNmbHdseGV4Iiwicm9sZSI6InNlcnZpY2Vfcm9sZSIsImlhdCI6MTc2MzIxODQyOSwiZXhwIjoyMDc4Nzk0NDI5fQ.MYm70X_aoHLSw_CuL3wfeL-YAaAY61yXeVaXAPv11wI"

    private val client = OkHttpClient()

    // ------------------------- UPLOAD FILE -------------------------

    private fun uploadFileToStorage(file: File, remotePath: String): Boolean {
        return try {
            val url = "$SUPABASE_URL/storage/v1/object/$BUCKET_NAME/$remotePath"

            Log.d(TAG, "POST: $url")

            val requestBody = file.asRequestBody("application/octet-stream".toMediaType())

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .addHeader("Authorization", "Bearer $SERVICE_KEY")
                .addHeader("apikey", SERVICE_KEY)
                .addHeader("x-upsert", "true")  // prevent duplicate errors
                .addHeader("Content-Type", "application/octet-stream")
                .build()

            val response = client.newCall(request).execute()

            when (response.code) {
                200, 201 -> {
                    Log.d(TAG, "✅ Uploaded: ${response.code}")
                    true
                }
                else -> {
                    Log.e(TAG, "❌ Storage error ${response.code}")
                    Log.e(TAG, "Body: ${response.body?.string()}")
                    false
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception while uploading", e)
            false
        }
    }


    // ------------------------- SAVE METADATA -------------------------

    private suspend fun saveMediaMetadata(
        childUid: String,
        fileName: String,
        fileType: String,
        remotePath: String,
        latitude: Double,
        longitude: Double,
        locationMethod: String,
        fileSize: Long
    ) {
        withContext(Dispatchers.IO) {
            try {
                val url = "$SUPABASE_URL/rest/v1/child_media"

                val body = JSONObject().apply {
                    put("id", "${childUid}_${System.currentTimeMillis()}")
                    put("child_uid", childUid)
                    put("firebase_uid", childUid)
                    put("file_name", fileName)
                    put("file_type", fileType)
                    put("remote_path", remotePath)
                    put("latitude", latitude)
                    put("longitude", longitude)
                    put("location_method", locationMethod)
                    put("file_size_bytes", fileSize)
                    put("created_at", System.currentTimeMillis())
                    put("status", "uploaded")
                }

                val requestBody = okhttp3.RequestBody.create(
                    "application/json".toMediaType(),
                    body.toString()
                )

                val req = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .addHeader("Authorization", "Bearer $SERVICE_KEY")
                    .addHeader("apikey", SERVICE_KEY)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Prefer", "return=minimal")
                    .build()

                val res = client.newCall(req).execute()

                if (res.code !in listOf(200, 201)) {
                    Log.e(TAG, "❌ Metadata insert failed: ${res.code} ${res.body?.string()}")
                } else {
                    Log.d(TAG, "✅ Metadata inserted")
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error saving metadata", e)
            }
        }
    }

    // ------------------------- PUBLIC FUNCTIONS -------------------------

    suspend fun uploadAudioToSupabase(
        context: Context,
        audioFile: File,
        childUid: String,
        latitude: Double,
        longitude: Double,
        locationMethod: String
    ): String? = withContext(Dispatchers.IO) {

        val remotePath = "audio/$childUid/${audioFile.name}"

        if (!uploadFileToStorage(audioFile, remotePath)) {
            return@withContext null
        }

        saveMediaMetadata(
            childUid,
            audioFile.name,
            "audio",
            remotePath,
            latitude,
            longitude,
            locationMethod,
            audioFile.length()
        )

        return@withContext remotePath
    }

    fun getPublicMediaUrl(remotePath: String): String {
        return "$SUPABASE_URL/storage/v1/object/public/$BUCKET_NAME/$remotePath"
    }
}
