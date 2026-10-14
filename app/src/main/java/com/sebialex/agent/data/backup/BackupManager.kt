package com.sebialex.agent.data.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.sebialex.agent.data.persistence.SessionStore
import com.sebialex.agent.data.secure.SecureSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.io.File

/**
 * Keeps settings and conversations alive across uninstall/reinstall.
 *
 * Two independent paths:
 *  1. **Backup folder** (Storage Access Framework): the user picks a folder once; the app keeps
 *     `agent-backup.json` there, rewritten a few seconds after every change. The file lives outside
 *     the app's private storage, so it survives uninstall. API keys are only included when a backup
 *     passphrase is set, and then only encrypted.
 *  2. **Android Auto Backup**: a key-free copy of the same bundle is kept in `files/autobackup/`,
 *     which is the only path the backup rules include. On a reinstall (or new phone) Android restores
 *     that file and the app imports it on first start. Keystore-bound data is never part of it.
 */
class BackupManager(
    private val context: Context,
    private val settings: SecureSettings,
    private val sessions: SessionStore,
    private val scope: CoroutineScope,
) {
    private val state = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
    private val autoDir = File(context.filesDir, "autobackup")
    private val autoFile = File(autoDir, BackupBundle.FILE_NAME)

    data class Status(
        val folderUri: String? = null,
        val folderName: String? = null,
        val lastBackupAt: Long = 0,
        val lastError: String? = null,
        val keysIncluded: Boolean = false,
        /** The chosen folder already holds a backup that was not restored yet: auto backup waits. */
        val pendingRestore: Boolean = false,
        val busy: Boolean = false,
    )

    private val _status = MutableStateFlow(readStatus())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** True on the first start of a fresh install that restored nothing: offer to import a backup file. */
    private val _offerRestore = MutableStateFlow(false)
    val offerRestore: StateFlow<Boolean> = _offerRestore.asStateFlow()

    data class RestoreResult(
        val conversations: Int,
        val settingsRestored: Boolean,
        val keysRestored: Int,
        /** The backup holds encrypted keys that were not restored (no or wrong passphrase). */
        val keysLocked: Boolean,
        val skipped: Int,
    )

    private var pending: Job? = null

    init {
        val first = !state.getBoolean(K_INITIALIZED, false)
        if (first) {
            // Auto Backup may have put the key-free bundle back before this first start.
            val restored = runCatching { restoreAuto() }.getOrDefault(false)
            val empty = settings.isPristine() && sessions.loadAll().isEmpty()
            state.edit().putBoolean(K_INITIALIZED, true).apply()
            _offerRestore.value = !restored && empty
        }
        sessions.onChanged = { schedule() }
        scope.launch {
            // Settings changes (model, providers, keys, switches) also trigger a backup.
            settings.state.drop(1).collect { schedule() }
        }
        scope.launch {
            settings.memoryChats.drop(1).collect { schedule() }
        }
    }

    fun dismissOffer() { _offerRestore.value = false }

    // ---- Folder -------------------------------------------------------------------------------------

    /**
     * Remembers the tree [uri] picked by the user (permission is persisted). When the folder already
     * holds a backup the app does not overwrite it: it waits for [restore] or an explicit [backUpNow].
     */
    fun setFolder(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        val old = state.getString(K_FOLDER, null)
        if (old != null && old != uri.toString()) runCatching {
            context.contentResolver.releasePersistableUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        val existing = findBackup(uri)
        state.edit().putString(K_FOLDER, uri.toString()).putBoolean(K_PENDING_RESTORE, existing != null)
            .putString(K_ERROR, null).apply()
        publish()
        if (existing == null) schedule(immediate = true)
    }

    /** Stops writing to the folder (the file already there stays). */
    fun clearFolder() {
        state.getString(K_FOLDER, null)?.let { old ->
            runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        }
        state.edit().remove(K_FOLDER).remove(K_PENDING_RESTORE).apply()
        publish()
    }

    /** The backup file inside the chosen folder, if any. */
    fun backupInFolder(): Uri? = state.getString(K_FOLDER, null)?.let { findBackup(Uri.parse(it)) }

    private fun findBackup(tree: Uri): Uri? = runCatching {
        val treeDoc = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeDoc)
        context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == BackupBundle.FILE_NAME) return@runCatching DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
            }
            null
        }
    }.getOrNull()

    // ---- Backing up ---------------------------------------------------------------------------------

    /** Writes a backup a few seconds after the last change (changes arrive in bursts while a chat streams). */
    private fun schedule(immediate: Boolean = false) {
        pending?.cancel()
        pending = scope.launch {
            if (!immediate) delay(DEBOUNCE_MS)
            runCatching { writeAll(userAction = false) }
        }
    }

    /** Back up now (user action). Also clears a pending-restore pause, so it overwrites the old file on purpose. */
    suspend fun backUpNow(): Result<Unit> = runCatching {
        pending?.cancel()
        writeAll(userAction = true)
        if (_status.value.lastError != null) throw IllegalStateException(_status.value.lastError)
    }

    @Synchronized
    private fun writeAll(userAction: Boolean) {
        setBusy(true)
        try {
            // 1. Key-free copy for Android Auto Backup (capped so it stays well under the 25 MB quota).
            writeAuto()
            // 2. The user's folder.
            val folder = state.getString(K_FOLDER, null) ?: return
            if (state.getBoolean(K_PENDING_RESTORE, false) && !userAction) return
            val passphrase = settings.backupPassphrase()
            val text = BackupBundle.build(
                settings.exportPlain(), sessions.loadAll(),
                passphrase?.let { settings.exportSecrets() }, passphrase?.toCharArray(),
            )
            try {
                writeToFolder(Uri.parse(folder), text)
                state.edit().putLong(K_LAST, System.currentTimeMillis()).putString(K_ERROR, null)
                    .putBoolean(K_KEYS, passphrase != null).putBoolean(K_PENDING_RESTORE, false).apply()
            } catch (e: Exception) {
                state.edit().putString(K_ERROR, e.message ?: "Could not write the backup folder").apply()
            }
        } finally {
            setBusy(false)
            publish()
        }
    }

    private fun writeToFolder(tree: Uri, text: String) {
        val resolver = context.contentResolver
        val target = findBackup(tree) ?: run {
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            DocumentsContract.createDocument(resolver, parent, "application/json", BackupBundle.FILE_NAME)
        } ?: throw java.io.IOException("Cannot create the backup file")
        // "wt" truncates; the bytes are built in memory first so a failure before this point never damages the old file.
        resolver.openOutputStream(target, "wt")?.use { it.write(text.toByteArray()) } ?: throw java.io.IOException("Cannot write the backup file")
    }

    private fun writeAuto() {
        var list = sessions.loadAll().sortedByDescending { it.updatedAt }
        var text = BackupBundle.build(settings.exportPlain(), list, null, null)
        while (text.length > AUTO_CAP_CHARS && list.isNotEmpty()) {
            list = list.take(list.size / 2)
            text = BackupBundle.build(settings.exportPlain(), list, null, null)
        }
        autoDir.mkdirs()
        val tmp = File(autoDir, BackupBundle.FILE_NAME + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(autoFile)) { autoFile.writeText(text); tmp.delete() }
    }

    // ---- Restoring ---------------------------------------------------------------------------------

    /** Imports the key-free copy that Android Auto Backup restored. Returns true when it held anything. */
    private fun restoreAuto(): Boolean {
        if (!autoFile.isFile) return false
        val r = apply(BackupBundle.parse(autoFile.readText()), null)
        return r.conversations > 0 || r.settingsRestored
    }

    /** Restores from [uri] (a backup file). [passphrase] unlocks API keys; without it everything else is still restored. */
    fun restore(uri: Uri, passphrase: String? = settings.backupPassphrase()): Result<RestoreResult> = runCatching {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw java.io.IOException("Cannot read the file")
        val result = apply(BackupBundle.parse(text), passphrase)
        state.edit().putBoolean(K_PENDING_RESTORE, false).apply()
        _offerRestore.value = false
        publish()
        result
    }

    /** Restore from the file in the chosen folder. */
    fun restoreFromFolder(passphrase: String? = settings.backupPassphrase()): Result<RestoreResult> {
        val uri = backupInFolder() ?: return Result.failure(java.io.FileNotFoundException("No backup file in the folder"))
        return restore(uri, passphrase)
    }

    @Synchronized
    private fun apply(parsed: BackupBundle.Parsed, passphrase: String?): RestoreResult {
        // Conversations: new or newer than the local copy. Running this twice changes nothing.
        val toWrite = BackupBundle.conversationsToImport(parsed.conversations, sessions.updatedAtById())
        val hook = sessions.onChanged
        sessions.onChanged = null
        try { toWrite.forEach { sessions.save(it) } } finally { sessions.onChanged = hook }
        // Settings: the backed-up choices apply over defaults. Values of unexpected types were already dropped.
        val values = parsed.settings.mapValues { BackupBundle.primitiveValue(it.value) }
        if (values.isNotEmpty()) settings.importPlain(values)
        // Keys.
        var keys = 0
        var locked = false
        if (parsed.hasSecrets) {
            if (passphrase.isNullOrEmpty()) locked = true else try {
                val secrets = BackupBundle.openSecrets(parsed, passphrase.toCharArray())
                settings.importSecrets(secrets)
                keys = secrets.keys.values.sumOf { it.size }
            } catch (_: BackupBundle.WrongPassphrase) {
                locked = true
            }
        }
        return RestoreResult(toWrite.size, values.isNotEmpty(), keys, locked, parsed.skippedConversations)
    }

    // ---- Status ---------------------------------------------------------------------------------------

    private fun setBusy(b: Boolean) { _status.value = _status.value.copy(busy = b) }

    private fun publish() { _status.value = readStatus().copy(busy = _status.value.busy) }

    private fun readStatus(): Status {
        val folder = state.getString(K_FOLDER, null)
        return Status(
            folderUri = folder,
            folderName = folder?.let { folderLabel(Uri.parse(it)) },
            lastBackupAt = state.getLong(K_LAST, 0),
            lastError = state.getString(K_ERROR, null),
            keysIncluded = state.getBoolean(K_KEYS, false),
            pendingRestore = state.getBoolean(K_PENDING_RESTORE, false),
        )
    }

    /** A readable path such as "Documents/Agent" from the tree id ("primary:Documents/Agent"). */
    private fun folderLabel(tree: Uri): String = runCatching {
        DocumentsContract.getTreeDocumentId(tree).substringAfter(':').ifEmpty { "Internal storage" }
    }.getOrDefault("Selected folder")

    companion object {
        private const val STATE_PREFS = "agent_backup_state"
        private const val K_INITIALIZED = "initialized"
        private const val K_FOLDER = "folder"
        private const val K_LAST = "last_backup_at"
        private const val K_ERROR = "last_error"
        private const val K_KEYS = "keys_included"
        private const val K_PENDING_RESTORE = "pending_restore"
        private const val DEBOUNCE_MS = 4_000L
        private const val AUTO_CAP_CHARS = 8_000_000
    }
}
