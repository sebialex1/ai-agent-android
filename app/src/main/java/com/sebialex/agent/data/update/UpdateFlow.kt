package com.sebialex.agent.data.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/** What [UpdateFlow] needs from Android: the installed build, the APK folder and the installer. */
interface UpdateHost {
    fun installed(): InstalledApp

    /** Where APKs are kept: the app's `cache/updates/`, the only folder the FileProvider shares. */
    val apkDir: File

    /** Whether this app may hand APKs to the package installer ("Install unknown apps"). */
    fun canInstallPackages(): Boolean

    /** Opens Android's package installer for [apk]; false when it could not be opened. */
    fun openInstaller(apk: File): Boolean

    /** Opens the system "Install unknown apps" page for this app. */
    fun openInstallSettings(): Boolean
}

/** Where the update stands. Every state is shown as one row with at most one action. */
sealed interface UpdateState {
    data object NotChecked : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val latest: AppRelease?) : UpdateState
    data object Offline : UpdateState
    data object TimedOut : UpdateState
    data class CheckFailed(val message: String) : UpdateState

    /** A newer build signed with another key: Android would refuse it, so it is never downloaded. */
    data class KeyChanged(val release: AppRelease) : UpdateState

    data class Downloading(val release: AppRelease, val done: Long, val total: Long) : UpdateState {
        /** 0..1, or null while the size is unknown. */
        val fraction: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
    }

    data class DownloadFailed(val release: AppRelease, val message: String) : UpdateState

    /** Downloaded, but installs from this app are not allowed yet; the installer opens once they are. */
    data class NeedsPermission(val release: AppRelease, val apk: File) : UpdateState

    /** Downloaded and verified. [handedOff]: the installer was opened (once, automatically). */
    data class Ready(val release: AppRelease, val apk: File, val handedOff: Boolean) : UpdateState
}

/**
 * The whole update flow, kept outside any screen so rotating, recomposing or reopening the page
 * never repeats a step: one check, one download per build (versionCode), one automatic installer
 * launch. One instance lives in the app container on the app-wide scope.
 *
 * Check -> (newer build, same key) download to `cache/updates/` as `.part`, verify the size,
 * rename -> open the system installer once (a content URI, the user confirms there; nothing is
 * installed silently). Without the install permission there is one "Allow installs" step; when the
 * app resumes with it granted, the installer opens once by itself. Later launches take a tap.
 */
