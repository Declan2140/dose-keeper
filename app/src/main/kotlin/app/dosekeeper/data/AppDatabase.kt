package app.dosekeeper.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Every schema change bumps [version] and adds a migration here. Destructive migration is never
 * enabled: losing someone's medicine list or history on update is one of the complaints this
 * app exists to fix.
 */
@Database(entities = [Profile::class, Medicine::class, DoseLog::class], version = 3, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profiles(): ProfileDao
    abstract fun medicines(): MedicineDao
    abstract fun doseLogs(): DoseLogDao

    companion object {
        const val NAME = "dosekeeper.db"

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()

        /** Refill tracking. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE medicines ADD COLUMN supplyLeft REAL")
                db.execSQL("ALTER TABLE medicines ADD COLUMN perDose REAL NOT NULL DEFAULT 1.0")
                db.execSQL("ALTER TABLE medicines ADD COLUMN refillSize REAL")
                db.execSQL("ALTER TABLE medicines ADD COLUMN lowSupplyNotified INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Pill pictures, and room for injection-site rotation. Every column is optional. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE medicines ADD COLUMN pillShape TEXT")
                db.execSQL("ALTER TABLE medicines ADD COLUMN pillColor TEXT")
                db.execSQL("ALTER TABLE medicines ADD COLUMN injectionSites TEXT")
                db.execSQL("ALTER TABLE dose_logs ADD COLUMN site TEXT")
            }
        }
    }
}
