package com.example.child_safety_app_version1.utils

import android.content.Context
import android.util.Log
import com.example.child_safety_app_version1.data.WeeklyInsights
import com.example.child_safety_app_version1.data.WeeklyUsageData
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object AIInsightsGenerator {
    private const val TAG = "AIInsightsGenerator"

    // Your key (ok)
    private const val GEMINI_API_KEY =
        "AIzaSyD2MXM-tDqNYz7m8tYFbKwFRIqxm-4FmOo"

    // ✔ FIXED — New correct Gemini v1 endpoint
    // correct Gemini v1 endpoint for your allowed model
    private const val GEMINI_API_URL =
        "https://generativelanguage.googleapis.com/v1/models/gemini-2.5-flash:generateContent"

    /**
     * Generates weekly AI insights using Gemini 1.5 Flash
     */
    suspend fun generateInsights(
        context: Context,
        childUid: String,
        childName: String,
        weeklyData: WeeklyUsageData
    ): WeeklyInsights? = withContext(Dispatchers.IO) {
        Log.d(TAG, "════ GENERATING AI INSIGHTS ════")

        try {
            if (GEMINI_API_KEY.isEmpty()) {
                Log.e(TAG, "❌ Missing API Key!")
                return@withContext null
            }

            // Build analytics data
            val topApps = weeklyData.getTopApps(10)
            val totalScreenTime = UsageStatsHelper.formatDuration(weeklyData.totalScreenTimeMs)
            val avgDaily = UsageStatsHelper.formatDuration(weeklyData.averageDailyScreenTimeMs)

            val prompt = buildPrompt(
                childName,
                weeklyData.weekId,
                weeklyData.startDate,
                weeklyData.endDate,
                totalScreenTime,
                avgDaily,
                weeklyData.dataAvailableDays,
                topApps
            )

            val insightsList = callGeminiAPI(GEMINI_API_KEY, prompt)
                ?: return@withContext null

            val weeklyInsights = WeeklyInsights(
                weekId = weeklyData.weekId,
                startDate = weeklyData.startDate,
                endDate = weeklyData.endDate,
                childUid = childUid,
                childName = childName,
                totalScreenTimeMs = weeklyData.totalScreenTimeMs,
                topApps = topApps,
                insights = insightsList,
                generatedAt = System.currentTimeMillis(),
                dataAvailableDays = weeklyData.dataAvailableDays,
                averageDailyScreenTimeMs = weeklyData.averageDailyScreenTimeMs
            )

            val saved = saveInsightsToFirestore(childUid, weeklyInsights)

            if (saved) weeklyInsights else null

        } catch (e: Exception) {
            Log.e(TAG, "❌ CRITICAL ERROR generating insights", e)
            null
        }
    }

    private fun buildPrompt(
        childName: String,
        weekId: String,
        startDate: String,
        endDate: String,
        totalScreenTime: String,
        avgDailyTime: String,
        dataAvailableDays: Int,
        topApps: List<com.example.child_safety_app_version1.data.TopAppInfo>
    ): String {
        return """
You are an expert analyzing weekly smartphone usage for a child named $childName.

WEEK: $weekId ($startDate → $endDate)
Total screen time: $totalScreenTime
Average per day: $avgDailyTime
Days with data: $dataAvailableDays

Top 10 apps:
${
            topApps.mapIndexed { i, app ->
                "${i + 1}. ${app.appName} - ${UsageStatsHelper.formatDuration(app.totalTimeMs)} (${String.format("%.1f", app.percentageOfTotal)}%)"
            }.joinToString("\n")
        }

Return ONLY a JSON array of short text insights.
Example:
["Insight 1...", "Insight 2...", "Insight 3..."]
""".trimIndent()
    }

    /**
     * FIXED Gemini API call — works with v1 models
     */
    private suspend fun callGeminiAPI(
        apiKey: String,
        prompt: String
    ): List<String>? = withContext(Dispatchers.IO) {

        var connection: HttpURLConnection? = null

        try {
            val url = URL(GEMINI_API_URL)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 30000
                readTimeout = 30000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", apiKey)
            }

            // ✔ FIXED — New Gemini v1 JSON structure
            val requestBody = JSONObject().apply {
                put(
                    "contents", JSONArray().put(
                        JSONObject().put(
                            "parts", JSONArray().put(
                                JSONObject().put("text", prompt)
                            )
                        )
                    )
                )
            }

            // Send request
            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(requestBody.toString())
            }

            val responseCode = connection.responseCode
            val responseText = if (responseCode == 200) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                val err = connection.errorStream?.bufferedReader()?.use { it.readText() }
                Log.e(TAG, "❌ Gemini API Error: $err")
                return@withContext null
            }

            val json = JSONObject(responseText)

            val rawText =
                json.getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")

            // Clean markdown fencing if present
            val cleaned = rawText
                .replace("```json", "")
                .replace("```", "")
                .trim()

            val arr = JSONArray(cleaned)
            val results = mutableListOf<String>()

            for (i in 0 until arr.length()) {
                val s = arr.getString(i).trim()
                if (s.isNotEmpty()) results.add(s)
            }

            results

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error calling Gemini API", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    // ---------------------------
    // Firestore
    // ---------------------------

    private suspend fun saveInsightsToFirestore(
        childUid: String,
        insights: WeeklyInsights
    ): Boolean {
        return try {
            FirebaseFirestore.getInstance()
                .collection("users")
                .document(childUid)
                .collection("weeklyInsights")
                .document(insights.weekId)
                .set(insights.toFirestoreMap())
                .await()
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Firestore save failed", e)
            false
        }
    }

    suspend fun loadInsightsFromFirestore(
        childUid: String,
        weekId: String
    ): WeeklyInsights? {
        return try {
            val doc = FirebaseFirestore.getInstance()
                .collection("users")
                .document(childUid)
                .collection("weeklyInsights")
                .document(weekId)
                .get()
                .await()

            if (!doc.exists()) null
            else WeeklyInsights.fromFirestore(doc.data ?: emptyMap())

        } catch (e: Exception) {
            Log.e(TAG, "❌ Load error", e)
            null
        }
    }

    suspend fun insightsExist(childUid: String, weekId: String): Boolean {
        return try {
            FirebaseFirestore.getInstance()
                .collection("users")
                .document(childUid)
                .collection("weeklyInsights")
                .document(weekId)
                .get()
                .await()
                .exists()
        } catch (_: Exception) {
            false
        }
    }
}