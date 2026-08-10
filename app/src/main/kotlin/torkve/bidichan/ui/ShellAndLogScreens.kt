package torkve.bidichan.ui

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import torkve.bidichan.AppModel

/**
 * An interactive shell on the peer. Deliberately a transcript with a line
 * entry rather than a terminal emulator: it is enough to run a command and read
 * the answer, and it carries no escape-sequence handling to get wrong.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShellScreen(model: AppModel, onBack: () -> Unit) {
    val output by model.shellOutput.collectAsState()
    var input by remember { mutableStateOf("") }
    val scroll = rememberScrollState()

    LaunchedEffect(Unit) { model.openShell() }
    DisposableEffect(Unit) { onDispose { model.closeShell() } }
    LaunchedEffect(output) { scroll.animateScrollTo(scroll.maxValue) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Shell") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(
                output.ifEmpty { "Opening a shell on the peer…" },
                Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(10.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Command") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = {
                    if (input.isNotEmpty()) {
                        model.sendShell(input)
                        input = ""
                    }
                }) { Text("Send") }
            }
        }
    }
}

/**
 * The on-device log, so a connection problem can be diagnosed without a
 * development machine attached.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(model: AppModel, onBack: () -> Unit) {
    var text by remember { mutableStateOf(model.logText()) }
    val scroll = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Logs") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { text = model.logText() }) { Text("Refresh") }
                    TextButton(onClick = {
                        model.clearLog()
                        text = ""
                    }) { Text("Clear") }
                },
            )
        },
    ) { padding ->
        Text(
            text.ifEmpty { "No logs yet." },
            Modifier.padding(padding).fillMaxSize().verticalScroll(scroll).padding(10.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
