package com.droidnotes.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class MathSpan(val start: Int = 0, val end: Int = 0)

sealed interface MathNode {
    val span: MathSpan

    data class Sequence(
        val nodes: List<MathNode>,
        override val span: MathSpan = MathSpan(),
    ) : MathNode

    data class Text(
        val text: String,
        val italic: Boolean = false,
        val bold: Boolean = false,
        override val span: MathSpan = MathSpan(),
    ) : MathNode

    data class Frac(
        val numerator: MathNode,
        val denominator: MathNode,
        override val span: MathSpan = MathSpan(),
    ) : MathNode

    data class Sqrt(
        val radicand: MathNode,
        val index: MathNode? = null,
        override val span: MathSpan = MathSpan(),
    ) : MathNode

    data class SupSub(
        val base: MathNode,
        val sup: MathNode? = null,
        val sub: MathNode? = null,
        override val span: MathSpan = MathSpan(),
    ) : MathNode

    data class BigOp(
        val symbol: String,
        val sub: MathNode? = null,
        val sup: MathNode? = null,
        override val span: MathSpan = MathSpan(),
    ) : MathNode

    data class Matrix(
        val rows: List<List<MathNode>>,
        val leftDelim: String,
        val rightDelim: String,
        override val span: MathSpan = MathSpan(),
    ) : MathNode

    data class Delimited(
        val left: String,
        val inner: MathNode,
        val right: String,
        override val span: MathSpan = MathSpan(),
    ) : MathNode
}

object LatexEngine {
    private val COMMAND_SYMBOLS = mapOf(
        // Greek lowercase
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ",
        "epsilon" to "ε", "varepsilon" to "ϵ", "zeta" to "ζ", "eta" to "η",
        "theta" to "θ", "vartheta" to "ϑ", "iota" to "ι", "kappa" to "κ",
        "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ",
        "pi" to "π", "varpi" to "ϖ", "rho" to "ρ", "sigma" to "σ",
        "varsigma" to "ς", "tau" to "τ", "upsilon" to "υ", "phi" to "φ",
        "varphi" to "ϕ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
        // Greek uppercase
        "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ",
        "Xi" to "Ξ", "Pi" to "Π", "Sigma" to "Σ", "Upsilon" to "Υ",
        "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
        // Operators & relations
        "pm" to "±", "mp" to "∓", "times" to "×", "div" to "÷",
        "cdot" to "·", "ast" to "∗", "star" to "⋆", "circ" to "∘",
        "oplus" to "⊕", "otimes" to "⊗", "odot" to "⊙", "dagger" to "†", "ddagger" to "‡",
        "leq" to "≤", "le" to "≤", "geq" to "≥", "ge" to "≥",
        "neq" to "≠", "ne" to "≠", "approx" to "≈", "equiv" to "≡",
        "sim" to "∼", "simeq" to "≃", "cong" to "≅", "propto" to "∝",
        "triangleq" to "≜", "coloneqq" to "≔",
        "ll" to "≪", "gg" to "≫", "subset" to "⊂", "supset" to "⊃",
        "subseteq" to "⊆", "supseteq" to "⊇", "subsetneq" to "⊊", "supsetneq" to "⊋",
        "in" to "∈", "notin" to "∉",
        "ni" to "∋", "cup" to "∪", "cap" to "∩", "emptyset" to "∅",
        "varnothing" to "∅", "setminus" to "∖",
        // Logic & calculus
        "forall" to "∀", "exists" to "∃", "nexists" to "∄",
        "neg" to "¬", "land" to "∧", "lor" to "∨",
        "vdash" to "⊢", "models" to "⊨", "top" to "⊤", "bot" to "⊥",
        "mid" to "∣", "nmid" to "∤",
        "implies" to "⟹", "iff" to "⟺", "to" to "→",
        "rightarrow" to "→", "leftarrow" to "←", "leftrightarrow" to "↔",
        "Rightarrow" to "⇒", "Leftarrow" to "⇐", "Leftrightarrow" to "⇔",
        "mapsto" to "↦", "uparrow" to "↑", "downarrow" to "↓",
        "infty" to "∞", "nabla" to "∇", "partial" to "∂", "prime" to "′",
        "hbar" to "ℏ", "ell" to "ℓ", "Re" to "ℜ", "Im" to "ℑ",
        "aleph" to "ℵ", "wp" to "℘", "angle" to "∠", "perp" to "⊥",
        "parallel" to "∥", "triangle" to "△", "degree" to "°",
        "therefore" to "∴", "because" to "∵",
        "lfloor" to "⌊", "rfloor" to "⌋", "lceil" to "⌈", "rceil" to "⌉",
        "langle" to "⟨", "rangle" to "⟩",
        "dots" to "…", "ldots" to "…", "cdots" to "⋯", "vdots" to "⋮", "ddots" to "⋱",
        "quad" to "  ", "qquad" to "    ", "," to " ", ";" to " ", " " to " ",
        "{" to "{", "}" to "}", "%" to "%", "&" to "&", "#" to "#", "_" to "_",
    )

