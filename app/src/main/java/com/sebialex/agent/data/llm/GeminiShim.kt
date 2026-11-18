package com.sebialex.agent.data.llm

import com.sebialex.agent.data.secure.AgtFile
import com.sebialex.agent.data.shell.SandboxShell
import com.sebialex.agent.data.shell.TermuxRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Runs the Gemini shim inside Termux, so the app starts and stops it instead of the user typing
 * commands. The shim is a Python server because Gemini's web protocol needs Chrome TLS
 * impersonation (curl_cffi), which cannot run inside the APK — Termux is where it lives.
 *
 * The imported session is passed as the environment the shim documents for headless use, and it
 * owns refreshing those cookies (GEMINI_AUTO_REFRESH), since nothing else is keeping them alive.
 */
object GeminiShim {
    const val PORT = 8100
    const val MODELS_URL = "http://127.0.0.1:$PORT/v1/models"
    /** Termux's home, where the shim runs and keeps its log. */
    const val HOME = "/data/data/com.termux/files/home"
    private const val LOG = "gemini-shim.log"

    /**
     * One-time setup in Termux. `pip install -U pip` is deliberately absent: Termux forbids it
     * because it breaks the python-pip package. `rust` is required because orjson ships no
     * Android wheel and maturin builds it from source; curl_cffi does have an Android wheel.
     */
    const val SETUP =
        "pkg install -y python python-pip git clang cmake make openssl binutils rust patchelf && " +
            "pip install \"git+https://github.com/FarisHijazi/gemini-web-api\""

    /** Launches the shim detached, with the imported session in its environment. */
    fun startScript(payload: AgtFile.Payload): String {
        val byName = payload.cookies.associate { it.name to it.value }
        val q = { s: String -> SandboxShell.shellQuote(s) }
        return "export GEMINI_1PSID=${q(byName["__Secure-1PSID"].orEmpty())} " +
            "GEMINI_1PSIDTS=${q(byName["__Secure-1PSIDTS"].orEmpty())} " +
            "GEMINI_AUTHUSER=${payload.authuser} GEMINI_AUTO_REFRESH=1; " +
            "nohup gemini-web-api > \"\$HOME/$LOG\" 2>&1 & echo started"
    }

    const val STOP = "pkill -f gemini-web-api; pkill -f gemini_openai; echo stopped"

    /** The shim's own log, for when it will not start. */
    const val LOG_TAIL = "tail -n 25 \"\$HOME/$LOG\" 2>/dev/null || echo 'no log yet'"

    /** Starts it through Termux; the real output (or the reason it could not) comes back. */
    fun start(termux: TermuxRunner, payload: AgtFile.Payload, workdir: String): String {
        val r = termux.run(startScript(payload), workdir, 30, "start Gemini shim")
        return if (r.err != -1) "termux error ${r.err}: ${r.errmsg}" else (r.stdout + r.stderr).trim()
    }

    fun stop(termux: TermuxRunner, workdir: String): String {
        val r = termux.run(STOP, workdir, 20, "stop Gemini shim")
        return (r.stdout + r.stderr).trim()
    }

    fun log(termux: TermuxRunner, workdir: String): String {
        val r = termux.run(LOG_TAIL, workdir, 20, "Gemini shim log")
        return (r.stdout + r.stderr).trim()
    }

    /**
     * Waits for the shim to answer, then returns its model ids. Null when it never came up in
     * [timeoutMs] — the caller shows the log rather than pretending it is running.
     */
    suspend fun awaitModels(timeoutMs: Long = 60_000): List<String>? = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val ids = runCatching { fetchModels() }.getOrNull()
            if (ids != null) return@withContext ids
            delay(2000)
        }
        null
    }

    /** One read of /v1/models; null when the shim is not answering yet. */
    fun fetchModels(): List<String>? {
        val conn = URL(MODELS_URL).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 2000
            conn.readTimeout = 4000
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode !in 200..299) {
                null
            } else {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val root = kotlinx.serialization.json.Json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject
                val data = root?.get("data") as? kotlinx.serialization.json.JsonArray
                data?.mapNotNull {
                    ((it as? kotlinx.serialization.json.JsonObject)?.get("id") as? kotlinx.serialization.json.JsonPrimitive)
                        ?.takeIf { p -> p.isString }?.content
                }
            }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { conn.disconnect() }
        }
    }
}