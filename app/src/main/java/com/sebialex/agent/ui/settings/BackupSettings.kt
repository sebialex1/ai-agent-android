package com.sebialex.agent.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sebialex.agent.data.backup.BackupManager
import com.sebialex.agent.data.secure.SecureSettings
import com.sebialex.agent.ui.theme.MonoSmall
import com.sebialex.agent.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Starts a restore: opens the system file picker (or restores [fixed] directly), restores without
 * asking, and only asks for the passphrase when the backup holds encrypted API keys that the stored
 * passphrase did not open. Returns the function that begins the flow and draws its dialogs.
 */
@Composable
internal fun rememberRestoreFlow(backup: BackupManager, onRestored: () -> Unit, onMessage: (String) -> Unit): (Boolean) -> Unit {
    val scope = rememberCoroutineScope()
    var lockedUri by remember { mutableStateOf<Uri?>(null) }
    var pass by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }

    fun run(uri: Uri?, passphrase: String?) {
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                if (uri == null) backup.restoreFromFolder(passphrase) else backup.restore(uri, passphrase)
            }
            r.onSuccess { res ->
                onRestored()
                if (res.keysLocked) {
                    lockedUri = uri ?: backup.backupInFolder()
                    wrong = !passphrase.isNullOrEmpty()
                } else {
                    lockedUri = null
                    onMessage(
                        "Restored ${res.conversations} conversation${if (res.conversations == 1) "" else "s"}" +
                            (if (res.settingsRestored) ", settings and providers" else "") +
                            (if (res.keysRestored > 0) ", ${res.keysRestored} API key${if (res.keysRestored == 1) "" else "s"}" else "") + ".",
                    )
                }
            }.onFailure { onMessage(it.message ?: "Restore failed") }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) run(uri, null) }
    // true: the file in the chosen backup folder; false: pick any backup file.
    val start: (Boolean) -> Unit = { fromFolder ->
        if (fromFolder) run(null, null) else picker.launch(arrayOf("application/json", "application/octet-stream", "text/plain"))
    }

    lockedUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { lockedUri = null; pass = "" },
            containerColor = Palette.Surface,
            title = { Text("API keys are encrypted", style = MaterialTheme.typography.titleSmall, color = Palette.TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Settings and conversations are restored. Enter the backup passphrase to restore the API keys too." +
                            if (wrong) " That passphrase did not match." else "",
                        style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary,
                    )
                    PassphraseField(pass) { pass = it }
                }
            },
            confirmButton = { TextButton(onClick = { val p = pass; pass = ""; run(uri, p) }) { Text("Restore keys", color = Palette.TextPrimary) } },
            dismissButton = { TextButton(onClick = { lockedUri = null; pass = "" }) { Text("Skip", color = Palette.TextSecondary) } },
        )
    }
    return start
}

@Composable
private fun PassphraseField(value: String, onChange: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        textStyle = MonoSmall.copy(color = Palette.TextPrimary),
        cursorBrush = SolidColor(Palette.TextPrimary),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.SurfaceHigh).padding(14.dp),
        decorationBox = { inner ->
            if (value.isEmpty()) Text("Passphrase", style = MonoSmall, color = Palette.TextTertiary)
            inner()
        },
    )
}

/** Shown once on the first start of a fresh install with nothing restored. */
@Composable
fun RestoreOffer(backup: BackupManager, onRestored: () -> Unit) {
    val offer by backup.offerRestore.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf<String?>(null) }
    val start = rememberRestoreFlow(backup, onRestored) { message = it; backup.dismissOffer() }
    if (offer) {
        AlertDialog(
            onDismissRequest = { backup.dismissOffer() },
            containerColor = Palette.Surface,
            title = { Text("Restore a backup?", style = MaterialTheme.typography.titleSmall, color = Palette.TextPrimary) },
            text = {
                Text(
                    "If you saved a backup file before reinstalling, pick agent-backup.json to bring back your providers, models, settings and conversations. You can also do this later in Settings > Backup & restore.",
                    style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary,
                )
            },
            confirmButton = { TextButton(onClick = { start(false) }) { Text("Choose file", color = Palette.TextPrimary) } },
            dismissButton = { TextButton(onClick = { backup.dismissOffer() }) { Text("Not now", color = Palette.TextSecondary) } },
        )
    }
    message?.let {
        AlertDialog(
            onDismissRequest = { message = null },
            containerColor = Palette.Surface,
            text = { Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK", color = Palette.TextPrimary) } },
        )
    }
}

/** The one action the backup folder row offers, depending on its state. */
enum class BackupFolderAction(val label: String) { CHOOSE("Choose"), RESTORE("Restore"), BACK_UP_NOW("Back up now") }

internal object BackupRows {
    /** No folder: choose one. A folder that already holds another backup: restore it. Otherwise: back up now. */
    fun folderAction(folderSet: Boolean, pendingRestore: Boolean): BackupFolderAction = when {
        !folderSet -> BackupFolderAction.CHOOSE
        pendingRestore -> BackupFolderAction.RESTORE
        else -> BackupFolderAction.BACK_UP_NOW
    }
}

