package app.dosekeeper.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.ripple
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import app.dosekeeper.app
import app.dosekeeper.core.DoseStatus
import app.dosekeeper.core.Supply
import app.dosekeeper.reminders.Notifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val heroDate = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())

@Composable
fun TodayScreen(onAddMedicine: () -> Unit, onOpenReliability: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val medicines by app.db.medicines().observeAll().collectAsState(initial = null)
    val profiles by app.db.profiles().observeAll().collectAsState(initial = emptyList())
    val since = remember { Instant.now().minus(Duration.ofDays(30)).toEpochMilli() }
    val logs by app.db.doseLogs().observeSince(since).collectAsState(initial = emptyList())
    var profileFilter by remember { mutableStateOf<Long?>(null) }
    var choosing by remember { mutableStateOf<TodayDose?>(null) }
    var unchecking by remember { mutableStateOf<TodayDose?>(null) }
    var refilling by remember { mutableStateOf<app.dosekeeper.data.Medicine?>(null) }
    var takingEarly by remember { mutableStateOf<TodayDose?>(null) }
    val snackbar = remember { SnackbarHostState() }

    // Re-evaluate late doses every 30 seconds, and catch up on anything missed while closed.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        app.engine.refreshAll()
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    val meds = medicines
    val model = remember(meds, profiles, logs, profileFilter, now) {
        meds?.let { buildToday(it, profiles, logs, profileFilter, Instant.ofEpochMilli(now)) }
    }
    val canRing = remember(now) { app.alarms.canScheduleExact() && app.notifier.notificationsAllowed() }

    val haptics = rememberHaptics()
    fun record(dose: TodayDose, status: DoseStatus) {
        if (status == DoseStatus.TAKEN) haptics.taken() else haptics.soft()
        scope.launch {
            app.engine.answer(dose.medicine.id, status)
            // Every answer can be taken back for a few seconds, in case of a slip of the thumb.
            snackbar.currentSnackbarData?.dismiss()
            val what = if (status == DoseStatus.TAKEN) "taken" else "skipped"
            val result = snackbar.showSnackbar("${dose.medicine.name} $what", actionLabel = "Undo", duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) app.engine.undo(dose.medicine.id, dose.due.toEpochMilli())
        }
    }
    fun answer(dose: TodayDose, status: DoseStatus) {
        // Taking a dose well before it's due asks first, so a stray swipe can't log it hours early.
        val early = Duration.between(Instant.now(), dose.due) > Duration.ofHours(1)
        if (status == DoseStatus.TAKEN && early) takingEarly = dose else record(dose, status)
    }
    fun snooze(dose: TodayDose) {
        haptics.soft()
        scope.launch { app.engine.snooze(dose.medicine.id) }
    }
    fun undo(dose: TodayDose) {
        haptics.soft()
        scope.launch { app.engine.undo(dose.medicine.id, dose.due.toEpochMilli()) }
    }

    // Sideways or on a short screen there's room for little more than a row, so the header shrinks
    // and the add button moves into it instead of floating over the list.
    val compact = LocalConfiguration.current.screenHeightDp < 480
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (!compact) {
                FloatingActionButton(onClick = onAddMedicine, shape = CircleShape, containerColor = MaterialTheme.colorScheme.primary) {
                    Icon(Icons.Filled.Add, "Add medicine")
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = if (compact) 16.dp else 96.dp),
        ) {
            item(key = "hero") {
                Hero(
                    model,
                    items = model?.remaining.orEmpty().map { CounterItem(it.id, Bottle.of(it.medForm)) },
                    compact = compact,
                    onAdd = onAddMedicine,
                )
            }
            if (!canRing) {
                item(key = "warn") {
                    Row(
                        Modifier.animateItem().padding(start = 24.dp, end = 24.dp, top = 16.dp).fillMaxWidth()
                            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(16.dp))
                            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Reminders may not ring on this phone yet.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onOpenReliability) { Text("Fix") }
                    }
                }
            }
            val low = meds.orEmpty()
                .filter { it.isActive && it.tracksSupply && (profileFilter == null || it.profileId == profileFilter) }
                .mapNotNull { med -> med.supplyOutlook(Instant.ofEpochMilli(now))?.takeIf { it.low }?.let { med to it } }
            low.forEach { (med, outlook) ->
                item(key = "low-${med.id}") {
                    LowSupplyCard(med, outlook, Modifier.animateItem(), onRefilled = { refilling = med })
                }
            }
            if (profiles.size > 1) {
                item(key = "filter") {
                    Segmented(
                        options = listOf<Long?>(null) + profiles.map { it.id },
                        selected = profileFilter,
                        label = { id -> profiles.firstOrNull { it.id == id }?.name ?: "Everyone" },
                        onSelect = { profileFilter = it },
                        modifier = Modifier.animateItem().padding(start = 24.dp, end = 24.dp, top = 16.dp),
                    )
                }
            }
            if (model == null) return@LazyColumn

            if (model.doses.isEmpty() && model.asNeeded.isEmpty()) {
                item(key = "empty") {
                    Text(
                        if (meds.isNullOrEmpty()) "No medicines yet. Tap + to add your first one."
                        else "Nothing due today.",
                        color = Muted,
                        modifier = Modifier.animateItem().padding(horizontal = 24.dp, vertical = 32.dp),
                    )
                }
            }

            // Every dose keeps one key wherever it sits, so taking it glides it down to Done.
            val late = model.doses.filter { it.overdue }
            if (late.isNotEmpty()) {
                item(key = "late-h") {
                    Row(Modifier.animateItem().padding(start = 24.dp, top = 24.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(Overdue, CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text("Late", style = MaterialTheme.typography.labelMedium, color = Overdue)
                    }
                }
                late.forEach { dose ->
                    item(key = dose.key) {
                        Box(Modifier.animateItem()) {
                            LateCard(dose, onTaken = { answer(dose, DoseStatus.TAKEN) }, onSnooze = { snooze(dose) }, onSkip = { answer(dose, DoseStatus.SKIPPED) })
                        }
                    }
                }
            }

            val rest = model.doses.filter { !it.overdue }
            var lastPart: String? = null
            for (dose in rest.filter { it.status == null }.sortedBy { it.due }) {
                val part = dose.due.partOfDayHeading()
                if (part != lastPart) {
                    lastPart = part
                    item(key = "part-$part") { TimeHeading(part, Modifier.animateItem()) }
                }
                item(key = dose.key) {
                    Box(Modifier.animateItem()) {
                        DoseRow(
                            dose, haptics,
                            onCircle = { answer(dose, DoseStatus.TAKEN) },
                            onClick = { choosing = dose },
                        )
                    }
                }
            }
            val done = rest.filter { it.status != null }.sortedBy { it.due }
            if (done.isNotEmpty()) {
                item(key = "done-h") { TimeHeading("Done", Modifier.animateItem()) }
                for (dose in done) {
                    item(key = dose.key) {
                        Box(Modifier.animateItem()) {
                            DoseRow(dose, haptics, onCircle = { undo(dose) }, onClick = { unchecking = dose })
                        }
                    }
                }
            }

            if (model.asNeeded.isNotEmpty()) {
                item(key = "an-h") { TimeHeading("As needed", Modifier.animateItem()) }
                model.asNeeded.forEach { row ->
                    item(key = "an-${row.medicine.id}") {
                        Box(Modifier.animateItem()) {
                            AsNeededRowView(row, onLog = {
                                haptics.taken()
                                scope.launch { app.engine.logAsNeeded(row.medicine.id) }
                            })
                        }
                    }
                }
            }
        }
    }

    refilling?.let { med ->
        RefillDialog(med, onDismiss = { refilling = null }, onRefill = { amount ->
            refilling = null
            haptics.soft()
            scope.launch { app.engine.refill(med.id, amount) }
        })
    }
    choosing?.let { dose ->
        AlertDialog(
            onDismissRequest = { choosing = null },
            title = { Text(dose.medicine.displayName) },
            text = { Text(listOfNotNull(dose.profile?.name, "due ${dose.due.localTime()}").joinToString(", ")) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { answer(dose, DoseStatus.TAKEN); choosing = null }) { Text("Taken") }
                    // Only a dose that's due can be snoozed. Snoozing one that isn't would just delay it.
                    if (!dose.due.isAfter(Instant.now())) TextButton(onClick = { snooze(dose); choosing = null }) { Text("Snooze") }
                    TextButton(onClick = { answer(dose, DoseStatus.SKIPPED); choosing = null }) { Text("Skip") }
                    TextButton(onClick = { choosing = null }) { Text("Cancel") }
                }
            },
        )
    }

    takingEarly?.let { dose ->
        AlertDialog(
            onDismissRequest = { takingEarly = null },
            title = { Text("Take ${dose.medicine.name} now?") },
            text = { Text("It isn't due until ${dose.due.friendlyDateTime().removePrefix("Today, ")}.") },
            confirmButton = { TextButton(onClick = { record(dose, DoseStatus.TAKEN); takingEarly = null }) { Text("Take now") } },
            dismissButton = { TextButton(onClick = { takingEarly = null }) { Text("Cancel") } },
        )
    }

    unchecking?.let { dose ->
        AlertDialog(
            onDismissRequest = { unchecking = null },
            title = { Text(dose.medicine.displayName) },
            text = {
                Text(
                    listOfNotNull(dose.profile?.name, "due ${dose.due.localTime()}").joinToString(", ") +
                        if (dose.status == DoseStatus.TAKEN) ". Taken at ${dose.at?.localTime() ?: ""}." else ". Skipped.",
                )
            },
            confirmButton = {
                TextButton(onClick = { undo(dose); unchecking = null }) {
                    Text(if (dose.status == DoseStatus.TAKEN) "Mark as not taken" else "Undo skip")
                }
            },
            dismissButton = { TextButton(onClick = { unchecking = null }) { Text("Cancel") } },
        )
    }
}

