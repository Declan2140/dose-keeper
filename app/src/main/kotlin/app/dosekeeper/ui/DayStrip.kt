package app.dosekeeper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** How many days the strip reaches back and ahead of today. */
const val DAYS_EACH_WAY = 3

private val spoken = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())

/**
 * A week of days with today in the middle. Tapping one shows that day: [selected] is how many days
 * it is from [today], negative for the past.
 */
@Composable
fun DayStrip(today: LocalDate, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    // With the phone's text made large, "Today" and "Wed" no longer fit seven across, so the
    // weekdays shrink to a letter. TalkBack still hears the whole date.
    val narrow = LocalDensity.current.fontScale >= 1.3f
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (offset in -DAYS_EACH_WAY..DAYS_EACH_WAY) {
            val date = today.plusDays(offset.toLong())
            val on = offset == selected
            val weekday = when {
                narrow -> date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault())
                offset == 0 -> "Today"
                else -> date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            }
            Column(
                Modifier.weight(1f).heightIn(min = 56.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (on) scheme.primary else Color.Transparent)
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(offset) })
                    .semantics { contentDescription = (if (offset == 0) "Today, " else "") + spoken.format(date) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    weekday,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (on) scheme.onPrimary else scheme.onSurfaceVariant,
                    maxLines = 1, softWrap = false,
                    modifier = Modifier.clearAndSetSemantics {},
                )
                Text(
                    date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) scheme.onPrimary else scheme.onSurface,
                    maxLines = 1, softWrap = false,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        }
    }
}
