package app.dosekeeper.reminders

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import app.dosekeeper.app
import app.dosekeeper.core.DoseStatus
import app.dosekeeper.data.Medicine
import app.dosekeeper.data.Profile
import app.dosekeeper.ui.AlarmTheme
import app.dosekeeper.ui.Bottle
import app.dosekeeper.ui.CounterScene
import app.dosekeeper.ui.Ink
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

/** The full-screen alarm, shown over the lock screen when a dose is due. */
class AlarmActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        render(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        render(intent)
    }

    private fun render(intent: Intent) {
        val id = intent.getLongExtra(AlarmScheduler.EXTRA_MEDICINE_ID, 0L)
        setContent {
            AlarmTheme {
                if (id == AlarmScheduler.TEST_ID) {
                    TestAlarm(onDone = {
                        app.notifier.cancelTest()
                        finish()
                    })
                } else {
                    var loaded by remember { mutableStateOf<Pair<Medicine, Profile?>?>(null) }
                    LaunchedEffect(id) {
                        val med = app.db.medicines().get(id)
                        if (med?.nextDueAt == null) finish() else loaded = med to app.db.profiles().get(med.profileId)
                    }
                    loaded?.let { (med, profile) ->
                        DoseAlarm(
                            med, profile,
                            onAnswer = { act(id) { app.engine.answer(id, it) } },
                            onSnooze = { act(id) { app.engine.snooze(id) } },
                        )
                    }
                }
            }
        }
    }

    private fun act(id: Long, block: suspend () -> Unit) {
        lifecycleScope.launch {
            block()
            app.notifier.cancelDose(id)
            finish()
        }
    }

    @Suppress("DEPRECATION")
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    companion object {
        fun intent(context: Context, medicineId: Long): Intent =
            Intent(context, AlarmActivity::class.java)
                .putExtra(AlarmScheduler.EXTRA_MEDICINE_ID, medicineId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    }
}

@Composable
private fun DoseAlarm(
    med: Medicine,
    profile: Profile?,
    onAnswer: (DoseStatus) -> Unit,
    onSnooze: () -> Unit,
) {
    val context = LocalContext.current
    val due = med.nextDueAt?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()) }
    val is24h = DateFormat.is24HourFormat(context)
    val clock = due?.format(DateTimeFormatter.ofPattern(if (is24h) "H:mm" else "h:mm"))
    val marker = if (is24h) null else due?.format(DateTimeFormatter.ofPattern("a", Locale.getDefault()))
    AlarmScreen(
        topLine = profile?.let { "For ${it.name}" },
        clock = clock,
        marker = marker,
        title = med.name,
        detail = listOf(med.strength, med.medForm.label.lowercase()).filter { it.isNotBlank() }.joinToString(" "),
        note = med.note,
        bottle = Bottle.of(med.medForm),
    ) {
        Button(
            onClick = { onAnswer(DoseStatus.TAKEN) },
            modifier = Modifier.fillMaxWidth().height(76.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Ink),
        ) { Text("Taken", fontSize = 24.sp, fontWeight = FontWeight.SemiBold) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onSnooze,
                modifier = Modifier.weight(1f).height(60.dp),
                border = BorderStroke(1.dp, Color(0xFF3A3F46)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            ) { Text("Snooze", fontSize = 18.sp, textAlign = TextAlign.Center) }
            OutlinedButton(
                onClick = { onAnswer(DoseStatus.SKIPPED) },
                modifier = Modifier.weight(1f).height(60.dp),
                border = BorderStroke(1.dp, Color(0xFF3A3F46)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            ) { Text("Skip", fontSize = 18.sp) }
        }
    }
}

@Composable
private fun TestAlarm(onDone: () -> Unit) {
    AlarmScreen(
        topLine = "Test alarm",
        clock = null,
        marker = null,
        title = "It works",
        detail = "If you can see and hear this, reminders will reach you, even with the screen locked.",
        note = "",
        bottle = Bottle.PILL,
    ) {
        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth().height(76.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Ink),
        ) { Text("Done", fontSize = 24.sp, fontWeight = FontWeight.SemiBold) }
    }
}

/** Dark full screen: the medicine's bottle drawn large and faint behind big type and buttons. */
@Composable
private fun AlarmScreen(
    topLine: String?,
    clock: String?,
    marker: String?,
    title: String,
    detail: String,
    note: String,
    bottle: Bottle,
    buttons: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Ink)) {
        CounterScene(
            bottles = listOf(bottle),
            modifier = Modifier.fillMaxSize(),
            dark = true,
            scale = 2f,
            counterHeight = maxHeight * 0.34f,
            endPadding = 40.dp,
            tileSize = 64.dp,
            maxShown = 1,
        )
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(start = 24.dp, end = 24.dp, top = 40.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (topLine != null) Text(topLine, fontSize = 17.sp, color = Color(0xFF9CA3AF))
                if (clock != null) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(clock, style = MaterialTheme.typography.displayLarge, color = Color.White)
                        if (marker != null) Text(" $marker", fontSize = 32.sp, color = Color(0xFF9CA3AF), modifier = Modifier.padding(bottom = 10.dp))
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.displaySmall, color = Color.White)
                if (detail.isNotBlank()) Text(detail, fontSize = 21.sp, lineHeight = 28.sp, color = Color(0xFFD1D5DB))
                if (note.isNotBlank()) Text(note, fontSize = 18.sp, lineHeight = 26.sp, color = Color(0xFF9CA3AF), modifier = Modifier.padding(top = 8.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = buttons)
        }
    }
}
