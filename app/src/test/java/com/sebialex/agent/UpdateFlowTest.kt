package com.sebialex.agent

import com.sebialex.agent.data.update.ApkDownloader
import com.sebialex.agent.data.update.AppRelease
import com.sebialex.agent.data.update.InstalledApp
import com.sebialex.agent.data.update.UpdateChecker
import com.sebialex.agent.data.update.UpdateFlow
import com.sebialex.agent.data.update.UpdateHost
import com.sebialex.agent.data.update.UpdateResult
import com.sebialex.agent.data.update.UpdateState
import com.sebialex.agent.ui.settings.BackupFolderAction
import com.sebialex.agent.ui.settings.BackupRows
import com.sebialex.agent.ui.settings.UpdateAction
import com.sebialex.agent.ui.settings.UpdateRows
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files

/**
 * The update flow outside any screen: one check, one download per build, one automatic installer
 * launch, the permission step, the cache, key changes, retries, the row texts/actions, and the
 * whole-check time limit on the real HTTP path.
 */
class UpdateFlowTest {
    private val cert = "2951755b3951833f1332b19caf578b012e2ac0342c713599a60df902a5fb1f4d"
    private val otherCert = "1a3feb081f10d6cfd642838bba22e51fd7bb63baa8c46c127cb29765a28a27a5"
    private val installed = InstalledApp(21, "0.1.21", cert)
    private lateinit var dir: File

    /** Runs every launched coroutine right away on the calling thread, so tests read state directly. */
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @Before fun setUp() { dir = File(Files.createTempDirectory("updates").toFile(), "updates") }
    @After fun tearDown() { dir.parentFile?.deleteRecursively() }

    private fun release(code: Long = 23, size: Long? = 10, certSha: String? = cert) = AppRelease(
        tag = "v0.1.$code", name = "Agent v0.1.$code", pageUrl = "https://github.com/x/releases/tag/v0.1.$code",
        apkUrl = "https://x/agent-v0.1.$code-signed.apk", apkName = "agent-v0.1.$code-signed.apk", apkSize = size,
        versionCode = code, certSha256 = certSha, publishedAt = null,
    )

    private inner class Host : UpdateHost {
        var canInstall = true
        val launches = mutableListOf<File>()
        var settingsOpened = 0
        override fun installed() = installed
        override val apkDir: File get() = dir
        override fun canInstallPackages() = canInstall
        override fun openInstaller(apk: File): Boolean { launches += apk; return true }
        override fun openInstallSettings(): Boolean { settingsOpened++; return true }
    }

    /** Writes [size] bytes; can wait on [gate], fail the first [failures] times, report progress. */
    private class Downloader(var size: Int = 10) : ApkDownloader {
        val urls = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        var failures = 0
        var halfway: (() -> Unit)? = null
        override suspend fun download(url: String, to: File, progress: (Long, Long) -> Unit) {
            urls += url
            progress(0, size.toLong())
            progress(size / 2L, size.toLong())
            halfway?.invoke()
            gate?.await()
            if (failures > 0) { failures--; to.writeBytes(ByteArray(1)); throw IOException("connection reset") }
            to.writeBytes(ByteArray(size))
            progress(size.toLong(), size.toLong())
        }
    }

    private class Checks(var result: UpdateResult) {
        var calls = 0
        suspend fun check(@Suppress("UNUSED_PARAMETER") i: InstalledApp): UpdateResult { calls++; return result }
    }

    private fun flow(checks: Checks, downloader: Downloader, host: Host) = UpdateFlow(checks::check, downloader, host, scope)

    @Test fun repeatedChecksGiveOneCheckOneDownloadOneInstallerLaunch() {
        val checks = Checks(UpdateResult.Available(release(), installed))
        val dl = Downloader()
        val host = Host()
        val f = flow(checks, dl, host)
        repeat(3) { f.checkOnce() }
        repeat(3) { f.check() }
        assertEquals(1, checks.calls)
        assertEquals(1, dl.urls.size)
        assertEquals(1, host.launches.size)
        val s = f.state.value as UpdateState.Ready
        assertTrue(s.handedOff)
        assertEquals(File(dir, UpdateFlow.apkName(23)), host.launches.single())
        assertFalse("no partial file left", File(dir, UpdateFlow.partName(23)).exists())
        // Reopening the installer takes the explicit tap, and only that.
        f.onResume()
        assertEquals(1, host.launches.size)
        f.install()
        assertEquals(2, host.launches.size)
    }

