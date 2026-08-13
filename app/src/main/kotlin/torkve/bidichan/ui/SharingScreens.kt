package torkve.bidichan.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import torkve.bidichan.AppModel
import torkve.bidichan.core.Profile
import torkve.bidichan.core.ProfileLinking
import java.io.ByteArrayOutputStream

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
                // Encoded once per link, not on every recomposition.
                val code = remember(link) { QrCode.encode(link) }

                Button(
                    onClick = { shareProfile(context, link, code) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (code == null) "Share link" else "Share code and link")
                }
                Text(
                    if (code == null) {
                        "Most chat apps will not make this tappable, because the app registers " +
                            "its own link scheme rather than a web address. The other device " +
                            "can copy the text and use Import from a link."
                    } else {
                        "Sends both. An app that takes images gets the code with the link " +
                            "alongside it; one that only takes text gets the link. Most chat " +
                            "apps will not make that text tappable — the other device can copy " +
                            "it and use Import from a link, or just scan the code."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )

                if (code != null) {
                    // The bitmap carries its own white field and quiet zone, so
                    // it stays scannable wherever it ends up — including in
                    // someone else's chat app.
                    Image(
                        bitmap = code,
                        contentDescription = "Scannable code for this profile",
                        // Nearest-neighbour, so the modules stay square edged
                        // rather than being smoothed into each other.
                        filterQuality = FilterQuality.None,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    )
                } else {
                    Text(
                        "This profile is too large to show as a code — its certificate takes " +
                            "more room than one can hold. Send the link as text instead.",
                        style = MaterialTheme.typography.bodySmall,
                    )
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

/**
 * Imports a profile from a link that arrived as plain text.
 *
 * Tapping a link only works where the sending app made it tappable, and most
 * chat apps only do that for web addresses — never for an app's own scheme. So
 * the text has to be importable by hand as well, or a profile shared over the
 * wrong app cannot be received at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportLinkScreen(
    onImport: (ProfileLinking.Incoming) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var decoded by remember { mutableStateOf<ProfileLinking.Incoming?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }

    // Once the link is read this becomes the same confirmation screen a tapped
    // link would have reached, so there is one place that describes an
    // arriving profile.
    val pending = decoded
    if (pending != null) {
        ImportProfileScreen(
            incoming = pending,
            onAdd = { onImport(pending) },
            onCancel = { decoded = null },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import from a link") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Link") },
                placeholder = { Text("bidichan://profile#…") },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                "Paste the link you were sent. It is not saved as a profile until you have seen " +
                    "what is in it.",
                style = MaterialTheme.typography.bodySmall,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { clipboardText(context)?.let { text = it } }) {
                    Text("Paste")
                }
                Button(
                    onClick = {
                        val raw = text.trim()
                        if (!ProfileLinking.isProfileLink(raw)) {
                            failure = "That does not look like a profile link. It should begin " +
                                "with ${ProfileLinking.prefix}."
                        } else {
                            // The core writes its errors to be read by a person.
                            runCatching { ProfileLinking.decode(raw) }
                                .onSuccess { decoded = it; failure = null }
                                .onFailure { failure = it.message ?: "That link could not be read." }
                        }
                    },
                    enabled = text.isNotBlank(),
                ) { Text("Read link") }
            }

            failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

/** The clipboard as plain text, or null when it holds nothing usable. */
private fun clipboardText(context: Context): String? {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val item = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return null
    return item.coerceToText(context).toString().trim().ifEmpty { null }
}

@Composable
private fun Detail(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Sends the profile: the scannable code where one could be made, and the link
 * as text either way.
 *
 * An app that takes images gets both — Telegram attaches the code and uses the
 * link as its caption — and one that only takes text still gets the link. That
 * matters because a link is not tappable in most chat apps, so the code is
 * often the only part the receiving side can act on directly.
 */
private fun shareProfile(context: Context, link: String, code: ImageBitmap?) {
    val uri = code?.let { pngBytes(it) }?.let { SharedPayloadProvider.offer(context, it, "profile-code.png", "image/png") }
    val intent = Intent(Intent.ACTION_SEND).apply {
        if (uri == null) {
            type = "text/plain"
        } else {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            // Some targets read the grant from the clip data rather than the
            // extra, so set both and flag the intent.
            clipData = ClipData.newUri(context.contentResolver, "Profile code", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        putExtra(Intent.EXTRA_TEXT, link)
    }
    context.startActivity(Intent.createChooser(intent, "Share profile"))
}

/** The code as PNG bytes, or null if it could not be encoded. */
private fun pngBytes(code: ImageBitmap): ByteArray? = runCatching {
    ByteArrayOutputStream().use { out ->
        code.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
    }
}.getOrNull()
