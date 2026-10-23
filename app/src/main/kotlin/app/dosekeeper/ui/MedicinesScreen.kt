package app.dosekeeper.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.dosekeeper.app
import app.dosekeeper.data.MedStatus
import app.dosekeeper.data.Medicine

@Composable
fun MedicinesScreen(onEdit: (Long) -> Unit) {
    val app = LocalContext.current.app
    val medicines by app.db.medicines().observeAll().collectAsState(initial = emptyList())
    val profiles by app.db.profiles().observeAll().collectAsState(initial = emptyList())
    var profileFilter by rememberSaveable { mutableStateOf<Long?>(null) }
    var showArchived by rememberSaveable { mutableStateOf(false) }

    val visible = medicines.filter { profileFilter == null || it.profileId == profileFilter }
    val current = visible.filter { it.medStatus != MedStatus.ARCHIVED }
    val archived = visible.filter { it.medStatus == MedStatus.ARCHIVED }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { onEdit(0) }, shape = CircleShape, containerColor = MaterialTheme.colorScheme.primary) {
                Icon(Icons.Filled.Add, "Add medicine")
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 32.dp, bottom = 96.dp),
        ) {
            item { Text("Medicines", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(bottom = 16.dp)) }
            if (profiles.size > 1) {
                item {
                    Segmented(
                        options = listOf<Long?>(null) + profiles.map { it.id },
                        selected = profileFilter,
                        label = { id -> profiles.firstOrNull { it.id == id }?.name ?: "Everyone" },
                        onSelect = { profileFilter = it },
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }
            if (current.isEmpty()) {
                item { Text("No medicines yet. Tap + to set up the first one.", Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(current, key = { it.id }) { med ->
                MedicineRow(med, profiles.firstOrNull { it.id == med.profileId }, onClick = { onEdit(med.id) })
            }
            if (archived.isNotEmpty()) {
                item {
                    TextButton(onClick = { showArchived = !showArchived }) {
                        Text(if (showArchived) "Hide archived (${archived.size})" else "Show archived (${archived.size})")
                    }
                }
                if (showArchived) {
                    items(archived, key = { it.id }) { med ->
                        MedicineRow(med, profiles.firstOrNull { it.id == med.profileId }, onClick = { onEdit(med.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun MedicineRow(med: Medicine, profile: app.dosekeeper.data.Profile?, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val dim = med.medStatus != MedStatus.ACTIVE
    Column {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            ProfileBadge(profile, dimmed = dim)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(med.displayName, style = MaterialTheme.typography.titleMedium, color = if (dim) scheme.onSurfaceVariant else scheme.onSurface)
                Text(runCatching { med.parsedSchedule.describe() }.getOrDefault(""), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                val status = when (med.medStatus) {
                    MedStatus.ACTIVE -> med.nextDueAt?.let { "Next ${java.time.Instant.ofEpochMilli(it).friendlyDateTime()}" }
                    MedStatus.PAUSED -> "Paused"
                    MedStatus.ARCHIVED -> "Archived"
                }
                if (status != null) Text(status, style = MaterialTheme.typography.bodySmall, color = if (dim) scheme.onSurfaceVariant else scheme.secondary)
            }
        }
        HorizontalDivider(color = scheme.outlineVariant)
    }
}