/**
 * The "Backup & restore" page: the folder as one row with one contextual action (tap the row to
 * change the folder), the API-key passphrase (its off switch lives in the edit view), and one
 * Restore group for both sources.
 */
@Composable
internal fun BackupSettings(backup: BackupManager, settings: SecureSettings, onRestored: () -> Unit) {
    val status by backup.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var editingPass by remember { mutableStateOf(false) }
    var pass by remember { mutableStateOf("") }
    var hasPass by remember { mutableStateOf(settings.backupPassphrase() != null) }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) backup.setFolder(uri) }
    val restore = rememberRestoreFlow(backup, onRestored) { message = it }
    fun restoreFromFolder() { if (backup.backupInFolder() != null) restore(true) else message = "No backup file in the folder yet." }

    SettingsGroup("Backup folder", help = "Pick a folder outside the app (for example Documents) so the backup survives an uninstall. Without one, only Android's own backup (Google) can bring data back. Backs up automatically a few seconds after each change. Tap the folder to change it.") {
        val action = BackupRows.folderAction(status.folderUri != null, status.pendingRestore)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { pickFolder.launch(null) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (status.folderUri == null) "Not set" else status.folderName.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary,
                )
                Text(
                    when {
                        status.folderUri == null -> "Choose a folder to back up automatically."
                        status.pendingRestore -> "This folder already holds a backup. Restore it, or replace it with a new one."
                        status.lastError != null -> "Last backup failed: ${status.lastError}"
                        status.lastBackupAt > 0 -> "Last backup " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(status.lastBackupAt)) +
                            if (status.keysIncluded) " \u00b7 API keys included (encrypted)" else " \u00b7 API keys not included"
                        else -> "Backs up automatically."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (status.lastError != null) Palette.Danger else Palette.TextTertiary,
                )
            }
            Spacer(Modifier.width(8.dp))
            PillButton(
                action.label,
                onClick = {
                    when (action) {
                        BackupFolderAction.CHOOSE -> pickFolder.launch(null)
                        BackupFolderAction.RESTORE -> restoreFromFolder()
                        BackupFolderAction.BACK_UP_NOW -> scope.launch {
                            val r = backup.backUpNow()
                            message = if (r.isSuccess) "Backup written." else "Backup failed: ${r.exceptionOrNull()?.message}"
                        }
                    }
                },
                primary = action != BackupFolderAction.BACK_UP_NOW,
                enabled = action != BackupFolderAction.BACK_UP_NOW || !status.busy,
            )
        }
        // Only while the folder holds a backup that was not restored: automatic backups wait, so
        // overwriting it stays a deliberate, separate step (never next to Restore as a second pill).
        if (status.pendingRestore) {
            RowDivider()
            NavRow("Replace it with a new backup", icon = com.sebialex.agent.R.drawable.ic_refresh, onClick = {
                if (!status.busy) scope.launch {
                    val r = backup.backUpNow()
                    message = if (r.isSuccess) "Backup written." else "Backup failed: ${r.exceptionOrNull()?.message}"
                }
            }, chevron = false)
        }
    }

    SettingsGroup("API keys in the backup", help = "Without a passphrase, API keys are never written to the backup. The passphrase is not stored in the backup: keep it, you need it to restore the keys on a new install.") {
        if (editingPass) {
            PassphraseField(pass) { pass = it }
            if (pass.isNotEmpty() && pass.trim().length < 8) Text("At least 8 characters.", style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                PillButton("Save", {
                    settings.setBackupPassphrase(pass.trim()); hasPass = pass.isNotBlank(); pass = ""; editingPass = false
                    scope.launch { backup.backUpNow() }
                }, primary = true, enabled = pass.trim().length >= 8)
                PillButton("Cancel", { pass = ""; editingPass = false })
            }
            // Turning the keys off belongs to editing the passphrase, not to the row.
            if (hasPass) PillButton("Stop including API keys", {
                settings.setBackupPassphrase(null); hasPass = false; pass = ""; editingPass = false
                scope.launch { backup.backUpNow() }
            }, danger = true)
        } else {
            NavRow(
                title = if (hasPass) "Included, encrypted" else "Not included",
                value = if (hasPass) "Tap to change the passphrase" else "Tap to set a passphrase",
                icon = com.sebialex.agent.R.drawable.ic_key,
                onClick = { editingPass = true },
                chevron = true,
            )
        }
    }

    SettingsGroup("Restore", help = "Restore from the backup folder or from any backup file, for example after a reinstall.") {
        if (status.folderUri != null) {
            NavRow("Restore from backup folder", value = status.folderName, icon = com.sebialex.agent.R.drawable.ic_folder, onClick = { restoreFromFolder() }, chevron = false)
            RowDivider()
        }
        NavRow("Restore from file", icon = com.sebialex.agent.R.drawable.ic_file, onClick = { restore(false) }, chevron = false)
    }

    message?.let {
        AlertDialog(
            onDismissRequest = { message = null },
            containerColor = Palette.Surface,
            text = { Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK", color = Palette.TextPrimary) } },
        )
    }
}
