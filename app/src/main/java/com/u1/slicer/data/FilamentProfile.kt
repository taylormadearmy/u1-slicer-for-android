package com.u1.slicer.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "filament_profiles")
data class FilamentProfile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val material: String,        // PLA, PETG, ABS, TPU, ASA, PA, PVA
    val nozzleTemp: Int,
    val bedTemp: Int,
    val retractLength: Float,
    val retractSpeed: Float,
    val color: String = "#808080", // Hex color for UI display
    val density: Float = 1.24f,    // g/cm3 for weight estimation
    val isDefault: Boolean = false
)
