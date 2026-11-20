package com.sebialex.agent.data.local

import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream

/**
 * Zip unpacking for on-device image models: an image model is a directory of files (weight
 * `.bin`s plus the tokenizer vocab), downloaded as a `.zip`. JVM-pure (java.util.zip only), so
 * unit tests cover it; [ImageModelStore] is the caller on the phone.
 */
internal object ImageZip {

    /** The CLIP tokenizer vocab [ImageGenRuntime]'s engine needs inside the model directory. */
    const val TOKENIZER = "bpe_simple_vocab_16e6.txt"

    /**
     * Extracts [zip] under [dest] (created if needed) and returns the model directory to point
     * the engine at. Zip paths are guarded against escaping ([dest]) ("zip slip"). Zips that
     * wrap the model files in a folder (like `sd15_bins/`) unpack fine: the wrapper is located,
     * not assumed. Entries with a size larger than [maxBytes] abort the extraction, so a stray
     * or malicious zip cannot fill the disk.
     *
     * [onProgress] reports total uncompressed bytes written so far.
     *
     * Throws [java.io.IOException] with a readable message when the zip cannot be read.
     */
    fun unzipTo(zip: File, dest: File, maxBytes: Long = MAX_UNPACKED, onProgress: (Long) -> Unit = {}): File {
        dest.mkdirs()
        if (!dest.isDirectory) throw java.io.IOException("Could not create ${dest.name}.")
        var written = 0L
        var entries = 0
        val outRoot = dest.canonicalFile
        try {
            ZipInputStream(FileInputStream(zip).buffered()).use { zs ->
                while (true) {
                    val entry = zs.nextEntry ?: break
                    entries++
                    val target = File(dest, entry.name)
                    // Zip-slip: an entry name like "../x" or an absolute path must not escape.
                    val canonical = target.canonicalFile
                    if (!canonical.path.startsWith(outRoot.path + File.separator) && canonical != outRoot) {
                        throw java.io.IOException("${entry.name}: unsafe path in the zip.")
                    }
                    if (entry.isDirectory) {
                        canonical.mkdirs()
                    } else {
                        if (entry.size > 0 && written + entry.size > maxBytes) {
                            throw java.io.IOException("The zip unpacks to more than ${LocalModels.formatSize(maxBytes)}.")
                        }
                        canonical.parentFile?.mkdirs()
                        canonical.outputStream().use { zs.copyTo(it) }
                        written += canonical.length()
                        onProgress(written)
                    }
                    zs.closeEntry()
                }
            }
            // ZipInputStream reads any bytes as an empty zip; that is never a model.
            if (entries == 0) throw java.io.IOException("Not a readable zip (no entries).")
        } catch (e: java.io.IOException) {
            dest.deleteRecursively()
            throw e
        } catch (e: Exception) {
            dest.deleteRecursively()
            throw java.io.IOException("Not a readable zip: ${e.message ?: e.javaClass.simpleName}")
        }
        return locateModelDir(dest)
    }

    /**
     * The directory [ImageGenRuntime] should be pointed at: the one holding the tokenizer vocab,
     * else the one with the most weight `.bin`s, else [root] itself (the caller's error message
     * then says the zip does not look like an image model).
     */
    fun locateModelDir(root: File): File {
        root.walkTopDown().filter { it.isFile && it.name == TOKENIZER }.firstOrNull()?.parentFile?.let { return it }
        return root.walkTopDown().maxByOrNull { d -> if (d.isDirectory) d.listFiles().orEmpty().count { it.isFile && it.name.endsWith(".bin") } else -1 }
            ?.takeIf { it.isDirectory && it.listFiles().orEmpty().any { f -> f.isFile && f.name.endsWith(".bin") } }
            ?: root
    }

    /** Looks like an unpacked image model: a directory holding the tokenizer or weight bins. */
    fun looksLikeModelDir(dir: File): Boolean {
        if (!dir.isDirectory) return false
        if (File(dir, TOKENIZER).isFile) return true
        return dir.walkTopDown().any { it.isFile && it.name.endsWith(".bin") }
    }

    private const val MAX_UNPACKED = 3L * 1024 * 1024 * 1024
}
