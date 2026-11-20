package com.droidnotes.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droidnotes.markdown.InteractiveLatexEditorBox
import com.droidnotes.markdown.LatexEngine
import com.droidnotes.markdown.MarkdownBlock
import com.droidnotes.markdown.MarkdownParser
import com.droidnotes.markdown.MathNodeView
import com.droidnotes.ui.components.trackFocusBounds
import com.droidnotes.ui.theme.JetBrainsMonoFamily

private enum class MathCategory(val label: String) {
    CALC("123 / Calc"),
    STRUCTURES("Structures"),
    GREEK("Greek"),
    OPERATORS("Operators"),
    SETS_LOGIC("Sets & Logic"),
    FUNCTIONS("Functions"),
}

private data class MathPaletteItem(
    val previewExpr: String,
    val insertText: String,
    val cursorBackOffset: Int = 0,
    val renderAsVisualMath: Boolean = false,
)

/**
 * Calculator-style cursor navigation & atomic deletion for natural math expressions.
 */
object MathCalculatorEngine {
    private val STRUCTURAL_COMMANDS = setOf(
        "frac", "dfrac", "tfrac", "binom", "dbinom", "tbinom", "sqrt",
        "begin", "end", "left", "right", "text", "mathrm", "operatorname",
        "mathbf", "boldsymbol", "mathbb", "mathcal", "vec", "hat", "widehat",
        "bar", "overline", "tilde", "widetilde", "dot", "ddot",
    )

    private val EMPTY_TEMPLATES = listOf(
        "\\frac{}{}", "\\dfrac{}{}", "\\tfrac{}{}", "\\binom{}{}",
        "\\sqrt[n]{}", "\\sqrt{}", "√{}", "^{}", "_{}",
    )

    private val INSIDE_EMPTY_PATTERNS = listOf(
        "\\frac{" to "}{}",
        "\\binom{" to "}{}",
        "\\sqrt[n]{" to "}",
        "\\sqrt{" to "}",
        "√{" to "}",
        "^{" to "}",
        "_{" to "}",
    )

    fun computeCursorStops(expr: String): List<Int> {
        val stops = sortedSetOf<Int>()
        stops.add(0)
        stops.add(expr.length)
        var i = 0
        while (i < expr.length) {
            val ch = expr[i]
            when {
                ch == '\\' -> {
                    val start = i
                    if (expr.startsWith("\\\\", i)) {
                        stops.add(start)
                        i += 2
                        stops.add(i)
                    } else if (i + 1 < expr.length && expr[i + 1].isLetter()) {
                        var j = i + 1
                        while (j < expr.length && expr[j].isLetter()) j++
                        val cmd = expr.substring(i + 1, j)
                        if (cmd == "begin" || cmd == "end") {
                            if (cmd == "begin") stops.add(start)
                            val close = expr.indexOf('}', j)
                            i = if (close != -1) close + 1 else j
                            stops.add(i)
                        } else if (cmd in STRUCTURAL_COMMANDS) {
                            stops.add(start)
                            i = j
                        } else {
                            stops.add(start)
                            i = j
                            stops.add(i)
                        }
                    } else {
                        stops.add(start)
                        i = (i + 2).coerceAtMost(expr.length)
                        stops.add(i)
                    }
                }
                ch == '^' || ch == '_' -> {
                    if (i + 1 < expr.length && expr[i + 1] == '{') {
                        // Step directly inside the script braces
                        i += 2
                        stops.add(i)
                    } else {
                        stops.add(i)
                        i++
                        stops.add(i)
                    }
                }
                ch == '{' || ch == '[' -> {
                    i++
                    stops.add(i)
                }
                ch == '}' || ch == ']' -> {
                    stops.add(i)
                    i++
                    // If immediately followed by '{' (e.g. \frac{a}{b}), skip stopping between '}' and '{'
                    if (!(i < expr.length && expr[i] == '{')) {
                        stops.add(i)
                    }
                }
                ch == '&' -> {
                    stops.add(i)
                    i++
                    stops.add(i)
                }
                Character.isHighSurrogate(ch) && i + 1 < expr.length -> {
                    stops.add(i)
                    i += 2
                    stops.add(i)
                }
                else -> {
                    stops.add(i)
                    i++
                    while (i < expr.length && Character.getType(expr[i]) == Character.NON_SPACING_MARK.toInt()) {
                        i++
                    }
                    stops.add(i)
                }
            }
        }
        return stops.toList()
    }

