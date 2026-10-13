package com.sebialex.agent.data.shell

import com.sebialex.agent.data.workspace.Workspace
import com.sebialex.agent.data.workspace.WorkspaceException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ShellResult(val output: String, val exitCode: Int, val cwd: String, val openFile: String? = null)

/** A shell that can run commands against the workspace. Real backends (e.g. a Termux bootstrap) plug in here. */
interface Shell {
    fun execute(command: String, cwd: String = "/"): ShellResult
}

/**
 * A Termux-like command interpreter that never spawns real processes. It implements the common
 * coreutils subset directly against the sandboxed [Workspace], and simulates interpreters and
 * package managers deterministically. Commands are only reached after the permission policy.
 */
class SandboxShell(private val ws: Workspace) : Shell {

    override fun execute(command: String, cwd: String): ShellResult {
        var dir = cwd
        val out = StringBuilder()
        var code = 0
        var openFile: String? = null
        // Supports `a && b` and `a ; b` chaining, and a single pipe into grep/head/tail/wc.
        val chain = splitChain(command)
        for ((segment, op) in chain) {
            if (op == "&&" && code != 0) break
            val r = runPipeline(segment, dir)
            dir = r.cwd
            code = r.exitCode
            if (r.openFile != null) openFile = r.openFile
            if (r.output.isNotEmpty()) {
                out.append(r.output)
                if (!r.output.endsWith('\n')) out.append('\n')
            }
        }
        return ShellResult(out.toString().trimEnd('\n'), code, dir, openFile)
    }

    private fun splitChain(command: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        var lastOp = ""
        var current = StringBuilder()
        var i = 0
        var quote: Char? = null
        while (i < command.length) {
            val c = command[i]
            if (quote != null) { if (c == quote) quote = null; current.append(c); i++; continue }
            if (c == '"' || c == '\'') { quote = c; current.append(c); i++; continue }
            if (command.startsWith("&&", i) || c == ';') {
                result += current.toString().trim() to lastOp
                lastOp = if (c == ';') ";" else "&&"
                current = StringBuilder()
                i += if (c == ';') 1 else 2
                continue
            }
            current.append(c); i++
        }
        if (current.isNotBlank()) result += current.toString().trim() to lastOp
        return result.filter { it.first.isNotEmpty() }
    }

    private fun runPipeline(segment: String, cwd: String): ShellResult {
        val stages = segment.split(Regex("\\s\\|\\s"))
        var r = runSingle(stages.first(), cwd, stdin = null)
        for (stage in stages.drop(1)) {
            if (r.exitCode != 0) break
            r = runSingle(stage, r.cwd, stdin = r.output)
        }
        return r
    }

    private fun tokenize(s: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        var has = false
        for (c in s) {
            when {
                quote != null -> if (c == quote) quote = null else cur.append(c)
                c == '"' || c == '\'' -> { quote = c; has = true }
                c.isWhitespace() -> if (cur.isNotEmpty() || has) { out += cur.toString(); cur.clear(); has = false }
                else -> cur.append(c)
            }
        }
        if (cur.isNotEmpty() || has) out += cur.toString()
        return out
    }

    private fun runSingle(raw: String, cwd: String, stdin: String?): ShellResult {
        // Output redirection
        var cmd = raw
        var redirect: String? = null
        var append = false
        Regex("\\s(>>?)\\s*(\\S+)\\s*$").find(raw)?.let {
            append = it.groupValues[1] == ">>"
            redirect = it.groupValues[2]
            cmd = raw.substring(0, it.range.first)
        }
        val r = try {
            dispatch(tokenize(cmd), cwd, stdin)
        } catch (e: WorkspaceException) {
            ShellResult(e.message ?: "error", 1, cwd)
        } catch (e: Exception) {
            ShellResult("${e.javaClass.simpleName}: ${e.message}", 1, cwd)
        }
        val target = redirect
        if (target != null && r.exitCode == 0) {
            return try {
                val text = if (r.output.isEmpty()) "" else r.output + "\n"
                ws.write(target, text, cwd, append)
                ShellResult("", 0, r.cwd)
            } catch (e: WorkspaceException) {
                ShellResult(e.message ?: "error", 1, cwd)
            }
        }
        return r
    }

