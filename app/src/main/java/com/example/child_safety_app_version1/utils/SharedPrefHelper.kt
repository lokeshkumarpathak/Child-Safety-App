package com.example.child_safety_app_version1.utils

import android.content.Context
import android.content.SharedPreferences

private const val PREFS_NAME = "ChildSafetyPrefs"
private const val KEY_ROLE = "role"

fun saveLoginState(context: Context, role: String) {
    val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit().putString(KEY_ROLE, role).apply()
}

fun getSavedRole(context: Context): String? {
    val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return prefs.getString(KEY_ROLE, null)
}

fun clearLoginState(context: Context) {
    val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit().remove(KEY_ROLE).apply()
}
