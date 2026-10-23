package app.dosekeeper.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Every schema change bumps [version] and adds a migration here. Destructive migration is never
 * enabled: losing someone's medicine list or history on update is one of the complaints this
 * app exists to fix.
 */
@Database(entities = [Profile::class, Medicine::class, DoseLog::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profiles(): ProfileDao
    abstract fun medicines(): MedicineDao
    abstract fun doseLogs(): DoseLogDao

    companion object {
        const val NAME = "dosekeeper.db"

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .build()
    }
}
