package com.u1.slicer.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "slice_jobs")
data class SliceJob(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val modelName: String,
    val gcodePath: String,
    /** Absolute path to the durable copy of the source 3MF/STL in files/jobs/<id>/. Null for jobs
     *  saved before this column was added (schema v2). */
    val sourcePath: String? = null,
    val totalLayers: Int,
    val estimatedTimeSeconds: Float,
    val estimatedFilamentMm: Float,
    val layerHeight: Float,
    val fillDensity: Float,
    val nozzleTemp: Int,
    val bedTemp: Int,
    val supportEnabled: Boolean,
    val filamentType: String,
    val timestamp: Long = System.currentTimeMillis()
)
