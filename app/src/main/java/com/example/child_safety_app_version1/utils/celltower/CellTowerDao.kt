package com.example.child_safety_app_version1.database.celltower

import androidx.room.*

/**
 * Data Access Object for cell tower database operations
 */
@Dao
interface CellTowerDao {

    // ==================== PRIMARY LOOKUP ====================

    /**
     * Find a specific cell tower by its identifiers
     * This is the main query used for location lookups
     */
    @Query("""
        SELECT * FROM cell_towers 
        WHERE mcc = :mcc 
        AND mnc = :mnc 
        AND lac = :lac 
        AND cell_id = :cellId 
        LIMIT 1
    """)
    suspend fun findCellTower(
        mcc: Int,
        mnc: Int,
        lac: Int,
        cellId: Int
    ): CellTowerEntity?

    // ==================== BULK INSERT ====================

    /**
     * Insert a single cell tower
     * OnConflict: REPLACE - Updates if tower already exists
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tower: CellTowerEntity): Long

    /**
     * Insert multiple cell towers (batch operation)
     * Use this for bulk imports from CSV
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(towers: List<CellTowerEntity>): List<Long>

    /**
     * Insert multiple towers and ignore conflicts (faster for large datasets)
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnoreConflicts(towers: List<CellTowerEntity>): List<Long>

    // ==================== UPDATE ====================

    /**
     * Update an existing cell tower
     */
    @Update
    suspend fun update(tower: CellTowerEntity): Int

    /**
     * Update last synced timestamp for a tower
     */
    @Query("""
        UPDATE cell_towers 
        SET last_synced = :timestamp 
        WHERE mcc = :mcc AND mnc = :mnc AND lac = :lac AND cell_id = :cellId
    """)
    suspend fun updateLastSynced(
        mcc: Int,
        mnc: Int,
        lac: Int,
        cellId: Int,
        timestamp: Long
    ): Int

    // ==================== DELETE ====================

    /**
     * Delete a specific cell tower
     */
    @Delete
    suspend fun delete(tower: CellTowerEntity): Int

    /**
     * Delete all cell towers (use for database reset)
     */
    @Query("DELETE FROM cell_towers")
    suspend fun deleteAll(): Int

    /**
     * Delete old towers (older than X days)
     */
    @Query("DELETE FROM cell_towers WHERE updated < :cutoffTimestamp")
    suspend fun deleteOldTowers(cutoffTimestamp: Long): Int

    // ==================== STATISTICS & INFO ====================

    /**
     * Get total count of cell towers in database
     */
    @Query("SELECT COUNT(*) FROM cell_towers")
    suspend fun getTotalCount(): Int

    /**
     * Get count of towers by MCC (country)
     */
    @Query("SELECT COUNT(*) FROM cell_towers WHERE mcc = :mcc")
    suspend fun getCountByMcc(mcc: Int): Int

    /**
     * Get count of towers by MCC and MNC (operator)
     */
    @Query("SELECT COUNT(*) FROM cell_towers WHERE mcc = :mcc AND mnc = :mnc")
    suspend fun getCountByOperator(mcc: Int, mnc: Int): Int

    /**
     * Get database size information
     */
    @Query("""
        SELECT 
            COUNT(*) as total,
            COUNT(DISTINCT mcc) as countries,
            COUNT(DISTINCT mnc) as operators,
            MIN(updated) as oldest,
            MAX(updated) as newest
        FROM cell_towers
    """)
    suspend fun getDatabaseStats(): DatabaseStats?

    // ==================== GEOGRAPHIC QUERIES ====================

    /**
     * Find nearby cell towers within a bounding box
     * Useful for pre-caching towers in an area
     */
    @Query("""
        SELECT * FROM cell_towers
        WHERE latitude BETWEEN :minLat AND :maxLat
        AND longitude BETWEEN :minLon AND :maxLon
        LIMIT :limit
    """)
    suspend fun findTowersInBoundingBox(
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double,
        limit: Int = 100
    ): List<CellTowerEntity>

    /**
     * Find all towers for a specific operator (MCC+MNC)
     */
    @Query("""
        SELECT * FROM cell_towers
        WHERE mcc = :mcc AND mnc = :mnc
        ORDER BY updated DESC
        LIMIT :limit
    """)
    suspend fun findTowersByOperator(
        mcc: Int,
        mnc: Int,
        limit: Int = 1000
    ): List<CellTowerEntity>

    // ==================== MAINTENANCE ====================

    /**
     * Get towers that need updating (older than 90 days)
     */
    @Query("""
        SELECT * FROM cell_towers
        WHERE updated < :cutoffTimestamp
        ORDER BY updated ASC
        LIMIT :limit
    """)
    suspend fun getTowersNeedingUpdate(
        cutoffTimestamp: Long,
        limit: Int = 100
    ): List<CellTowerEntity>

    /**
     * Check if database has been populated
     */
    @Query("SELECT EXISTS(SELECT 1 FROM cell_towers LIMIT 1)")
    suspend fun isDatabasePopulated(): Boolean

    /**
     * Get sample tower (for testing)
     */
    @Query("SELECT * FROM cell_towers LIMIT 1")
    suspend fun getSampleTower(): CellTowerEntity?
}

/**
 * Data class for database statistics
 */
data class DatabaseStats(
    val total: Int,
    val countries: Int,
    val operators: Int,
    val oldest: Long,
    val newest: Long
) {
    override fun toString(): String {
        return """
            Database Statistics:
            - Total Towers: $total
            - Countries: $countries
            - Operators: $operators
            - Oldest Data: ${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date(oldest))}
            - Newest Data: ${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date(newest))}
        """.trimIndent()
    }
}