    private val BIG_OPERATORS = mapOf(
        "sum" to "∑",
        "prod" to "∏",
        "coprod" to "∐",
        "int" to "∫",
        "iint" to "∬",
        "iiint" to "∭",
        "oint" to "∮",
        "bigcup" to "⋃",
        "bigcap" to "⋂",
        "bigoplus" to "⨁",
        "bigotimes" to "⨂",
        "bigvee" to "⋁",
        "bigwedge" to "⋀",
        "lim" to "lim",
        "max" to "max",
        "min" to "min",
        "sup" to "sup",
        "inf" to "inf",
    )

    private val UNICODE_BIG_OPERATORS = setOf(
        '∑', '∏', '∐', '∫', '∬', '∭', '∮', '⋃', '⋂', '⨁', '⨂', '⋁', '⋀',
    )

    private val TEXT_FUNCTIONS = setOf(
        "sin", "cos", "tan", "cot", "sec", "csc",
        "arcsin", "arccos", "arctan", "sinh", "cosh", "tanh",
        "log", "ln", "exp", "det", "dim", "ker", "gcd", "arg", "deg", "mod",
    )

    private val BLACKBOARD = mapOf(
        "R" to "ℝ", "N" to "ℕ", "Z" to "ℤ", "Q" to "ℚ", "C" to "ℂ", "P" to "ℙ", "E" to "𝔼",
    )

    private val UNICODE_BLACKBOARD_CHARS = setOf('ℝ', 'ℕ', 'ℤ', 'ℚ', 'ℂ', 'ℙ')

    private val CALLIGRAPHIC = mapOf(
        "E" to "ℰ", "F" to "ℱ", "H" to "ℋ", "L" to "ℒ", "M" to "ℳ", "O" to "𝒪", "P" to "𝒫", "B" to "ℬ",
    )

    fun parse(expression: String): MathNode {
        val parser = Parser(expression.trim(), alwaysWrapSequence = false)
        return parser.parseSequence(stopOn = emptySet())
    }

    fun parseForEditing(expression: String): MathNode.Sequence {
        val parser = Parser(expression, alwaysWrapSequence = true)
        val parsed = parser.parseSequence(stopOn = emptySet())
        return parsed as? MathNode.Sequence ?: MathNode.Sequence(listOf(parsed), MathSpan(0, expression.length))
    }

    fun toReadableString(expression: String): String {
        return nodeToPlainText(parse(expression))
    }

