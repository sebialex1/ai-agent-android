package com.sebialex.agent.domain.markdown

/**
 * A LaTeX math formula as a small layout tree. Covers what models actually write in answers:
 * letters and numbers, Greek and operator symbols, super- and subscripts, fractions, roots,
 * big operators with limits, accents, `\left…\right` fences, matrices / cases, `\text{}` and
 * the blackboard / bold / calligraphic alphabets. Anything else is kept as readable text, never
 * dropped, so an unknown command shows up as itself instead of vanishing.
 *
 * The tree is rendered natively by the UI (display math as real stacked layout, inline math as
 * styled text runs, see [MathText]); there is no WebView and no bundled script.
 */
sealed interface MathNode {
    /** A horizontal sequence. */
    data class Row(val items: List<MathNode>) : MathNode

    /** One symbol or word. [kind] decides font style and the spacing around it. */
    data class Sym(val text: String, val kind: SymKind) : MathNode

    data class Scripts(val base: MathNode, val sup: MathNode?, val sub: MathNode?) : MathNode

    /** A fraction; without [bar] it is a binomial-style stack. */
    data class Frac(val num: MathNode, val den: MathNode, val bar: Boolean = true) : MathNode

    data class Sqrt(val body: MathNode, val index: MathNode? = null) : MathNode

    /** ∑, ∏, ∫, lim …: limits go above and below in display math, beside it inline. */
    data class BigOp(val symbol: String, val sub: MathNode? = null, val sup: MathNode? = null, val limits: Boolean = true) : MathNode

    /** A mark over (or under, for [Accent.under]) its body: hat, bar, vector arrow, dot, tilde. */
    data class Accent(val body: MathNode, val mark: AccentMark) : MathNode

    /** Delimiters that grow with their body (`\left( … \right)`, matrix brackets). Empty = none. */
    data class Fenced(val open: String, val close: String, val body: MathNode) : MathNode

    /** A grid of cells (matrix, cases, aligned). [leftAligned] for cases / aligned. */
    data class Matrix(val rows: List<List<MathNode>>, val leftAligned: Boolean = false) : MathNode

    /** Explicit spacing in em (`\,` `\quad` …). */
    data class Space(val em: Float) : MathNode
}

enum class SymKind {
    /** A single letter: italic. */
    VARIABLE,
    NUMBER,
    /** Binary operators and relations: spaced on both sides. */
    OPERATOR,
    PUNCT,
    /** Upright words: `\text{}`, function names such as sin and log, unknown commands. */
    TEXT,
}

enum class AccentMark(val combining: Char) {
    HAT('\u0302'), BAR('\u0304'), VEC('\u20D7'), DOT('\u0307'), DDOT('\u0308'), TILDE('\u0303'), UNDERLINE('\u0332');

    val under: Boolean get() = this == UNDERLINE
}

/**
 * Parses LaTeX math into a [MathNode]. Never throws: unbalanced braces simply end the group,
 * and a command it does not know becomes upright text with its name.
 */
object LatexParser {
    fun parse(tex: String): MathNode = runCatching {
        val reader = Reader(tokenize(tex))
        // A stray closing brace ends a group early; at the top level it is skipped, never the rest.
        val parts = mutableListOf<MathNode>()
        while (true) {
            parts += reader.row(setOf())
            if (reader.peek() == null) break
            reader.pos++
        }
        parts.singleOrNull() ?: MathNode.Row(parts)
    }.getOrElse { MathNode.Sym(tex, SymKind.TEXT) }

    // ---- Tokens ------------------------------------------------------------------------

    private sealed interface Tok {
        data class Cmd(val name: String) : Tok
        data class Ch(val c: Char) : Tok
    }

