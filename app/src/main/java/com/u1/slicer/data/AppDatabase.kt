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
    version = 6,
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

        /**
         * v3 → v4: drop printSpeed from filament_profiles.
         * Speed is a process concern (not per-filament); the column was never meaningfully used.
         * SQLite pre-3.35 doesn't support DROP COLUMN, so we recreate the table.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE filament_profiles_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        material TEXT NOT NULL,
                        nozzleTemp INTEGER NOT NULL,
                        bedTemp INTEGER NOT NULL,
                        retractLength REAL NOT NULL,
                        retractSpeed REAL NOT NULL,
                        color TEXT NOT NULL DEFAULT '#808080',
                        density REAL NOT NULL DEFAULT 1.24,
                        isDefault INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO filament_profiles_new
                        (id, name, material, nozzleTemp, bedTemp, retractLength, retractSpeed, color, density, isDefault)
                    SELECT id, name, material, nozzleTemp, bedTemp, retractLength, retractSpeed, color, density, isDefault
                    FROM filament_profiles
                """.trimIndent())
                db.execSQL("DROP TABLE filament_profiles")
                db.execSQL("ALTER TABLE filament_profiles_new RENAME TO filament_profiles")
            }
        }

        /**
         * v4 → v5: add canonicalListSize + colorMappingCsv columns to
         * slice_jobs (Phase 2, 2026-04-28). Lets `shareJobGcode()`
         * reproduce the canonical→physical mapping when sharing a
         * historical job. Both columns nullable; pre-v5 rows correspond
         * to v1.6.13-era jobs whose stored G-code is already physical-
         * slot, so null → identity copy on share.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE slice_jobs ADD COLUMN canonicalListSize INTEGER")
                db.execSQL("ALTER TABLE slice_jobs ADD COLUMN colorMappingCsv TEXT")
            }
        }

        /**
         * v5 → v6: add selectedExtruderAtSlice column to slice_jobs
         * (Phase 2 post-round-2-review, 2026-04-28). Persists the
         * physical slot picked for single-colour jobs at slice time so
         * `shareJobGcode` can remap T0 → selected slot instead of
         * defaulting to slot 0 (Reviewer 3 P1 finding).
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE slice_jobs ADD COLUMN selectedExtruderAtSlice INTEGER")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "u1_slicer.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
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
            // Temperatures from Snapmaker/OrcaSlicer Generic profiles (Snapmaker GitHub)
            val defaults = listOf(
                FilamentProfile(
                    name = "Generic PLA",
                    material = "PLA",
                    nozzleTemp = 220,
                    bedTemp = 55,
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
                    bedTemp = 70,
                    retractLength = 1.0f,
                    retractSpeed = 40f,
                    color = "#2196F3",
                    density = 1.27f,
                    isDefault = true
                ),
                FilamentProfile(
                    name = "Generic ABS",
                    material = "ABS",
                    nozzleTemp = 270,
                    bedTemp = 90,
                    retractLength = 0.8f,
                    retractSpeed = 45f,
                    color = "#FF9800",
                    density = 1.04f,
                    isDefault = true
                ),
                FilamentProfile(
                    name = "Generic TPU",
                    material = "TPU",
                    nozzleTemp = 225,
                    bedTemp = 60,
                    retractLength = 0.5f,
                    retractSpeed = 25f,
                    color = "#9C27B0",
                    density = 1.21f,
                    isDefault = true
                ),
                FilamentProfile(
                    name = "Generic ASA",
                    material = "ASA",
                    nozzleTemp = 260,
                    bedTemp = 90,
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
                    bedTemp = 100,
                    retractLength = 1.2f,
                    retractSpeed = 40f,
                    color = "#FFEB3B",
                    density = 1.14f,
                    isDefault = true
                ),
                FilamentProfile(
                    name = "Generic PVA",
                    material = "PVA",
                    nozzleTemp = 210,
                    bedTemp = 65,
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
