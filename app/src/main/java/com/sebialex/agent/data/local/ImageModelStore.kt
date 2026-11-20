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

/**
 * On-device image models ([ImageModels]): a `.zip` download (or a user-picked one) that unpacks
 * into a model directory the MediaPipe Image Generator reads (see [ImageGenRuntime]). Separate
 * from [ModelStore] on purpose: text models are single `.litertlm` files kept in Downloads,
 * image models are directories built by unpacking a zip, so here DownloadManager only delivers
 * the zip into an app-owned folder and the unpack happens right after, before the zip is
 * deleted. The unpacked model directory lives under [root] (next to the text models, same
 * reinstall-surviving rules).
 */
class ImageModelStore(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Base directory holding one folder per image model. */
    val root: File,
    private val hfToken: () -> String = { "" },
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val dm: DownloadManager? = context.getSystemService(DownloadManager::class.java)

    private val _statuses = MutableStateFlow<Map<String, ModelStatus>>(emptyMap())
    /** Status per image model id, for every catalog model and imported zip. */
    val statuses: StateFlow<Map<String, ModelStatus>> = _statuses.asStateFlow()

    /** The imported image models (from picked zips), in name order. */
    private val _imported = MutableStateFlow<List<ImageModel>>(emptyList())
    val imported: StateFlow<List<ImageModel>> = _imported.asStateFlow()

    private val failures = HashMap<String, String>()
    /** Model ids being unpacked (or copied in for an import), with bytes written so far. */
    private val unpacking = HashMap<String, Long>()

    /** Model ids downloading their [ImageModel.parts], with bytes written so far. */
    private val partsProgress = HashMap<String, Long>()
    private val partsJobs = HashMap<String, Job>()
    private var poller: Job? = null

    init {
        root.mkdirs()
        refresh()
    }

    /** Catalog models + the imported zips, for the settings page and the status refresh. */
    fun models(): List<ImageModel> = ImageModels.all + _imported.value

    fun status(id: String): ModelStatus = _statuses.value[id] ?: ModelStatus.Missing

    /** The unpacked model directory for [id], or null while missing/incomplete/in progress. */
    fun readyDir(id: String): File? {
        val model = ImageModels.resolve(id) ?: return null
        val dir = modelDir(model)
        return if (ImageZip.looksLikeModelDir(dir) && !busy(model.id)) dir else null
    }

    fun freeBytes(): Long = runCatching { root.usableSpace }.getOrDefault(0L)

    private fun modelDir(model: ImageModel) = File(root, model.slug)

    private fun zipsDir(): File = File(context.getExternalFilesDir(null) ?: context.filesDir, ZIPS_DIR)

    private fun zipFile(model: ImageModel) = File(zipsDir(), model.zipName)

    /** Holds finished parts of a [ImageModel.parts] model until the last one arrived. */
    private fun partsStagingDir(model: ImageModel) = File(root, model.slug + ".parts")

    /** A parts download's in-flight file, kept between runs so it can resume. */
    private fun partFile(model: ImageModel, part: ImageModelPart) =
        File(zipsDir(), "${model.slug}-${part.name}.part")

    private fun busy(id: String): Boolean =
        prefs.getLong(id, -1L) >= 0 || synchronized(this) { unpacking.containsKey(id) || partsProgress.containsKey(id) }

    /** Re-reads the disk and the download queue. Cheap; safe from any thread. */
    @Synchronized
    fun refresh() {
        // Imports: unpacked model directories that are not a catalog slug. The directory name is
        // the model of record (see [ImageModels.imported]), so the ids stay stable forever.
        val known = ImageModels.all.map { it.slug }.toSet()
        val scanned = root.listFiles().orEmpty()
            .filter { it.isDirectory && it.name !in known }
            .sortedBy { it.name.lowercase() }
            .map { ImageModels.imported(it.name) }
        _imported.value = scanned

        var active = false
        val out = LinkedHashMap<String, ModelStatus>()
        for (m in models()) {
            synchronized(this) { unpacking[m.id] }?.let {
                out[m.id] = ModelStatus.Importing(it, m.sizeBytes)
                active = true
                continue
            }
            synchronized(this) { partsProgress[m.id] }?.let { done ->
                out[m.id] = ModelStatus.Downloading(done, m.sizeBytes)
                active = true
                continue
            }
            val download = downloadStatus(m)
            if (download != null) {
                out[m.id] = download
                active = true
                continue
            }
            out[m.id] = when {
                ImageZip.looksLikeModelDir(modelDir(m)) -> ModelStatus.Ready(modelDir(m), dirBytes(modelDir(m)))
                failures[m.id] != null -> ModelStatus.Failed(failures[m.id]!!)
                else -> ModelStatus.Missing
            }
        }
        _statuses.value = out
        if (active) ensurePolling()
    }

    private fun dirBytes(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Starts the zip download (or the ncnn models' own file downloads). */
    fun download(model: ImageModel): String? {
        if (model.parts.isNotEmpty()) return downloadParts(model)
        val url = model.url ?: return "This model has no download link."
        val manager = dm ?: return "This phone has no download service."
        if (ImageZip.looksLikeModelDir(modelDir(model))) return null
        val needed = model.sizeBytes + SPACE_MARGIN + UNPACK_MARGIN
        if (model.sizeBytes > 0 && freeBytes() < needed) {
            return "Not enough free space: ${LocalModels.formatSize(model.sizeBytes)} needed, ${LocalModels.formatSize(freeBytes())} free."
        }
        synchronized(this) {
            failures.remove(model.id)
            prefs.getLong(model.id, -1L).takeIf { it >= 0 }?.let { runCatching { manager.remove(it) } }
            zipFile(model).delete()
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(model.name)
                .setDescription("On-device image model, ${LocalModels.formatSize(model.sizeBytes)}")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, ZIPS_DIR, model.zipName)
                .setMimeType("application/zip")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .apply { if (hfToken().isNotBlank()) addRequestHeader("Authorization", "Bearer " + hfToken()) }
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

    /** Cancels a running download. The unpacked directory stays untouched. */
    fun cancel(model: ImageModel) {
        synchronized(this) {
            prefs.getLong(model.id, -1L).takeIf { it >= 0 }?.let { id -> runCatching { dm?.remove(id) } }
            prefs.edit().remove(model.id).apply()
            zipFile(model).delete()
            partsJobs.remove(model.id)?.cancel()
            partsProgress.remove(model.id)
            partsStagingDir(model).deleteRecursively()
            model.parts.forEach { partFile(model, it).delete() }
            failures.remove(model.id)
        }
        refresh()
    }

    /**
     * The ncnn models' download: their files live on Hugging Face as individual files, so each
     * [ImageModelPart] is fetched directly (with resume support) into a staging directory,
     * and the finished set is moved into the model directory. Progress keeps parts-based models
     * in ModelStatus.Downloading through [refresh]; a failed part leaves the part files on disk
     * so "Try again" continues where it stopped.
     */
    private fun downloadParts(model: ImageModel): String? {
        val needed = model.sizeBytes + SPACE_MARGIN
        if (model.sizeBytes > 0 && freeBytes() < needed) {
            return "Not enough free space: ${LocalModels.formatSize(model.sizeBytes)} needed, ${LocalModels.formatSize(freeBytes())} free."
        }
        synchronized(this) {
            failures.remove(model.id)
            if (partsJobs.containsKey(model.id)) return null
            partsProgress[model.id] = 0L
        }
        partsJobs[model.id] = scope.launch(Dispatchers.IO) {
            try {
                val staging = partsStagingDir(model)
                staging.mkdirs()
                for (part in model.parts) {
                    val done = File(staging, part.name)
                    if (done.isFile && done.length() == part.sizeBytes) continue
                    val partial = partFile(model, part)
                    var written = partial.takeIf { it.isFile }?.length() ?: 0L
                    if (written > part.sizeBytes) written = 0L
                    val connection = java.net.URL(part.url).openConnection() as java.net.HttpURLConnection
                    try {
                        connection.instanceFollowRedirects = true
                        connection.connectTimeout = 15_000
                        connection.readTimeout = 60_000
                        if (written > 0) connection.setRequestProperty("Range", "bytes=$written-")
                        if (hfToken().isNotBlank()) connection.setRequestProperty("Authorization", "Bearer " + hfToken())
                        connection.connect()
                        val code = connection.responseCode
                        if (code !in 200..299) throw IOException("HTTP $code for ${part.name}")
                        connection.inputStream.use { input ->
                            java.io.FileOutputStream(partial, written > 0 && code == 206).use { out ->
                                if (written > 0 && code == 206) out.getChannel().position(written)
                                val buf = ByteArray(1 shl 20)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    synchronized(this@ImageModelStore) {
                                        partsProgress[model.id] = partsDoneBefore(model, part) + partial.length()
                                    }
                                }
                            }
                        }
                        if (partial.length() != part.sizeBytes) {
                            throw IOException("${part.name}: downloaded ${LocalModels.formatSize(partial.length())} of ${LocalModels.formatSize(part.sizeBytes)}.")
                        }
                        moveInto(partial, done)
                    } finally {
                        connection.disconnect()
                    }
                }
                // All parts present: move them into the model directory proper.
                val dir = modelDir(model)
                dir.mkdirs()
                staging.listFiles().orEmpty().forEach { moveInto(it, File(dir, it.name)) }
                staging.deleteRecursively()
                model.parts.forEach { partFile(model, it).delete() }
                failures.remove(model.id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failures[model.id] = e.message ?: "Download failed."
            } finally {
                synchronized(this) { partsProgress.remove(model.id) }
                synchronized(this) { partsJobs.remove(model.id) }
                refresh()
            }
        }
        refresh()
        return null
    }

    /** Bytes of [model]'s parts that come before [part] and are already in the staging dir. */
    private fun partsDoneBefore(model: ImageModel, part: ImageModelPart): Long =
        model.parts.takeWhile { it !== part }.sumOf { p ->
            File(partsStagingDir(model), p.name).takeIf { it.isFile }?.length() ?: 0L
        }

    /** renameTo across directories, or copy + delete. */
    private fun moveInto(from: File, to: File) {
        if (from.renameTo(to)) return
        from.copyTo(to, overwrite = true)
        from.delete()
    }

    /** Deletes the unpacked model directory (and any partial download). */
    fun delete(model: ImageModel) {
        cancel(model)
        modelDir(model).deleteRecursively()
        refresh()
    }

    /**
     * Unpacks a user-picked image model zip. Any zip whose contents look like a MediaPipe model
     * (tokenizer vocab or weight `.bin`s inside) works; the model shows up as an imported one.
     */
    suspend fun import(uri: Uri): Result<ImageModel> = withContext(Dispatchers.IO) {
        var name: String? = null
        var size = 0L
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        val zipName = name?.substringAfterLast('/')?.trim().orEmpty()
        if (!zipName.endsWith(".zip", ignoreCase = true)) {
            return@withContext Result.failure(IOException("Pick an image model .zip (got ${zipName.ifBlank { "an unnamed file" }})."))
        }
        val model = ImageModels.imported(zipName)
        if (model.slug in ImageModels.all.map { it.slug }) {
            return@withContext Result.failure(IOException("\"$zipName\" collides with a built-in model. Rename the file first."))
        }
        if (size > 0 && freeBytes() < size + UNPACK_MARGIN) {
            return@withContext Result.failure(IOException("Not enough free space for ${LocalModels.formatSize(size)}."))
        }
        val tmp = File(zipsDir(), "${model.zipName}.import")
        val result = runCatching {
            zipsDir().mkdirs()
            synchronized(this@ImageModelStore) { unpacking[model.id] = 0L }
            try {
                context.contentResolver.openInputStream(uri)?.use { inp ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(1 shl 20)
                        var copied = 0L
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            copied += n
                            synchronized(this@ImageModelStore) { unpacking[model.id] = copied }
                        }
                    }
                } ?: throw IOException("Could not open the file.")
                unpack(tmp, model)
            } finally {
                tmp.delete()
                synchronized(this@ImageModelStore) { unpacking.remove(model.id) }
            }
            model
        }
        refresh()
        result
    }

    /** The DownloadManager queue's view of [model], starting the unpack of a finished download. */
    private fun downloadStatus(model: ImageModel): ModelStatus? {
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
            zipFile(model).delete()
            prefs.edit().remove(model.id).apply()
            return null
        }
        val knownTotal = if (row.total > 0) row.total else model.sizeBytes
        when (row.status) {
            DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING ->
                return ModelStatus.Downloading(row.done, knownTotal)
            DownloadManager.STATUS_PAUSED -> return ModelStatus.Downloading(row.done, knownTotal, pausedReason(row.reason))
            DownloadManager.STATUS_FAILED -> {
                runCatching { manager.remove(id) }
                zipFile(model).delete()
                prefs.edit().remove(model.id).apply()
                failures[model.id] = "Download failed. Try again."
                return null
            }
            DownloadManager.STATUS_SUCCESSFUL -> {
                prefs.edit().remove(model.id).apply()
                val zip = zipFile(model)
                when {
                    !zip.isFile || zip.length() == 0L -> {
                        zip.delete()
                        failures[model.id] = "The downloaded zip is missing."
                    }
                    model.sizeBytes > 0 && zip.length() != model.sizeBytes -> {
                        zip.delete()
                        failures[model.id] = "Download incomplete (${LocalModels.formatSize(zip.length())} of ${LocalModels.formatSize(model.sizeBytes)}). Try again."
                    }
                    else -> {
                        // Unpacking runs off the queue; the status turns Importing right away.
                        scope.launch(Dispatchers.IO) {
                            synchronized(this@ImageModelStore) { unpacking[model.id] = 0L }
                            unpack(zip, model)
                        }
                        return ModelStatus.Importing(0, model.sizeBytes)
                    }
                }
                return null
            }
            else -> return ModelStatus.Downloading(row.done, knownTotal)
        }
    }

    /** Unpacks [zip] into the model's directory, tracking progress, then deletes the zip. */
    private fun unpack(zip: File, model: ImageModel) {
        try {
            ImageZip.unzipTo(zip, modelDir(model)) { written ->
                synchronized(this@ImageModelStore) { if (unpacking.containsKey(model.id)) unpacking[model.id] = written }
            }
            zip.delete()
            failures.remove(model.id)
        } catch (e: Exception) {
            synchronized(this@ImageModelStore) { failures[model.id] = e.message ?: "Unpacking failed." }
            modelDir(model).deleteRecursively()
        } finally {
            synchronized(this@ImageModelStore) { unpacking.remove(model.id) }
            refresh()
        }
    }

    /** Polls the download queue while something is in flight, then stops by itself. */
    private fun ensurePolling() {
        if (poller?.isActive == true) return
        poller = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(POLL_MS)
                refresh()
                if (_statuses.value.entries.none { (id, st) ->
                        (st is ModelStatus.Downloading) && prefs.getLong(id, -1L) >= 0
                    }
                ) break
            }
        }
    }

    private fun pausedReason(reason: Int): String = when (reason) {
        DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Waiting for Wi-Fi"
        DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Waiting for network"
        DownloadManager.PAUSED_WAITING_TO_RETRY -> "Connection lost, retrying"
        else -> "Paused"
    }

    private data class Row(val status: Int, val done: Long, val total: Long, val reason: Int)

    companion object {
        /** Folder under the models directory ([ModelStore.DIR] siblings); one folder per model. */
        const val DIR = "image"
        private const val ZIPS_DIR = "image-zips"
        private const val PREFS = "image_model_downloads"
        private const val POLL_MS = 700L
        /** Extra room for unpacking: the zip and its unpacked contents coexist briefly. */
        private const val UNPACK_MARGIN = 2_500L * 1024 * 1024
        private const val SPACE_MARGIN = 300L * 1024 * 1024
    }
}
