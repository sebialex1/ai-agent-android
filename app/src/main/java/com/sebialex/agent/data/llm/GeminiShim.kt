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
     * Idempotent upgrade for the installed shim. Upstream resolves only its own hardcoded
     * model names and silently serves its default for anything else, with a static
     * /v1/models — so a model Google actually published to the signed-in account (say
     * gemini-4-hydrogen) never appeared and any such request degraded to the default model.
     * Two edits, each guarded: the name resolver hands unknown names to gemini_webapi,
     * which resolves them against the account's live registry (gemini-webapi 2.1.x, where
     * thinking tiers from the account also surface as models), and the /v1/models route
     * lists that registry next to the built-ins. Skipped (loudly in the log) when upstream
     * changes either file's shape. Runs during setup and again on every start, so a pip
     * upgrade cannot silently drop it.
     */
    val PATCH = """
        python3 - <<'PYPATCH'
        import gemini_openai, io, os
        base = os.path.dirname(gemini_openai.__file__)
        mark = "# [ai-agent-android patch]"
        def patch(path, old, new):
            s = io.open(path, encoding="utf-8").read()
            if mark in s:
                return "already"
            if old not in s:
                return "skipped: upstream layout changed"
            io.open(path, "w", encoding="utf-8").write(s.replace(old, new))
            return "patched"
        r1 = patch(os.path.join(base, "config.py"),
            "return _CANON.get(key2) or _ALIASES.get(key2) or DEFAULT_MODEL",
            "passthrough = _CANON.get(key2) or _ALIASES.get(key2)\n"
            "    if passthrough is not None:\n"
            "        return passthrough\n"
            "    " + mark + " unknown names go untouched to gemini_webapi, which resolves them\n"
            "    # against the models this account really publishes; no silent default.\n"
            "    return key2 or DEFAULT_MODEL")
        r2 = patch(os.path.join(base, "server.py"),
            'data = [\n        {"id": name, "object": "model", "created": now, "owned_by": "google-gemini"}\n'
            '        for name in config.list_public_models()\n    ]',
            'builtins = config.list_public_models()\n'
            '    live: list[str] = []\n'
            '    try:\n'
            '        client = await webapi_manager.get()\n'
            '        registry = client.list_models() or []\n'
            '        live = [n for n in (m.model_name for m in registry) if n and n not in builtins]\n'
            '    except Exception:\n'
            '        pass\n'
            '    ' + mark + " the account's real model registry is listed beside the built-ins\n"
            '    data = [\n'
            '        {"id": name, "object": "model", "created": now, "owned_by": "google-gemini"}\n'
            '        for name in builtins + live\n'
            '    ]')
        print("shim patch:", r1, r2)
        PYPATCH""".trimIndent()

    /**
     * One-time setup in Termux. `pip install -U pip` is deliberately absent: Termux forbids it
     * because it breaks the python-pip package. `rust` is required because orjson ships no
     * Android wheel and maturin builds it from source; curl_cffi does have an Android wheel.
     */
    val SETUP =
        "pkg install -y python python-pip git clang cmake make openssl binutils rust patchelf && " +
            "pip install \"git+https://github.com/FarisHijazi/gemini-web-api\" && " + PATCH



    /** Launches the shim detached, with the imported session in its environment. */
    fun startScript(payload: AgtFile.Payload): String {
        val byName = payload.cookies.associate { it.name to it.value }
        val q = { s: String -> SandboxShell.shellQuote(s) }
        return PATCH + "; " +
            "export GEMINI_1PSID=${q(byName["__Secure-1PSID"].orEmpty())} " +
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