    private fun tokenize(s: String): List<Tok> {
        val out = mutableListOf<Tok>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                if (s[i + 1].isLetter()) {
                    var j = i + 1
                    while (j < s.length && s[j].isLetter()) j++
                    out += Tok.Cmd(s.substring(i + 1, j))
                    i = j
                } else {
                    out += Tok.Cmd(s[i + 1].toString())
                    i += 2
                }
                continue
            }
            out += Tok.Ch(c)
            i++
        }
        return out
    }

    // ---- Reader ------------------------------------------------------------------------

    private class Reader(val toks: List<Tok>) {
        var pos = 0

        fun peek(): Tok? = toks.getOrNull(pos)

        private fun isStop(t: Tok?, stops: Set<String>): Boolean = when (t) {
            null -> true
            is Tok.Ch -> t.c == '}' || (t.c == '&' && "&" in stops)
            is Tok.Cmd -> t.name in stops
        }

        /** Reads atoms until a closing brace, the end, or one of [stops] (`right`, `&`, `\\`, `end`). */
        fun row(stops: Set<String>): MathNode {
            val items = mutableListOf<MathNode>()
            while (!isStop(peek(), stops)) {
                val t = peek()
                // A row separator outside a grid is just a gap.
                if (t is Tok.Cmd && t.name == "\\") { pos++; items += MathNode.Space(1f); continue }
                if (t is Tok.Ch && t.c == '&') { pos++; continue }
                val atom = atom() ?: continue
                items += scripts(atom)
            }
            return items.singleOrNull() ?: MathNode.Row(items)
        }

        /** Attaches any number of `^` and `_` to [base]. Big operators keep them as limits. */
        private fun scripts(base: MathNode): MathNode {
            var sup: MathNode? = null
            var sub: MathNode? = null
            var primes = 0
            while (true) {
                val t = peek()
                when {
                    t is Tok.Ch && t.c == '^' -> { pos++; sup = argument() }
                    t is Tok.Ch && t.c == '_' -> { pos++; sub = argument() }
                    t is Tok.Ch && t.c == '\'' -> { pos++; primes++ }
                    else -> break
                }
            }
            if (primes > 0) {
                val p = MathNode.Sym("\u2032".repeat(primes), SymKind.PUNCT)
                sup = if (sup == null) p else MathNode.Row(listOf(p, sup))
            }
            if (sup == null && sub == null) return base
            return if (base is MathNode.BigOp) base.copy(sub = sub ?: base.sub, sup = sup ?: base.sup) else MathNode.Scripts(base, sup, sub)
        }

        /** One argument: a `{group}` or the next single atom. */
        fun argument(): MathNode {
            val t = peek() ?: return MathNode.Row(emptyList())
            if (t is Tok.Ch && t.c == '{') return group()
            if (t is Tok.Ch && t.c == ' ') { pos++; return argument() }
            // \frac12 is one half: a bare digit argument is a single digit, not the whole number.
            if (t is Tok.Ch && t.c.isDigit()) { pos++; return MathNode.Sym(t.c.toString(), SymKind.NUMBER) }
            return atom() ?: MathNode.Row(emptyList())
        }

        fun group(): MathNode {
            pos++ // {
            val inner = row(setOf())
            if ((peek() as? Tok.Ch)?.c == '}') pos++
            return inner
        }

        /** Raw text of a `{…}` argument (for \text, \begin names), braces balanced. */
        fun rawGroup(): String {
            val t = peek()
            if (t !is Tok.Ch || t.c != '{') return ""
            pos++
            var depth = 1
            val sb = StringBuilder()
            while (pos < toks.size) {
                val tok = toks[pos++]
                when {
                    tok is Tok.Ch && tok.c == '{' -> { depth++; sb.append('{') }
                    tok is Tok.Ch && tok.c == '}' -> { depth--; if (depth == 0) break; sb.append('}') }
                    tok is Tok.Ch -> sb.append(tok.c)
                    tok is Tok.Cmd -> sb.append(if (tok.name.length == 1 && !tok.name[0].isLetter()) tok.name else "\\" + tok.name)
                }
            }
            return sb.toString()
        }

        fun optional(): MathNode? {
            val t = peek()
            if (t !is Tok.Ch || t.c != '[') return null
            pos++
            val items = mutableListOf<MathNode>()
            while (true) {
                val n = peek() ?: break
                if (n is Tok.Ch && n.c == ']') { pos++; break }
                if (n is Tok.Ch && n.c == '}') break
                atom()?.let { items += scripts(it) }
            }
            return items.singleOrNull() ?: MathNode.Row(items)
        }

        fun atom(): MathNode? {
            val t = peek() ?: return null
            pos++
            return when (t) {
                is Tok.Ch -> char(t.c)
                is Tok.Cmd -> command(t.name)
            }
        }

        private fun char(c: Char): MathNode? = when {
            c == '{' -> { pos--; group() }
            c == ' ' || c == '\n' || c == '\t' || c == '~' -> null
            c.isDigit() || c == '.' -> {
                // Consecutive digits stay one number, so "12.5" is not three symbols.
                val sb = StringBuilder().append(c)
                while (true) {
                    val n = peek() as? Tok.Ch ?: break
                    if (n.c.isDigit() || (n.c == '.' && sb.last().isDigit())) { sb.append(n.c); pos++ } else break
                }
                MathNode.Sym(sb.toString(), SymKind.NUMBER)
            }
            c.isLetter() -> MathNode.Sym(c.toString(), SymKind.VARIABLE)
            c in "+-=<>*/" -> MathNode.Sym(
                when (c) { '-' -> "\u2212"; '*' -> "\u2217"; else -> c.toString() },
                if (c == '/') SymKind.PUNCT else SymKind.OPERATOR,
            )
            c == '^' || c == '_' -> null
            else -> MathNode.Sym(c.toString(), SymKind.PUNCT)
        }

        private fun command(name: String): MathNode? {
            when (name) {
                "frac", "dfrac", "tfrac", "cfrac" -> return MathNode.Frac(argument(), argument())
                "binom", "dbinom", "tbinom" -> return MathNode.Fenced("(", ")", MathNode.Frac(argument(), argument(), bar = false))
                "sqrt" -> { val idx = optional(); return MathNode.Sqrt(argument(), idx) }
                "text", "textrm", "mathrm", "textit", "textbf", "mbox", "operatorname", "textnormal", "texttt", "mathtt", "mathsf" ->
                    return MathNode.Sym(rawGroup().replace("\\ ", " "), SymKind.TEXT)
                "mathbf", "boldsymbol", "bm" -> return styled(argument(), BOLD)
                "mathbb" -> return styled(argument(), DOUBLE_STRUCK)
                "mathcal", "mathscr" -> return styled(argument(), SCRIPT)
                "mathit" -> return argument()
                "left" -> return fenced()
                "right" -> return null
                "begin" -> return environment(rawGroup())
                "end" -> { rawGroup(); return null }
                "hat", "widehat" -> return MathNode.Accent(argument(), AccentMark.HAT)
                "bar", "overline" -> return MathNode.Accent(argument(), AccentMark.BAR)
                "vec", "overrightarrow" -> return MathNode.Accent(argument(), AccentMark.VEC)
                "dot" -> return MathNode.Accent(argument(), AccentMark.DOT)
                "ddot" -> return MathNode.Accent(argument(), AccentMark.DDOT)
                "tilde", "widetilde" -> return MathNode.Accent(argument(), AccentMark.TILDE)
                "underline" -> return MathNode.Accent(argument(), AccentMark.UNDERLINE)
                "displaystyle", "textstyle", "scriptstyle", "limits", "nolimits", "big", "Big", "bigg", "Bigg",
                "bigl", "bigr", "Bigl", "Bigr", "biggl", "biggr", "nonumber", "notag" -> return null
                "label", "tag" -> { rawGroup(); return null }
            }
            SPACES[name]?.let { return MathNode.Space(it) }
            BIG_OPS[name]?.let { return MathNode.BigOp(it, limits = name !in SIDE_LIMIT_OPS) }
            if (name in LIMIT_FUNCS) return MathNode.BigOp(name, limits = true)
            if (name in FUNCS) return MathNode.Sym(name, SymKind.TEXT)
            SYMBOLS[name]?.let { (text, kind) -> return MathNode.Sym(text, kind) }
            // Escaped characters: \{ \} \% \$ \& \# \_ and \| .
            if (name.length == 1 && !name[0].isLetter()) {
                return MathNode.Sym(if (name == "|") "\u2016" else name, SymKind.PUNCT)
            }
            return MathNode.Sym("\\" + name, SymKind.TEXT)
        }

        private fun delimiter(): String {
            val t = peek() ?: return ""
            pos++
            return when (t) {
                is Tok.Ch -> if (t.c == '.') "" else t.c.toString()
                is Tok.Cmd -> when (t.name) {
                    "{", "lbrace" -> "{"
                    "}", "rbrace" -> "}"
                    "|", "Vert" -> "\u2016"
                    "vert" -> "|"
                    "langle" -> "\u27E8"
                    "rangle" -> "\u27E9"
                    "lfloor" -> "\u230A"; "rfloor" -> "\u230B"
                    "lceil" -> "\u2308"; "rceil" -> "\u2309"
                    else -> ""
                }
            }
        }

        private fun fenced(): MathNode {
            val open = delimiter()
            val body = row(setOf("right"))
            var close = ""
            if ((peek() as? Tok.Cmd)?.name == "right") { pos++; close = delimiter() }
            return MathNode.Fenced(open, close, body)
        }

        private fun environment(name: String): MathNode {
            val env = name.trim().removeSuffix("*")
            // array takes a column spec ({cc}) that is not part of the content.
            if (env == "array") rawGroup()
            val rows = mutableListOf<List<MathNode>>()
            var cells = mutableListOf<MathNode>()
            while (true) {
                cells += row(setOf("&", "\\", "end"))
                val t = peek()
                when {
                    t is Tok.Ch && t.c == '&' -> pos++
                    t is Tok.Cmd && t.name == "\\" -> { pos++; rows += cells; cells = mutableListOf() }
                    t is Tok.Cmd && t.name == "end" -> { pos++; rawGroup(); break }
                    else -> break
                }
            }
            // A trailing \\ leaves one empty cell behind; it is not a row.
            if (!(cells.size == 1 && cells[0] == MathNode.Row(emptyList()))) rows += cells
            val left = env in setOf("cases", "aligned", "align", "gathered", "split", "eqnarray", "alignat")
            val grid = MathNode.Matrix(rows, leftAligned = left)
            return when (env) {
                "pmatrix" -> MathNode.Fenced("(", ")", grid)
                "bmatrix" -> MathNode.Fenced("[", "]", grid)
                "Bmatrix" -> MathNode.Fenced("{", "}", grid)
                "vmatrix" -> MathNode.Fenced("|", "|", grid)
                "Vmatrix" -> MathNode.Fenced("\u2016", "\u2016", grid)
                "cases" -> MathNode.Fenced("{", "", grid)
                else -> grid
            }
        }

        private fun styled(node: MathNode, alphabet: Map<Char, String>): MathNode = when (node) {
            is MathNode.Sym -> MathNode.Sym(node.text.map { alphabet[it] ?: it.toString() }.joinToString(""), if (node.kind == SymKind.VARIABLE) SymKind.TEXT else node.kind)
            is MathNode.Row -> MathNode.Row(node.items.map { styled(it, alphabet) })
            else -> node
        }
    }

    // ---- Tables ------------------------------------------------------------------------

    private val SPACES = mapOf(
        "," to 0.17f, ":" to 0.22f, ";" to 0.28f, ">" to 0.22f, " " to 0.28f, "!" to 0f,
        "quad" to 1f, "qquad" to 2f, "enspace" to 0.5f, "thinspace" to 0.17f,
    )

    private val BIG_OPS = mapOf(
        "sum" to "\u2211", "prod" to "\u220F", "coprod" to "\u2210", "int" to "\u222B", "iint" to "\u222C",
        "iiint" to "\u222D", "oint" to "\u222E", "bigcup" to "\u22C3", "bigcap" to "\u22C2", "bigoplus" to "\u2A01",
        "bigotimes" to "\u2A02", "bigvee" to "\u22C1", "bigwedge" to "\u22C0",
    )

    /** Integrals put their limits beside the sign, as in print. */
    private val SIDE_LIMIT_OPS = setOf("int", "iint", "iiint", "oint")

    private val LIMIT_FUNCS = setOf("lim", "max", "min", "sup", "inf", "limsup", "liminf", "argmax", "argmin")

    private val FUNCS = setOf(
        "sin", "cos", "tan", "cot", "sec", "csc", "arcsin", "arccos", "arctan", "sinh", "cosh", "tanh", "coth",
        "log", "ln", "lg", "exp", "det", "dim", "ker", "deg", "gcd", "lcm", "arg", "Pr", "hom", "mod", "bmod", "pmod",
    )

    private fun op(s: String) = s to SymKind.OPERATOR
    private fun sym(s: String) = s to SymKind.PUNCT
    private fun v(s: String) = s to SymKind.VARIABLE

    private val SYMBOLS: Map<String, Pair<String, SymKind>> = mapOf(
        // Greek
        "alpha" to v("\u03B1"), "beta" to v("\u03B2"), "gamma" to v("\u03B3"), "delta" to v("\u03B4"),
        "epsilon" to v("\u03F5"), "varepsilon" to v("\u03B5"), "zeta" to v("\u03B6"), "eta" to v("\u03B7"),
        "theta" to v("\u03B8"), "vartheta" to v("\u03D1"), "iota" to v("\u03B9"), "kappa" to v("\u03BA"),
        "lambda" to v("\u03BB"), "mu" to v("\u03BC"), "nu" to v("\u03BD"), "xi" to v("\u03BE"), "omicron" to v("\u03BF"),
        "pi" to v("\u03C0"), "varpi" to v("\u03D6"), "rho" to v("\u03C1"), "varrho" to v("\u03F1"), "sigma" to v("\u03C3"),
        "varsigma" to v("\u03C2"), "tau" to v("\u03C4"), "upsilon" to v("\u03C5"), "phi" to v("\u03D5"),
        "varphi" to v("\u03C6"), "chi" to v("\u03C7"), "psi" to v("\u03C8"), "omega" to v("\u03C9"),
        "Gamma" to sym("\u0393"), "Delta" to sym("\u0394"), "Theta" to sym("\u0398"), "Lambda" to sym("\u039B"),
        "Xi" to sym("\u039E"), "Pi" to sym("\u03A0"), "Sigma" to sym("\u03A3"), "Upsilon" to sym("\u03A5"),
        "Phi" to sym("\u03A6"), "Psi" to sym("\u03A8"), "Omega" to sym("\u03A9"),
        // Binary operators
        "times" to op("\u00D7"), "cdot" to op("\u22C5"), "div" to op("\u00F7"), "pm" to op("\u00B1"), "mp" to op("\u2213"),
        "ast" to op("\u2217"), "star" to op("\u22C6"), "circ" to op("\u2218"), "bullet" to op("\u2219"),
        "cup" to op("\u222A"), "cap" to op("\u2229"), "setminus" to op("\u2216"), "oplus" to op("\u2295"),
        "otimes" to op("\u2297"), "wedge" to op("\u2227"), "land" to op("\u2227"), "vee" to op("\u2228"), "lor" to op("\u2228"),
        // Relations
        "leq" to op("\u2264"), "le" to op("\u2264"), "geq" to op("\u2265"), "ge" to op("\u2265"), "neq" to op("\u2260"),
        "ne" to op("\u2260"), "approx" to op("\u2248"), "equiv" to op("\u2261"), "sim" to op("\u223C"), "simeq" to op("\u2243"),
        "cong" to op("\u2245"), "propto" to op("\u221D"), "ll" to op("\u226A"), "gg" to op("\u226B"),
        "in" to op("\u2208"), "notin" to op("\u2209"), "ni" to op("\u220B"), "subset" to op("\u2282"), "supset" to op("\u2283"),
        "subseteq" to op("\u2286"), "supseteq" to op("\u2287"), "mid" to op("\u2223"), "parallel" to op("\u2225"),
        "perp" to op("\u22A5"), "models" to op("\u22A8"), "vdash" to op("\u22A2"), "coloneqq" to op("\u2254"),
        // Arrows
        "to" to op("\u2192"), "rightarrow" to op("\u2192"), "leftarrow" to op("\u2190"), "gets" to op("\u2190"),
        "leftrightarrow" to op("\u2194"), "Rightarrow" to op("\u21D2"), "Leftarrow" to op("\u21D0"),
        "Leftrightarrow" to op("\u21D4"), "implies" to op("\u21D2"), "iff" to op("\u21D4"), "impliedby" to op("\u21D0"),
        "mapsto" to op("\u21A6"), "longrightarrow" to op("\u27F6"), "longmapsto" to op("\u27FC"),
        "uparrow" to sym("\u2191"), "downarrow" to sym("\u2193"),
        "longleftrightarrow" to op("\u27F7"), "Longrightarrow" to op("\u27F9"),
        // Misc
        "infty" to sym("\u221E"), "partial" to sym("\u2202"), "nabla" to sym("\u2207"), "forall" to sym("\u2200"),
        "exists" to sym("\u2203"), "nexists" to sym("\u2204"), "emptyset" to sym("\u2205"), "varnothing" to sym("\u2205"),
        "neg" to sym("\u00AC"), "lnot" to sym("\u00AC"), "angle" to sym("\u2220"), "triangle" to sym("\u25B3"),
        "ldots" to sym("\u2026"), "dots" to sym("\u2026"), "cdots" to sym("\u22EF"), "vdots" to sym("\u22EE"), "ddots" to sym("\u22F1"),
        "prime" to sym("\u2032"), "degree" to sym("\u00B0"), "hbar" to v("\u210F"), "ell" to v("\u2113"),
        "Re" to sym("\u211C"), "Im" to sym("\u2111"), "aleph" to sym("\u2135"), "therefore" to sym("\u2234"), "because" to sym("\u2235"),
        "langle" to sym("\u27E8"), "rangle" to sym("\u27E9"), "lfloor" to sym("\u230A"), "rfloor" to sym("\u230B"),
        "lceil" to sym("\u2308"), "rceil" to sym("\u2309"), "vert" to sym("|"), "Vert" to sym("\u2016"),
        "lbrace" to sym("{"), "rbrace" to sym("}"), "colon" to sym(":"), "percent" to sym("%"),
        "checkmark" to sym("\u2713"), "square" to sym("\u25A1"), "blacksquare" to sym("\u25A0"), "qed" to sym("\u220E"),
    )

    private val BOLD: Map<Char, String> = buildMap {
        ('A'..'Z').forEachIndexed { i, c -> put(c, String(Character.toChars(0x1D400 + i))) }
        ('a'..'z').forEachIndexed { i, c -> put(c, String(Character.toChars(0x1D41A + i))) }
        ('0'..'9').forEachIndexed { i, c -> put(c, String(Character.toChars(0x1D7CE + i))) }
    }

    private val DOUBLE_STRUCK: Map<Char, String> = buildMap {
        // The letters that already existed in Unicode's letterlike block are used from there.
        val special = mapOf('C' to "\u2102", 'H' to "\u210D", 'N' to "\u2115", 'P' to "\u2119", 'Q' to "\u211A", 'R' to "\u211D", 'Z' to "\u2124")
        ('A'..'Z').forEachIndexed { i, c -> put(c, special[c] ?: String(Character.toChars(0x1D538 + i))) }
        ('0'..'9').forEachIndexed { i, c -> put(c, String(Character.toChars(0x1D7D8 + i))) }
    }

    private val SCRIPT: Map<Char, String> = buildMap {
        val special = mapOf(
            'B' to "\u212C", 'E' to "\u2130", 'F' to "\u2131", 'H' to "\u210B", 'I' to "\u2110", 'L' to "\u2112",
            'M' to "\u2133", 'R' to "\u211B",
        )
        ('A'..'Z').forEachIndexed { i, c -> put(c, special[c] ?: String(Character.toChars(0x1D49C + i))) }
    }
}