class UpdateFlow(
    private val checker: suspend (InstalledApp) -> UpdateResult,
    private val downloader: ApkDownloader,
    private val host: UpdateHost,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.NotChecked)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val installed: InstalledApp by lazy { host.installed() }

    private val lock = Any()
    private var autoChecked = false
    /** Active download per versionCode. */
    private val downloads = HashMap<Long, Job>()
    /** versionCodes whose installer was already opened automatically. */
    private val autoLaunched = HashSet<Long>()

    /** The automatic check when the Updates page opens: once per app run. */
    fun checkOnce() {
        synchronized(lock) {
            if (autoChecked) return
            autoChecked = true
        }
        check()
    }

    /** A check on request. Ignored while one runs, and once a build is downloading or downloaded. */
    fun check() {
        synchronized(lock) {
            autoChecked = true
            if (busy(_state.value)) return
            _state.value = UpdateState.Checking
        }
        scope.launch {
            val r = try {
                checker(installed)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateResult.Failed(e.message ?: e.javaClass.simpleName)
            }
            when (r) {
                is UpdateResult.Available -> {
                    // A build signed with another key would be refused by Android: never fetched.
                    if (r.keyChanged) {
                        _state.value = UpdateState.KeyChanged(r.release)
                    } else {
                        download(r.release)
                    }
                }
                is UpdateResult.UpToDate -> {
                    _state.value = UpdateState.UpToDate(r.latest)
                    // The update was installed (or is older than this build): its APK is no longer needed.
                    clearCache(keep = null)
                }
                UpdateResult.Offline -> _state.value = UpdateState.Offline
                UpdateResult.TimedOut -> _state.value = UpdateState.TimedOut
                is UpdateResult.Failed -> _state.value = UpdateState.CheckFailed(r.message)
            }
        }
    }

    /** Retry: the check after a failed check, the same build after a failed download. */
    fun retry() {
        when (val s = _state.value) {
            UpdateState.Offline, UpdateState.TimedOut, is UpdateState.CheckFailed -> check()
            is UpdateState.DownloadFailed -> download(s.release)
            else -> Unit
        }
    }

    /** The explicit "Install" tap: reopens the installer for the downloaded build. */
    fun install() {
        var refetch: AppRelease? = null
        synchronized(lock) {
            val s = _state.value as? UpdateState.Ready ?: return
            when {
                // The system may clear the cache: fetch the same build again.
                !s.apk.isFile -> refetch = s.release
                !host.canInstallPackages() -> _state.value = UpdateState.NeedsPermission(s.release, s.apk)
                else -> {
                    val ok = host.openInstaller(s.apk)
                    _state.value = s.copy(handedOff = s.handedOff || ok)
                }
            }
            Unit
        }
        refetch?.let { download(it) }
    }

    /** The one recovery action without the install permission: the system page that grants it. */
    fun allowInstalls() {
        if (_state.value is UpdateState.NeedsPermission) host.openInstallSettings()
    }

    /** The app came back to the front: with the permission now granted, the installer opens once. */
    fun onResume() {
        synchronized(lock) {
            val s = _state.value as? UpdateState.NeedsPermission ?: return
            if (!host.canInstallPackages()) return
            val ok = host.openInstaller(s.apk)
            s.release.versionCode?.let { autoLaunched += it }
            _state.value = UpdateState.Ready(s.release, s.apk, handedOff = ok)
        }
    }

    private fun busy(s: UpdateState) = s is UpdateState.Checking || s is UpdateState.Downloading ||
        s is UpdateState.NeedsPermission || s is UpdateState.Ready

    /** Downloads [release] unless it is downloading already, or reuses its APK from the cache. */
    private fun download(release: AppRelease) {
        val code = release.versionCode
        val url = release.apkUrl
        if (code == null || url == null) {
            _state.value = UpdateState.DownloadFailed(release, "the release has no APK")
            return
        }
        val job = synchronized(lock) {
            if (downloads[code]?.isActive == true) return
            cached(release)?.let { apk ->
                handOff(release, apk)
                return
            }
            _state.value = UpdateState.Downloading(release, 0, release.apkSize ?: -1)
            scope.launch(start = CoroutineStart.LAZY) { fetch(release, code, url) }.also { downloads[code] = it }
        }
        job.start()
    }

    private suspend fun fetch(release: AppRelease, code: Long, url: String) {
        val dir = host.apkDir
        val part = File(dir, partName(code))
        val apk = File(dir, apkName(code))
        var failure: String? = null
        try {
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("could not create ${dir.name}/")
            clearCache(keep = code)
            part.delete()
            var reported = 0L
            downloader.download(url, part) { done, reportedTotal ->
                val total = release.apkSize ?: reportedTotal
                val step = if (total > 0) maxOf(total / 100, 1L) else PROGRESS_STEP_BYTES
                if (done - reported >= step || done == total || done == 0L) {
                    reported = done
                    _state.value = UpdateState.Downloading(release, done, total)
                }
            }
            val size = part.length()
            val expected = release.apkSize
            if (size <= 0L) throw IOException("the download is empty")
            if (expected != null && size != expected) throw IOException("the download is incomplete ($size of $expected bytes)")
            apk.delete()
            if (!part.renameTo(apk)) throw IOException("could not save the APK")
        } catch (e: kotlinx.coroutines.CancellationException) {
            part.delete()
            throw e
        } catch (e: Exception) {
            part.delete()
            failure = e.message ?: e.javaClass.simpleName
        } finally {
            synchronized(lock) { downloads.remove(code) }
        }
        if (failure != null) _state.value = UpdateState.DownloadFailed(release, failure)
        else synchronized(lock) { handOff(release, apk) }
    }

    /** Hands a verified APK to the installer: automatically once per build, later on a tap. */
    private fun handOff(release: AppRelease, apk: File) {
        if (!host.canInstallPackages()) {
            _state.value = UpdateState.NeedsPermission(release, apk)
            return
        }
        val first = release.versionCode?.let { autoLaunched.add(it) } ?: false
        val ok = first && host.openInstaller(apk)
        _state.value = UpdateState.Ready(release, apk, handedOff = ok)
    }

    /** The cached APK of [release] when it is complete (its size matches the release asset). */
    private fun cached(release: AppRelease): File? {
        val code = release.versionCode ?: return null
        val f = File(host.apkDir, apkName(code))
        if (!f.isFile || f.length() <= 0L) return null
        val expected = release.apkSize
        return if (expected == null || f.length() == expected) f else null
    }

    /** Deletes cached APKs and partial downloads of every build but [keep]. */
    private fun clearCache(keep: Long?) {
        val mine = keep?.let { setOf(apkName(it), partName(it)) }.orEmpty()
        host.apkDir.listFiles()?.forEach { f -> if (f.name !in mine) runCatching { f.delete() } }
    }

    companion object {
        private const val PROGRESS_STEP_BYTES = 256L * 1024
        fun apkName(versionCode: Long) = "agent-$versionCode.apk"
        fun partName(versionCode: Long) = "agent-$versionCode.apk.part"
    }
}
