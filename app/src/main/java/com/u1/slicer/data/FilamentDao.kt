package com.u1.slicer.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FilamentDao {
    @Query("SELECT * FROM filament_profiles ORDER BY name ASC")
    fun getAll(): Flow<List<FilamentProfile>>

    @Query("SELECT * FROM filament_profiles WHERE id = :id")
    suspend fun getById(id: Long): FilamentProfile?

    @Insert
    suspend fun insert(profile: FilamentProfile): Long

    @Update
    suspend fun update(profile: FilamentProfile)

    @Delete
    suspend fun delete(profile: FilamentProfile)

    @Query("UPDATE filament_profiles SET isDefault = 0")
    suspend fun clearAllDefaults()

    @Query("SELECT COUNT(*) FROM filament_profiles")
    suspend fun count(): Int
}
