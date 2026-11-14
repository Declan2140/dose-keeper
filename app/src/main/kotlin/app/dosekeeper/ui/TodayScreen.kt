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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import app.dosekeeper.data.Medicine
import kotlinx.coroutines.coroutineScope
import kotlin.math.abs
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

    // The day on show, counted from today: -1 is yesterday. [day] is the one that was asked for;
    // [shownDay] follows once the old day's rows have slid away.
    val reduced = LocalReducedMotion.current
    var day by rememberSaveable { mutableIntStateOf(0) }
    var shownDay by rememberSaveable { mutableIntStateOf(0) }
    val motion = remember { ListMotion(entered = entrancePlayed) }
    val today = remember(now) { Instant.ofEpochMilli(now).localDate() }

    // Once per app open, the header fades in and the rows rise into place one after another.
    LaunchedEffect(Unit) {
        if (entrancePlayed) return@LaunchedEffect
        entrancePlayed = true
        motion.entrance.animateTo(1f, tween(if (reduced) Motion.FADE_MS else ListMotion.ENTRANCE_MS.toInt(), easing = LinearEasing))
    }
    // A later day comes in from the right and an earlier one from the left, like turning to it on
    // a calendar. Asking for another day partway through carries on from wherever the rows are.
    LaunchedEffect(day) {
        if (day != shownDay) {
            val forward = if (day > shownDay) 1f else -1f
            coroutineScope {
                launch { motion.alpha.animateTo(0f, tween(if (reduced) Motion.FADE_MS else 110)) }
                if (!reduced) motion.slide.animateTo(-forward * 0.25f, tween(110, easing = FastOutLinearInEasing))
            }
            shownDay = day
            motion.slide.snapTo(if (reduced) 0f else forward * 0.6f)
        }
        coroutineScope {
            launch { motion.alpha.animateTo(1f, tween(if (reduced) Motion.FADE_MS else 160)) }
            motion.slide.animateTo(0f, Motion.smooth())
        }
    }

    val meds = medicines
    val model = remember(meds, profiles, logs, profileFilter, now, shownDay) {
        meds?.let {
            val at = Instant.ofEpochMilli(now)
            if (shownDay == 0) buildToday(it, profiles, logs, profileFilter, at)
            else buildDay(it, profiles, logs, profileFilter, at.localDate().plusDays(shownDay.toLong()), at)
        }
    }
    val onToday = shownDay == 0
    // With one person every row would show the same initial, so the medicine's picture leads instead.
    val showPerson = profiles.size > 1
    val canRing = remember(now) { app.alarms.canScheduleExact() && app.notifier.notificationsAllowed() }

    val haptics = rememberHaptics()
    fun record(dose: TodayDose, status: DoseStatus) {
        // The Taken buzz is held for a moment so it's felt just as the tick lands.
        if (status == DoseStatus.TAKEN) scope.launch { delay(Motion.TICK_LANDS_MS); haptics.taken() } else haptics.soft()
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
            // Rows that belong to the day on show. They rise in as the app opens, slide when the
            // day changes, and glide to their new place when a dose is answered.
            var slot = 1
            fun dayItem(key: Any, content: @Composable () -> Unit) {
                val index = slot++
                item(key = key) { Box(moving(reduced).listMotion(motion, index, reduced)) { content() } }
            }
            // A heading can come round twice in one day (a 2 AM dose and a 10 PM dose are both
            // "Bedtime"), and every row in a list needs a key of its own.
            val headings = mutableMapOf<String, Int>()
            fun headingKey(part: String): String {
                val nth = (headings[part] ?: 0) + 1
                headings[part] = nth
                return "part-$part-$nth"
            }

            item(key = "hero") {
                Hero(
                    model,
                    items = model?.remaining.orEmpty().map { CounterItem(it.id, Bottle.of(it.medForm)) },
                    compact = compact,
                    onAdd = onAddMedicine,
                    date = today.plusDays(shownDay.toLong()),
                    dayOffset = shownDay,
                    motion = motion,
                    onShift = { by -> day = (day + by).coerceIn(-DAYS_EACH_WAY, DAYS_EACH_WAY) },
                )
            }
            item(key = "days") {
                Column(Modifier.listMotion(motion, 0, reduced, changesWithDay = false)) {
                    DayStrip(today, selected = day, onSelect = { day = it })
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            if (!canRing && onToday) {
                dayItem("warn") {
                    Row(
                        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).fillMaxWidth()
                            .background(MaterialTheme.colorScheme.errorContainer, CardShape)
                            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Reminders may not ring on this phone yet.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onOpenReliability) { Text("Fix") }
                    }
                }
            }
            if (onToday) {
                val low = meds.orEmpty()
                    .filter { it.isActive && it.tracksSupply && (profileFilter == null || it.profileId == profileFilter) }
                    .mapNotNull { med -> med.supplyOutlook(Instant.ofEpochMilli(now))?.takeIf { it.low }?.let { med to it } }
                low.forEach { (med, outlook) ->
                    dayItem("low-${med.id}") { LowSupplyCard(med, outlook, onRefilled = { refilling = med }) }
                }
            }
            if (profiles.size > 1) {
                item(key = "filter") {
                    Segmented(
                        options = listOf<Long?>(null) + profiles.map { it.id },
                        selected = profileFilter,
                        label = { id -> profiles.firstOrNull { it.id == id }?.name ?: "Everyone" },
                        onSelect = { profileFilter = it },
                        modifier = moving(reduced).listMotion(motion, 0, reduced, changesWithDay = false).padding(start = 16.dp, end = 16.dp, top = 16.dp),
                    )
                }
            }
            if (model == null) return@LazyColumn

            if (model.doses.isEmpty() && model.asNeeded.isEmpty() && model.timed.isEmpty()) {
                dayItem("empty") {
                    Text(
                        when {
                            meds.isNullOrEmpty() -> "No medicines yet. Tap + to add your first one."
                            shownDay < 0 -> "Nothing was recorded on this day."
                            shownDay > 0 -> "Nothing is scheduled for this day."
                            else -> "Nothing due today."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 32.dp),
                    )
                }
            }

            if (!onToday) {
                // Another day is for looking at: what was taken, or what's coming. Changes are made on Today.
                dayItem("note-${shownDay.compareTo(0)}") {
                    Text(
                        if (shownDay < 0) "Looking back. Doses are marked on Today." else "A preview of what's scheduled.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp),
                    )
                }
                var lastPart: String? = null
                for (dose in model.doses) {
                    val part = dose.due.partOfDay()
                    if (part != lastPart) {
                        lastPart = part
                        dayItem(headingKey(part)) { TimeHeading(part) }
                    }
                    dayItem(dose.key) { DoseRow(dose, haptics, showPerson, preview = true, onCircle = {}, onClick = {}) }
                }
                if (model.timed.isNotEmpty()) {
                    dayItem("timed-h") { TimeHeading("On a timer") }
                    model.timed.forEach { (med, profile) ->
                        dayItem("timed-${med.id}") { TimedRow(med, profile, showPerson) }
                    }
                }
                return@LazyColumn
            }

            // Every dose keeps one key wherever it sits, so taking it glides it down to Done.
            val late = model.doses.filter { it.overdue }
            if (late.isNotEmpty()) {
                dayItem("late-h") {
                    Row(Modifier.padding(start = 24.dp, top = 28.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.error, CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text("Late", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                    }
                }
                late.forEach { dose ->
                    dayItem(dose.key) {
                        LateCard(dose, showPerson, onTaken = { answer(dose, DoseStatus.TAKEN) }, onSnooze = { snooze(dose) }, onSkip = { answer(dose, DoseStatus.SKIPPED) })
                    }
                }
            }

            val rest = model.doses.filter { !it.overdue }
            var lastPart: String? = null
            for (dose in rest.filter { it.status == null }.sortedBy { it.due }) {
                val part = dose.due.partOfDayHeading()
                if (part != lastPart) {
                    lastPart = part
                    dayItem(headingKey(part)) { TimeHeading(part) }
                }
                dayItem(dose.key) {
                    DoseRow(dose, haptics, showPerson, onCircle = { answer(dose, DoseStatus.TAKEN) }, onClick = { choosing = dose })
                }
            }
            val done = rest.filter { it.status != null }.sortedBy { it.due }
            if (done.isNotEmpty()) {
                dayItem("done-h") { TimeHeading("Done") }
                for (dose in done) {
                    dayItem(dose.key) { DoseRow(dose, haptics, showPerson, onCircle = { undo(dose) }, onClick = { unchecking = dose }) }
                }
            }

            if (model.asNeeded.isNotEmpty()) {
                dayItem("an-h") { TimeHeading("As needed") }
                model.asNeeded.forEach { row ->
                    dayItem("an-${row.medicine.id}") {
                        AsNeededRowView(row, showPerson, onLog = {
                            scope.launch { delay(Motion.TICK_LANDS_MS); haptics.taken() }
                            scope.launch { app.engine.logAsNeeded(row.medicine.id) }
                        })
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

/** Set once the rows have made their entrance, so it plays once per app open and not on every return to the tab. */
private var entrancePlayed = false

/** Dose cards are fully rounded at the ends, like a pill. */
private val CardShape = RoundedCornerShape(24.dp)

/** How far a finished dose's name fades back. On the card it still passes 4.5:1 in both themes. */
private const val DONE_NAME_ALPHA = 0.72f

/**
 * How a row arrives, leaves and moves to a new place in the list. It only ever slides and fades;
 * with animations removed it fades and takes its new place at once.
 */
private fun LazyItemScope.moving(reduced: Boolean): Modifier =
    if (reduced) Modifier.animateItem(fadeInSpec = Motion.fade(), placementSpec = null, fadeOutSpec = Motion.fade())
    else Modifier.animateItem(placementSpec = Motion.glide(IntOffset.VisibilityThreshold))

/** Morning, Afternoon, Evening or Bedtime. */
private fun Instant.partOfDay(): String = when (atZone(java.time.ZoneId.systemDefault()).hour) {
    in 4..11 -> "Morning"
    in 12..16 -> "Afternoon"
    in 17..20 -> "Evening"
    else -> "Bedtime"
}

/** The part of the day, with the day in front when it isn't today. */
private fun Instant.partOfDayHeading(): String {
    val part = partOfDay()
    return if (localDate() != LocalDate.now()) "${localDate().friendly()}, ${part.lowercase()}" else part
}

/** "Today", "Yesterday", "Tomorrow", or the weekday for days further off. */
private fun dayTitle(offset: Int, date: LocalDate): String = when (offset) {
    0 -> "Today"
    -1 -> "Yesterday"
    1 -> "Tomorrow"
    else -> date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault())
}

/** "2 of 5 taken" for today and past days, "5 scheduled" for a coming one. Null when the day is empty. */
private fun TodayModel.statusLine(dayOffset: Int): String? = when {
    scheduledCount == 0 -> null
    dayOffset > 0 -> "$scheduledCount scheduled"
    takenCount == scheduledCount -> "All taken"
    else -> "$takenCount of $scheduledCount taken"
}

@Composable
private fun TimeHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 5.dp),
    )
}

@Composable
private fun Hero(
    model: TodayModel?,
    items: List<CounterItem>,
    compact: Boolean,
    onAdd: () -> Unit,
    date: LocalDate,
    dayOffset: Int,
    motion: ListMotion,
    onShift: (Int) -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val title = dayTitle(dayOffset, date)
    val status = model?.statusLine(dayOffset)
    if (compact) {
        CompactHero(title, date, status, onAdd)
        return
    }
    // Swiping across the picture moves a day at a time, the same way the days sit in the strip below.
    val shift by rememberUpdatedState(onShift)
    val swipe = Modifier.pointerInput(Unit) {
        var dragged = 0f
        detectHorizontalDragGestures(
            onDragStart = { dragged = 0f },
            onDragEnd = { if (abs(dragged) > 56.dp.toPx()) shift(if (dragged < 0) 1 else -1) },
        ) { _, amount -> dragged += amount }
    }
    CounterScene(items, Modifier.fillMaxWidth().height(206.dp).background(MaterialTheme.colorScheme.surfaceContainerLow).then(swipe), dark = MaterialTheme.colorScheme.background == Ink) {
        Column(Modifier.padding(start = 24.dp, top = 32.dp).listMotion(motion, 0, reduced, travel = 0.12f)) {
            Text(date.format(heroDate), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.headlineLarge)
            if (model != null && status != null) {
                Spacer(Modifier.height(18.dp))
                if (dayOffset <= 0) {
                    val fraction by animateFloatAsState(
                        model.takenCount.toFloat() / model.scheduledCount,
                        if (reduced) Motion.fade() else spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow),
                        label = "progress",
                    )
                    // The bar grows by stretching from its starting edge, which costs nothing to draw.
                    // Changing its width would make the header measure itself again every frame.
                    val fromRight = LocalLayoutDirection.current == LayoutDirection.Rtl
                    Box(Modifier.width(136.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.outlineVariant)) {
                        Box(
                            Modifier.fillMaxSize()
                                .graphicsLayer {
                                    scaleX = fraction.coerceIn(0f, 1f)
                                    transformOrigin = TransformOrigin(if (fromRight) 1f else 0f, 0.5f)
                                }
                                .background(MaterialTheme.colorScheme.secondary),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
                // The count rolls up when a dose is taken, and back down on undo.
                AnimatedContent(
                    targetState = model.takenCount to status,
                    transitionSpec = {
                        if (reduced) {
                            ContentTransform(fadeIn(Motion.fade()), fadeOut(Motion.fade()), sizeTransform = null)
                        } else {
                            val up = targetState.first >= initialState.first
                            ContentTransform(
                                slideInVertically { if (up) it else -it } + fadeIn(),
                                slideOutVertically { if (up) -it else it } + fadeOut(),
                                sizeTransform = null,
                            )
                        }
                    },
                    label = "count",
                ) { (_, text) ->
                    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** One line for a short screen: the day, the count, and the add button. No counter art. */
@Composable
private fun CompactHero(title: String, date: LocalDate, status: String?, onAdd: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(start = 24.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.width(12.dp))
        Text(
            date.format(heroDate) + (status?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        androidx.compose.material3.FilledIconButton(onClick = onAdd) { Icon(Icons.Filled.Add, "Add medicine") }
    }
}

/**
 * What a row starts with. With several people it's the person's initial; with one it's the
 * medicine's own picture, or its container for medicines that have no pill picture.
 */
@Composable
private fun Lead(medicine: Medicine, profile: app.dosekeeper.data.Profile?, showPerson: Boolean, dimmed: Boolean = false) {
    val backdrop = MaterialTheme.colorScheme.background
    if (showPerson) {
        ProfileBadge(profile, dimmed = dimmed, container = backdrop)
        return
    }
    Box(Modifier.size(44.dp).background(backdrop, CircleShape), contentAlignment = Alignment.Center) {
        if (medicine.shape != null) PillVisualizer(medicine, size = 30.dp, dimmed = dimmed)
        else BottleGlyph(Bottle.of(medicine.medForm), height = 28.dp, dimmed = dimmed)
    }
}

@Composable
private fun LateCard(dose: TodayDose, showPerson: Boolean, onTaken: () -> Unit, onSnooze: () -> Unit, onSkip: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    // Pressing Taken dips the whole card, the same as pressing a dose that isn't late.
    val taking = remember { MutableInteractionSource() }
    val press = rememberPressScale(taking)
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth()
            .graphicsLayer { scaleX = press.value; scaleY = press.value }
            .background(scheme.errorContainer, CardShape)
            .border(1.dp, scheme.error.copy(alpha = 0.25f), CardShape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Read out as one sentence: "Amoxicillin 250 mg, late, due 8:00 AM, blue capsule".
        Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
            Lead(dose.medicine, dose.profile, showPerson)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(dose.medicine.displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(dose.profile?.name.takeIf { showPerson }, "late, due ${dose.due.friendlyDateTime().removePrefix("Today, ")}").joinToString(", ")
                        .replaceFirstChar { it.uppercase() } +
                        (dose.snoozedUntil?.let { ", rings again at ${it.localTime()}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            if (showPerson) PillVisualizer(dose.medicine, Modifier.padding(start = 8.dp))
        }
        // With the phone's font size turned well up, three buttons side by side would cut their
        // labels off, so they stack instead. They grow with the text rather than clipping it.
        if (LocalDensity.current.fontScale >= 1.5f) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onTaken, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), interactionSource = taking) { Text("Taken") }
                OutlinedButton(onClick = onSnooze, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Snooze") }
                OutlinedButton(onClick = onSkip, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Skip") }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onTaken, modifier = Modifier.weight(1.5f).heightIn(min = 48.dp), interactionSource = taking) { Text("Taken") }
                OutlinedButton(onClick = onSnooze, modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(0.dp)) { Text("Snooze") }
                OutlinedButton(onClick = onSkip, modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(0.dp)) { Text("Skip") }
            }
        }
    }
}

/**
 * One dose on its own card. The medicine's current dose can be tapped on its check or swiped right
 * for Taken; letting go before the threshold springs it back with nothing done. Due doses move up
 * to the Late cards, which have Snooze. There's no snooze swipe here, since snoozing a dose that
 * isn't due would only delay it.
 *
 * The card dips slightly under a finger and springs back on release. [preview] is a dose on a day
 * other than today, shown to look at only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DoseRow(dose: TodayDose, haptics: Haptics, showPerson: Boolean, preview: Boolean = false, onCircle: () -> Unit, onClick: () -> Unit) {
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

    val rowTouch = remember { MutableInteractionSource() }
    val checkTouch = remember { MutableInteractionSource() }
    val press = rememberPressScale(rowTouch, checkTouch)
    Box(
        Modifier.padding(horizontal = 16.dp, vertical = 5.dp).graphicsLayer {
            scaleX = press.value
            scaleY = press.value
            shape = CardShape
            clip = true
        },
    ) {
        SwipeToDismissBox(
            state = state,
            enableDismissFromStartToEnd = dose.actionable,
            enableDismissFromEndToStart = false,
            backgroundContent = { SwipeBackground(state) },
        ) {
            DoseRowContent(dose, showPerson, preview, rowTouch, checkTouch, onCircle, onClick)
        }
    }
}

/** The green "Taken" strip uncovered by a swipe. It brightens once letting go will take the dose. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeBackground(state: SwipeToDismissBoxState) {
    val scheme = MaterialTheme.colorScheme
    val reduced = LocalReducedMotion.current
    val showing = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
    val armed = state.targetValue != SwipeToDismissBoxValue.Settled
    val strength by animateFloatAsState(if (!showing) 0f else if (armed) 1f else 0.55f, Motion.fade(), label = "swipe")
    val iconScale by animateFloatAsState(if (armed || reduced) 1.15f else 0.85f, Motion.Pop, label = "swipeIcon")
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = strength }.background(scheme.secondary))
        if (showing) {
            Row(Modifier.fillMaxSize().padding(horizontal = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = scheme.onSecondary,
                    modifier = Modifier.size(24.dp).graphicsLayer { scaleX = iconScale; scaleY = iconScale },
                )
                Text("Taken", color = scheme.onSecondary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 10.dp))
            }
        }
    }
}

@Composable
private fun DoseRowContent(
    dose: TodayDose,
    showPerson: Boolean,
    preview: Boolean,
    rowTouch: MutableInteractionSource,
    checkTouch: MutableInteractionSource,
    onCircle: () -> Unit,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val done = dose.status != null
    // A finished dose's name steps back by fading, which is cheaper than changing its color and
    // still passes 4.5:1 on the card.
    val nameAlpha by animateFloatAsState(if (done) DONE_NAME_ALPHA else 1f, tween(300), label = "name")
    Row(
        Modifier.fillMaxWidth().background(cardColor())
            .clickable(interactionSource = rowTouch, indication = ripple(), enabled = !preview && (dose.actionable || dose.undoable), onClick = onClick)
            .heightIn(min = 72.dp)
            .padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Lead(dose.medicine, dose.profile, showPerson, dimmed = done)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                dose.medicine.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSurface,
                textDecoration = if (dose.status == DoseStatus.TAKEN) TextDecoration.LineThrough else null,
                modifier = Modifier.graphicsLayer { alpha = nameAlpha },
            )
            val time = dose.due.localTime()
            // The exact moment it was marked taken gets its own line, so "did I take it?" is
            // answered at a glance. The time is the real tap time and can't be edited.
            if (dose.status == DoseStatus.TAKEN && dose.at != null) {
                Text(dose.at.takenLabel(), style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
            }
            val sub = when (dose.status) {
                DoseStatus.TAKEN -> "Due $time"
                DoseStatus.SKIPPED -> "Due $time, skipped"
                DoseStatus.MISSED -> "Due $time, missed"
                null -> listOfNotNull(time, dose.profile?.name.takeIf { showPerson }).joinToString(", ") +
                    (dose.snoozedUntil?.let { ", rings again at ${it.localTime()}" } ?: "")
            }
            Text(sub, style = MaterialTheme.typography.bodySmall, color = if (dose.status == DoseStatus.MISSED) scheme.error else scheme.onSurfaceVariant)
        }
        if (showPerson) PillVisualizer(dose.medicine, Modifier.padding(horizontal = 8.dp), dimmed = done)
        when {
            dose.status == DoseStatus.TAKEN -> CheckCircle(true, enabled = !preview && dose.undoable, label = "Mark as not taken", touch = checkTouch, onClick = onCircle)
            dose.actionable -> CheckCircle(false, enabled = true, label = "Mark as taken", touch = checkTouch, onClick = onCircle)
            dose.status == null && !preview -> Text("Later", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(end = 10.dp))
        }
    }
}

/**
 * The round check. The green fill springs out from the middle and the tick pops in just after,
 * a touch past full size before it settles. With animations removed, both simply fade in.
 */
@Composable
private fun CheckCircle(checked: Boolean, enabled: Boolean, label: String, touch: MutableInteractionSource, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val reduced = LocalReducedMotion.current
    val fill by animateFloatAsState(if (checked) 1f else 0f, if (reduced) Motion.fade() else Motion.Fill, label = "fill")
    val tick by animateFloatAsState(if (checked) 1f else 0f, if (reduced) Motion.fade() else Motion.Pop, label = "tick")
    val ring = if (scheme.background == Ink) ControlOutlineDark else ControlOutlineLight
    // The touch target is 48dp; the circle drawn inside it stays 44dp.
    Box(
        Modifier.size(48.dp)
            .clickable(
                enabled = enabled,
                onClickLabel = label,
                role = Role.Button,
                interactionSource = touch,
                indication = ripple(bounded = false, radius = 26.dp),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(44.dp).graphicsLayer { alpha = (1f - fill).coerceIn(0f, 1f) }.border(1.5.dp, ring, CircleShape))
        Box(
            Modifier.size(44.dp)
                .graphicsLayer {
                    if (reduced) alpha = fill.coerceIn(0f, 1f) else { scaleX = fill; scaleY = fill }
                }
                .background(scheme.secondary, CircleShape),
        )
        Icon(
            Icons.Filled.Check,
            contentDescription = if (checked) "Taken" else "Not taken yet",
            tint = scheme.onSecondary,
            modifier = Modifier.size(22.dp).graphicsLayer {
                alpha = tick.coerceIn(0f, 1f)
                if (!reduced) { scaleX = tick; scaleY = tick }
            },
        )
    }
}

@Composable
private fun AsNeededRowView(row: AsNeededRow, showPerson: Boolean, onLog: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth()
            .background(cardColor(), CardShape)
            .padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Lead(row.medicine, row.profile, showPerson)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
            Text(row.medicine.displayName, style = MaterialTheme.typography.titleMedium)
            val sub = when {
                row.notBefore != null -> "Not before ${row.notBefore.localTime()}"
                row.lastTaken != null -> "Last taken ${row.lastTaken.friendlyDateTime()}"
                else -> "Not taken yet"
            }
            Text(sub, style = MaterialTheme.typography.bodySmall, color = if (row.notBefore != null) scheme.error else scheme.onSurfaceVariant)
        }
        if (showPerson) PillVisualizer(row.medicine, Modifier.padding(horizontal = 8.dp))
        OutlinedButton(onClick = onLog, modifier = Modifier.heightIn(min = 48.dp)) { Text("Took it") }
    }
}

/** A medicine on a repeating timer, on a coming day: its times aren't known yet, so it says how often. */
@Composable
private fun TimedRow(medicine: Medicine, profile: app.dosekeeper.data.Profile?, showPerson: Boolean) {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth()
            .background(cardColor(), CardShape)
            .heightIn(min = 72.dp)
            .padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Lead(medicine, profile, showPerson)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(medicine.displayName, style = MaterialTheme.typography.titleMedium)
            val often = runCatching { medicine.parsedSchedule.describe() }.getOrDefault("")
            Text(
                listOfNotNull(often.takeIf { it.isNotEmpty() }, profile?.name.takeIf { showPerson }).joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "Time to refill" on Today, shown from about a week before a tracked supply runs out. */
@Composable
private fun LowSupplyCard(med: Medicine, outlook: Supply.Outlook, onRefilled: () -> Unit) {
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, CardShape)
            .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Refill ${med.name}", style = MaterialTheme.typography.titleSmall)
            Text(supplyLine(outlook, med.medForm.unit, med.supplyLeft ?: 0.0), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
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
