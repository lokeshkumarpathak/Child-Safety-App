package com.example.child_safety_app_version1.utils.celltower

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityWcdma
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.ActivityCompat

/**
 * Data class representing a cell tower
 */
data class CellTowerInfo(
    val cellId: Int,              // Cell ID (CID)
    val lac: Int,                 // Location Area Code (LAC) or TAC for LTE
    val mcc: Int,                 // Mobile Country Code (India: 404, 405)
    val mnc: Int,                 // Mobile Network Code (Airtel, Jio, etc.)
    val signalStrength: Int,      // Signal strength (0-4, higher is better)
    val networkType: String,      // LTE, GSM, WCDMA, etc.
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Manager class to retrieve cell tower information from the device
 */
class CellTowerManager(private val context: Context) {

    companion object {
        private const val TAG = "CellTowerManager"
    }

    private val telephonyManager: TelephonyManager by lazy {
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    }

    /**
     * Get all visible cell towers
     * Returns a list of cell towers sorted by signal strength (strongest first)
     */
    fun getAllCellTowers(): List<CellTowerInfo> {
        if (!hasRequiredPermissions()) {
            Log.e(TAG, "❌ Missing required permissions for cell tower access")
            return emptyList()
        }

        try {
            val allCellInfo = telephonyManager.allCellInfo

            if (allCellInfo.isNullOrEmpty()) {
                Log.w(TAG, "⚠️ No cell towers detected - airplane mode or no signal?")
                return emptyList()
            }

            Log.d(TAG, "📡 Found ${allCellInfo.size} cell tower(s)")

            val towers = allCellInfo.mapNotNull { cellInfo ->
                parseCellInfo(cellInfo)
            }

            // Sort by signal strength (strongest first)
            val sortedTowers = towers.sortedByDescending { it.signalStrength }

            Log.d(TAG, "✅ Successfully parsed ${sortedTowers.size} tower(s)")
            sortedTowers.forEachIndexed { index, tower ->
                Log.d(TAG, "  Tower ${index + 1}: ${tower.networkType} - CID:${tower.cellId}, Signal:${tower.signalStrength}/4")
            }

            return sortedTowers

        } catch (e: SecurityException) {
            Log.e(TAG, "❌ SecurityException - missing permissions", e)
            return emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error getting cell tower info", e)
            e.printStackTrace()
            return emptyList()
        }
    }

    /**
     * Get the primary (strongest signal) cell tower
     */
    fun getPrimaryCellTower(): CellTowerInfo? {
        return getAllCellTowers().firstOrNull()
    }

    /**
     * Parse CellInfo object into our CellTowerInfo data class
     */
    private fun parseCellInfo(cellInfo: CellInfo): CellTowerInfo? {
        return try {
            when (cellInfo) {
                is CellInfoLte -> parseLteCellInfo(cellInfo)
                is CellInfoGsm -> parseGsmCellInfo(cellInfo)
                is CellInfoWcdma -> parseWcdmaCellInfo(cellInfo)
                else -> {
                    Log.d(TAG, "⚠️ Unsupported cell type: ${cellInfo.javaClass.simpleName}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error parsing cell info", e)
            null
        }
    }

    /**
     * Parse LTE (4G) cell tower info
     * Compatible with API 26+
     */
    private fun parseLteCellInfo(cellInfo: CellInfoLte): CellTowerInfo? {
        val identity = cellInfo.cellIdentity as CellIdentityLte

        // Get MCC/MNC - API level dependent
        val mcc: Int
        val mnc: Int

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // API 28+: Use getMccString()
            mcc = identity.mccString?.toIntOrNull() ?: return null
            mnc = identity.mncString?.toIntOrNull() ?: return null
        } else {
            // API 26-27: Use deprecated methods
            @Suppress("DEPRECATION")
            mcc = identity.mcc.takeIf { it != Int.MAX_VALUE } ?: return null
            @Suppress("DEPRECATION")
            mnc = identity.mnc.takeIf { it != Int.MAX_VALUE } ?: return null
        }

        val cid = identity.ci
        val tac = identity.tac

        if (cid == Int.MAX_VALUE || tac == Int.MAX_VALUE) {
            Log.d(TAG, "⚠️ LTE cell has invalid/unavailable values")
            return null
        }

        val signalStrength = cellInfo.cellSignalStrength.level // 0-4

        return CellTowerInfo(
            cellId = cid,
            lac = tac,
            mcc = mcc,
            mnc = mnc,
            signalStrength = signalStrength,
            networkType = "LTE"
        ).also {
            Log.d(TAG, "📱 LTE Tower: MCC=$mcc, MNC=$mnc, CID=$cid, TAC=$tac, Signal=$signalStrength/4")
        }
    }

    /**
     * Parse GSM (2G) cell tower info
     * Compatible with API 26+
     */
    private fun parseGsmCellInfo(cellInfo: CellInfoGsm): CellTowerInfo? {
        val identity = cellInfo.cellIdentity as CellIdentityGsm

        // Get MCC/MNC - API level dependent
        val mcc: Int
        val mnc: Int

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // API 28+: Use getMccString()
            mcc = identity.mccString?.toIntOrNull() ?: return null
            mnc = identity.mncString?.toIntOrNull() ?: return null
        } else {
            // API 26-27: Use deprecated methods
            @Suppress("DEPRECATION")
            mcc = identity.mcc.takeIf { it != Int.MAX_VALUE } ?: return null
            @Suppress("DEPRECATION")
            mnc = identity.mnc.takeIf { it != Int.MAX_VALUE } ?: return null
        }

        val cid = identity.cid
        val lac = identity.lac

        if (cid == Int.MAX_VALUE || lac == Int.MAX_VALUE) {
            Log.d(TAG, "⚠️ GSM cell has invalid/unavailable values")
            return null
        }

        val signalStrength = cellInfo.cellSignalStrength.level // 0-4

        return CellTowerInfo(
            cellId = cid,
            lac = lac,
            mcc = mcc,
            mnc = mnc,
            signalStrength = signalStrength,
            networkType = "GSM"
        ).also {
            Log.d(TAG, "📱 GSM Tower: MCC=$mcc, MNC=$mnc, CID=$cid, LAC=$lac, Signal=$signalStrength/4")
        }
    }

    /**
     * Parse WCDMA (3G) cell tower info
     * Compatible with API 26+
     */
    private fun parseWcdmaCellInfo(cellInfo: CellInfoWcdma): CellTowerInfo? {
        val identity = cellInfo.cellIdentity as CellIdentityWcdma

        // Get MCC/MNC - API level dependent
        val mcc: Int
        val mnc: Int

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // API 28+: Use getMccString()
            mcc = identity.mccString?.toIntOrNull() ?: return null
            mnc = identity.mncString?.toIntOrNull() ?: return null
        } else {
            // API 26-27: Use deprecated methods
            @Suppress("DEPRECATION")
            mcc = identity.mcc.takeIf { it != Int.MAX_VALUE } ?: return null
            @Suppress("DEPRECATION")
            mnc = identity.mnc.takeIf { it != Int.MAX_VALUE } ?: return null
        }

        val cid = identity.cid
        val lac = identity.lac

        if (cid == Int.MAX_VALUE || lac == Int.MAX_VALUE) {
            Log.d(TAG, "⚠️ WCDMA cell has invalid/unavailable values")
            return null
        }

        val signalStrength = cellInfo.cellSignalStrength.level // 0-4

        return CellTowerInfo(
            cellId = cid,
            lac = lac,
            mcc = mcc,
            mnc = mnc,
            signalStrength = signalStrength,
            networkType = "WCDMA"
        ).also {
            Log.d(TAG, "📱 WCDMA Tower: MCC=$mcc, MNC=$mnc, CID=$cid, LAC=$lac, Signal=$signalStrength/4")
        }
    }

    /**
     * Check if we have the required permissions
     */
    private fun hasRequiredPermissions(): Boolean {
        val fineLocation = ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val phoneState = ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        if (!fineLocation) {
            Log.e(TAG, "❌ Missing ACCESS_FINE_LOCATION permission")
        }
        if (!phoneState) {
            Log.e(TAG, "❌ Missing READ_PHONE_STATE permission")
        }

        return fineLocation && phoneState
    }

    /**
     * Get network operator name (e.g., "Airtel", "Jio")
     */
    fun getNetworkOperatorName(): String {
        return try {
            telephonyManager.networkOperatorName ?: "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }
    }

    /**
     * Check if device is in airplane mode
     */
    fun isAirplaneModeOn(): Boolean {
        return try {
            android.provider.Settings.Global.getInt(
                context.contentResolver,
                android.provider.Settings.Global.AIRPLANE_MODE_ON,
                0
            ) != 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get detailed debug information
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Cell Tower Debug Info ===")
            appendLine("Network Operator: ${getNetworkOperatorName()}")
            appendLine("Airplane Mode: ${isAirplaneModeOn()}")
            appendLine("Has Permissions: ${hasRequiredPermissions()}")

            val towers = getAllCellTowers()
            appendLine("Towers Detected: ${towers.size}")

            towers.forEachIndexed { index, tower ->
                appendLine("\nTower ${index + 1}:")
                appendLine("  Type: ${tower.networkType}")
                appendLine("  MCC: ${tower.mcc}")
                appendLine("  MNC: ${tower.mnc}")
                appendLine("  CID: ${tower.cellId}")
                appendLine("  LAC: ${tower.lac}")
                appendLine("  Signal: ${tower.signalStrength}/4")
            }
        }
    }
}