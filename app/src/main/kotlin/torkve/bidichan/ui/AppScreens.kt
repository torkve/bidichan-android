package torkve.bidichan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import torkve.bidichan.AppModel
import torkve.bidichan.core.Profile

/** Which screen is showing. Deliberately tiny — this app has two. */
private sealed interface Screen {
    data object List : Screen
    data class Edit(val profile: Profile) : Screen
}

@Composable
fun AppScreens(
    model: AppModel,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
) {
    var screen by remember { mutableStateOf<Screen>(Screen.List) }
    when (val s = screen) {
        is Screen.List -> ProfileListScreen(
            model = model,
            onConnect = onConnect,
            onDisconnect = onDisconnect,
            onEdit = { screen = Screen.Edit(it) },
            onAdd = { screen = Screen.Edit(Profile()) },
        )
        is Screen.Edit -> ProfileEditScreen(
            model = model,
            initial = s.profile,
            onDone = { screen = Screen.List },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileListScreen(
    model: AppModel,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onEdit: (Profile) -> Unit,
    onAdd: () -> Unit,
) {
    val profiles by model.profiles.collectAsState()
    val status by model.status.collectAsState()
    val error by model.error.collectAsState()
    val activeId by model.activeProfileId.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text("bidichan") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = "Add a profile")
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            StatusRow(status)
            error?.let { message ->
                Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(message, style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { model.clearError() }) { Text("Dismiss") }
                    }
                }
            }
            if (profiles.isEmpty()) {
                Text(
                    "No profiles yet. Add one to get started.",
                    Modifier.padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            LazyColumn(Modifier.fillMaxWidth()) {
                items(profiles, key = { it.id }) { profile ->
                    ProfileRow(
                        profile = profile,
                        isActive = profile.id == activeId,
                        isLive = model.isLive,
                        onEdit = { onEdit(profile) },
                        onConnect = { onConnect(profile.id) },
                        onDisconnect = onDisconnect,
                        onDelete = { model.delete(profile) },
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusRow(status: String) {
    val colour = when (status) {
        "Connected" -> Color(0xFF2E7D32)
        "Reconnecting…", "Connecting…" -> Color(0xFFEF6C00)
        else -> Color.Gray
    }
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.size(10.dp).clip(CircleShape).background(colour))
        Spacer(Modifier.width(10.dp))
        Text(status, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ProfileRow(
    profile: Profile,
    isActive: Boolean,
    isLive: Boolean,
    onEdit: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onEdit),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(profile.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        profile.serverAddress.ifEmpty { "not configured" },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                }
            }
            Spacer(Modifier.size(8.dp))
            if (isActive && isLive) {
                Button(onClick = onDisconnect) { Text("Disconnect") }
            } else {
                Button(onClick = onConnect, enabled = profile.serverAddress.isNotEmpty()) {
                    Text("Connect")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEditScreen(model: AppModel, initial: Profile, onDone: () -> Unit) {
    var p by remember { mutableStateOf(initial) }
    var psk by remember { mutableStateOf(model.psk(initial)) }

    Scaffold(topBar = { TopAppBar(title = { Text(p.name.ifEmpty { "Profile" }) }) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Field("Name", p.name) { p = p.copy(name = it) }
                    Field("Server (host:port)", p.serverAddress) { p = p.copy(serverAddress = it) }
                    Field("Hostname / SNI", p.hostname) { p = p.copy(hostname = it) }
                    Field("Path (empty derives it from the key)", p.path) { p = p.copy(path = it) }
                    Field("Pre-shared key (hex)", psk) { psk = it }

                    Toggle("Behind a TLS-terminating proxy", p.noTlsBinding) {
                        p = p.copy(noTlsBinding = it)
                    }
                    Toggle("Provide a packet interface", p.enableTun) { p = p.copy(enableTun = it) }
                    if (p.enableTun) {
                        Field("Address (IPv4 CIDR)", p.tunCidr) { p = p.copy(tunCidr = it) }
                        Field("Address (IPv6 CIDR, empty to disable)", p.tunCidr6) {
                            p = p.copy(tunCidr6 = it)
                        }
                        Field("MTU", p.tunMtu.toString()) {
                            p = p.copy(tunMtu = it.toIntOrNull() ?: p.tunMtu)
                        }
                        Toggle("Route all traffic", p.fullTunnel) { p = p.copy(fullTunnel = it) }
                    }
                    Field("Reconnect window (seconds)", p.resumeGraceSeconds.toString()) {
                        p = p.copy(resumeGraceSeconds = it.toIntOrNull() ?: p.resumeGraceSeconds)
                    }
                    Text(
                        "How long the connection may be gone before the session is given up. " +
                            "Within this window the tunnel reconnects by itself and open " +
                            "channels — and the connections running through them — carry on " +
                            "where they left off.",
                        style = MaterialTheme.typography.bodySmall,
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = {
                            model.upsert(p)
                            if (psk.isNotBlank()) model.setPsk(p, psk)
                            onDone()
                        }) { Text("Save") }
                        Button(onClick = onDone) { Text("Cancel") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
