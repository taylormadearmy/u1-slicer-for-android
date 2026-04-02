package com.u1.slicer.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SliceJobDao {
    @Query("SELECT * FROM slice_jobs ORDER BY timestamp DESC")
    fun getAll(): Flow<List<SliceJob>>

    @Insert
    suspend fun insert(job: SliceJob): Long

    @Delete
    suspend fun delete(job: SliceJob)

    @Query("DELETE FROM slice_jobs")
    suspend fun deleteAll()

    @Query("UPDATE slice_jobs SET sourcePath = :path WHERE id = :id")
    suspend fun updateSourcePath(id: Long, path: String)
}
