package com.notebook.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.notebook.app.data.local.entity.ElementAssetReferenceEntity
import com.notebook.app.data.local.entity.PageAssetReferenceEntity

/**
 * STO-08 C2B-1: low-level ownership projection operations, not a repository API.
 *
 * Canonical page/element JSON remains the source of truth. Approved callers must
 * validate the canonical owner, finalized asset bytes, and expected prior edge,
 * then coordinate owner JSON, edge and revision writes within the SAME outer
 * AppDatabase.withTransaction. These primitives do not verify file bytes or
 * establish ownership-index completeness. No production caller is wired yet.
 *
 * A returned update/delete count of zero is a conflict, not a safe absence.
 * Neither a missing edge nor any gate status authorizes asset reclamation.
 */
@Dao
interface AssetOwnershipDao {
    @Query("SELECT * FROM page_asset_references WHERE page_id = :pageId LIMIT 1")
    suspend fun findPageReference(pageId: String): PageAssetReferenceEntity?

    @Query("SELECT * FROM element_asset_references WHERE element_id = :elementId LIMIT 1")
    suspend fun findElementReference(elementId: String): ElementAssetReferenceEntity?

    @Query("SELECT * FROM page_asset_references WHERE asset_id = :assetId ORDER BY page_id ASC")
    suspend fun listPageReferencesByAsset(assetId: String): List<PageAssetReferenceEntity>

    @Query("SELECT * FROM element_asset_references WHERE asset_id = :assetId ORDER BY element_id ASC")
    suspend fun listElementReferencesByAsset(assetId: String): List<ElementAssetReferenceEntity>

    /** ABORT, never IGNORE or REPLACE: an existing owner edge is a conflict. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPageReference(reference: PageAssetReferenceEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertElementReference(reference: ElementAssetReferenceEntity)

    /** Checked compare-and-set; caller must require exactly one changed row. */
    @Query(
        """UPDATE page_asset_references SET asset_id = :newAssetId
           WHERE page_id = :pageId AND asset_id = :expectedAssetId"""
    )
    suspend fun changePageAssetIfMatches(
        pageId: String, expectedAssetId: String, newAssetId: String,
    ): Int

    @Query(
        """UPDATE element_asset_references SET asset_id = :newAssetId
           WHERE element_id = :elementId AND asset_id = :expectedAssetId"""
    )
    suspend fun changeElementAssetIfMatches(
        elementId: String, expectedAssetId: String, newAssetId: String,
    ): Int

    /** Guarded release for a future approved transaction; NEVER delete bytes. */
    @Query(
        """DELETE FROM page_asset_references
           WHERE page_id = :pageId AND asset_id = :expectedAssetId"""
    )
    suspend fun removePageReferenceIfMatches(pageId: String, expectedAssetId: String): Int

    @Query(
        """DELETE FROM element_asset_references
           WHERE element_id = :elementId AND asset_id = :expectedAssetId"""
    )
    suspend fun removeElementReferenceIfMatches(elementId: String, expectedAssetId: String): Int

    /**
     * One-statement, fail-closed gate read. Empty, duplicate, invalid or future
     * VERIFIED states map to UNSAFE. UNVERIFIED and UNRESOLVED are diagnostic
     * states only; this DAO intentionally provides no 'may delete asset' method.
     */
    @Query(
        """SELECT CASE
               WHEN COUNT(*) != 1 OR MIN(id) != 1 THEN 'UNSAFE'
               WHEN MIN(state) = 'UNVERIFIED' THEN 'UNVERIFIED'
               WHEN MIN(state) = 'UNRESOLVED' THEN 'UNRESOLVED'
               ELSE 'UNSAFE'
           END
           FROM asset_reference_index_state"""
    )
    suspend fun readFailClosedGateStatus(): String
}
