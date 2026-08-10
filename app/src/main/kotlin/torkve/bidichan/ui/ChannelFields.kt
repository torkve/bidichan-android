package torkve.bidichan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import torkve.bidichan.core.ChannelConfig

/**
 * The editor for one [ChannelConfig], shared by the ad-hoc "Add channel" sheet
 * and the per-profile defaults — so a channel opened by hand and one opened
 * automatically are configured identically.
 */
@Composable
fun ChannelFields(config: ChannelConfig, onChange: (ChannelConfig) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Text("Kind", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChannelConfig.Kind.entries.forEach { kind ->
                FilterChip(
                    selected = config.kind == kind,
                    onClick = {
                        // Move the port with the kind unless it was customised.
                        val port = if (config.port == config.kind.defaultPort) {
                            kind.defaultPort
                        } else {
                            config.port
                        }
                        onChange(config.copy(kind = kind, port = port))
                    },
                    label = { Text(kind.title) },
                )
            }
        }

        OutlinedTextField(
            value = config.label,
            onValueChange = { onChange(config.copy(label = it)) },
            label = { Text("Label (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = if (config.port == 0) "" else config.port.toString(),
            onValueChange = {
                onChange(config.copy(port = it.filter(Char::isDigit).toIntOrNull() ?: 0))
            },
            label = { Text("Port") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        SwitchRow(
            label = "Listen on all interfaces",
            help = if (config.allInterfaces) {
                "Reachable from the local network."
            } else {
                "Reachable only from this device."
            },
            checked = config.allInterfaces,
        ) { onChange(config.copy(allInterfaces = it)) }

        if (config.kind.isProxy) {
            SwitchRow(
                label = "Point the system at it",
                help = "Only an HTTP proxy can be published this way, and only on " +
                    "Android 10 or later. Otherwise point apps at it themselves.",
                checked = config.routeSystem,
            ) { onChange(config.copy(routeSystem = it)) }
        } else {
            OutlinedTextField(
                value = config.target,
                onValueChange = { onChange(config.copy(target = it)) },
                label = { Text("Target (host:port)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    help: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            help?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** True when the config would produce a channel the core will accept. */
fun ChannelConfig.isValid(): Boolean =
    port in 1..65535 && (kind.isProxy || target.contains(":"))