    private fun dispatch(args: List<String>, cwd: String, stdin: String?): ShellResult {
        if (args.isEmpty()) return ok("", cwd)
        val name = args[0]
        val rest = args.drop(1)
        val flags = rest.filter { it.startsWith("-") && it.length > 1 }.joinToString("") { it.drop(1) }
        val operands = rest.filterNot { it.startsWith("-") && it.length > 1 }
        return when (name) {
            "pwd" -> ok("/workspace" + if (cwd == "/") "" else cwd, cwd)
            "cd" -> {
                val target = ws.toVirtual(operands.firstOrNull() ?: "/", cwd)
                val f = ws.resolve(target)
                if (!f.isDirectory) fail("cd: ${operands.first()}: not a directory", cwd) else ok("", target)
            }
            "ls" -> ok(ls(operands.ifEmpty { listOf(".") }, cwd, long = 'l' in flags, all = 'a' in flags), cwd)
            "tree" -> ok(tree(operands.firstOrNull() ?: ".", cwd), cwd)
            "cat" -> if (operands.isEmpty()) ok(stdin ?: "", cwd) else ok(operands.joinToString("\n") { ws.read(it, cwd).trimEnd('\n') }, cwd)
            "head", "tail" -> {
                val n = rest.firstOrNull { it.startsWith("-") && it.drop(1).toIntOrNull() != null }?.drop(1)?.toInt()
                    ?: rest.indexOf("-n").takeIf { it >= 0 }?.let { rest.getOrNull(it + 1)?.toIntOrNull() } ?: 10
                val files = operands.filter { it.toIntOrNull() == null }
                val text = if (files.isEmpty()) stdin ?: "" else ws.read(files.first(), cwd)
                val lines = text.trimEnd('\n').split('\n')
                ok((if (name == "head") lines.take(n) else lines.takeLast(n)).joinToString("\n"), cwd)
            }
            "wc" -> {
                val text = if (operands.isEmpty()) stdin ?: "" else ws.read(operands.first(), cwd)
                val lines = if (text.isEmpty()) 0 else text.trimEnd('\n').count { it == '\n' } + 1
                val words = text.split(Regex("\\s+")).count { it.isNotEmpty() }
                ok(if ('l' in flags) "$lines" else "%7d %7d %7d %s".format(lines, words, text.length, operands.firstOrNull() ?: ""), cwd)
            }
            "grep" -> {
                val pattern = operands.firstOrNull() ?: return fail("usage: grep PATTERN [FILE]", cwd)
                val ignore = 'i' in flags
                val regex = Regex(Regex.escape(pattern), if (ignore) setOf(RegexOption.IGNORE_CASE) else emptySet())
                val sourcesList = if (operands.size > 1) operands.drop(1).map { it to ws.read(it, cwd) } else listOf("" to (stdin ?: ""))
                val hits = sourcesList.flatMap { (file, text) ->
                    text.split('\n').mapIndexedNotNull { i, line ->
                        if (regex.containsMatchIn(line)) buildString {
                            if (operands.size > 2) append(file).append(':')
                            if ('n' in flags) append(i + 1).append(':')
                            append(line)
                        } else null
                    }
                }
                if (hits.isEmpty()) ShellResult("", 1, cwd) else ok(hits.joinToString("\n"), cwd)
            }
            "find" -> {
                val base = ws.resolve(operands.firstOrNull() ?: ".", cwd)
                val nameIdx = rest.indexOf("-name")
                val glob = if (nameIdx >= 0) rest.getOrNull(nameIdx + 1) else null
                val regex = glob?.let { Regex("^" + Regex.escape(it).replace("*", "\\E.*\\Q") + "$") }
                ok(base.walkTopDown().filter { regex == null || regex.matches(it.name) }
                    .joinToString("\n") { "." + ws.virtualOf(it).removePrefix(ws.virtualOf(base).trimEnd('/')) }, cwd)
            }
            "echo" -> ok(operands.joinToString(" "), cwd)
            "printf" -> ok(operands.joinToString(" ").replace("\\n", "\n"), cwd)
            "mkdir" -> { operands.forEach { ws.mkdir(it, cwd) }; ok("", cwd) }
            "touch" -> {
                operands.forEach { if (!ws.exists(it, cwd)) ws.write(it, "", cwd) else ws.resolve(it, cwd).setLastModified(System.currentTimeMillis()) }
                ok("", cwd)
            }
            "rm" -> { operands.forEach { ws.delete(it, cwd, recursive = 'r' in flags || 'R' in flags) }; ok("", cwd) }
            "rmdir" -> { operands.forEach { ws.delete(it, cwd, recursive = false) }; ok("", cwd) }
            "mv" -> if (operands.size < 2) fail("usage: mv SRC DST", cwd) else { ws.move(operands[0], operands[1], cwd); ok("", cwd) }
            "cp" -> if (operands.size < 2) fail("usage: cp SRC DST", cwd) else { ws.copy(operands[0], operands[1], cwd); ok("", cwd) }
            "date" -> ok(SimpleDateFormat("EEE MMM d HH:mm:ss zzz yyyy", Locale.US).format(Date()), cwd)
            "whoami" -> ok("agent", cwd)
            "uname" -> ok(if ('a' in flags) "Linux localhost 6.1.0-android aarch64 Android" else "Linux", cwd)
            "env" -> ok("HOME=/workspace\nPWD=/workspace$cwd\nSHELL=/system/bin/sh\nPREFIX=/workspace/.usr", cwd)
            "open", "edit" -> {
                val target = operands.firstOrNull() ?: return fail("usage: $name FILE", cwd)
                val f = ws.resolve(target, cwd)
                if (!f.exists()) ws.write(target, "", cwd)
                ShellResult("", 0, cwd, openFile = ws.virtualOf(f))
            }
            "python", "python3" -> python(operands, cwd)
            "pip", "pip3", "pkg", "apt", "apt-get", "npm" -> install(name, operands, cwd)
            "curl", "wget" -> ok("$name: network access is routed through the web tool in this sandbox", cwd).copy(exitCode = 0)
            "git" -> ok(gitStub(operands), cwd)
            "help" -> ok(HELP, cwd)
            "clear" -> ok("\u000c", cwd)
            else -> fail("$name: command not found", cwd, 127)
        }
    }

