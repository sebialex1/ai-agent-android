package com.sebialex.agent.data.local

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Where one on-device model stands on this phone. */
sealed interface ModelStatus {
    data object Missing : ModelStatus
    /** [total] is 0 until the server reports a size. [waiting] explains a paused download. */
    data class Downloading(val done: Long, val total: Long, val waiting: String? = null) : ModelStatus {
        val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    }
    data class Importing(val done: Long, val total: Long) : ModelStatus {
        val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    }
    data class Ready(val file: File, val bytes: Long) : ModelStatus
    data class Failed(val message: String) : ModelStatus
}

/**
 * Model files on this phone. Downloads go through the system [DownloadManager], so multi-GB
 * files keep downloading with a notification when the app is in the background, survive the
 * app being killed and retry on flaky networks. Files land in the app-specific external files
 * directory (no storage permission needed, removed with the app) as `<file>.part` and are
 * renamed once the byte count matches the catalog. Users can also import their own `.litertlm`.
 */
class ModelStore(private val context: Context, private val scope: CoroutineScope) {

    /** App-specific external storage, the only place DownloadManager can write for us. */
    private val externalDir: File? = context.getExternalFilesDir(DIR)
    val dir: File = (externalDir ?: File(context.filesDir, DIR)).apply { mkdirs() }
    /** LiteRT-LM's compiled GPU/CPU caches, kept apart from the model files. */
    val cacheDir: File = File(dir, CACHE_DIR).apply { mkdirs() }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val dm: DownloadManager? = context.getSystemService(DownloadManager::class.java)

    private val _statuses = MutableStateFlow<Map<String, ModelStatus>>(emptyMap())
    /** Status per model id, for every catalog model and imported file. */
    val statuses: StateFlow<Map<String, ModelStatus>> = _statuses.asStateFlow()

    private val _imported = MutableStateFlow<List<LocalModel>>(emptyList())
    /** `.litertlm` files the user imported, in name order. */
    val imported: StateFlow<List<LocalModel>> = _imported.asStateFlow()

    /** Errors from the last attempt per model, shown until the next attempt. */
    private val failures = HashMap<String, String>()
    private val importing = HashMap<String, ModelStatus.Importing>()
    private var poller: Job? = null

    init {
        refresh()
    }

    fun models(): List<LocalModel> = LocalModels.catalog + _imported.value

    /** Resolves a model id to its entry, including imported files that are on disk. */
    fun model(id: String): LocalModel? = LocalModels.resolve(id)

    /** The complete model file, or null while it is missing, partial or still downloading. */
    fun fileFor(model: LocalModel): File? {
        val f = File(dir, model.fileName)
        if (!f.isFile || f.length() == 0L) return null
        if (model.sizeBytes > 0 && f.length() != model.sizeBytes) return null
        return f
    }

    fun status(id: String): ModelStatus = _statuses.value[id] ?: ModelStatus.Missing

    /** Free space where models are stored. */
    fun freeBytes(): Long = runCatching { dir.usableSpace }.getOrDefault(0L)

    /** Re-reads the disk and the download queue. Cheap; safe from any thread. */
    @Synchronized
    fun refresh() {
        val catalogFiles = LocalModels.catalog.map { it.fileName }.toSet()
        val imports = dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(MODEL_EXT) && it.name !in catalogFiles }
            .sortedBy { it.name.lowercase() }
            .map { LocalModels.imported(it.name) }
        _imported.value = imports

