package com.example.child_safety_app_version1.data

import android.graphics.drawable.Drawable

/**
 * Data class representing usage information for a single app
 */
data class AppUsageInfo(
    val packageName: String,
    val appName: String,
    val totalTimeMs: Long,
    val lastUsed: Long,
    val icon: Drawable? = null
) {
    /**
     * Converts to Firestore-compatible map
     */
    fun toFirestoreMap(): HashMap<String, Any> {
        return hashMapOf(
            "packageName" to packageName,
            "appName" to appName,
            "totalTimeMs" to totalTimeMs,
            "lastUsed" to lastUsed,
            "uploadedAt" to System.currentTimeMillis()
        )
    }
}

/**
 * Data class representing daily usage statistics
 */
data class DailyUsageStats(
    val date: String,
    val apps: List<AppUsageInfo>,
    val totalScreenTime: Long
) {
    companion object {
        fun fromAppList(apps: List<AppUsageInfo>, date: String): DailyUsageStats {
            val totalTime = apps.sumOf { it.totalTimeMs }
            return DailyUsageStats(
                date = date,
                apps = apps,
                totalScreenTime = totalTime
            )
        }
    }
}

/**
 * Lightweight data class for installed app information
 */
data class AppInfo(
    val packageName: String,
    val appName: String,
    val category: AppCategory = AppCategory.OTHER,
    val isSystemApp: Boolean = false
)

/**
 * Enum for app categories
 */
enum class AppCategory {
    EDUCATIONAL,
    ENTERTAINMENT,
    SOCIAL,
    GAMES,
    PRODUCTIVITY,
    SYSTEM,
    OTHER
}