    private fun nodeToPlainText(node: MathNode): String = when (node) {
        is MathNode.Sequence -> node.nodes.joinToString("") { nodeToPlainText(it) }
        is MathNode.Text -> node.text
        is MathNode.Frac -> "(${nodeToPlainText(node.numerator)})/(${nodeToPlainText(node.denominator)})"
        is MathNode.Sqrt -> "√(${nodeToPlainText(node.radicand)})"
        is MathNode.SupSub -> {
            val b = nodeToPlainText(node.base)
            val sub = node.sub?.let { "_${nodeToPlainText(it)}" }.orEmpty()
            val sup = node.sup?.let { "^${nodeToPlainText(it)}" }.orEmpty()
            "$b$sub$sup"
        }
        is MathNode.BigOp -> {
            val sub = node.sub?.let { "_(${nodeToPlainText(it)})" }.orEmpty()
            val sup = node.sup?.let { "^(${nodeToPlainText(it)})" }.orEmpty()
            "${node.symbol}$sub$sup"
        }
        is MathNode.Matrix -> {
            val body = node.rows.joinToString("; ") { row -> row.joinToString(", ") { nodeToPlainText(it) } }
            "${node.leftDelim}$body${node.rightDelim}"
        }
        is MathNode.Delimited -> "${node.left}${nodeToPlainText(node.inner)}${node.right}"
    }

