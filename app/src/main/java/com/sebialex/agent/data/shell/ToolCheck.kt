package com.sebialex.agent.data.shell

/**
 * Checks that the programs a command needs are installed before the command runs, so the agent
 * never fires a long `yt-dlp …` or `ffmpeg …` line that can only fail with "not found".
 *
 * [programs] reads the program names of a command line (the first word of every `&&`, `||`,
 * `;`, `|` or newline separated part, quote-aware); [script] builds one `command -v` probe for
 * all of them, run in a single round trip; [parse] reads its answer. [installHint] knows how to
 * install the common tools in Termux (pkg for system tools, pip for Python ones).
 */
object ToolCheck {

    /** How to install a program in Termux, by program name. Unknown programs get a `pkg` guess. */
    private val INSTALL: Map<String, String> = mapOf(
        "yt-dlp" to "pip install -U yt-dlp",
        "youtube-dl" to "pip install -U yt-dlp",
        "gallery-dl" to "pip install -U gallery-dl",
        "spotdl" to "pip install -U spotdl",
        "instaloader" to "pip install -U instaloader",
        "speedtest-cli" to "pip install -U speedtest-cli",
        "ffmpeg" to "pkg install -y ffmpeg",
        "ffprobe" to "pkg install -y ffmpeg",
        "ffplay" to "pkg install -y ffmpeg",
        "aria2c" to "pkg install -y aria2",
        "convert" to "pkg install -y imagemagick",
        "magick" to "pkg install -y imagemagick",
        "identify" to "pkg install -y imagemagick",
        "mogrify" to "pkg install -y imagemagick",
        "node" to "pkg install -y nodejs",
        "npm" to "pkg install -y nodejs",
        "npx" to "pkg install -y nodejs",
        "7z" to "pkg install -y p7zip",
        "sqlite3" to "pkg install -y sqlite",
        "ssh" to "pkg install -y openssh",
        "scp" to "pkg install -y openssh",
        "go" to "pkg install -y golang",
        "rustc" to "pkg install -y rust",
        "cargo" to "pkg install -y rust",
        "java" to "pkg install -y openjdk-17",
        "javac" to "pkg install -y openjdk-17",
        "python" to "pkg install -y python",
        "python3" to "pkg install -y python",
        "pip" to "pkg install -y python",
        "pip3" to "pkg install -y python",
        "gcc" to "pkg install -y clang",
        "clang" to "pkg install -y clang",
        "make" to "pkg install -y make",
        "exiftool" to "pkg install -y exiftool",
        "tesseract" to "pkg install -y tesseract",
        "pdftotext" to "pkg install -y poppler",
        "pdftoppm" to "pkg install -y poppler",
        "qpdf" to "pkg install -y qpdf",
        "lua" to "pkg install -y lua54",
        "dig" to "pkg install -y dnsutils",
        "nslookup" to "pkg install -y dnsutils",
        "gh" to "pkg install -y gh",
    )

    /** Words that start a segment but are not programs to look up. */
    private val SKIP = setOf(
        "if", "then", "else", "elif", "fi", "for", "in", "do", "done", "while", "until", "case", "esac", "function",
        "{", "}", "(", ")", "[", "[[", "]]", "!", "time", "exec", "env", "nohup", "export", "source", ".", "set", "unset",
        "local", "eval", "command", "which", "type", "builtin", "true", "false", "test", "read", "return", "exit",
        "shift", "trap", "wait", "alias", "cd", "pwd", "echo", "printf", "let", "declare", "ulimit", "umask", "hash",
        "sudo", "su", // blocked by the permission policy anyway
    )

    /** Package managers and installers: a command that installs is never pre-checked. */
    private val INSTALLS = Regex(
        "(^|[;&|\\s])(apt|apt-get|pkg|pip|pip3|npm|gem|cargo)\\s+(install|i|add|upgrade|update|reinstall)\\b|-m\\s+pip\\s+install\\b|dpkg\\s+-i\\b",
    )

