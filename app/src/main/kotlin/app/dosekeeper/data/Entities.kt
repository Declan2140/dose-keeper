package app.dosekeeper.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.dosekeeper.core.DoseStatus
import app.dosekeeper.core.Schedule
import app.dosekeeper.core.ScheduleCodec
import app.dosekeeper.core.TrackerState
import java.time.Instant

/** A person or pet whose medicines are tracked. */
@Entity(tableName = "profiles")
data class Profile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val colorArgb: Int,
    val icon: String,
    val sortOrder: Int = 0,
)

/** [unit] is what refill tracking counts: "12 pills left", "80 ml left". */
enum class MedForm(val label: String, val unit: String) {
    PILL("Pill", "pills"), LIQUID("Liquid", "ml"), DROPS("Drops", "drops"), INHALER("Inhaler", "puffs"),
    INJECTION("Injection", "doses"), CREAM("Cream", "doses"), OTHER("Other", "doses")
}

enum class MedStatus { ACTIVE, PAUSED, ARCHIVED }

@Entity(tableName = "medicines", indices = [Index("profileId")])
data class Medicine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val name: String,
    val strength: String = "",
    val form: String = MedForm.PILL.name,
    val note: String = "",
    /** [ScheduleCodec] text. */
    val schedule: String,
    val status: String = MedStatus.ACTIVE.name,
    val anchorAt: Long? = null,
    val nextDueAt: Long? = null,
    val snoozedUntil: Long? = null,
    /** A custom alarm sound (unlock feature). Null uses the default alarm sound. */
    val soundUri: String? = null,
    /** Refill tracking (unlock feature): how much is left, in [MedForm.unit]. Null when not tracked. */
    val supplyLeft: Double? = null,
    /** How much one dose uses, e.g. 2 pills or 5 ml. */
    @ColumnInfo(defaultValue = "1.0") val perDose: Double = 1.0,
    /** The size of the last refill, offered again next time. */
    val refillSize: Double? = null,
    /** Set once the "running low" notice has been shown, so it isn't repeated every dose. */
    @ColumnInfo(defaultValue = "0") val lowSupplyNotified: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val parsedSchedule: Schedule get() = ScheduleCodec.decode(schedule)
    val medForm: MedForm get() = runCatching { MedForm.valueOf(form) }.getOrDefault(MedForm.OTHER)
    val medStatus: MedStatus get() = runCatching { MedStatus.valueOf(status) }.getOrDefault(MedStatus.ACTIVE)
    val isActive: Boolean get() = medStatus == MedStatus.ACTIVE

    val tracker: TrackerState
        get() = TrackerState(anchorAt?.let(Instant::ofEpochMilli), nextDueAt?.let(Instant::ofEpochMilli), snoozedUntil?.let(Instant::ofEpochMilli))

    fun withTracker(state: TrackerState) = copy(
        anchorAt = state.anchor?.toEpochMilli(),
        nextDueAt = state.nextDue?.toEpochMilli(),
        snoozedUntil = state.snoozedUntil?.toEpochMilli(),
    )

    val tracksSupply: Boolean get() = supplyLeft != null

    /** "Amoxicillin 250 mg" */
    val displayName: String get() = if (strength.isBlank()) name else "$name $strength"
}

/** One line of history. [dueAt] is null for "as needed" doses. */
@Entity(
    tableName = "dose_logs",
    indices = [Index(value = ["medicineId", "dueAt"], unique = true), Index("actionAt")],
)
data class DoseLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val medicineId: Long,
    val dueAt: Long?,
    val status: String,
    val actionAt: Long,
) {
    val doseStatus: DoseStatus get() = DoseStatus.valueOf(status)
}
