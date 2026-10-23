package app.dosekeeper.reminders

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import app.dosekeeper.app
import app.dosekeeper.core.DoseStatus
import app.dosekeeper.data.Medicine
import app.dosekeeper.data.Profile
import app.dosekeeper.ui.DoseKeeperTheme
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

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
            DoseKeeperTheme {
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
                            snoozeMinutes = app.settings.snoozeMinutes,
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
    snoozeMinutes: Int,
    onAnswer: (DoseStatus) -> Unit,
    onSnooze: () -> Unit,
) {
    val color = profile?.let { Color(it.colorArgb) } ?: MaterialTheme.colorScheme.primary
    val due = med.nextDueAt?.let {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.safeDrawingPadding().padding(24.dp).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(24.dp))
                if (profile != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.size(18.dp).background(color, CircleShape))
                        Text("  ${profile.name}", fontSize = 26.sp, color = color, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(16.dp))
                }
                Text(med.name, fontSize = 40.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                val detail = listOf(med.strength, med.medForm.label).filter { it.isNotBlank() }.joinToString(" · ")
                Text(detail, fontSize = 24.sp, textAlign = TextAlign.Center)
                if (due != null) Text("Due $due", fontSize = 22.sp, modifier = Modifier.padding(top = 8.dp))
                if (med.note.isNotBlank()) {
                    Text(med.note, fontSize = 20.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp))
                }
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { onAnswer(DoseStatus.TAKEN) },
                    modifier = Modifier.fillMaxWidth().height(88.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = color),
                ) { Text("Taken", fontSize = 30.sp) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onSnooze, modifier = Modifier.weight(1f).height(72.dp)) {
                        Text("Snooze $snoozeMinutes min", fontSize = 20.sp, textAlign = TextAlign.Center)
                    }
                    OutlinedButton(onClick = { onAnswer(DoseStatus.SKIPPED) }, modifier = Modifier.weight(1f).height(72.dp)) {
                        Text("Skip", fontSize = 20.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun TestAlarm(onDone: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().padding(24.dp).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Test alarm", fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Text(
                "If you can see and hear this, reminders will reach you, even with the screen locked.",
                fontSize = 22.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 24.dp),
            )
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(80.dp)) { Text("Done", fontSize = 28.sp) }
        }
    }
}
