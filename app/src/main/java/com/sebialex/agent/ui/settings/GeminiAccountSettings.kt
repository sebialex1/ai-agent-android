package com.sebialex.agent.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sebialex.agent.data.backup.BackupBundle
import com.sebialex.agent.data.llm.CustomProviders
import com.sebialex.agent.data.llm.GeminiShim
import com.sebialex.agent.data.secure.AgtFile
import com.sebialex.agent.data.secure.SecureSettings
import com.sebialex.agent.data.shell.TermuxBridge
import com.sebialex.agent.ui.components.AppSheet
import com.sebialex.agent.ui.components.SheetOption
import com.sebialex.agent.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Settings > Models > Providers > Gemini (my account).
 *
 * Sign-in cannot happen in the app (Google refuses embedded WebViews), so a desktop tool exports
 * the session as an encrypted `.agt`. This imports it, keeps it in the Keystore-backed store, and
 * writes the cookie file a local shim reads. The app itself never speaks Gemini's web protocol.
 */
@Composable
internal fun GeminiAccountGroup(settings: SecureSettings, termux: TermuxBridge) {
    val snap by settings.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var shimRunning by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    // Any settings edit republishes the snapshot, so this re-reads after an import or removal.
    val stored = remember(snap) { settings.geminiAccount() }
    val summary = remember(stored) {
        stored?.let { runCatching { AgtFile.describe(Json.decodeFromString(AgtFile.Payload.serializer(), it)) }.getOrNull() }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
            }
            if (text == null) message = "Could not read that file." else pending = text
        }
    }

    /** Writes the jar where a Termux-side shim can read it. Never into a conversation workspace. */
    fun handOff(payload: AgtFile.Payload): String? = runCatching {
        val out = java.io.File(termux.sharedRoot, "gemini-cookies.json")
        out.parentFile?.mkdirs()
        out.writeText(AgtFile.shimFile(payload))
        out.absolutePath
    }.getOrNull()

    SettingsGroup("Gemini (my account)") {
        NavRow(
            title = if (summary == null) "Not imported" else "Session imported",
            value = summary ?: "Import an .agt exported from your desktop browser",
            icon = com.sebialex.agent.R.drawable.ic_key,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PillButton(if (summary == null) "Import .agt" else "Replace", onClick = { picker.launch(arrayOf("*/*")) }, primary = true)
            if (summary != null) {
                PillButton("Write cookie file", onClick = {
                    val payload = runCatching { Json.decodeFromString(AgtFile.Payload.serializer(), stored!!) }.getOrNull()
                    val path = payload?.let { handOff(it) }
                    message = if (path != null) "Written to $path" else "Could not write the cookie file. Allow All files access in Settings > General > Sandbox."
                })
                com.sebialex.agent.ui.components.QuietIconButton(
                    com.sebialex.agent.R.drawable.ic_refresh, "Refresh models",
                    onClick = {
                        scope.launch {
                            val catalog = withContext(Dispatchers.IO) {
                                runCatching { CustomProviders.fetchCatalog(CustomProviders.GEMINI_ACCOUNT_URL, null) }.getOrNull()
                            }
                            if (catalog == null) {
                                message = "No answer from the shim at ${CustomProviders.GEMINI_ACCOUNT_URL}. Start it, then refresh."
                            } else {
                                settings.saveCustomProvider(CustomProviders.geminiAccountProvider(catalog.first, catalog.second))
                                message = "${catalog.first.size} models listed."
                            }
                        }
                    },
                    tint = Palette.IconMuted, size = 34.dp, iconSize = 16.dp,
                )
                PillButton(if (shimRunning) "Stop" else "Start", primary = !shimRunning, enabled = !busy, onClick = {
                    val raw = stored
                    if (raw == null) { message = "Import a session first."; return@PillButton }
                    busy = true
                    scope.launch {
                        try {
                            if (shimRunning) {
                                withContext(Dispatchers.IO) { runCatching { GeminiShim.stop(termux, GeminiShim.HOME) } }
                                shimRunning = false
                                message = "Stopped."
                                return@launch
                            }
                            val payload = runCatching { Json.decodeFromString(AgtFile.Payload.serializer(), raw) }.getOrNull()
                            if (payload == null) { message = "The stored session could not be read."; return@launch }
                            val started = withContext(Dispatchers.IO) {
                                runCatching { GeminiShim.start(termux, payload, GeminiShim.HOME) }.getOrNull()
                            }
                            if (started == null) {
                                message = "Termux did not answer. Check Settings > General > Sandbox."
                                return@launch
                            }
                            message = "Starting the shim…"
                            val ids = GeminiShim.awaitModels(60_000)
                            if (ids == null) {
                                val log = withContext(Dispatchers.IO) { runCatching { GeminiShim.log(termux, GeminiShim.HOME) }.getOrNull() }
                                message = "The shim did not come up. " + (log?.take(400) ?: started)
                            } else {
                                shimRunning = true
                                settings.saveCustomProvider(CustomProviders.geminiAccountProvider(ids))
                                message = "Running · ${ids.size} models listed."
                            }
                        } finally {
                            busy = false
                        }
                    }
                })
                PillButton("Remove", danger = true, onClick = {
                    settings.setGeminiAccount(null)
                    settings.removeCustomProvider(CustomProviders.GEMINI_ACCOUNT_PRESET)
                    runCatching { java.io.File(termux.sharedRoot, "gemini-cookies.json").delete() }
                    message = "Session removed."
                })
            }
        }
        NavRow(
            title = "Set up in Termux",
            value = "Copy the one-time install command",
            icon = com.sebialex.agent.R.drawable.ic_terminal,
            chevron = false,
            onClick = {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(GeminiShim.SETUP))
                message = "Copied. Paste it in Termux once. orjson builds from source with Rust, so it can take a while."
            },
        )
        message?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Palette.TextSecondary) }
    }

    pending?.let { text ->
        var pass by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        AppSheet(onDismiss = { pending = null }, title = "Unlock the session", subtitle = "The passphrase you set when exporting") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicTextField(
                    value = pass,
                    onValueChange = { pass = it; error = null },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Palette.TextPrimary),
                    cursorBrush = SolidColor(Palette.TextPrimary),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (pass.isEmpty()) Text("Passphrase", style = MaterialTheme.typography.bodyLarge, color = Palette.TextTertiary)
                        inner()
                    },
                )
                error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Palette.Danger) }
                SheetOption(
                    icon = com.sebialex.agent.R.drawable.ic_check,
                    title = "Import",
                    onClick = {
                        val result = runCatching { AgtFile.parse(text, pass.toCharArray()) }
                        val payload = result.getOrNull()
                        if (payload == null) {
                            error = when (val e = result.exceptionOrNull()) {
                                is BackupBundle.WrongPassphrase -> "Wrong passphrase."
                                is BackupBundle.BadBackup -> e.message ?: "That file is not a usable session."
                                else -> "Could not read that file."
                            }
                            return@SheetOption
                        }
                        settings.setGeminiAccount(Json.encodeToString(AgtFile.Payload.serializer(), payload))
                        // Register it as a provider so its models list in the chat and image pickers.
                        // The shim may not be running yet; keep whatever was listed before if so.
                        val existing = settings.customProvider(CustomProviders.GEMINI_ACCOUNT_PRESET)
                        scope.launch {
                            val catalog = withContext(Dispatchers.IO) {
                                runCatching { CustomProviders.fetchCatalog(CustomProviders.GEMINI_ACCOUNT_URL, null) }.getOrNull()
                            }
                            settings.saveCustomProvider(
                                CustomProviders.geminiAccountProvider(
                                    catalog?.first ?: existing?.models.orEmpty(),
                                    catalog?.second ?: existing?.modelInfo.orEmpty(),
                                ),
                            )
                        }
                        val path = handOff(payload)
                        message = if (path != null) "Imported. Cookie file at $path" else "Imported. Could not write the cookie file — allow All files access."
                        pending = null
                    },
                )
            }
        }
    }
}