    private fun ls(paths: List<String>, cwd: String, long: Boolean, all: Boolean): String = paths.joinToString("\n") { p ->
        val f = ws.resolve(p, cwd)
        if (!f.exists()) throw WorkspaceException("ls: $p: no such file or directory")
        val entries = ws.list(p, cwd).filter { all || !it.name.startsWith(".") }
        if (!long) entries.joinToString("  ") { if (it.isDirectory) it.name + "/" else it.name }
        else entries.joinToString("\n") {
            val type = if (it.isDirectory) "d" else "-"
            val size = if (it.isDirectory) 4096 else it.length()
            val date = SimpleDateFormat("MMM d HH:mm", Locale.US).format(Date(it.lastModified()))
            "%srw-r--r--  agent  %7d  %s  %s".format(type, size, date, it.name)
        }
    }

    private fun tree(path: String, cwd: String): String {
        val base = ws.resolve(path, cwd)
        val sb = StringBuilder(".\n")
        fun walk(dir: java.io.File, prefix: String) {
            val kids = dir.listFiles()?.filterNot { it.name.startsWith(".") }?.sortedBy { it.name } ?: return
            kids.forEachIndexed { i, f ->
                val last = i == kids.lastIndex
                sb.append(prefix).append(if (last) "\u2514\u2500\u2500 " else "\u251c\u2500\u2500 ").append(f.name).append('\n')
                if (f.isDirectory) walk(f, prefix + if (last) "    " else "\u2502   ")
            }
        }
        walk(base, "")
        return sb.toString().trimEnd('\n')
    }

    private fun python(operands: List<String>, cwd: String): ShellResult {
        val script = operands.firstOrNull() ?: return ok("Python 3.11.9 (sandbox)", cwd)
        if (script == "-c") return ok(operands.drop(1).joinToString(" ").substringAfter("print(").substringBeforeLast(")").trim('"', '\''), cwd)
        val source = ws.read(script, cwd)
        // Deterministic simulation: echo every print("...") literal in order.
        val prints = Regex("print\\(\\s*[fr]?([\"'])(.*?)\\1\\s*\\)").findAll(source).map { it.groupValues[2] }.toList()
        val outFile = Regex("open\\(\\s*[\"']([^\"']+)[\"']\\s*,\\s*[\"']w[\"']").find(source)?.groupValues?.get(1)
        val body = Regex("CONTENT\\s*=\\s*\"\"\"([\\s\\S]*?)\"\"\"").find(source)?.groupValues?.get(1)
        if (outFile != null && body != null) ws.write(outFile, body.trimStart('\n'), cwd)
        return ok(prints.joinToString("\n"), cwd)
    }

    private fun install(manager: String, operands: List<String>, cwd: String): ShellResult {
        val pkgs = operands.filterNot { it == "install" || it == "i" || it == "add" }
        if (pkgs.isEmpty()) return ok("$manager: nothing to do", cwd)
        val lines = pkgs.flatMap { listOf("Collecting $it", "Installing $it into /workspace/.usr") } + "Successfully installed ${pkgs.joinToString(" ")}"
        ws.mkdir(".usr", "/")
        ws.write(".usr/installed.txt", pkgs.joinToString("\n", postfix = "\n"), "/", append = true)
        return ok(lines.joinToString("\n"), cwd)
    }

    private fun gitStub(operands: List<String>) = when (operands.firstOrNull()) {
        "status" -> "On branch main\nnothing to commit, working tree clean"
        "log" -> "commit 4f1c2ab (HEAD -> main)\n    Initial workspace"
        else -> "git: '${operands.firstOrNull() ?: ""}' is not available in the sandbox"
    }

    private fun ok(output: String, cwd: String) = ShellResult(output, 0, cwd)
    private fun fail(output: String, cwd: String, code: Int = 1) = ShellResult(output, code, cwd)

    companion object {
        const val HELP = "pwd  cd  ls  tree  cat  head  tail  wc  grep  find  echo  printf\n" +
            "mkdir  touch  rm  rmdir  mv  cp  open  edit  python  pip  pkg  git  date  clear"
    }
}