/** One styled piece of inline math: [shift] +1 is a superscript level, −1 a subscript level. */
data class MathRun(val text: String, val shift: Int = 0, val italic: Boolean = false)

/**
 * Flattens a [MathNode] into text runs for inline math inside a paragraph: real superscripts
 * and subscripts (the UI raises/lowers them), fractions as a/b, roots as √x, accents as
 * combining marks. [plain] is the same as one Unicode string, for tests and copying.
 */
object MathText {
    fun runs(node: MathNode): List<MathRun> = mutableListOf<MathRun>().also { emit(node, 0, it) }.let(::merge)

    fun plain(tex: String): String = runs(LatexParser.parse(tex)).joinToString("") { r ->
        when {
            r.shift > 0 -> r.text.map { SUPERSCRIPTS[it] ?: it }.joinToString("").let { if (it == r.text && r.text.length > 1) "^($it)" else if (it == r.text) "^$it" else it }
            r.shift < 0 -> r.text.map { SUBSCRIPTS[it] ?: it }.joinToString("").let { if (it == r.text && r.text.length > 1) "_($it)" else if (it == r.text) "_$it" else it }
            else -> r.text
        }
    }

    private fun emit(n: MathNode, shift: Int, out: MutableList<MathRun>) {
        when (n) {
            is MathNode.Row -> n.items.forEach { emit(it, shift, out) }
            is MathNode.Sym -> out += when (n.kind) {
                SymKind.OPERATOR -> MathRun(if (shift == 0) " ${n.text} " else n.text, shift)
                SymKind.VARIABLE -> MathRun(n.text, shift, italic = true)
                else -> MathRun(n.text, shift)
            }
            is MathNode.Scripts -> {
                emit(n.base, shift, out)
                n.sub?.let { emit(it, shift - 1, out) }
                n.sup?.let { emit(it, shift + 1, out) }
            }
            is MathNode.Frac -> {
                val sep = if (n.bar) "/" else " choose "
                group(n.num, shift, out); out += MathRun(sep, shift); group(n.den, shift, out)
            }
            is MathNode.Sqrt -> {
                n.index?.let { emit(it, shift + 1, out) }
                out += MathRun("\u221A", shift)
                group(n.body, shift, out)
            }
            is MathNode.BigOp -> {
                out += MathRun(n.symbol + if (n.symbol.first().isLetter()) " " else "", shift)
                n.sub?.let { emit(it, shift - 1, out) }
                n.sup?.let { emit(it, shift + 1, out) }
                out += MathRun(" ", shift)
            }
            is MathNode.Accent -> {
                val before = out.size
                emit(n.body, shift, out)
                // The mark combines with the last character of the body.
                if (out.size > before) out[out.lastIndex] = out.last().let { it.copy(text = it.text + n.mark.combining) }
            }
            is MathNode.Fenced -> {
                if (n.open.isNotEmpty()) out += MathRun(n.open, shift)
                emit(n.body, shift, out)
                if (n.close.isNotEmpty()) out += MathRun(n.close, shift)
            }
            is MathNode.Matrix -> {
                n.rows.forEachIndexed { r, row ->
                    if (r > 0) out += MathRun("; ", shift)
                    row.forEachIndexed { c, cell -> if (c > 0) out += MathRun(", ", shift); emit(cell, shift, out) }
                }
            }
            is MathNode.Space -> if (n.em > 0f) out += MathRun(if (n.em >= 1f) "  " else " ", shift)
        }
    }

    /** Wraps a fraction part in parentheses when it is more than one symbol. */
    private fun group(n: MathNode, shift: Int, out: MutableList<MathRun>) {
        val simple = n is MathNode.Sym || (n is MathNode.Scripts && n.base is MathNode.Sym) || n is MathNode.Fenced
        if (!simple) out += MathRun("(", shift)
        emit(n, shift, out)
        if (!simple) out += MathRun(")", shift)
    }

    private fun merge(runs: List<MathRun>): List<MathRun> {
        val out = mutableListOf<MathRun>()
        for (r in runs) {
            if (r.text.isEmpty()) continue
            val last = out.lastOrNull()
            if (last != null && last.shift == r.shift && last.italic == r.italic) out[out.lastIndex] = last.copy(text = last.text + r.text) else out += r
        }
        return out
    }

    private val SUPERSCRIPTS = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴', '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
        '+' to '⁺', '\u2212' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾', 'n' to 'ⁿ', 'i' to 'ⁱ',
    )
    private val SUBSCRIPTS = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄', '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
        '+' to '₊', '\u2212' to '₋', '=' to '₌', '(' to '₍', ')' to '₎',
    )
}