/** One key per dose, the same whether it is late, open or done. */
private val TodayDose.key: String get() = "dose-${medicine.id}-${due.toEpochMilli()}"

/** Morning, Afternoon, Evening or Bedtime, with the day in front when it isn't today. */
private fun Instant.partOfDayHeading(): String {
    val part = when (atZone(java.time.ZoneId.systemDefault()).hour) {
        in 4..11 -> "Morning"
        in 12..16 -> "Afternoon"
        in 17..20 -> "Evening"
        else -> "Bedtime"
    }
    return if (localDate() != LocalDate.now()) "${localDate().friendly()}, ${part.lowercase()}" else part
}

@Composable
private fun TimeHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 2.dp),
    )
}

@Composable
private fun Hero(model: TodayModel?, items: List<CounterItem>, compact: Boolean, onAdd: () -> Unit) {
    if (compact) {
        CompactHero(model, onAdd)
        return
    }
    CounterScene(items, Modifier.fillMaxWidth().height(206.dp).background(MaterialTheme.colorScheme.surfaceContainerLow), dark = MaterialTheme.colorScheme.background == Ink) {
        Column(Modifier.padding(start = 24.dp, top = 32.dp)) {
            Text(LocalDate.now().format(heroDate), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Today", style = MaterialTheme.typography.headlineLarge)
            if (model != null && model.scheduledCount > 0) {
                Spacer(Modifier.height(18.dp))
                val fraction by animateFloatAsState(
                    model.takenCount.toFloat() / model.scheduledCount,
                    spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow),
                    label = "progress",
                )
                Box(Modifier.width(136.dp).height(6.dp).background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(3.dp))) {
                    Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).background(MaterialTheme.colorScheme.secondary, RoundedCornerShape(3.dp)))
                }
                Spacer(Modifier.height(8.dp))
                // The count rolls up when a dose is taken, and back down on undo.
                AnimatedContent(
                    targetState = model.takenCount to model.scheduledCount,
                    transitionSpec = {
                        val up = targetState.first >= initialState.first
                        (slideInVertically { if (up) it else -it } + fadeIn()) togetherWith
                            (slideOutVertically { if (up) -it else it } + fadeOut())
                    },
                    label = "count",
                ) { (taken, total) ->
                    Text(
                        if (taken == total) "All taken" else "$taken of $total taken",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** One line for a short screen: Today, the count, and the add button. No counter art. */
@Composable
private fun CompactHero(model: TodayModel?, onAdd: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(start = 24.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Today", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.width(12.dp))
        Text(
            LocalDate.now().format(heroDate) + (model?.takeIf { it.scheduledCount > 0 }?.let { m ->
                if (m.takenCount == m.scheduledCount) " · All taken" else " · ${m.takenCount} of ${m.scheduledCount} taken"
            } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        androidx.compose.material3.FilledIconButton(onClick = onAdd) { Icon(Icons.Filled.Add, "Add medicine") }
    }
}

@Composable
private fun LateCard(dose: TodayDose, onTaken: () -> Unit, onSnooze: () -> Unit, onSkip: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.padding(horizontal = 24.dp, vertical = 4.dp).fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer, shape)
            .border(1.dp, Overdue.copy(alpha = 0.25f), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProfileBadge(dose.profile)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(dose.medicine.displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(dose.profile?.name, "due ${dose.due.friendlyDateTime().removePrefix("Today, ")}").joinToString(", ") +
                        (dose.snoozedUntil?.let { ", rings again at ${it.localTime()}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onTaken, modifier = Modifier.weight(1.5f).height(48.dp)) { Text("Taken") }
            OutlinedButton(onClick = onSnooze, modifier = Modifier.weight(1f).height(48.dp), contentPadding = PaddingValues(0.dp)) { Text("Snooze") }
            OutlinedButton(onClick = onSkip, modifier = Modifier.weight(1f).height(48.dp), contentPadding = PaddingValues(0.dp)) { Text("Skip") }
        }
    }
}

/**
 * A dose that isn't due yet. The medicine's current dose can be swiped right for Taken; letting go
 * before the threshold springs it back with nothing done. Due doses move up to the Late cards,
 * which have Snooze. There's no snooze swipe here, since snoozing a dose that isn't due would only
 * delay it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DoseRow(dose: TodayDose, haptics: Haptics, onCircle: () -> Unit, onClick: () -> Unit) {
    val currentDose by rememberUpdatedState(dose)
    val taken by rememberUpdatedState(onCircle)
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (currentDose.actionable && value == SwipeToDismissBoxValue.StartToEnd) taken()
            false
        },
        positionalThreshold = { it * 0.4f },
    )
    LaunchedEffect(state.targetValue) {
        if (state.targetValue != SwipeToDismissBoxValue.Settled) haptics.tick()
    }

    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = dose.actionable,
        enableDismissFromEndToStart = false,
        backgroundContent = { SwipeBackground(state) },
    ) {
        DoseRowContent(dose, onCircle, onClick)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeBackground(state: SwipeToDismissBoxState) {
    val scheme = MaterialTheme.colorScheme
    val direction = state.dismissDirection
    val armed = state.targetValue != SwipeToDismissBoxValue.Settled
    val color by animateColorAsState(
        when {
            direction == SwipeToDismissBoxValue.StartToEnd -> if (armed) scheme.secondary else scheme.secondary.copy(alpha = 0.55f)
            direction == SwipeToDismissBoxValue.EndToStart -> if (armed) scheme.outline else scheme.surfaceVariant
            else -> Color.Transparent
        },
        label = "swipe",
    )
    val iconScale by animateFloatAsState(if (armed) 1.15f else 0.85f, spring(dampingRatio = 0.45f), label = "swipeIcon")
    val towardsEnd = direction == SwipeToDismissBoxValue.StartToEnd
    Row(
        Modifier.fillMaxSize().background(color).padding(horizontal = 28.dp),
        horizontalArrangement = if (towardsEnd) Arrangement.Start else Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (direction == SwipeToDismissBoxValue.Settled) return@Row
        val tint = if (towardsEnd) scheme.onSecondary else scheme.onSurface
        if (!towardsEnd) Text("Snooze", color = tint, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 10.dp))
        Icon(
            if (towardsEnd) Icons.Filled.Check else Icons.Filled.Snooze,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(24.dp).graphicsLayer { scaleX = iconScale; scaleY = iconScale },
        )
        if (towardsEnd) Text("Taken", color = tint, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 10.dp))
    }
}

@Composable
private fun DoseRowContent(dose: TodayDose, onCircle: () -> Unit, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val done = dose.status != null
    val nameColor by animateColorAsState(if (done) scheme.onSurfaceVariant.copy(alpha = 0.7f) else scheme.onSurface, tween(300), label = "name")
    Column(Modifier.background(scheme.background).padding(horizontal = 24.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = dose.actionable || dose.undoable, onClick = onClick).padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProfileBadge(dose.profile, dimmed = done)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    dose.medicine.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = nameColor,
                    textDecoration = if (dose.status == DoseStatus.TAKEN) TextDecoration.LineThrough else null,
                )
                val time = dose.due.localTime()
                val sub = when (dose.status) {
                    DoseStatus.TAKEN -> "$time, taken at ${dose.at?.localTime() ?: ""}"
                    DoseStatus.SKIPPED -> "$time, skipped"
                    DoseStatus.MISSED -> "$time, missed"
                    null -> listOfNotNull(time, dose.profile?.name, dose.medicine.medForm.label.lowercase()).joinToString(", ") +
                        (dose.snoozedUntil?.let { ", rings again at ${it.localTime()}" } ?: "")
                }
                Text(sub, style = MaterialTheme.typography.bodySmall, color = if (dose.status == DoseStatus.MISSED) Overdue else scheme.onSurfaceVariant)
            }
            when {
                dose.status == DoseStatus.TAKEN -> CheckCircle(true, enabled = dose.undoable, label = "Mark as not taken", onClick = onCircle)
                dose.actionable -> CheckCircle(false, enabled = true, label = "Mark as taken", onClick = onCircle)
                dose.status == null -> Text("Later", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            }
        }
        HorizontalDivider(color = scheme.outlineVariant)
    }
}

/** The round check. Filling it springs out from the middle and the tick pops in after. */
@Composable
private fun CheckCircle(checked: Boolean, enabled: Boolean, label: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val fill by animateFloatAsState(
        if (checked) 1f else 0f,
        spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow),
        label = "fill",
    )
    val tick by animateFloatAsState(
        if (checked) 1f else 0f,
        spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessLow),
        label = "tick",
    )
    Box(
        Modifier.size(44.dp)
            .border(1.5.dp, scheme.outline.copy(alpha = (1f - fill).coerceIn(0f, 1f)), CircleShape)
            .clickable(
                enabled = enabled,
                onClickLabel = label,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = false, radius = 24.dp),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(44.dp).graphicsLayer { scaleX = fill; scaleY = fill }.background(scheme.secondary, CircleShape))
        Icon(
            Icons.Filled.Check,
            contentDescription = if (checked) "Taken" else null,
            tint = scheme.onSecondary,
            modifier = Modifier.size(22.dp).graphicsLayer {
                alpha = tick.coerceIn(0f, 1f)
                scaleX = tick
                scaleY = tick
            },
        )
    }
}

@Composable
private fun AsNeededRowView(row: AsNeededRow, onLog: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            ProfileBadge(row.profile)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(row.medicine.displayName, style = MaterialTheme.typography.titleMedium)
                val sub = when {
                    row.notBefore != null -> "Not before ${row.notBefore.localTime()}"
                    row.lastTaken != null -> "Last taken ${row.lastTaken.friendlyDateTime()}"
                    else -> "Not taken yet"
                }
                Text(sub, style = MaterialTheme.typography.bodySmall, color = if (row.notBefore != null) Overdue else scheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = onLog) { Text("Took it") }
        }
        HorizontalDivider(color = scheme.outlineVariant)
    }
}

/** "Time to refill" on Today, shown from about a week before a tracked supply runs out. */
@Composable
private fun LowSupplyCard(med: app.dosekeeper.data.Medicine, outlook: Supply.Outlook, modifier: Modifier, onRefilled: () -> Unit) {
    Row(
        modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp).fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Refill ${med.name}", style = MaterialTheme.typography.titleSmall)
            Text(supplyLine(outlook, med.medForm.unit, med.supplyLeft ?: 0.0), style = MaterialTheme.typography.bodySmall, color = Overdue)
        }
        TextButton(onClick = onRefilled) { Text("Refilled") }
    }
}

/** Adds a refill to the count. Offers the last refill size, since it's usually the same. */
@Composable
private fun RefillDialog(med: app.dosekeeper.data.Medicine, onDismiss: () -> Unit, onRefill: (Double) -> Unit) {
    var amount by remember { mutableStateOf(med.refillSize?.let(Notifier::formatAmount) ?: "") }
    val parsed = amount.replace(',', '.').trim().toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Refill ${med.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("How many ${med.medForm.unit} did you get? They're added to the ${Notifier.formatAmount(med.supplyLeft ?: 0.0)} you have left.")
                Field(
                    amount, { amount = it }, label = { Text("Amount (${med.medForm.unit})") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
        confirmButton = { TextButton(onClick = { parsed?.let(onRefill) }, enabled = parsed != null) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
