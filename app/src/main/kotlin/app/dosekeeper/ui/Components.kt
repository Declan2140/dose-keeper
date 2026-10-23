package app.dosekeeper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dosekeeper.app
import app.dosekeeper.data.Profile

/** Profile icon keys. Only "pet" changes the look; the rest show the name's initial. */
const val ICON_PERSON = "person"
const val ICON_PET = "pet"

/** A plain grey circle with the person's initial, or a paw for a pet. */
@Composable
fun ProfileBadge(profile: Profile?, size: Dp = 44.dp, dimmed: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val content = if (dimmed) scheme.onSurfaceVariant.copy(alpha = 0.6f) else scheme.onSurface
    Box(Modifier.size(size).background(scheme.surfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
        if (profile?.icon == ICON_PET) {
            Icon(Icons.Filled.Pets, contentDescription = profile.name, tint = content, modifier = Modifier.size(size * 0.48f))
        } else {
            Text(
                profile?.name?.trim()?.firstOrNull()?.uppercase() ?: "?",
                color = content,
                fontWeight = FontWeight.SemiBold,
                fontSize = (size.value * 0.4f).sp,
            )
        }
    }
}

/**
 * A segmented switch on a grey track, for choosing one of a few values. Falls back to
 * wrapping chips when there are too many options to share one row.
 */
@Composable
fun <T> Segmented(options: List<T>, selected: T?, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    if (options.size > 4) {
        ChoiceChips(options, selected, label, onSelect, modifier)
        return
    }
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().background(scheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val shape = RoundedCornerShape(9.dp)
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .then(if (isSelected) Modifier.shadow(1.dp, shape).background(scheme.surface, shape) else Modifier)
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(option) })
                    .padding(horizontal = 6.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) scheme.onSurface else scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Wrapping chips for choosing one value from a longer list. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceChips(options: List<T>, selected: T?, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) },
                shape = RoundedCornerShape(22.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = scheme.primary,
                    selectedLabelColor = scheme.onPrimary,
                ),
            )
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 24.dp, bottom = 8.dp),
    )
}

/** A flat grouped block on the grey fill, used instead of raised cards. */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(16.dp)) { content() }
    }
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
                Text("One payment of ${price ?: "$5"}, no subscription. It unlocks unlimited people and pets, full history, custom alarm sounds, and future features like refills and PDF export.")
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

/** A borderless text field on the grey fill, used for every form input. */
@Composable
fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    minLines: Int = 1,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
) {
    val scheme = MaterialTheme.colorScheme
    androidx.compose.material3.TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = label,
        placeholder = placeholder,
        supportingText = supportingText,
        keyboardOptions = keyboardOptions,
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        visualTransformation = visualTransformation,
        shape = RoundedCornerShape(12.dp),
        colors = androidx.compose.material3.TextFieldDefaults.colors(
            focusedContainerColor = scheme.surfaceVariant,
            unfocusedContainerColor = scheme.surfaceVariant,
            disabledContainerColor = scheme.surfaceVariant,
            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            focusedLabelColor = scheme.onSurfaceVariant,
            cursorColor = scheme.onSurface,
        ),
    )
}