    private val NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._+-]*$")

    fun installs(command: String): Boolean = INSTALLS.containsMatchIn(command)

    /** The install command for [program] in Termux. */
    fun installHint(program: String): String = INSTALL[program] ?: "pkg install -y $program"

    /**
     * Program names used by [command], in order and without duplicates. Only plain names are
     * returned (no paths, variables or substitutions), minus shell keywords and [builtins].
     */
    fun programs(command: String, builtins: Set<String> = emptySet()): List<String> {
        val out = LinkedHashSet<String>()
        for (segment in segments(command)) {
            val words = segment.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            // Skip leading VAR=value assignments and wrappers like `time` / `env`.
            val head = words.firstOrNull { w -> !(w.contains('=') && !w.startsWith("=")) && w !in setOf("time", "env", "nohup", "exec") }
                ?: continue
            if (head in SKIP || head in builtins) continue
            if (!NAME.matches(head)) continue
            out += head
        }
        return out.toList()
    }

    /**
     * Splits on `&&`, `||`, `;`, `|`, `|&`, `&` and unescaped newlines outside quotes; `$(…)` and
     * backticks are not entered. A `&` that belongs to a redirection (`2>&1`, `>&2`, `<&3`, `&>log`)
     * is not a separator, and a backslash escapes the next character (`\;`, a `\` line continuation),
     * so the file descriptor of `2>&1` or the next line of a continued command is never taken for a
     * program name (that made `yt-dlp … 2>&1` fail with "1 is not installed").
     */
    private fun segments(command: String): List<String> {
        val parts = mutableListOf<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        var depth = 0
        var i = 0
        while (i < command.length) {
            val c = command[i]
            val next = command.getOrNull(i + 1)
            when {
                quote == '\'' -> { if (c == quote) quote = null; cur.append(c) }
                c == '\\' -> {
                    // A line continuation joins the lines; any other escaped character is literal.
                    if (next == '\n') cur.append(' ') else { cur.append(c); if (next != null) cur.append(next) }
                    i++
                }
                quote != null -> { if (c == quote) quote = null; cur.append(c) }
                c == '\'' || c == '"' || c == '`' -> { quote = c; cur.append(c) }
                c == '$' && next == '(' -> { depth++; cur.append("$("); i++ }
                c == ')' && depth > 0 -> { depth--; cur.append(c) }
                depth == 0 && c == '&' && (cur.endsWith(">") || cur.endsWith("<") || next == '>') -> cur.append(c)
                depth == 0 && c == '|' && cur.endsWith(">") -> cur.append(c) // `>|` (noclobber override)
                depth == 0 && (c == ';' || c == '|' || c == '&' || c == '\n') -> {
                    parts += cur.toString(); cur.clear()
                    // `&&`, `||` and `|&` are one separator.
                    if ((c == '&' || c == '|') && next == c) i++ else if (c == '|' && next == '&') i++
                }
                else -> cur.append(c)
            }
            i++
        }
        parts += cur.toString()
        return parts.filter { it.isNotBlank() }
    }

    /**
     * True when [command] changes the program search path itself (`PATH=… cmd`, `export PATH=…`):
     * the probe runs without that change, so it cannot speak for the command and is skipped.
     */
    fun changesPath(command: String): Boolean = PATH_CHANGE.containsMatchIn(command)

    private val PATH_CHANGE = Regex("(^|[\\s;&|(])(export\\s+)?PATH\\+?=")

    /** One shell probe for every program: prints `ok:<name>:<path>` or `missing:<name>` per line. */
    fun script(programs: List<String>): String =
        "for t in " + programs.joinToString(" ") { SandboxShell.shellQuote(it) } +
            "; do p=\$(command -v \"\$t\" 2>/dev/null); if [ -n \"\$p\" ]; then echo \"ok:\$t:\$p\"; else echo \"missing:\$t\"; fi; done"

    /** Reads [script]'s output: program name to its path, or null when it is missing. Null when unreadable. */
    fun parse(output: String, programs: List<String>): Map<String, String?>? {
        val found = HashMap<String, String?>()
        output.lineSequence().map { it.trim() }.forEach { line ->
            when {
                line.startsWith("ok:") -> {
                    val rest = line.removePrefix("ok:")
                    found[rest.substringBefore(':')] = rest.substringAfter(':', "").ifEmpty { "installed" }
                }
                line.startsWith("missing:") -> found[line.removePrefix("missing:")] = null
            }
        }
        // A probe that did not answer for every program (Termux error, no shell) proves nothing.
        return if (programs.all { it in found }) found else null
    }

    /** The friendly error a command gets instead of running when [missing] programs are not installed. */
    fun missingMessage(missing: List<String>): String = buildString {
        val names = missing.joinToString(", ")
        append(if (missing.size == 1) "$names is not installed" else "Not installed: $names")
        append(", so the command was not run.\n")
        append("Install with:\n")
        missing.map { installHint(it) }.distinct().forEach { append("  ").append(it).append('\n') }
        append("Offer to install it (or install it with the shell tool if the task clearly needs it), then retry.")
    }

    /** One line per program for the check_tools tool. */
    fun report(result: Map<String, String?>): String = result.entries.joinToString("\n") { (name, path) ->
        if (path != null) "$name: installed ($path)" else "$name: missing \u2014 install with `${installHint(name)}`"
    }
}
