package com.example.child_safety_app_version1.data

/**
 * Data class representing weekly usage insights
 */
data class WeeklyInsights(
    val weekId: String,              // e.g., "2025-W46"
    val startDate: String,           // "2025-11-10"
    val endDate: String,             // "2025-11-16"
    val childUid: String,
    val childName: String,
    val totalScreenTimeMs: Long,
    val topApps: List<TopAppInfo>,
    val insights: List<String>,      // AI-generated insights
    val generatedAt: Long,
    val dataAvailableDays: Int,      // How many days had data (0-7)
    val averageDailyScreenTimeMs: Long
) {
    fun toFirestoreMap(): HashMap<String, Any> {
        return hashMapOf(
            "weekId" to weekId,
            "startDate" to startDate,
            "endDate" to endDate,
            "childUid" to childUid,
            "childName" to childName,
            "totalScreenTimeMs" to totalScreenTimeMs,
            "topApps" to topApps.map { it.toMap() },
            "insights" to insights,
            "generatedAt" to generatedAt,
            "dataAvailableDays" to dataAvailableDays,
            "averageDailyScreenTimeMs" to averageDailyScreenTimeMs
        )
    }

    companion object {
        fun fromFirestore(data: Map<String, Any>): WeeklyInsights {
            @Suppress("UNCHECKED_CAST")
            val topAppsList = (data["topApps"] as? List<Map<String, Any>>)?.map {
                TopAppInfo.fromMap(it)
            } ?: emptyList()

            @Suppress("UNCHECKED_CAST")
            val insightsList = data["insights"] as? List<String> ?: emptyList()

            return WeeklyInsights(
                weekId = data["weekId"] as? String ?: "",
                startDate = data["startDate"] as? String ?: "",
                endDate = data["endDate"] as? String ?: "",
                childUid = data["childUid"] as? String ?: "",
                childName = data["childName"] as? String ?: "",
                totalScreenTimeMs = (data["totalScreenTimeMs"] as? Number)?.toLong() ?: 0L,
                topApps = topAppsList,
                insights = insightsList,
                generatedAt = (data["generatedAt"] as? Number)?.toLong() ?: 0L,
                dataAvailableDays = (data["dataAvailableDays"] as? Number)?.toInt() ?: 0,
                averageDailyScreenTimeMs = (data["averageDailyScreenTimeMs"] as? Number)?.toLong() ?: 0L
            )
        }
    }
}

/**
 * Data class for top app information in insights
 */
data class TopAppInfo(
    val packageName: String,
    val appName: String,
    val totalTimeMs: Long,
    val percentageOfTotal: Double,
    val category: String = "Other"
) {
    fun toMap(): Map<String, Any> {
        return mapOf(
            "packageName" to packageName,
            "appName" to appName,
            "totalTimeMs" to totalTimeMs,
            "percentageOfTotal" to percentageOfTotal,
            "category" to category
        )
    }

    companion object {
        fun fromMap(map: Map<String, Any>): TopAppInfo {
            return TopAppInfo(
                packageName = map["packageName"] as? String ?: "",
                appName = map["appName"] as? String ?: "",
                totalTimeMs = (map["totalTimeMs"] as? Number)?.toLong() ?: 0L,
                percentageOfTotal = (map["percentageOfTotal"] as? Number)?.toDouble() ?: 0.0,
                category = map["category"] as? String ?: "Other"
            )
        }
    }
}

/**
 * Data class for aggregated weekly data (used during collection)
 */
data class WeeklyUsageData(
    val weekId: String,
    val startDate: String,
    val endDate: String,
    val dailyData: Map<String, DailyUsageStats>, // date -> stats
    val aggregatedApps: Map<String, AppUsageInfo> // packageName -> combined usage
) {
    val totalScreenTimeMs: Long
        get() = aggregatedApps.values.sumOf { it.totalTimeMs }

    val dataAvailableDays: Int
        get() = dailyData.size

    val averageDailyScreenTimeMs: Long
        get() = if (dataAvailableDays > 0) totalScreenTimeMs / dataAvailableDays else 0L

    fun getTopApps(limit: Int = 10): List<TopAppInfo> {
        val totalTime = totalScreenTimeMs.toDouble()
        return aggregatedApps.values
            .sortedByDescending { it.totalTimeMs }
            .take(limit)
            .map { app ->
                TopAppInfo(
                    packageName = app.packageName,
                    appName = app.appName,
                    totalTimeMs = app.totalTimeMs,
                    percentageOfTotal = if (totalTime > 0) (app.totalTimeMs / totalTime) * 100 else 0.0,
                    category = categorizeApp(app.packageName, app.appName)
                )
            }
    }

    private fun categorizeApp(packageName: String, appName: String): String {
        val combined = "$packageName $appName".lowercase()
        return when {
            combined.contains("game") || combined.contains("play") -> "Games"
            combined.contains("social") || combined.contains("facebook") ||
                    combined.contains("instagram") || combined.contains("whatsapp") -> "Social"
            combined.contains("youtube") || combined.contains("netflix") ||
                    combined.contains("spotify") -> "Entertainment"
            combined.contains("chrome") || combined.contains("browser") -> "Browsing"
            combined.contains("camera") || combined.contains("gallery") -> "Media"
            combined.contains("education") || combined.contains("learn") -> "Education"
            else -> "Other"
        }
    }
}

/**
 * Request status for insights generation
 */
data class InsightsRequest(
    val requestId: String,
    val childUid: String,
    val weekId: String,
    val requestedAt: Long,
    val status: InsightsRequestStatus,
    val errorMessage: String? = null
) {
    fun toFirestoreMap(): HashMap<String, Any> {
        val map = hashMapOf<String, Any>(
            "requestId" to requestId,
            "childUid" to childUid,
            "weekId" to weekId,
            "requestedAt" to requestedAt,
            "status" to status.name
        )
        if (errorMessage != null) {
            map["errorMessage"] = errorMessage
        }
        return map
    }
}

enum class InsightsRequestStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED
}