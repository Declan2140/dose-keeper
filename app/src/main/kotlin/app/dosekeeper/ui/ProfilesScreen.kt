package app.dosekeeper.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.dosekeeper.DoseKeeperApp
import app.dosekeeper.app
import app.dosekeeper.billing.UnlockManager
import app.dosekeeper.data.Profile
import kotlinx.coroutines.launch

/**
 * People and pets. Free users keep one profile; adding a second is the moment the unlock is
 * offered. [startAdding] opens the add dialog straight away (from the medicine form).
 */
@Composable
fun ProfilesScreen(startAdding: Boolean = false) {
    val app = LocalContext.current.app
    val scope = rememberCoroutineScope()
    val profiles by app.db.profiles().observeAll().collectAsState(initial = emptyList())
    val unlocked by app.unlock.unlocked.collectAsState()
    var editing by remember { mutableStateOf<Profile?>(null) }
    var adding by remember { mutableStateOf(startAdding) }
    var showUnlock by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val canAdd = unlocked || profiles.size < UnlockManager.FREE_PROFILES
    if (adding && profiles.isNotEmpty() && !canAdd) {
        adding = false
        showUnlock = true
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Each person or pet gets their own color, so you can tell at a glance whose dose is due.", style = MaterialTheme.typography.bodySmall)
        profiles.forEach { p ->
            Card(Modifier.fillMaxWidth().clickable { editing = p }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    ProfileBadge(p)
                    Spacer(Modifier.width(12.dp))
                    Text(p.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Text("Edit", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Button(onClick = { if (canAdd) adding = true else showUnlock = true }, modifier = Modifier.fillMaxWidth()) { Text("Add a person or pet") }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }

    if (adding) {
        ProfileDialog(null, onDismiss = { adding = false }, onSave = { p ->
            scope.launch { app.db.profiles().insert(p.copy(sortOrder = profiles.size)) }
            adding = false
        })
    }
    editing?.let { current ->
        ProfileDialog(
            current,
            onDismiss = { editing = null },
            onSave = { p -> scope.launch { app.db.profiles().update(p) }; editing = null },
            onDelete = if (profiles.size > 1) {
                {
                    scope.launch {
                        if (app.db.medicines().countForProfile(current.id) > 0) {
                            message = "${current.name} still has medicines. Archive them first, then delete the profile."
                        } else {
                            app.db.profiles().delete(current)
                            message = null
                        }
                    }
                    editing = null
                }
            } else null,
        )
    }
    if (showUnlock) UnlockDialog("The free version covers one person. Add family members and pets with the unlock.", onDismiss = { showUnlock = false })
}

@Composable
private fun ProfileDialog(initial: Profile?, onDismiss: () -> Unit, onSave: (Profile) -> Unit, onDelete: (() -> Unit)? = null) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var pet by remember { mutableStateOf(initial?.icon == ICON_PET) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add a person or pet" else "Edit ${initial.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Field(name, { name = it }, label = { Text("Name") }, singleLine = true, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("This is a pet", Modifier.weight(1f))
                    Switch(pet, { pet = it })
                }
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete profile", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                val icon = if (pet) ICON_PET else ICON_PERSON
                val base = initial ?: Profile(name = name.trim(), colorArgb = DoseKeeperApp.PROFILE_COLORS.first(), icon = icon)
                onSave(base.copy(name = name.trim(), icon = icon))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
