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

/** The outline drawn for a medicine, so two medicines can be told apart at a glance. */
enum class PillShape(val label: String) {
    ROUND("Round tablet"), CAPSULE("Capsule"), INJECTION("Injection");

    companion object {
        /** The picture a medicine gets until one is chosen. Liquids, drops and the rest have none. */
        fun defaultFor(form: MedForm): PillShape? = when (form) {
            MedForm.PILL -> ROUND
            MedForm.INJECTION -> INJECTION
            else -> null
        }
    }
}

/**
 * The few muted colors a pill picture can be filled with. Stored by name, so the color can also be
 * said in words ("blue capsule") and never has to be the only thing telling two medicines apart.
 */
enum class PillColor(val label: String, val argb: Long) {
    WHITE("White", 0xFFF4F4F1), YELLOW("Yellow", 0xFFE8D58E), ORANGE("Orange", 0xFFE5AE82), PINK("Pink", 0xFFE6B0BA),
    RED("Red", 0xFFCB7F78), BLUE("Blue", 0xFF93B4D8), GREEN("Green", 0xFF9FC6A6), PURPLE("Purple", 0xFFB5A6D6),
}

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
    /** A [PillShape] name. Null uses the default for the form. */
    val pillShape: String? = null,
    /** A [PillColor] name. Null draws the picture uncolored. */
    val pillColor: String? = null,
    /** Injection sites used in turn, one per line (see SiteRotation). Null when the medicine has none. */
    val injectionSites: String? = null,
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

    /** The picture to draw for this medicine, or null when its form has none and none was chosen. */
    val shape: PillShape?
        get() = pillShape?.let { runCatching { PillShape.valueOf(it) }.getOrNull() } ?: PillShape.defaultFor(medForm)
    val color: PillColor? get() = pillColor?.let { runCatching { PillColor.valueOf(it) }.getOrNull() }

    /** "blue capsule", for screen readers and anywhere the picture needs words. Null without a picture. */
    val looksLike: String?
        get() = shape?.let { s -> listOfNotNull(color?.label, s.label).joinToString(" ").lowercase() }

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
    /** Where an injection went, for medicines with [Medicine.injectionSites]. Null otherwise. */
    val site: String? = null,
) {
    val doseStatus: DoseStatus get() = DoseStatus.valueOf(status)
}
