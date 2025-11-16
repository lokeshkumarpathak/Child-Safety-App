package com.example.child_safety_app_version1.database.celltower

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity representing a cell tower location in the database
 *
 * This table stores cell tower information downloaded from OpenCelliD
 * and is used for offline location lookups when internet is unavailable
 */
@Entity(
    tableName = "cell_towers",
    indices = [
        // Create composite index for fast lookups by MCC+MNC+LAC+CID
        Index(value = ["mcc", "mnc", "lac", "cell_id"], unique = true),
        // Create index for geographic searches (future feature)
        Index(value = ["latitude", "longitude"])
    ]
)
data class CellTowerEntity(

    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    // Cell Tower Identifiers
    @ColumnInfo(name = "mcc")
    val mcc: Int,                    // Mobile Country Code (India: 404, 405)

    @ColumnInfo(name = "mnc")
    val mnc: Int,                    // Mobile Network Code (Operator)

    @ColumnInfo(name = "lac")
    val lac: Int,                    // Location Area Code / TAC

    @ColumnInfo(name = "cell_id")
    val cellId: Int,                 // Cell ID (CID)

    // Location Information
    @ColumnInfo(name = "latitude")
    val latitude: Double,            // Latitude of cell tower

    @ColumnInfo(name = "longitude")
    val longitude: Double,           // Longitude of cell tower

    @ColumnInfo(name = "range")
    val range: Int,                  // Coverage range in meters (accuracy)

    // Network Type
    @ColumnInfo(name = "radio")
    val radio: String,               // GSM, UMTS, LTE, etc.

    // Metadata
    @ColumnInfo(name = "samples")
    val samples: Int = 1,            // Number of measurements (data quality indicator)

    @ColumnInfo(name = "created")
    val created: Long,               // Timestamp when first added to OpenCelliD

    @ColumnInfo(name = "updated")
    val updated: Long,               // Timestamp when last updated in OpenCelliD

    @ColumnInfo(name = "last_synced")
    val lastSynced: Long = System.currentTimeMillis() // When we downloaded it
) {

    /**
     * Get accuracy as Float for compatibility with location APIs
     */
    fun getAccuracyFloat(): Float = range.toFloat()

    /**
     * Check if this tower data is fresh (less than 90 days old)
     */
    fun isFresh(): Boolean {
        val ninetyDaysInMillis = 90L * 24 * 60 * 60 * 1000
        return (System.currentTimeMillis() - updated) < ninetyDaysInMillis
    }

    /**
     * Check if tower data is high quality (many samples)
     */
    fun isHighQuality(): Boolean = samples >= 5

    /**
     * Get human-readable description
     */
    override fun toString(): String {
        return "CellTower(MCC=$mcc, MNC=$mnc, LAC=$lac, CID=$cellId, " +
                "Location=($latitude, $longitude), Range=${range}m, Radio=$radio)"
    }

    companion object {
        /**
         * Create entity from CSV row (OpenCelliD format)
         * CSV Format: radio,mcc,mnc,lac,cellid,longitude,latitude,range,samples,created,updated
         */
        fun fromCsvRow(csvRow: String): CellTowerEntity? {
            return try {
                val parts = csvRow.split(",")
                if (parts.size < 11) return null

                CellTowerEntity(
                    radio = parts[0],
                    mcc = parts[1].toIntOrNull() ?: return null,
                    mnc = parts[2].toIntOrNull() ?: return null,
                    lac = parts[3].toIntOrNull() ?: return null,
                    cellId = parts[4].toIntOrNull() ?: return null,
                    longitude = parts[5].toDoubleOrNull() ?: return null,
                    latitude = parts[6].toDoubleOrNull() ?: return null,
                    range = parts[7].toIntOrNull() ?: 500,
                    samples = parts[8].toIntOrNull() ?: 1,
                    created = parts[9].toLongOrNull() ?: 0,
                    updated = parts[10].toLongOrNull() ?: 0
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}