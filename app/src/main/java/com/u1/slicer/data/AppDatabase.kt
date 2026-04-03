package com.u1.slicer.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [FilamentProfile::class, SliceJob::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun filamentDao(): FilamentDao
    abstract fun sliceJobDao(): SliceJobDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** v1 → v2: add sourcePath column for durable source model storage (F61). */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE slice_jobs ADD COLUMN sourcePath TEXT")
            }
        }

        /** v2 → v3: add estimatedFilamentGrams column (B40 fix — show correct grams in summary). */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE slice_jobs ADD COLUMN estimatedFilamentGrams REAL NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "u1_slicer.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .addCallback(SeedCallback())
                    .build()
                INSTANCE = instance
                instance
            }
        }

        private class SeedCallback : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                INSTANCE?.let { database ->
                    CoroutineScope(Dispatchers.IO).launch {
                        seedDefaultFilaments(database.filamentDao())
                    }
                }
            }
        }

        private suspend fun seedDefaultFilaments(dao: FilamentDao) {
            val defaults = listOf(
                FilamentProfile(
                    name = "Generic PLA",
                    material = "PLA",
                    nozzleTemp = 210,
                    bedTemp = 60,
                    printSpeed = 60f,
                    retractLength = 0.8f,
                    retractSpeed = 45f,
                    color = "#4CAF50",
                    density = 1.24f,
                    isDefault = true
                ),
                FilamentProfile(
                    name = "Generic PETG",
                    material = "PETG",
                    nozzleTemp = 235,
                    bedTemp = 80,
                    printSpeed = 50f,
                    retractLength = 1.0f,
                    retractSpeed = 40f,
                    color = "#2196F3",
                    density = 1.27f,
                    isDefault = true
                ),
                FilamentProfile(
                    name = "Generic ABS",
                    material = "ABS",
                    nozzleTemp = 245,
                    bedTemp = 100,
                    printSpeed = 50f,
                    retractLength = 0.8f,
                    retractSpeed = 45f,
                    color = "#FF9800",
                    density = 1.04f,
                    isDefault = true
                ),
                FilamentProfile(
                    name = "Generic TPU",
                    material = "TPU",
                    nozzleTemp = 220,
                    bedTemp = 50,
                    printSpeed = 25f,
                    retractLength = 0.5f,
                    retractSpeed = 25f,
                    color = "#9C27B0",
                    density = 1.21f,
                    isDefault = true
                ),
                FilamentProfile(
                    name = "Generic ASA",
                    material = "ASA",
                    nozzleTemp = 250,
                    bedTemp = 100,
                    printSpeed = 50f,
                    retractLength = 0.8f,
                    retractSpeed = 45f,
                    color = "#F44336",
                    density = 1.07f,
                    isDefault = true
                ),
                FilamentProfile(
                    name = "Generic PA (Nylon)",
                    material = "PA",
                    nozzleTemp = 260,
                    bedTemp = 80,
                    printSpeed = 40f,
                    retractLength = 1.2f,
                    retractSpeed = 40f,
                    color = "#FFEB3B",
                    density = 1.14f,
                    isDefault = true
                ),
                FilamentProfile(
                    name = "Generic PVA",
                    material = "PVA",
                    nozzleTemp = 200,
                    bedTemp = 50,
                    printSpeed = 30f,
                    retractLength = 1.0f,
                    retractSpeed = 25f,
                    color = "#BDBDBD",
                    density = 1.23f,
                    isDefault = true
                )
            )
            defaults.forEach { dao.insert(it) }
        }
    }
}
