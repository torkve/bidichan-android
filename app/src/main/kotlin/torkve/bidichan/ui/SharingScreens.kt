package torkve.bidichan.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import torkve.bidichan.AppModel
import torkve.bidichan.core.Profile
import torkve.bidichan.core.ProfileLinking

/**
 * Hands a profile to another device as a link. The one real decision is whether
 * the pre-shared key travels with it, so that is what this screen is about.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareProfileScreen(model: AppModel, profile: Profile, onBack: () -> Unit) {
    val context = LocalContext.current
    val savedKey = remember(profile.id) { model.psk(profile) }
    val hasKey = savedKey.isNotEmpty()

    var includeKey by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf<String?>(null) }

    // Rebuilt whenever the key choice changes, rather than on every recomposition.
    LaunchedEffect(includeKey, profile.id) {
        runCatching { ProfileLinking.link(profile, includeKey, savedKey) }
            .onSuccess { link = it; failure = null }
            .onFailure { link = ""; failure = it.message ?: "Could not build a link." }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Share ${profile.name}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Include the pre-shared key", Modifier.weight(1f))
                Switch(
                    checked = includeKey,
                    enabled = hasKey,
                    onCheckedChange = { includeKey = it },
                )
            }

            Text(
                when {
                    !hasKey -> "This profile has no key saved, so the other device will need it " +
                        "entered separately."
                    includeKey -> "Anyone who gets this link can use the tunnel. Nothing in the " +
                        "link is encrypted — send it the way you would send the key itself, and " +
                        "delete it afterwards."
                    else -> "The link carries the settings only. The other device will ask for " +
                        "the key, which you can send separately."
                },
                style = MaterialTheme.typography.bodySmall,
            )

            failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            if (link.isNotEmpty()) {
                Button(onClick = { shareText(context, link) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Share link")
                }
                Text(link, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }
}

/**
 * Confirms a profile that arrived as a link before it is saved. An incoming
 * link is someone else's configuration, so it is shown in full first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportProfileScreen(
    incoming: ProfileLinking.Incoming,
    onAdd: () -> Unit,
    onCancel: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import profile") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel")
                    }
                },
                actions = { TextButton(onClick = onAdd) { Text("Add") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Detail("Name", incoming.profile.name)
            Detail("Server", incoming.profile.serverAddress)
            Detail("Hostname", incoming.profile.hostname)
            if (incoming.profile.path.isNotEmpty()) Detail("Path", incoming.profile.path)

            if (incoming.profile.channels.isNotEmpty()) {
                Text(
                    "Default channels",
                    Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.titleSmall,
                )
                incoming.profile.channels.forEach { Detail(it.displayName, it.kind.title) }
            }

            Text(
                if (incoming.carriesKey) {
                    "This link includes the pre-shared key, so the profile is ready to connect. " +
                        "Delete the link from wherever you received it."
                } else {
                    "This link carries settings only. Add the pre-shared key before connecting."
                },
                Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Share profile"))
}