    @Test fun checksWhileDownloadingAreIgnored() {
        val checks = Checks(UpdateResult.Available(release(), installed))
        val dl = Downloader().apply { gate = CompletableDeferred() }
        val host = Host()
        val f = flow(checks, dl, host)
        f.checkOnce()
        assertTrue(f.state.value is UpdateState.Downloading)
        repeat(3) { f.check(); f.checkOnce(); f.retry() }
        assertEquals(1, checks.calls)
        assertEquals(1, dl.urls.size)
        assertTrue(host.launches.isEmpty())
        dl.gate!!.complete(Unit)
        assertEquals(1, host.launches.size)
        assertTrue(f.state.value is UpdateState.Ready)
    }

    @Test fun permissionPathOffersOneActionThenLaunchesExactlyOnce() {
        val checks = Checks(UpdateResult.Available(release(), installed))
        val host = Host().apply { canInstall = false }
        val f = flow(checks, Downloader(), host)
        f.checkOnce()
        val s = f.state.value
        assertTrue(s is UpdateState.NeedsPermission)
        assertEquals(UpdateAction.ALLOW_INSTALLS, UpdateRows.row(s, installed).action)
        assertTrue(host.launches.isEmpty())
        f.allowInstalls()
        assertEquals(1, host.settingsOpened)
        // Back without granting: nothing happens, the same single action stays.
        f.onResume()
        f.onResume()
        assertTrue(host.launches.isEmpty())
        assertTrue(f.state.value is UpdateState.NeedsPermission)
        // Granted: the installer opens once by itself, and never again on later resumes.
        host.canInstall = true
        f.onResume()
        f.onResume()
        f.check()
        assertEquals(1, host.launches.size)
        assertEquals(UpdateAction.INSTALL, UpdateRows.row(f.state.value, installed).action)
    }

    @Test fun cachedApkIsReusedWithoutDownloading() {
        dir.mkdirs()
        val cached = File(dir, UpdateFlow.apkName(23)).apply { writeBytes(ByteArray(10)) }
        val dl = Downloader()
        val host = Host()
        val f = flow(Checks(UpdateResult.Available(release(), installed)), dl, host)
        f.checkOnce()
        assertTrue(dl.urls.isEmpty())
        assertEquals(listOf(cached), host.launches)
    }

    @Test fun cachedApkOfTheWrongSizeIsDownloadedAgain() {
        dir.mkdirs()
        File(dir, UpdateFlow.apkName(23)).writeBytes(ByteArray(3))
        File(dir, UpdateFlow.apkName(19)).writeBytes(ByteArray(3)) // an older build's leftover
        val dl = Downloader()
        val f = flow(Checks(UpdateResult.Available(release(), installed)), dl, Host())
        f.checkOnce()
        assertEquals(1, dl.urls.size)
        assertEquals(10L, File(dir, UpdateFlow.apkName(23)).length())
        assertFalse(File(dir, UpdateFlow.apkName(19)).exists())
    }

    @Test fun aKeyChangeIsNeverDownloaded() {
        val dl = Downloader()
        val host = Host()
        val f = flow(Checks(UpdateResult.Available(release(certSha = otherCert), installed)), dl, host)
        f.checkOnce()
        val s = f.state.value
        assertTrue(s is UpdateState.KeyChanged)
        assertTrue(dl.urls.isEmpty())
        assertTrue(host.launches.isEmpty())
        val row = UpdateRows.row(s, installed)
        assertEquals(UpdateAction.RELEASE_NOTES, row.action)
        assertEquals(release().pageUrl, row.url)
        assertTrue(row.warning)
        // Retry and install do nothing here: there is nothing to download.
        f.retry(); f.install()
        assertTrue(dl.urls.isEmpty())
    }

    @Test fun aFailedDownloadRetriesTheSameBuild() {
        val checks = Checks(UpdateResult.Available(release(), installed))
        val dl = Downloader().apply { failures = 1 }
        val host = Host()
        val f = flow(checks, dl, host)
        f.checkOnce()
        val failed = f.state.value
        assertTrue(failed is UpdateState.DownloadFailed)
        assertEquals(UpdateAction.RETRY, UpdateRows.row(failed, installed).action)
        assertFalse(File(dir, UpdateFlow.partName(23)).exists())
        assertFalse(File(dir, UpdateFlow.apkName(23)).exists())
        f.retry()
        assertEquals(1, checks.calls) // no new check: the same build again
        assertEquals(listOf(release().apkUrl, release().apkUrl), dl.urls)
        assertEquals(1, host.launches.size)
    }

    @Test fun aDownloadOfTheWrongSizeFails() {
        val dl = Downloader(size = 7)
        val host = Host()
        val f = flow(Checks(UpdateResult.Available(release(size = 10), installed)), dl, host)
        f.checkOnce()
        val s = f.state.value as UpdateState.DownloadFailed
        assertTrue(s.message, "incomplete" in s.message)
        assertTrue(host.launches.isEmpty())
        assertFalse(File(dir, UpdateFlow.apkName(23)).exists())
    }

