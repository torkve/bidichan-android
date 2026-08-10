package torkve.bidichan.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import torkve.bidichan.AppModel
import torkve.bidichan.core.ChannelSnapshot
import torkve.bidichan.core.Profile

/**
 * The live view of one connected profile: what the tunnel is doing, the
 * channels that are open, and the ways in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(
    model: AppModel,
    profile: Profile,
    onBack: () -> Unit,
    onDisconnect: () -> Unit,
    onAddChannel: () -> Unit,
    onChannel: (ChannelSnapshot) -> Unit,
    onShell: () -> Unit,
    onLogs: () -> Unit,
) {
    val status by model.status.collectAsState()
    val channels by model.channels.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(profile.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item { StatusRow(status) }

            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = onDisconnect) { Text("Disconnect") }
                    TextButton(onClick = onLogs) { Text("Logs") }
                    TextButton(onClick = onShell) { Text("Shell") }
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Channels", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onAddChannel) { Text("Add") }
                }
            }

            if (channels.isEmpty()) {
                item {
                    Text(
                        "No open channels.",
                        Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            items(channels, key = { it.id }) { channel ->
                ChannelRow(channel) { onChannel(channel) }
            }
        }
    }
}

@Composable
private fun ChannelRow(channel: ChannelSnapshot, onClick: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(12.dp)) {
            val label = channel.label
            if (!label.isNullOrEmpty()) {
                Text(label, style = MaterialTheme.typography.titleSmall)
                Text("${channel.kind} · #${channel.id}", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("${channel.kind} · #${channel.id}", style = MaterialTheme.typography.titleSmall)
            }
            Text(channel.description, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun StatusRow(status: String) {
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
        Column {
            Text(status, style = MaterialTheme.typography.bodyLarge)
            if (status == "Reconnecting…") {
                Text(
                    "Channels are stalled, not closed — they carry on when the link returns.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** Detail and actions for one open channel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelDetailScreen(
    model: AppModel,
    channel: ChannelSnapshot,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val bound = boundAddress(channel.description)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Channel #${channel.id}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            channel.label?.takeIf { it.isNotEmpty() }?.let { Row2("Label", it) }
            Row2("Kind", channel.kind)
            Row2("ID", "#${channel.id}")
            bound?.let { Row2("Bound", it) }
            Text(channel.description, style = MaterialTheme.typography.bodySmall)

            bound?.let { address ->
                Button(onClick = { copyToClipboard(context, address) }) {
                    Text("Copy $address")
                }
            }

            Button(onClick = {
                model.closeChannel(channel.id)
                onBack()
            }) { Text("Close channel") }
        }
    }
}

@Composable
private fun Row2(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Pulls the bound address out of a channel's description, which is the only
 * place the kernel-assigned port shows up.
 */
internal fun boundAddress(description: String): String? =
    Regex("""\b(\d{1,3}(?:\.\d{1,3}){3}:\d+|\[[0-9a-fA-F:]+]:\d+)""")
        .find(description)
        ?.value

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("bidichan", text))
}