        var active = false
        val out = LinkedHashMap<String, ModelStatus>()
        for (m in LocalModels.catalog + imports) {
            importing[m.id]?.let { out[m.id] = it; active = true; continue }
            val download = downloadStatus(m)
            if (download != null) {
                out[m.id] = download
                active = true
                continue
            }
            val file = fileFor(m)
            out[m.id] = when {
                file != null -> ModelStatus.Ready(file, file.length())
                failures[m.id] != null -> ModelStatus.Failed(failures[m.id]!!)
                else -> ModelStatus.Missing
            }
        }
        // An import that is still copying has no file in the list yet.
        importing.forEach { (id, st) -> if (id !in out) { out[id] = st; active = true } }
        _statuses.value = out
        if (active) ensurePolling()
    }

    /** Starts a download. Returns an error message instead when it cannot start. */
    fun download(model: LocalModel): String? {
        val url = model.url ?: return "This model has no download link."
        val manager = dm ?: return "This phone has no download service."
        if (externalDir == null) return "Storage is not available right now. Try again in a moment."
        if (fileFor(model) != null) return null
        val needed = model.sizeBytes + SPACE_MARGIN
        if (model.sizeBytes > 0 && freeBytes() < needed) {
            return "Not enough free space: ${LocalModels.formatSize(model.sizeBytes)} needed, ${LocalModels.formatSize(freeBytes())} free."
        }
        synchronized(this) {
            failures.remove(model.id)
            prefs.getLong(model.id, -1L).takeIf { it >= 0 }?.let { runCatching { manager.remove(it) } }
            partFile(model).delete()
            File(dir, model.fileName).takeIf { it.exists() }?.delete()
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(model.name)
                .setDescription("On-device model, ${LocalModels.formatSize(model.sizeBytes)}")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, DIR, model.fileName + PART)
                .setMimeType("application/octet-stream")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
            val id = try {
                manager.enqueue(request)
            } catch (e: Exception) {
                return "Could not start the download: ${e.message ?: e.javaClass.simpleName}"
            }
            prefs.edit().putLong(model.id, id).apply()
        }
        refresh()
        return null
    }

    /** Cancels a running download and removes its partial file. */
    fun cancel(model: LocalModel) {
        synchronized(this) {
            prefs.getLong(model.id, -1L).takeIf { it >= 0 }?.let { id -> runCatching { dm?.remove(id) } }
            prefs.edit().remove(model.id).apply()
            partFile(model).delete()
            failures.remove(model.id)
        }
        refresh()
    }

    /** Deletes the model file (and any partial download). The caller unloads it first. */
    fun delete(model: LocalModel) {
        cancel(model)
        File(dir, model.fileName).delete()
        // Cache file names are chosen by LiteRT-LM, so clear them all; other models rebuild
        // theirs on the next load.
        cacheDir.listFiles().orEmpty().forEach { it.deleteRecursively() }
        refresh()
    }

    /**
     * Copies a user-picked `.litertlm` into the models directory. A file with a catalog name
     * and the catalog's exact size simply becomes that catalog model.
     */
    suspend fun import(uri: Uri): Result<LocalModel> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name: String? = null
        var size = 0L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        val fileName = name?.substringAfterLast('/')?.trim().orEmpty()
        if (!fileName.endsWith(MODEL_EXT)) {
            return@withContext Result.failure(IOException("Pick a $MODEL_EXT model file (got ${fileName.ifBlank { "an unnamed file" }})."))
        }
        if (size > 0 && freeBytes() < size + SPACE_MARGIN) {
            return@withContext Result.failure(IOException("Not enough free space for ${LocalModels.formatSize(size)}."))
        }
        val model = LocalModels.catalog.firstOrNull { it.fileName == fileName } ?: LocalModels.imported(fileName)
        val tmp = File(dir, "$fileName.import")
        val target = File(dir, fileName)
        try {
            synchronized(this@ModelStore) { importing[model.id] = ModelStatus.Importing(0, size) }
            refresh()
            val input = resolver.openInputStream(uri) ?: throw IOException("Could not open the file.")
            input.use { inp ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 20)
                    var copied = 0L
                    var lastReport = 0L
                    while (isActive) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        copied += n
                        if (copied - lastReport >= REPORT_EVERY) {
                            lastReport = copied
                            synchronized(this@ModelStore) { importing[model.id] = ModelStatus.Importing(copied, size) }
                            refresh()
                        }
                    }
                }
            }
            if (!isActive) throw kotlinx.coroutines.CancellationException("import cancelled")
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("Could not move the imported file into place.")
            Result.success(model)
        } catch (e: kotlinx.coroutines.CancellationException) {
            tmp.delete()
            throw e
        } catch (e: Exception) {
            tmp.delete()
            Result.failure(e)
        } finally {
            synchronized(this@ModelStore) { importing.remove(model.id) }
            refresh()
        }
    }

    private fun partFile(model: LocalModel) = File(dir, model.fileName + PART)

    /**
     * The download queue's view of [model], finishing successful downloads on the way.
     * Null when no download is tracked for it.
     */
    private fun downloadStatus(model: LocalModel): ModelStatus? {
        val id = prefs.getLong(model.id, -1L).takeIf { it >= 0 } ?: return null
        val manager = dm ?: return null
        val row = runCatching {
            manager.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                if (!c.moveToFirst()) null
                else Row(
                    status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                    done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                    total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                    reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                )
            }
        }.getOrNull()
        if (row == null) {
            // Cancelled from the notification or cleared by the system.
            prefs.edit().remove(model.id).apply()
            partFile(model).delete()
            return null
        }
        val total = if (row.total > 0) row.total else model.sizeBytes
        return when (row.status) {
            DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING -> ModelStatus.Downloading(row.done, total)
            DownloadManager.STATUS_PAUSED -> ModelStatus.Downloading(row.done, total, pausedReason(row.reason))
            DownloadManager.STATUS_SUCCESSFUL -> {
                prefs.edit().remove(model.id).apply()
                val part = partFile(model)
                val target = File(dir, model.fileName)
                val size = part.length()
                when {
                    !part.isFile -> failures[model.id] = "The downloaded file is missing."
                    model.sizeBytes > 0 && size != model.sizeBytes -> {
                        part.delete()
                        failures[model.id] = "Download incomplete (${LocalModels.formatSize(size)} of ${LocalModels.formatSize(model.sizeBytes)}). Try again."
                    }
                    else -> {
                        target.delete()
                        if (!part.renameTo(target)) failures[model.id] = "Could not move the downloaded file into place."
                    }
                }
                null
            }
            DownloadManager.STATUS_FAILED -> {
                prefs.edit().remove(model.id).apply()
                runCatching { manager.remove(id) }
                partFile(model).delete()
                failures[model.id] = failedReason(row.reason)
                null
            }
            else -> ModelStatus.Downloading(row.done, total)
        }
    }

    private data class Row(val status: Int, val done: Long, val total: Long, val reason: Int)

    /** Polls the download queue while something is in flight, then stops by itself. */
    private fun ensurePolling() {
        if (poller?.isActive == true) return
        poller = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(POLL_MS)
                refresh()
                val busy = _statuses.value.values.any { it is ModelStatus.Downloading || it is ModelStatus.Importing }
                if (!busy) break
            }
        }
    }

    private fun pausedReason(reason: Int): String = when (reason) {
        DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Waiting for Wi-Fi"
        DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Waiting for network"
        DownloadManager.PAUSED_WAITING_TO_RETRY -> "Connection lost, retrying"
        else -> "Paused"
    }

    private fun failedReason(reason: Int): String = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Not enough free space."
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Storage is not available."
        DownloadManager.ERROR_CANNOT_RESUME -> "The download could not resume. Try again."
        DownloadManager.ERROR_HTTP_DATA_ERROR -> "Network error while downloading. Try again."
        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "Too many redirects from the server."
        DownloadManager.ERROR_FILE_ERROR -> "Could not write the file."
        in 400..599 -> "The server answered HTTP $reason."
        else -> "Download failed ($reason). Try again."
    }

    companion object {
        const val DIR = "models"
        const val MODEL_EXT = ".litertlm"
        private const val CACHE_DIR = "cache"
        private const val PART = ".part"
        private const val PREFS = "local_model_downloads"
        private const val POLL_MS = 700L
        private const val REPORT_EVERY = 16L shl 20
        /** Extra room kept free beyond the file itself (GPU caches, other apps). */
        private const val SPACE_MARGIN = 300L * 1024 * 1024
    }
}