    private class Parser(
        private val input: String,
        private val alwaysWrapSequence: Boolean,
    ) {
        private var pos = 0

        fun parseSequence(stopOn: Set<String>): MathNode {
            val seqStart = pos
            val nodes = mutableListOf<MathNode>()
            while (pos < input.length) {
                skipSpacesPreserveSingleIfNeeded(nodes)
                if (pos >= input.length) break

                val ch = input[pos]
                if (ch == '}' && "}" in stopOn) break
                if (ch == ']' && "]" in stopOn) break
                if (ch == '&' && "&" in stopOn) break
                if (input.startsWith("\\\\", pos) && "\\\\" in stopOn) break
                if (input.startsWith("\\end{", pos) && "\\end" in stopOn) break
                if (input.startsWith("\\right", pos) && "\\right" in stopOn) break

                val atom = parseAtom() ?: break
                val withScripts = attachScripts(atom)
                nodes.add(withScripts)
            }
            val seqEnd = pos
            return if (!alwaysWrapSequence && nodes.size == 1) {
                nodes[0]
            } else {
                MathNode.Sequence(nodes, MathSpan(seqStart, seqEnd))
            }
        }

        private fun skipSpacesPreserveSingleIfNeeded(nodes: MutableList<MathNode>) {
            val spaceStart = pos
            var hadSpace = false
            while (pos < input.length && input[pos].isWhitespace()) {
                hadSpace = true
                pos++
            }
            if (hadSpace && nodes.isNotEmpty()) {
                val prev = nodes.last()
                if (prev is MathNode.Text && !prev.text.endsWith(" ")) {
                    nodes.add(MathNode.Text(" ", span = MathSpan(spaceStart, pos)))
                }
            }
        }

        private fun parseAtom(): MathNode? {
            if (pos >= input.length) return null
            val atomStart = pos
            val ch = input[pos]
            return when {
                ch == '{' -> {
                    pos++
                    val inner = parseSequence(setOf("}"))
                    if (pos < input.length && input[pos] == '}') pos++
                    inner
                }
                ch == '\\' -> parseCommand()
                ch in UNICODE_BIG_OPERATORS -> {
                    pos++
                    val scripts = readScriptsOnly()
                    MathNode.BigOp(ch.toString(), sub = scripts.second, sup = scripts.first, span = MathSpan(atomStart, pos))
                }
                ch == '√' -> {
                    pos++
                    val radicand = parseSlotArgument()
                    MathNode.Sqrt(radicand, null, span = MathSpan(atomStart, pos))
                }
                ch.isLetter() -> {
                    val wordStart = pos
                    var wordEnd = pos
                    while (wordEnd < input.length && input[wordEnd].isLetter()) wordEnd++
                    val word = input.substring(wordStart, wordEnd)
                    if (word.length >= 2) {
                        when (word) {
                            "lim", "max", "min", "sup", "inf" -> {
                                pos = wordEnd
                                val scripts = readScriptsOnly()
                                return MathNode.BigOp(
                                    word,
                                    sub = scripts.second,
                                    sup = scripts.first,
                                    span = MathSpan(atomStart, pos),
                                )
                            }
                            in TEXT_FUNCTIONS -> {
                                pos = wordEnd
                                return MathNode.Text(word, italic = false, span = MathSpan(atomStart, pos))
                            }
                        }
                    }
                    pos++
                    val sb = StringBuilder().append(ch)
                    while (pos < input.length && Character.getType(input[pos]) == Character.NON_SPACING_MARK.toInt()) {
                        sb.append(input[pos])
                        pos++
                    }
                    val isBlackboard = ch in UNICODE_BLACKBOARD_CHARS
                    MathNode.Text(sb.toString(), italic = !isBlackboard, span = MathSpan(atomStart, pos))
                }
                Character.isHighSurrogate(ch) && pos + 1 < input.length -> {
                    val pair = input.substring(pos, pos + 2)
                    pos += 2
                    MathNode.Text(pair, italic = false, span = MathSpan(atomStart, pos))
                }
                else -> {
                    pos++
                    MathNode.Text(ch.toString(), span = MathSpan(atomStart, pos))
                }
            }
        }

        private fun parseCommand(): MathNode {
            val cmdStart = pos
            pos++ // skip backslash
            if (pos >= input.length) return MathNode.Text("\\", span = MathSpan(cmdStart, pos))
            val next = input[pos]
            if (!next.isLetter()) {
                pos++
                val mapped = COMMAND_SYMBOLS[next.toString()] ?: next.toString()
                return MathNode.Text(mapped, span = MathSpan(cmdStart, pos))
            }
            val start = pos
            while (pos < input.length && input[pos].isLetter()) pos++
            val cmd = input.substring(start, pos)

            return when (cmd) {
                "frac", "dfrac", "tfrac" -> {
                    val num = parseSlotArgument()
                    val den = parseSlotArgument()
                    MathNode.Frac(num, den, span = MathSpan(cmdStart, pos))
                }
                "binom", "dbinom", "tbinom" -> {
                    val top = parseSlotArgument()
                    val bot = parseSlotArgument()
                    MathNode.Matrix(listOf(listOf(top), listOf(bot)), "(", ")", span = MathSpan(cmdStart, pos))
                }
                "vec" -> {
                    val content = readRawBraceContent()
                    MathNode.Text(if (content.isNotEmpty()) "${content}\u20D7" else "x\u20D7", italic = true, span = MathSpan(cmdStart, pos))
                }
                "hat", "widehat" -> {
                    val content = readRawBraceContent()
                    MathNode.Text(if (content.isNotEmpty()) "${content}\u0302" else "x\u0302", italic = true, span = MathSpan(cmdStart, pos))
                }
                "bar", "overline" -> {
                    val content = readRawBraceContent()
                    MathNode.Text(if (content.isNotEmpty()) "${content}\u0304" else "x\u0304", italic = true, span = MathSpan(cmdStart, pos))
                }
                "tilde", "widetilde" -> {
                    val content = readRawBraceContent()
                    MathNode.Text(if (content.isNotEmpty()) "${content}\u0303" else "x\u0303", italic = true, span = MathSpan(cmdStart, pos))
                }
                "dot" -> {
                    val content = readRawBraceContent()
                    MathNode.Text(if (content.isNotEmpty()) "${content}\u0307" else "x\u0307", italic = true, span = MathSpan(cmdStart, pos))
                }
                "ddot" -> {
                    val content = readRawBraceContent()
                    MathNode.Text(if (content.isNotEmpty()) "${content}\u0308" else "x\u0308", italic = true, span = MathSpan(cmdStart, pos))
                }
                "sqrt" -> {
                    var idx: MathNode? = null
                    skipWhitespace()
                    if (pos < input.length && input[pos] == '[') {
                        pos++
                        idx = parseSequence(setOf("]"))
                        if (pos < input.length && input[pos] == ']') pos++
                    }
                    val radicand = parseSlotArgument()
                    MathNode.Sqrt(radicand, idx, span = MathSpan(cmdStart, pos))
                }
                "text", "mathrm", "operatorname" -> {
                    val text = readRawBraceContent()
                    MathNode.Text(text, italic = false, span = MathSpan(cmdStart, pos))
                }
                "mathbf", "boldsymbol" -> {
                    val text = readRawBraceContent()
                    MathNode.Text(text, bold = true, span = MathSpan(cmdStart, pos))
                }
                "mathbb" -> {
                    val raw = readRawBraceContent()
                    val mapped = raw.map { BLACKBOARD[it.toString()] ?: it.toString() }.joinToString("")
                    MathNode.Text(mapped, span = MathSpan(cmdStart, pos))
                }
                "mathcal" -> {
                    val raw = readRawBraceContent()
                    val mapped = raw.map { CALLIGRAPHIC[it.toString()] ?: it.toString() }.joinToString("")
                    MathNode.Text(mapped, span = MathSpan(cmdStart, pos))
                }
                "left" -> {
                    skipWhitespace()
                    val left = readDelimiter()
                    val inner = parseSequence(setOf("\\right"))
                    var right = ""
                    if (input.startsWith("\\right", pos)) {
                        pos += 6
                        skipWhitespace()
                        right = readDelimiter()
                    }
                    MathNode.Delimited(left, inner, right, span = MathSpan(cmdStart, pos))
                }
                "begin" -> {
                    val env = readRawBraceContent()
                    val (leftDelim, rightDelim) = when (env) {
                        "pmatrix" -> "(" to ")"
                        "bmatrix" -> "[" to "]"
                        "Bmatrix" -> "{" to "}"
                        "vmatrix" -> "|" to "|"
                        "Vmatrix" -> "‖" to "‖"
                        "cases" -> "{" to ""
                        else -> "" to ""
                    }
                    val rows = mutableListOf<List<MathNode>>()
                    var currentRow = mutableListOf<MathNode>()
                    while (pos < input.length && !input.startsWith("\\end{", pos)) {
                        val cell = parseSequence(setOf("&", "\\\\", "\\end"))
                        currentRow.add(cell)
                        when {
                            pos < input.length && input[pos] == '&' -> pos++
                            input.startsWith("\\\\", pos) -> {
                                pos += 2
                                rows.add(currentRow)
                                currentRow = mutableListOf()
                            }
                            else -> break
                        }
                    }
                    if (currentRow.isNotEmpty()) rows.add(currentRow)
                    if (input.startsWith("\\end{", pos)) {
                        pos += 5
                        val close = input.indexOf('}', pos)
                        if (close != -1) pos = close + 1
                    }
                    MathNode.Matrix(rows, leftDelim, rightDelim, span = MathSpan(cmdStart, pos))
                }
                in BIG_OPERATORS -> {
                    val op = BIG_OPERATORS.getValue(cmd)
                    val scripts = readScriptsOnly()
                    MathNode.BigOp(op, sub = scripts.second, sup = scripts.first, span = MathSpan(cmdStart, pos))
                }
                in TEXT_FUNCTIONS -> MathNode.Text(cmd, italic = false, span = MathSpan(cmdStart, pos))
                else -> MathNode.Text(COMMAND_SYMBOLS[cmd] ?: cmd, span = MathSpan(cmdStart, pos))
            }
        }

        private fun attachScripts(base: MathNode): MathNode {
            if (base is MathNode.BigOp) return base
            val (sup, sub) = readScriptsOnly()
            return if (sup != null || sub != null) {
                MathNode.SupSub(base, sup, sub, span = MathSpan(base.span.start, pos))
            } else {
                base
            }
        }

        private fun readScriptsOnly(): Pair<MathNode?, MathNode?> {
            var sup: MathNode? = null
            var sub: MathNode? = null
            while (true) {
                val beforeWs = pos
                skipWhitespace()
                if (pos >= input.length) {
                    pos = beforeWs
                    break
                }
                when (input[pos]) {
                    '^' -> {
                        pos++
                        sup = parseSlotArgument()
                    }
                    '_' -> {
                        pos++
                        sub = parseSlotArgument()
                    }
                    else -> {
                        pos = beforeWs
                        return sup to sub
                    }
                }
            }
            return sup to sub
        }

        private fun parseSlotArgument(): MathNode {
            skipWhitespace()
            val slotStart = pos
            val parsed = parseAtom()
            if (parsed == null) {
                return if (alwaysWrapSequence) {
                    MathNode.Sequence(emptyList(), MathSpan(slotStart, slotStart))
                } else {
                    MathNode.Text("", span = MathSpan(slotStart, slotStart))
                }
            }
            return if (alwaysWrapSequence && parsed !is MathNode.Sequence) {
                MathNode.Sequence(listOf(parsed), parsed.span)
            } else {
                parsed
            }
        }

        private fun readRawBraceContent(): String {
            skipWhitespace()
            if (pos >= input.length) return ""
            if (input[pos] != '{') {
                val c = input[pos]
                pos++
                return c.toString()
            }
            pos++
            var depth = 1
            val start = pos
            while (pos < input.length && depth > 0) {
                if (input[pos] == '{') depth++
                else if (input[pos] == '}') depth--
                if (depth > 0) pos++
            }
            val res = input.substring(start, pos)
            if (pos < input.length && input[pos] == '}') pos++
            return res
        }

        private fun readDelimiter(): String {
            if (pos >= input.length) return ""
            return when (val ch = input[pos]) {
                '.' -> {
                    pos++
                    ""
                }
                '\\' -> {
                    pos++
                    if (pos < input.length) {
                        val c = input[pos]
                        pos++
                        if (c == '|') "‖" else c.toString()
                    } else ""
                }
                else -> {
                    pos++
                    ch.toString()
                }
            }
        }

        private fun skipWhitespace() {
            while (pos < input.length && input[pos].isWhitespace()) pos++
        }
    }
}

