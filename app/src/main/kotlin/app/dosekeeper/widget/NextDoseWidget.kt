package app.dosekeeper.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import app.dosekeeper.app
import app.dosekeeper.core.DoseStatus
import app.dosekeeper.data.DoseLog
import app.dosekeeper.data.Medicine
import app.dosekeeper.data.Profile
import app.dosekeeper.ui.MainActivity
import app.dosekeeper.ui.buildToday
import app.dosekeeper.ui.localTime
import app.dosekeeper.ui.takenLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.Instant

/** What the widget shows. Worked out from the same Today list the app shows. */
sealed interface WidgetView {
    /** The most recent dose taken today, such as "Lisinopril, taken at 8:02 AM". Null if none yet. */
    val lastTaken: String?

    /**
     * The next dose still open today. [canTake] is false while it's more than an hour away:
     * the app asks before logging a dose that early, and a widget button can't ask.
     */
    data class Dose(
        val medicineId: Long,
        val dueAt: Long,
        val name: String,
        val who: String?,
        val dueTime: String,
        val late: Boolean,
        val canTake: Boolean,
        override val lastTaken: String?,
    ) : WidgetView

    data class Clear(val message: String, override val lastTaken: String?) : WidgetView
}

fun buildWidgetView(medicines: List<Medicine>, profiles: List<Profile>, logs: List<DoseLog>, now: Instant): WidgetView {
    val today = buildToday(medicines, profiles, logs, profileFilter = null, now = now)
    val lastTaken = today.doses
        .filter { it.status == DoseStatus.TAKEN && it.at != null }
        .maxByOrNull { it.at!! }
        ?.let { "${it.medicine.name}, ${it.at!!.takenLabel().replaceFirstChar(Char::lowercase)}" }
    // Today's list is already in order: late doses first, then by time.
    val next = today.doses.firstOrNull { it.status == null && it.actionable }
        ?: return WidgetView.Clear(if (today.doses.isEmpty()) "Nothing due today" else "All done for today", lastTaken)
    return WidgetView.Dose(
        medicineId = next.medicine.id,
        dueAt = next.due.toEpochMilli(),
        name = next.medicine.displayName,
        who = next.profile?.name?.takeIf { profiles.size > 1 },
        dueTime = next.due.localTime(),
        late = next.overdue,
        canTake = Duration.between(now, next.due) <= Duration.ofHours(1),
        lastTaken = lastTaken,
    )
}

/**
 * The home screen widget: the next dose and one Taken button. Tapping Taken logs the dose
 * through the same engine as the notification's button, without opening the app.
 */
class NextDoseWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val db = context.app.db
        val since = Instant.now().minus(Duration.ofDays(2)).toEpochMilli()
        // Read once up front so the first frame is already right, then keep watching: while the
        // widget's session is alive, any change in the database redraws it by itself.
        val medicines0 = db.medicines().getAll()
        val profiles0 = db.profiles().getAll()
        val logs0 = db.doseLogs().observeSince(since).first()
        provideContent {
            val medicines by db.medicines().observeAll().collectAsState(initial = medicines0)
            val profiles by db.profiles().observeAll().collectAsState(initial = profiles0)
            val logs by db.doseLogs().observeSince(since).collectAsState(initial = logs0)
            // A dose turns late just by time passing, with nothing changing in the database.
            val tick by produceState(0) {
                while (true) {
                    delay(30_000)
                    value++
                }
            }
            val view = remember(medicines, profiles, logs, tick) { buildWidgetView(medicines, profiles, logs, Instant.now()) }
            Content(view)
        }
    }
}

class NextDoseWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NextDoseWidget()
}

/** The Taken button on the widget. */
class TakeDoseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val medicineId = parameters[MEDICINE_ID] ?: return
        val dueAt = parameters[DUE_AT] ?: return
        context.app.engine.answer(medicineId, DoseStatus.TAKEN, onlyIfDueAt = dueAt)
        NextDoseWidget().updateAll(context)
    }

    companion object {
        val MEDICINE_ID = ActionParameters.Key<Long>("medicineId")
        val DUE_AT = ActionParameters.Key<Long>("dueAt")
    }
}

// The app's own palette: white or graphite, grey text, one green. Every pair is at least 4.5:1.
private val surface = ColorProvider(day = Color.White, night = Color(0xFF15181C))
private val ink = ColorProvider(day = Color(0xFF15181C), night = Color.White)
private val muted = ColorProvider(day = Color(0xFF4B5563), night = Color(0xFFB4BAC2))
private val overdue = ColorProvider(day = Color(0xFFC42B1C), night = Color(0xFFFF8A80))
private val accent = ColorProvider(day = Color(0xFF1F6F5C), night = Color(0xFF7CC8B0))
private val onAccent = ColorProvider(day = Color.White, night = Color(0xFF15181C))

private val label = TextStyle(color = muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
private val title = TextStyle(color = ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
private val body = TextStyle(color = muted, fontSize = 14.sp)

@Composable
private fun Content(view: WidgetView) {
    // Tall enough for an extra line only when the widget has been made bigger than its smallest size.
    val roomy = LocalSize.current.height >= 150.dp
    Column(
        GlanceModifier.fillMaxSize()
            .appWidgetBackground()
            .background(surface)
            .cornerRadius(20.dp)
            .clickable(actionStartActivity(Intent(LocalContext.current, MainActivity::class.java)))
            .padding(12.dp),
    ) {
        when (view) {
            is WidgetView.Dose -> {
                // "Late" is written out and colored, so the color is never the only signal.
                Text(
                    when {
                        view.late -> "Late"
                        view.canTake -> "Due soon"
                        else -> "Next dose"
                    },
                    style = if (view.late) label.copy(color = overdue) else label,
                    maxLines = 1,
                )
                Text(view.name, style = title, maxLines = if (roomy) 2 else 1)
                Text(listOfNotNull("Due ${view.dueTime}", view.who).joinToString(" · "), style = body, maxLines = 1)
                Spacer(GlanceModifier.defaultWeight())
                if (roomy && view.lastTaken != null) {
                    Text("Last: ${view.lastTaken}", style = label, maxLines = 2, modifier = GlanceModifier.padding(bottom = 8.dp))
                }
                if (view.canTake) {
                    Box(
                        GlanceModifier.fillMaxWidth().height(48.dp)
                            .background(accent)
                            .cornerRadius(24.dp)
                            .semantics { contentDescription = "Mark ${view.name} as taken" }
                            .clickable(
                                actionRunCallback<TakeDoseAction>(
                                    actionParametersOf(TakeDoseAction.MEDICINE_ID to view.medicineId, TakeDoseAction.DUE_AT to view.dueAt),
                                ),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Taken", style = TextStyle(color = onAccent, fontSize = 16.sp, fontWeight = FontWeight.Bold))
                    }
                }
            }
            is WidgetView.Clear -> {
                Text("Today", style = label, maxLines = 1)
                Text(view.message, style = title, maxLines = 2)
                Spacer(GlanceModifier.defaultWeight())
                view.lastTaken?.let { Text("Last: $it", style = label, maxLines = 3) }
            }
        }
    }
}
