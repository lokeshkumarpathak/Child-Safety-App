package com.example.child_safety_app_version1.data

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Enum representing different app access modes
 */
enum class AppMode(
    val modeName: String,
    val displayName: String,
    val description: String,
    val icon: ImageVector
) {
    NORMAL(
        modeName = "NORMAL",
        displayName = "Normal Mode",
        description = "Standard device usage with app restrictions",
        icon = Icons.Default.CheckCircle
    ),
    STUDY(
        modeName = "STUDY",
        displayName = "Study Mode",
        description = "Focused study with only educational apps allowed",
        icon = Icons.Default.School
    ),
    BEDTIME(
        modeName = "BEDTIME",
        displayName = "Bedtime Mode",
        description = "Minimal access - emergency calls only",
        icon = Icons.Default.Bedtime
    );

    companion object {
        /**
         * Convert string to AppMode
         */
        fun fromString(value: String): AppMode {
            return try {
                valueOf(value.uppercase())
            } catch (e: Exception) {
                NORMAL
            }
        }

        /**
         * Get default allowed apps for a given mode
         */
        fun getDefaultAllowedApps(mode: AppMode): List<String> {
            return when (mode) {
                NORMAL -> {
                    // Normal mode has no default blocked apps
                    emptyList()
                }
                STUDY -> {
                    // Study mode: educational apps by default
                    listOf(
                        "com.android.calculator2",
                        "com.google.android.calculator",
                        "com.google.android.keep",
                        "com.google.android.apps.classroom",
                        "com.duolingo",
                        "org.khanacademy.android",
                        "com.photomath.app",
                        "com.google.android.apps.docs",
                        "com.google.android.apps.docs.editors.sheets",
                        "com.google.android.apps.translate",
                        "com.android.chrome"
                    )
                }
                BEDTIME -> {
                    // Bedtime mode: minimal access (only phone + safety app)
                    // These are added automatically by AppModeManager
                    emptyList()
                }
            }
        }

        /**
         * Get system apps that are always allowed
         * These are essential system apps needed in all modes
         */
        fun getSystemAllowedApps(): List<String> {
            return listOf(
                // Phone app - always needed for emergencies
                "com.android.phone",
                "com.android.dialer",

                // Child Safety App - the control app itself
                "com.example.child_safety_app_version1",

                // System essentials
                "android.package.name",
                "com.android.systemui",
                "com.android.settings",
                "com.android.launcher",
                "com.android.launcher3"
            )
        }

        /**
         * Get all modes
         */
        fun getAllModes(): List<AppMode> {
            return listOf(NORMAL, STUDY, BEDTIME)
        }
    }
}