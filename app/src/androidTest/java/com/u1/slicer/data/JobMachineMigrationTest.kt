package com.u1.slicer.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JobMachineMigrationTest {
    @Test fun migrationRetainsLegacyJobsAndAllowsExactNewIdentities() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .callback(object : SupportSQLiteOpenHelper.Callback(8) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE slice_jobs (id INTEGER PRIMARY KEY, modelName TEXT NOT NULL)")
                        db.execSQL("INSERT INTO slice_jobs VALUES (1, 'Legacy U1 job')")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build()
        )
        helper.use {
            val db = helper.writableDatabase
            AppDatabase.MIGRATION_8_9.migrate(db)
            db.query("SELECT modelName, sliceTarget, machineProfileFingerprint FROM slice_jobs WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Legacy U1 job", cursor.getString(0))
                assertTrue(cursor.isNull(1))
                assertTrue(cursor.isNull(2))
            }
            db.execSQL("INSERT INTO slice_jobs (id, modelName, sliceTarget, machineProfileFingerprint) VALUES (2, 'Voron job', 'GenericKlipper', 'exact-profile')")
            db.query("SELECT sliceTarget, machineProfileFingerprint FROM slice_jobs WHERE id = 2").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("GenericKlipper", cursor.getString(0))
                assertEquals("exact-profile", cursor.getString(1))
            }
        }
    }
}