    fun moveLeft(expr: String, cursor: Int): Int {
        val stops = computeCursorStops(expr)
        return stops.lastOrNull { it < cursor } ?: 0
    }

    fun moveRight(expr: String, cursor: Int): Int {
        val stops = computeCursorStops(expr)
        return stops.firstOrNull { it > cursor } ?: expr.length
    }

    fun smartBackspace(expr: String, cursor: Int): Pair<String, Int> {
        val pos = cursor.coerceIn(0, expr.length)
        if (pos == 0) return expr to 0

        // 1. Cursor inside an empty template slot: e.g., \frac{|}{}, √{|}, ^{|}, _{|}, \binom{|}{}
        for ((before, after) in INSIDE_EMPTY_PATTERNS) {
            if (pos >= before.length &&
                expr.substring(pos - before.length, pos) == before &&
                expr.startsWith(after, pos)
            ) {
                val start = pos - before.length
                val end = pos + after.length
                return (expr.removeRange(start, end)) to start
            }
        }

        // 2. Cursor at start of empty second arg of \frac{a}{|}: step into numerator instead of breaking braces
        if (pos >= 2 && expr.substring(pos - 2, pos) == "}{") {
            return expr to (pos - 2)
        }

        // 3. Cursor right after an empty template: delete the whole template cleanly
        for (tpl in EMPTY_TEMPLATES) {
            if (pos >= tpl.length && expr.substring(pos - tpl.length, pos) == tpl) {
                val start = pos - tpl.length
                return expr.removeRange(start, pos) to start
            }
        }

        // 4. Cursor right after closing '}' or ']' of a filled structure: step inside before '}'
        if (expr[pos - 1] == '}' || expr[pos - 1] == ']') {
            return expr to (pos - 1)
        }

        // 5. Cursor right after '{' of a non-empty slot: step before the command/brace instead of unbalancing
        if (expr[pos - 1] == '{') {
            return expr to moveLeft(expr, pos)
        }

        // 6. Cursor right after a \command: delete the entire \command atomically
        var scan = pos - 1
        while (scan >= 0 && expr[scan].isLetter()) scan--
        if (scan >= 0 && expr[scan] == '\\' && scan < pos - 1) {
            return expr.removeRange(scan, pos) to scan
        }

        // 7. Combining marks or surrogate pairs: delete whole grapheme
        var delStart = pos - 1
        while (delStart > 0 && Character.getType(expr[delStart]) == Character.NON_SPACING_MARK.toInt()) {
            delStart--
        }
        if (delStart > 0 && Character.isLowSurrogate(expr[delStart]) && Character.isHighSurrogate(expr[delStart - 1])) {
            delStart--
        }

        return expr.removeRange(delStart, pos) to delStart
    }
}

