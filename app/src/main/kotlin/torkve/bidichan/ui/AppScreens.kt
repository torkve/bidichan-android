package torkve.bidichan.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import torkve.bidichan.AppModel
import torkve.bidichan.core.ChannelConfig
import torkve.bidichan.core.ChannelSnapshot
import torkve.bidichan.core.Profile
import torkve.bidichan.core.ProfileLinking

/** Where the user is. Small enough to keep explicit rather than pull in a router. */
private sealed interface Screen {
    data object List : Screen
    data class Edit(val profile: Profile) : Screen
    data class Connection(val profile: Profile) : Screen
    data class Channel(val channel: ChannelSnapshot) : Screen
    data object AddChannel : Screen
    data object Shell : Screen
    data object Logs : Screen
    data class Share(val profile: Profile) : Screen
    data object ImportLink : Screen
}

@Composable
fun AppScreens(
    model: AppModel,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    incoming: ProfileLinking.Incoming? = null,
    onImport: (ProfileLinking.Incoming) -> Unit = {},
    onDismissImport: () -> Unit = {},
    linkError: String? = null,
    onDismissLinkError: () -> Unit = {},
) {
    // An arriving link takes precedence: it is a decision waiting on the user.
    if (incoming != null) {
        ImportProfileScreen(
            incoming = incoming,
            onAdd = { onImport(incoming) },
            onCancel = onDismissImport,
        )
        return
    }
    if (linkError != null) {
        AlertDialog(
            onDismissRequest = onDismissLinkError,
            confirmButton = { TextButton(onClick = onDismissLinkError) { Text("OK") } },
            title = { Text("That link could not be read") },
            text = { Text(linkError) },
        )
    }
    var screen by remember { mutableStateOf<Screen>(Screen.List) }
    val profiles by model.profiles.collectAsState()
    val activeId by model.activeProfileId.collectAsState()

    // The connection screen belongs to a profile; if it goes away, so does the
    // screen looking at it.
    val active = profiles.firstOrNull { it.id == activeId }

    when (val s = screen) {
        is Screen.List -> ProfileListScreen(
            model = model,
            onConnect = { id ->
                model.connecting(id)
                onConnect(id)
                profiles.firstOrNull { it.id == id }?.let { screen = Screen.Connection(it) }
            },
            onOpen = { screen = Screen.Connection(it) },
            onEdit = { screen = Screen.Edit(it) },
            onAdd = { screen = Screen.Edit(Profile()) },
            onImportLink = { screen = Screen.ImportLink },
            onLogs = { screen = Screen.Logs },
            onShare = { screen = Screen.Share(it) },
        )

        is Screen.Edit -> ProfileEditScreen(
            model = model,
            initial = s.profile,
            onDone = { screen = Screen.List },
        )

        is Screen.Connection -> ConnectionScreen(
            model = model,
            profile = active ?: s.profile,
            onBack = { screen = Screen.List },
            onDisconnect = {
                onDisconnect()
                screen = Screen.List
            },
            onAddChannel = { screen = Screen.AddChannel },
            onChannel = { screen = Screen.Channel(it) },
            onShell = { screen = Screen.Shell },
            onLogs = { screen = Screen.Logs },
        )

        is Screen.Channel -> ChannelDetailScreen(
            model = model,
            channel = s.channel,
            onBack = { screen = Screen.Connection(active ?: Profile()) },
        )

        is Screen.AddChannel -> AddChannelScreen(
            onOpen = { config ->
                model.openChannel(config)
                screen = Screen.Connection(active ?: Profile())
            },
            onCancel = { screen = Screen.Connection(active ?: Profile()) },
        )

        is Screen.Shell -> ShellScreen(
            model = model,
            onBack = { screen = Screen.Connection(active ?: Profile()) },
        )

        is Screen.Logs -> LogScreen(model = model, onBack = { screen = Screen.List })

        is Screen.Share -> ShareProfileScreen(
            model = model,
            profile = s.profile,
            onBack = { screen = Screen.List },
        )

        is Screen.ImportLink -> ImportLinkScreen(
            onImport = { accepted ->
                onImport(accepted)
                screen = Screen.List
            },
            onBack = { screen = Screen.List },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileListScreen(
    model: AppModel,
    onConnect: (String) -> Unit,
    onOpen: (Profile) -> Unit,
    onEdit: (Profile) -> Unit,
    onAdd: () -> Unit,
    onImportLink: () -> Unit,
    onLogs: () -> Unit,
    onShare: (Profile) -> Unit,
) {
    val profiles by model.profiles.collectAsState()
    val status by model.status.collectAsState()
    val error by model.error.collectAsState()
    val activeId by model.activeProfileId.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("bidichan") },
                actions = {
                    // Importing a link is rare enough not to earn a permanent
                    // control, but it has to be reachable: a link sent over a
                    // chat app is usually not tappable, so pasting it is the
                    // only way in.
                    TextButton(onClick = onImportLink) { Text("Import") }
                    TextButton(onClick = onLogs) { Text("Logs") }
                },
            )
        },
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
                        TextButton(onClick = { model.clearError() }) { Text("Dismiss") }
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
                        isActive = profile.id == activeId && model.isLive,
                        onEdit = { onEdit(profile) },
                        onOpen = { onOpen(profile) },
                        onConnect = { onConnect(profile.id) },
                        onShare = { onShare(profile) },
                        onDelete = { model.delete(profile) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileRow(
    profile: Profile,
    isActive: Boolean,
    onEdit: () -> Unit,
    onOpen: () -> Unit,
    onConnect: () -> Unit,
    onShare: () -> Unit,
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isActive) {
                    Button(onClick = onOpen) { Text("Open") }
                } else {
                    Button(onClick = onConnect, enabled = profile.serverAddress.isNotEmpty()) {
                        Text("Connect")
                    }
                }
                TextButton(onClick = onShare) { Text("Share") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddChannelScreen(onOpen: (ChannelConfig) -> Unit, onCancel: () -> Unit) {
    var config by remember { mutableStateOf(ChannelConfig()) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add channel") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { onOpen(config) },
                        enabled = config.isValid(),
                    ) { Text("Open") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            ChannelFields(config) { config = it }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEditScreen(model: AppModel, initial: Profile, onDone: () -> Unit) {
    var p by remember { mutableStateOf(initial) }
    var psk by remember { mutableStateOf(model.psk(initial)) }
    var editing by remember { mutableStateOf<ChannelConfig?>(null) }

    val target = editing
    if (target != null) {
        ChannelConfigEditor(
            initial = target,
            onDone = { edited ->
                p = if (p.channels.any { it.id == edited.id }) {
                    p.copy(channels = p.channels.map { if (it.id == edited.id) edited else it })
                } else {
                    p.copy(channels = p.channels + edited)
                }
                editing = null
            },
            onCancel = { editing = null },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(p.name.ifEmpty { "Profile" }) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        // Clamped on save rather than while typing: bounding
                        // each keystroke would put "1400" out of reach, since
                        // "1" would already have become the floor.
                        val saved = p.withUsableNumbers()
                        p = saved
                        model.upsert(saved)
                        if (psk.isNotBlank()) model.setPsk(saved, psk)
                        onDone()
                    }) { Text("Save") }
                },
            )
        },
    ) { padding ->
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
                        Field(range("MTU", Profile.MTU_RANGE), p.tunMtu.toString()) {
                            p = p.copy(tunMtu = it.toIntOrNull() ?: p.tunMtu)
                        }
                        Toggle("Route all traffic", p.fullTunnel) { p = p.copy(fullTunnel = it) }
                    }
                    Field(
                        range("Reconnect window (seconds)", Profile.RESUME_GRACE_RANGE),
                        p.resumeGraceSeconds.toString(),
                    ) {
                        p = p.copy(resumeGraceSeconds = it.toIntOrNull() ?: p.resumeGraceSeconds)
                    }
                    Text(
                        "How long the connection may be gone before the session is given up. " +
                            "Within this window the tunnel reconnects by itself and open " +
                            "channels — and the connections running through them — carry on " +
                            "where they left off.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("Default channels", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Opened automatically once this profile connects.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = { editing = ChannelConfig() }) { Text("Add") }
                }
            }

            items(p.channels, key = { it.id }) { channel ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).clickable { editing = channel }) {
                            Text(channel.displayName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${channel.kind.title} · ${channel.listenAddr}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        IconButton(onClick = {
                            p = p.copy(channels = p.channels.filterNot { it.id == channel.id })
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelConfigEditor(
    initial: ChannelConfig,
    onDone: (ChannelConfig) -> Unit,
    onCancel: () -> Unit,
) {
    var config by remember { mutableStateOf(initial) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Default channel") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel")
                    }
                },
                actions = {
                    TextButton(onClick = { onDone(config) }, enabled = config.isValid()) {
                        Text("Done")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            ChannelFields(config) { config = it }
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

/**
 * A field label that states the range the value is clamped to on save, so the
 * clamp is something the user was told about rather than something that happens
 * to them. Built from the range itself, so the two cannot drift.
 */
private fun range(label: String, r: IntRange) = "$label (${r.first}–${r.last})"

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
