package app.dosekeeper.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<Profile>>

    @Query("SELECT * FROM profiles ORDER BY sortOrder, id")
    suspend fun getAll(): List<Profile>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun get(id: Long): Profile?

    @Query("SELECT COUNT(*) FROM profiles")
    suspend fun count(): Int

    @Insert
    suspend fun insert(profile: Profile): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(profiles: List<Profile>)

    @Update
    suspend fun update(profile: Profile)

    @Delete
    suspend fun delete(profile: Profile)

    @Query("DELETE FROM profiles")
    suspend fun deleteAll()
}

@Dao
interface MedicineDao {
    @Query("SELECT * FROM medicines ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<Medicine>>

    @Query("SELECT * FROM medicines ORDER BY name COLLATE NOCASE")
    suspend fun getAll(): List<Medicine>

    @Query("SELECT * FROM medicines WHERE id = :id")
    suspend fun get(id: Long): Medicine?

    @Query("SELECT COUNT(*) FROM medicines WHERE profileId = :profileId AND status != 'ARCHIVED'")
    suspend fun countForProfile(profileId: Long): Int

    @Insert
    suspend fun insert(medicine: Medicine): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(medicines: List<Medicine>)

    @Update
    suspend fun update(medicine: Medicine)

    @Query("DELETE FROM medicines")
    suspend fun deleteAll()
}

@Dao
interface DoseLogDao {
    @Query("SELECT * FROM dose_logs WHERE actionAt >= :from OR dueAt >= :from ORDER BY COALESCE(dueAt, actionAt) DESC")
    fun observeSince(from: Long): Flow<List<DoseLog>>

    @Query("SELECT * FROM dose_logs ORDER BY id")
    suspend fun getAll(): List<DoseLog>

    @Query("SELECT * FROM dose_logs WHERE medicineId = :medicineId AND status = 'TAKEN' ORDER BY actionAt DESC LIMIT 1")
    suspend fun lastTaken(medicineId: Long): DoseLog?

    /** The latest scheduled dose on record for a medicine, answered or missed. */
    @Query("SELECT * FROM dose_logs WHERE medicineId = :medicineId AND dueAt IS NOT NULL ORDER BY dueAt DESC LIMIT 1")
    suspend fun lastScheduled(medicineId: Long): DoseLog?

    @Query("SELECT COUNT(*) FROM dose_logs WHERE COALESCE(dueAt, actionAt) < :before")
    fun observeCountBefore(before: Long): Flow<Int>

    /** Ignores a second entry for the same scheduled dose, so missed doses are never logged twice. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(log: DoseLog): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(logs: List<DoseLog>)

    @Update
    suspend fun update(log: DoseLog)

    @Delete
    suspend fun delete(log: DoseLog)

    @Query("DELETE FROM dose_logs")
    suspend fun deleteAll()
}