private val MATH_PALETTE: Map<MathCategory, List<MathPaletteItem>> = mapOf(
    MathCategory.CALC to listOf(
        MathPaletteItem("7", "7"),
        MathPaletteItem("8", "8"),
        MathPaletteItem("9", "9"),
        MathPaletteItem("+", " + "),
        MathPaletteItem("−", " - "),
        MathPaletteItem("\\frac{a}{b}", "\\frac{}{}", cursorBackOffset = 3, renderAsVisualMath = true),
        MathPaletteItem("4", "4"),
        MathPaletteItem("5", "5"),
        MathPaletteItem("6", "6"),
        MathPaletteItem("×", " × "),
        MathPaletteItem("÷", " ÷ "),
        MathPaletteItem("√{x}", "√{}", cursorBackOffset = 1, renderAsVisualMath = true),
        MathPaletteItem("1", "1"),
        MathPaletteItem("2", "2"),
        MathPaletteItem("3", "3"),
        MathPaletteItem("=", " = "),
        MathPaletteItem("(", "("),
        MathPaletteItem(")", ")"),
        MathPaletteItem("0", "0"),
        MathPaletteItem(".", "."),
        MathPaletteItem(",", ", "),
        MathPaletteItem("x^{n}", "^{}", cursorBackOffset = 1, renderAsVisualMath = true),
        MathPaletteItem("x_{i}", "_{}", cursorBackOffset = 1, renderAsVisualMath = true),
        MathPaletteItem("x", "x"),
        MathPaletteItem("y", "y"),
        MathPaletteItem("z", "z"),
        MathPaletteItem("a", "a"),
        MathPaletteItem("b", "b"),
        MathPaletteItem("n", "n"),
        MathPaletteItem("π", "π"),
        MathPaletteItem("β", "β"),
    ),
    MathCategory.STRUCTURES to listOf(
        MathPaletteItem("\\frac{a}{b}", "\\frac{}{}", cursorBackOffset = 3, renderAsVisualMath = true),
        MathPaletteItem("√{x}", "√{}", cursorBackOffset = 1, renderAsVisualMath = true),
        MathPaletteItem("\\sqrt[n]{x}", "\\sqrt[n]{}", cursorBackOffset = 1, renderAsVisualMath = true),
        MathPaletteItem("x^{n}", "^{}", cursorBackOffset = 1, renderAsVisualMath = true),
        MathPaletteItem("x_{i}", "_{}", cursorBackOffset = 1, renderAsVisualMath = true),
        MathPaletteItem("x_{i}^{n}", "_{}^{}", cursorBackOffset = 4, renderAsVisualMath = true),
        MathPaletteItem("∑_{i=1}^{n}", "∑_{i=1}^{n} ", renderAsVisualMath = true),
        MathPaletteItem("∏_{i=1}^{n}", "∏_{i=1}^{n} ", renderAsVisualMath = true),
        MathPaletteItem("∫_{a}^{b}", "∫_{a}^{b} ", renderAsVisualMath = true),
        MathPaletteItem("∬", "∬ ", renderAsVisualMath = true),
        MathPaletteItem("∭", "∭ ", renderAsVisualMath = true),
        MathPaletteItem("∮", "∮ ", renderAsVisualMath = true),
        MathPaletteItem("lim_{x→∞}", "lim_{x → ∞} ", renderAsVisualMath = true),
        MathPaletteItem("\\binom{n}{k}", "\\binom{}{}", cursorBackOffset = 3, renderAsVisualMath = true),
        MathPaletteItem("\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}", "\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}", renderAsVisualMath = true),
        MathPaletteItem("\\begin{bmatrix} a & b \\\\ c & d \\end{bmatrix}", "\\begin{bmatrix} a & b \\\\ c & d \\end{bmatrix}", renderAsVisualMath = true),
        MathPaletteItem("\\begin{cases} a & x>0 \\\\ b & x≤0 \\end{cases}", "\\begin{cases} a & x > 0 \\\\ b & x ≤ 0 \\end{cases}", renderAsVisualMath = true),
        MathPaletteItem("v\u20D7", "v\u20D7", renderAsVisualMath = true),
        MathPaletteItem("x\u0302", "x\u0302", renderAsVisualMath = true),
        MathPaletteItem("x\u0304", "x\u0304", renderAsVisualMath = true),
        MathPaletteItem("x\u0303", "x\u0303", renderAsVisualMath = true),
        MathPaletteItem("x\u0307", "x\u0307", renderAsVisualMath = true),
    ),
    MathCategory.GREEK to listOf(
        "α", "β", "γ", "δ", "ε", "ζ", "η", "θ", "ι", "κ", "λ", "μ",
        "ν", "ξ", "π", "ρ", "σ", "ς", "τ", "υ", "φ", "ϕ", "χ", "ψ", "ω",
        "Γ", "Δ", "Θ", "Λ", "Ξ", "Π", "Σ", "Υ", "Φ", "Ψ", "Ω",
    ).map { sym -> MathPaletteItem(previewExpr = sym, insertText = sym) },
    MathCategory.OPERATORS to listOf(
        "±", "∓", "×", "÷", "·", "∗", "∘", "⊕", "⊗", "⊙",
        "≤", "≥", "≠", "≈", "≡", "∼", "≃", "≅", "∝", "≪", "≫",
        "≜", "≔", "∞", "∂", "∇", "′", "°", "∠", "⊥", "∥", "△",
        "ℏ", "ℓ", "†", "‡",
    ).map { sym -> MathPaletteItem(previewExpr = sym, insertText = sym) },
    MathCategory.SETS_LOGIC to listOf(
        "∈", "∉", "∋", "⊂", "⊃", "⊆", "⊇", "⊊", "⊋",
        "∪", "∩", "⋃", "⋂", "∅", "∖",
        "∀", "∃", "∄", "¬", "∧", "∨", "⊢", "⊨", "⊤", "⊥",
        "∴", "∵", "→", "←", "↔", "⇒", "⇐", "⇔", "↦", "⟹", "⟺",
        "ℝ", "ℕ", "ℤ", "ℚ", "ℂ", "ℙ", "𝔼",
    ).map { sym -> MathPaletteItem(previewExpr = sym, insertText = sym) },
    MathCategory.FUNCTIONS to listOf(
        MathPaletteItem("sin", "sin()", cursorBackOffset = 1),
        MathPaletteItem("cos", "cos()", cursorBackOffset = 1),
        MathPaletteItem("tan", "tan()", cursorBackOffset = 1),
        MathPaletteItem("cot", "cot()", cursorBackOffset = 1),
        MathPaletteItem("sec", "sec()", cursorBackOffset = 1),
        MathPaletteItem("csc", "csc()", cursorBackOffset = 1),
        MathPaletteItem("arcsin", "arcsin()", cursorBackOffset = 1),
        MathPaletteItem("arccos", "arccos()", cursorBackOffset = 1),
        MathPaletteItem("arctan", "arctan()", cursorBackOffset = 1),
        MathPaletteItem("sinh", "sinh()", cursorBackOffset = 1),
        MathPaletteItem("cosh", "cosh()", cursorBackOffset = 1),
        MathPaletteItem("tanh", "tanh()", cursorBackOffset = 1),
        MathPaletteItem("ln", "ln()", cursorBackOffset = 1),
        MathPaletteItem("log", "log()", cursorBackOffset = 1),
        MathPaletteItem("exp", "exp()", cursorBackOffset = 1),
        MathPaletteItem("det", "det()", cursorBackOffset = 1),
        MathPaletteItem("gcd", "gcd()", cursorBackOffset = 1),
        MathPaletteItem("mod", " mod "),
        MathPaletteItem("max", "max_{}", cursorBackOffset = 1),
        MathPaletteItem("min", "min_{}", cursorBackOffset = 1),
        MathPaletteItem("⟨x⟩", "⟨⟩", cursorBackOffset = 1),
        MathPaletteItem("⌊x⌋", "⌊⌋", cursorBackOffset = 1),
        MathPaletteItem("⌈x⌉", "⌈⌉", cursorBackOffset = 1),
        MathPaletteItem("|x|", "||", cursorBackOffset = 1),
        MathPaletteItem("‖x‖", "‖‖", cursorBackOffset = 1),
        MathPaletteItem("…", "…"),
    ),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LatexScopeEditorContent(
    block: MarkdownBlock.LatexBlock,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit,
) {
    var exprField by remember(block.lineIndex, block.endLineIndex) {
        mutableStateOf(
            TextFieldValue(
                text = block.expression,
                selection = TextRange(block.expression.length),
            )
        )
    }
    var selectedCategory by remember { mutableStateOf(MathCategory.CALC) }
    var showRawKeyboard by remember { mutableStateOf(false) }

    fun setCursor(pos: Int) {
        val safe = pos.coerceIn(0, exprField.text.length)
        exprField = exprField.copy(selection = TextRange(safe))
    }

    fun insertMathItem(item: MathPaletteItem) {
        val text = exprField.text
        val start = exprField.selection.min.coerceIn(0, text.length)
        val end = exprField.selection.max.coerceIn(start, text.length)
        val updated = text.replaceRange(start, end, item.insertText)
        val cursor = (start + item.insertText.length - item.cursorBackOffset).coerceIn(0, updated.length)
        exprField = TextFieldValue(text = updated, selection = TextRange(cursor))
    }

    fun backspaceAtCursor() {
        val text = exprField.text
        val start = exprField.selection.min.coerceIn(0, text.length)
        val end = exprField.selection.max.coerceIn(start, text.length)
        if (end > start) {
            val updated = text.removeRange(start, end)
            exprField = TextFieldValue(updated, TextRange(start))
        } else {
            val (updated, newCursor) = MathCalculatorEngine.smartBackspace(text, start)
            exprField = TextFieldValue(updated, TextRange(newCursor))
        }
    }

    Column(modifier = Modifier.padding(16.dp)) {
        ScopeHeader(
            title = "Edit Math Block",
            subtitle = "Tap equation or use ← → to move cursor · Lines ${block.lineIndex + 1}–${block.endLineIndex + 1}",
            onDismiss = onDismiss,
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Interactive Calculator-Style Visual Math Input Display (always visible at top)
        InteractiveLatexEditorBox(
            expression = exprField.text,
            cursorPos = exprField.selection.end,
            onCursorMove = ::setCursor,
            fontSizeSp = 19f,
            modifier = Modifier.testTag("math_interactive_display"),
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Calculator Navigation & Edit Bar: ← | → | ⌫ | AC | ⌨
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CalcActionKey(
                label = "←",
                onClick = { setCursor(MathCalculatorEngine.moveLeft(exprField.text, exprField.selection.end)) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("math_cursor_left"),
            )
            CalcActionKey(
                label = "→",
                onClick = { setCursor(MathCalculatorEngine.moveRight(exprField.text, exprField.selection.end)) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("math_cursor_right"),
            )
            CalcActionKey(
                label = "⌫",
                onClick = ::backspaceAtCursor,
                modifier = Modifier
                    .weight(1f)
                    .testTag("math_backspace"),
            )
            CalcActionKey(
                label = "AC",
                onClick = { exprField = TextFieldValue("", TextRange(0)) },
                modifier = Modifier
                    .weight(0.9f)
                    .testTag("math_clear_all"),
            )
            CalcActionKey(
                label = "⌨",
                highlighted = showRawKeyboard,
                onClick = { showRawKeyboard = !showRawKeyboard },
                modifier = Modifier
                    .weight(0.85f)
                    .testTag("math_toggle_keyboard"),
            )
        }

        if (showRawKeyboard) {
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black, RoundedCornerShape(6.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                if (exprField.text.isEmpty()) {
                    Text(
                        text = "Type with device keyboard…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = exprField,
                    onValueChange = { exprField = it },
                    cursorBrush = SolidColor(Color.White),
                    textStyle = TextStyle(
                        color = Color.White,
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 13.sp,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .trackFocusBounds()
                        .testTag("scope_latex_input"),
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Category tabs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MathCategory.entries.forEach { cat ->
                val isSelected = selectedCategory == cat
                Surface(
                    onClick = { selectedCategory = cat },
                    color = if (isSelected) Color.White else Color(0xFF141414),
                    contentColor = if (isSelected) Color.Black else Color.White,
                    border = BorderStroke(
                        1.dp,
                        if (isSelected) Color.White else MaterialTheme.colorScheme.outline,
                    ),
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Text(
                        text = cat.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) Color.Black else Color.White,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Scrollable natural math objects / calculator keypad
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
        ) {
            val items = MATH_PALETTE[selectedCategory].orEmpty()
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black, RoundedCornerShape(6.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items.forEach { item ->
                    Surface(
                        onClick = { insertMathItem(item) },
                        color = Color(0xFF141414),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .heightIn(min = 38.dp)
                                .padding(horizontal = 11.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (item.renderAsVisualMath) {
                                val node = remember(item.previewExpr) { LatexEngine.parse(item.previewExpr) }
                                MathNodeView(node = node, fontSize = 13.sp, color = Color.White)
                            } else {
                                Text(
                                    text = item.previewExpr,
                                    color = Color.White,
                                    fontSize = 15.sp,
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        ScopeFooter(
            confirmLabel = "Apply Math",
            onDismiss = onDismiss,
            onConfirm = { onApply(MarkdownParser.serializeLatexBlock(exprField.text)) },
            confirmTag = "scope_apply_latex",
        )
    }
}

@Composable
private fun CalcActionKey(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
) {
    Surface(
        onClick = onClick,
        color = if (highlighted) Color.White else Color(0xFF141414),
        contentColor = if (highlighted) Color.Black else Color.White,
        border = BorderStroke(1.dp, if (highlighted) Color.White else MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(4.dp),
        modifier = modifier.minimumInteractiveComponentSize(),
    ) {
        Box(
            modifier = Modifier.padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (highlighted) Color.Black else Color.White,
            )
        }
    }
}
