package app.dosekeeper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** First launch: no sign-up. A one-screen welcome, the permissions with one line each, then a medicine. */
@Composable
fun Onboarding(onFinished: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (step == 0) {
                Spacer(Modifier.height(24.dp))
                Text("Dose Keeper", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text("Medicine reminders that ring every time.", style = MaterialTheme.typography.titleLarge)
                Text("No account. No internet needed. Your data stays on this phone.")
                Text("Track medicines for yourself, your family and your pets, on set times, every few hours, or as needed.")
                Text(
                    "Dose Keeper records and reminds only. It doesn't give medical advice. Always follow your doctor's, pharmacist's or vet's instructions.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { step = 1 }, Modifier.fillMaxWidth().height(56.dp)) { Text("Get started") }
            } else {
                Text("Let reminders ring", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Android blocks alarms until you allow them. Tap each red item.")
                val checks = rememberChecks()
                checks.forEach { CheckRow(it) }
                Spacer(Modifier.height(12.dp))
                Button(onClick = onFinished, Modifier.fillMaxWidth().height(56.dp)) { Text("Add my first medicine") }
                if (checks.any { !it.ok }) {
                    TextButton(onClick = onFinished, Modifier.fillMaxWidth()) { Text("I'll do this later in Settings") }
                }
            }
        }
    }
}
