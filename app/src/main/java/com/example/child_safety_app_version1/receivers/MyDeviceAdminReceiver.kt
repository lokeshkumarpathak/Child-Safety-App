package com.example.child_safety_app_version1.receivers

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class MyDeviceAdminReceiver : DeviceAdminReceiver() {
    companion object {
        private const val TAG = "MyDeviceAdminReceiver"
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence? {
        Log.d(TAG, "onDisableRequested() - child is trying to disable Device Admin / uninstall")
        // Launch the UninstallConsentActivity so the child sees the custom UI
        try {
            val uiIntent = Intent(context, com.example.child_safety_app_version1.userInterface.UninstallConsentActivity::class.java)
            uiIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            context.startActivity(uiIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch UninstallConsentActivity: ${e.message}")
        }
        return "This app can be uninstalled only on the consent of your parent"
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        Log.d(TAG, "onReceive action=${intent.action}")
    }
}