@Composable
fun LatexBlockView(
    expression: String,
    fontSizeSp: Float,
    modifier: Modifier = Modifier,
) {
    val parsed = remember(expression) { LatexEngine.parse(expression) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF0A0A0A), RoundedCornerShape(6.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        MathNodeView(
            node = parsed,
            fontSize = (fontSizeSp + 1f).sp,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
fun InteractiveLatexEditorBox(
    expression: String,
    cursorPos: Int,
    onCursorMove: (Int) -> Unit,
    fontSizeSp: Float,
    modifier: Modifier = Modifier,
) {
    val parsed = remember(expression) { LatexEngine.parseForEditing(expression) }
    val clampedCursor = cursorPos.coerceIn(0, expression.length)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 76.dp)
            .background(Color.Black, RoundedCornerShape(6.dp))
            .border(1.dp, Color.White, RoundedCornerShape(6.dp))
            .pointerInput(expression.length) {
                detectTapGestures { offset ->
                    val target = if (offset.x < size.width * 0.12f) 0 else expression.length
                    onCursorMove(target)
                }
            }
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        MathNodeView(
            node = parsed,
            fontSize = fontSizeSp.sp,
            color = Color.White,
            cursorPos = clampedCursor,
            onCursorMove = onCursorMove,
        )
    }
}

@Composable
private fun MathCursorBar(fontSize: TextUnit) {
    Box(
        modifier = Modifier
            .padding(horizontal = 1.dp)
            .width(2.dp)
            .height((fontSize.value * 1.15f).coerceAtLeast(12f).dp)
            .background(Color.White)
    )
}

@Composable
fun MathNodeView(
    node: MathNode,
    fontSize: TextUnit,
    color: Color,
    cursorPos: Int? = null,
    onCursorMove: ((Int) -> Unit)? = null,
) {
    when (node) {
        is MathNode.Sequence -> {
            if (node.nodes.isEmpty() && cursorPos != null && onCursorMove != null) {
                val activeHere = cursorPos == node.span.start
                Box(
                    modifier = Modifier
                        .defaultMinSize(
                            minWidth = (fontSize.value * 0.95f).coerceAtLeast(16f).dp,
                            minHeight = (fontSize.value * 1.15f).coerceAtLeast(18f).dp,
                        )
                        .background(
                            if (activeHere) Color(0xFF1F1F1F) else Color(0xFF0E0E0E),
                            RoundedCornerShape(3.dp),
                        )
                        .border(
                            width = 1.dp,
                            color = if (activeHere) Color.White else Color(0xFF525252),
                            shape = RoundedCornerShape(3.dp),
                        )
                        .clickable { onCursorMove(node.span.start) }
                        .padding(horizontal = 3.dp, vertical = 1.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (activeHere) {
                        MathCursorBar(fontSize)
                    }
                }
                return
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
            ) {
                val children = node.nodes
                if (children.isEmpty()) return@Row

                if (cursorPos != null && cursorPos in node.span.start..children.first().span.start) {
                    MathCursorBar(fontSize)
                }

                children.forEachIndexed { idx, child ->
                    MathNodeView(
                        node = child,
                        fontSize = fontSize,
                        color = color,
                        cursorPos = cursorPos,
                        onCursorMove = onCursorMove,
                    )
                    if (cursorPos != null) {
                        if (idx < children.lastIndex) {
                            val nextStart = children[idx + 1].span.start
                            if (cursorPos in child.span.end..nextStart) {
                                MathCursorBar(fontSize)
                            }
                        } else if (cursorPos in child.span.end..node.span.end) {
                            MathCursorBar(fontSize)
                        }
                    }
                }
            }
        }
        is MathNode.Text -> {
            val tapMod = if (onCursorMove != null) {
                Modifier.pointerInput(node.span) {
                    detectTapGestures { offset ->
                        val target = if (offset.x < size.width / 2f) node.span.start else node.span.end
                        onCursorMove(target)
                    }
                }
            } else {
                Modifier
            }
            Text(
                text = node.text,
                color = color,
                fontSize = fontSize,
                fontStyle = if (node.italic) FontStyle.Italic else FontStyle.Normal,
                fontWeight = if (node.bold) FontWeight.Bold else FontWeight.Normal,
                modifier = tapMod,
            )
        }
        is MathNode.Frac -> {
            val subSize = (fontSize.value * 0.82f).sp
            Column(
                modifier = Modifier
                    .width(IntrinsicSize.Max)
                    .padding(horizontal = 3.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
                    MathNodeView(
                        node = node.numerator,
                        fontSize = subSize,
                        color = color,
                        cursorPos = cursorPos,
                        onCursorMove = onCursorMove,
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.2.dp)
                        .background(color)
                )
                Box(modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
                    MathNodeView(
                        node = node.denominator,
                        fontSize = subSize,
                        color = color,
                        cursorPos = cursorPos,
                        onCursorMove = onCursorMove,
                    )
                }
            }
        }
        is MathNode.Sqrt -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (node.index != null) {
                    Box(modifier = Modifier.padding(bottom = 8.dp)) {
                        MathNodeView(
                            node = node.index,
                            fontSize = (fontSize.value * 0.65f).sp,
                            color = color,
                            cursorPos = cursorPos,
                            onCursorMove = onCursorMove,
                        )
                    }
                }
                Text(
                    text = "√",
                    color = color,
                    fontSize = (fontSize.value * 1.15f).sp,
                    modifier = if (onCursorMove != null) {
                        Modifier.clickable { onCursorMove(node.radicand.span.start) }
                    } else {
                        Modifier
                    },
                )
                Box(
                    modifier = Modifier
                        .drawBehind {
                            drawLine(
                                color = color,
                                start = Offset(0f, 1.dp.toPx()),
                                end = Offset(size.width, 1.dp.toPx()),
                                strokeWidth = 1.2.dp.toPx(),
                            )
                        }
                        .padding(start = 2.dp, end = 3.dp, top = 3.dp)
                ) {
                    MathNodeView(
                        node = node.radicand,
                        fontSize = fontSize,
                        color = color,
                        cursorPos = cursorPos,
                        onCursorMove = onCursorMove,
                    )
                }
            }
        }
        is MathNode.SupSub -> {
            val scriptSize = (fontSize.value * 0.72f).sp
            Row(verticalAlignment = Alignment.CenterVertically) {
                MathNodeView(
                    node = node.base,
                    fontSize = fontSize,
                    color = color,
                    cursorPos = cursorPos,
                    onCursorMove = onCursorMove,
                )
                if (cursorPos != null && cursorPos == node.base.span.end) {
                    MathCursorBar(fontSize)
                }
                Column(
                    modifier = Modifier.padding(start = 1.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    if (node.sup != null) {
                        Box(modifier = Modifier.padding(bottom = if (node.sub != null) 2.dp else 6.dp)) {
                            MathNodeView(
                                node = node.sup,
                                fontSize = scriptSize,
                                color = color,
                                cursorPos = cursorPos,
                                onCursorMove = onCursorMove,
                            )
                        }
                    }
                    if (node.sub != null) {
                        Box(modifier = Modifier.padding(top = if (node.sup != null) 2.dp else 6.dp)) {
                            MathNodeView(
                                node = node.sub,
                                fontSize = scriptSize,
                                color = color,
                                cursorPos = cursorPos,
                                onCursorMove = onCursorMove,
                            )
                        }
                    }
                }
            }
        }
        is MathNode.BigOp -> {
            val limitSize = (fontSize.value * 0.68f).sp
            Column(
                modifier = Modifier.padding(horizontal = 3.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (node.sup != null) {
                    MathNodeView(
                        node = node.sup,
                        fontSize = limitSize,
                        color = color,
                        cursorPos = cursorPos,
                        onCursorMove = onCursorMove,
                    )
                }
                Text(
                    text = node.symbol,
                    color = color,
                    fontSize = (fontSize.value * 1.35f).sp,
                    modifier = if (onCursorMove != null) {
                        Modifier.pointerInput(node.span) {
                            detectTapGestures { offset ->
                                val target = if (offset.x < size.width / 2f) node.span.start else node.span.end
                                onCursorMove(target)
                            }
                        }
                    } else {
                        Modifier
                    },
                )
                if (node.sub != null) {
                    MathNodeView(
                        node = node.sub,
                        fontSize = limitSize,
                        color = color,
                        cursorPos = cursorPos,
                        onCursorMove = onCursorMove,
                    )
                }
            }
        }
        is MathNode.Matrix -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (node.leftDelim.isNotEmpty()) {
                    Text(
                        text = node.leftDelim,
                        color = color,
                        fontSize = (fontSize.value * (1.2f + node.rows.size * 0.35f)).sp,
                    )
                }
                Column(
                    modifier = Modifier.padding(horizontal = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    node.rows.forEach { row ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            row.forEach { cell ->
                                MathNodeView(
                                    node = cell,
                                    fontSize = fontSize,
                                    color = color,
                                    cursorPos = cursorPos,
                                    onCursorMove = onCursorMove,
                                )
                            }
                        }
                    }
                }
                if (node.rightDelim.isNotEmpty()) {
                    Text(
                        text = node.rightDelim,
                        color = color,
                        fontSize = (fontSize.value * (1.2f + node.rows.size * 0.35f)).sp,
                    )
                }
            }
        }
        is MathNode.Delimited -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (node.left.isNotEmpty()) {
                    Text(text = node.left, color = color, fontSize = (fontSize.value * 1.15f).sp)
                }
                MathNodeView(
                    node = node.inner,
                    fontSize = fontSize,
                    color = color,
                    cursorPos = cursorPos,
                    onCursorMove = onCursorMove,
                )
                if (node.right.isNotEmpty()) {
                    Text(text = node.right, color = color, fontSize = (fontSize.value * 1.15f).sp)
                }
            }
        }
    }
}
