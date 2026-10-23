package app.dosekeeper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Elderly
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.dosekeeper.app
import app.dosekeeper.data.Profile

/** Icons a profile can use. Stored by key so backups stay readable. */
val PROFILE_ICONS: Map<String, ImageVector> = linkedMapOf(
    "person" to Icons.Filled.Person,
    "child" to Icons.Filled.ChildCare,
    "elder" to Icons.Filled.Elderly,
    "face" to Icons.Filled.Face,
    "pet" to Icons.Filled.Pets,
)

@Composable
fun ProfileBadge(profile: Profile?, size: Dp = 36.dp) {
    val color = profile?.let { Color(it.colorArgb) } ?: MaterialTheme.colorScheme.primary
    Box(Modifier.size(size).background(color, CircleShape), contentAlignment = Alignment.Center) {
        Icon(PROFILE_ICONS[profile?.icon] ?: Icons.Filled.Person, contentDescription = profile?.name, tint = Color.White, modifier = Modifier.size(size * 0.6f))
    }
}

/** A row of chips for choosing one value. Wraps onto more lines with large text. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceChips(options: List<T>, selected: T?, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) })
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = modifier.padding(top = 20.dp, bottom = 8.dp))
}

/** Shown when a free user reaches a paid feature. The only place the unlock is mentioned. */
@Composable
fun UnlockDialog(reason: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val unlock = context.app.unlock
    val price by unlock.price.collectAsState()
    val unlocked by unlock.unlocked.collectAsState()
    if (unlocked) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Unlock Dose Keeper") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(reason)
                Text("One payment of ${price ?: "$5"}, no subscription. It unlocks unlimited profiles, full history, custom alarm sounds, and future features like refills and PDF export.")
                Text("Reminders, backups and every schedule type stay free for everyone.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { context.findActivity()?.let(unlock::buy) }) { Text("Unlock for ${price ?: "$5"}") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Not now") }
        },
    )
}

fun android.content.Context.findActivity(): android.app.Activity? {
    var c = this
    while (c is android.content.ContextWrapper) {
        if (c is android.app.Activity) return c
        c = c.baseContext
    }
    return null
}