    @Test fun downloadProgressFraction() {
        assertEquals(0.5f, UpdateState.Downloading(release(), 5, 10).fraction!!, 0.0001f)
        assertEquals(1f, UpdateState.Downloading(release(), 12, 10).fraction!!, 0.0001f)
        assertNull(UpdateState.Downloading(release(), 5, -1).fraction)
        // Through the flow: halfway through the transfer the row shows half a bar.
        val dl = Downloader(size = 10)
        var seen: Float? = null
        val f = flow(Checks(UpdateResult.Available(release(), installed)), dl, Host())
        dl.halfway = { seen = (f.state.value as UpdateState.Downloading).fraction }
        f.checkOnce()
        assertEquals(0.5f, seen!!, 0.0001f)
        val row = UpdateRows.row(UpdateState.Downloading(release(), 5, 10), installed)
        assertNull(row.action)
        assertEquals(0.5f, row.progress!!, 0.0001f)
        assertTrue(UpdateRows.row(UpdateState.Downloading(release(), 5, -1), installed).indeterminate)
    }

    @Test fun everyStateShowsAtMostTheOneExpectedAction() {
        val r = release()
        val apk = File(dir, "a.apk")
        val expected = mapOf(
            UpdateState.NotChecked to UpdateAction.CHECK,
            UpdateState.Checking to null,
            UpdateState.UpToDate(r) to UpdateAction.CHECK,
            UpdateState.Offline to UpdateAction.RETRY,
            UpdateState.TimedOut to UpdateAction.RETRY,
            UpdateState.CheckFailed("x") to UpdateAction.RETRY,
            UpdateState.KeyChanged(r) to UpdateAction.RELEASE_NOTES,
            UpdateState.Downloading(r, 1, 10) to null,
            UpdateState.DownloadFailed(r, "x") to UpdateAction.RETRY,
            UpdateState.NeedsPermission(r, apk) to UpdateAction.ALLOW_INSTALLS,
            UpdateState.Ready(r, apk, handedOff = true) to UpdateAction.INSTALL,
            UpdateState.Ready(r, apk, handedOff = false) to UpdateAction.INSTALL,
        )
        expected.forEach { (state, action) ->
            val row = UpdateRows.row(state, installed)
            assertEquals(state.toString(), action, row.action)
            assertTrue(row.status.isNotBlank() && row.detail.isNotBlank())
        }
        assertEquals("GitHub did not answer in time", UpdateRows.row(UpdateState.TimedOut, installed).status)
    }

    /** The same one-action rule on the backup folder row. */
    @Test fun backupFolderRowHasOneContextualAction() {
        assertEquals(BackupFolderAction.CHOOSE, BackupRows.folderAction(folderSet = false, pendingRestore = false))
        assertEquals(BackupFolderAction.RESTORE, BackupRows.folderAction(folderSet = true, pendingRestore = true))
        assertEquals(BackupFolderAction.BACK_UP_NOW, BackupRows.folderAction(folderSet = true, pendingRestore = false))
    }

    @Test fun upToDateClearsOldApks() {
        dir.mkdirs()
        File(dir, UpdateFlow.apkName(20)).writeBytes(ByteArray(3))
        val f = flow(Checks(UpdateResult.UpToDate(null, installed)), Downloader(), Host())
        f.checkOnce()
        assertTrue(f.state.value is UpdateState.UpToDate)
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    /** A server that accepts the connection and never answers: the check must stop at its own limit. */
    @Test fun aSilentServerEndsAsTimedOutQuickly() {
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val held = java.util.Collections.synchronizedList(mutableListOf<Socket>())
        val acceptor = Thread {
            try { while (true) held += server.accept() } catch (e: IOException) { }
        }.apply { isDaemon = true; start() }
        try {
            val checker = UpdateChecker(repo = "o/r", api = "http://127.0.0.1:${server.localPort}", limitMillis = 300)
            val started = System.nanoTime()
            val r = runBlocking { checker.check(installed) }
            val ms = (System.nanoTime() - started) / 1_000_000
            assertEquals(UpdateResult.TimedOut, r)
            // Well under the 5 s socket timeouts: the limit closed the connection.
            assertTrue("took $ms ms", ms < 3_000)
            assertTrue("the server saw the connection", held.isNotEmpty())

            // The flow shows it as a time-out with Retry, never as "Offline".
            val f = UpdateFlow(checker::check, Downloader(), Host(), CoroutineScope(Dispatchers.IO))
            f.checkOnce()
            val deadline = System.currentTimeMillis() + 3_000
            while (f.state.value !is UpdateState.TimedOut && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertEquals(UpdateState.TimedOut, f.state.value)
        } finally {
            server.close()
            held.forEach { runCatching { it.close() } }
            acceptor.join(1_000)
        }
    }
}
