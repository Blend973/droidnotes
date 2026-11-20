package com.droidnotes.markdown

object MarkdownParser {
    private const val MAX_CACHE_ENTRIES = 24
    private val cache = object : LinkedHashMap<Int, Pair<String, MarkdownDocument>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Pair<String, MarkdownDocument>>?): Boolean {
            return size > MAX_CACHE_ENTRIES
        }
    }

    fun parse(source: String): MarkdownDocument {
        val key = source.hashCode()
        synchronized(cache) {
            val hit = cache[key]
            if (hit != null && hit.first == source) {
                return hit.second
            }
        }
        val parsed = parseUncached(source)
        synchronized(cache) {
            cache[key] = source to parsed
        }
        return parsed
    }

    fun toggleTaskCheckboxInSource(source: String, targetLineIndex: Int): String {
        val lines = source.lines().toMutableList()
        if (targetLineIndex !in lines.indices) return source
        val line = lines[targetLineIndex]
        lines[targetLineIndex] = when {
            TASK_UNCHECKED_REGEX.matches(line) -> TASK_UNCHECKED_REGEX.replace(line, "$1[x]$2")
            TASK_CHECKED_REGEX.matches(line) -> TASK_CHECKED_REGEX.replace(line, "$1[ ]$2")
            else -> line
        }
        return lines.joinToString("\n")
    }

    fun replaceLineRange(
        source: String,
        startLineIndex: Int,
        endLineIndex: Int,
        newBlockMarkdown: String,
    ): String {
        val lines = source.lines()
        if (startLineIndex !in lines.indices || endLineIndex !in startLineIndex until lines.size) {
            return source
        }
        val quotePrefixMatch = QUOTE_PREFIX_REGEX.find(lines[startLineIndex])
        val quotePrefix = quotePrefixMatch?.value.orEmpty()
        val replacementLines = newBlockMarkdown.lines().map { line ->
            if (quotePrefix.isNotEmpty()) {
                if (line.isBlank()) quotePrefix.trimEnd() else "$quotePrefix$line"
            } else {
                line
            }
        }
        val combined = ArrayList<String>(lines.size - (endLineIndex - startLineIndex + 1) + replacementLines.size)
        for (idx in 0 until startLineIndex) combined.add(lines[idx])
        combined.addAll(replacementLines)
        for (idx in (endLineIndex + 1) until lines.size) combined.add(lines[idx])
        return combined.joinToString("\n")
    }

    fun serializeTable(
        headers: List<String>,
        alignments: List<TableAlign>,
        rows: List<List<String>>,
    ): String {
        val colCount = maxOf(
            headers.size,
            alignments.size,
            rows.maxOfOrNull { it.size } ?: 0,
        ).coerceAtLeast(1)

        fun cleanCell(raw: String): String = raw.replace('\n', ' ').replace("|", "\\|").trim()

        val sb = StringBuilder()
        sb.append("| ")
        for (c in 0 until colCount) {
            if (c > 0) sb.append(" | ")
            sb.append(cleanCell(headers.getOrNull(c).orEmpty()))
        }
        sb.append(" |\n| ")
        for (c in 0 until colCount) {
            if (c > 0) sb.append(" | ")
            sb.append(
                when (alignments.getOrNull(c) ?: TableAlign.LEFT) {
                    TableAlign.LEFT -> ":---"
                    TableAlign.CENTER -> ":---:"
                    TableAlign.RIGHT -> "---:"
                }
            )
        }
        sb.append(" |")
        rows.forEach { row ->
            sb.append("\n| ")
            for (c in 0 until colCount) {
                if (c > 0) sb.append(" | ")
                sb.append(cleanCell(row.getOrNull(c).orEmpty()))
            }
            sb.append(" |")
        }
        return sb.toString()
    }

    fun serializeChart(spec: ChartSpec): String {
        val sb = StringBuilder()
        sb.appendLine("```chart")
        sb.appendLine("type: ${spec.type.name.lowercase()}")
        if (spec.title.isNotBlank()) {
            sb.appendLine("title: ${spec.title.trim()}")
        }
        sb.appendLine("data:")
        spec.points.forEach { pt ->
            val valueStr = if (pt.value % 1f == 0f) pt.value.toInt().toString() else pt.value.toString()
            val labelStr = pt.label.trim().ifBlank { "Item" }.replace(",", " ")
            val hex = ChartEngine.normalizeColorHex(pt.colorHex)
            if (hex != null) {
                sb.appendLine("$labelStr, $valueStr, $hex")
            } else {
                sb.appendLine("$labelStr, $valueStr")
            }
        }
        sb.append("```")
        return sb.toString()
    }

    fun serializeDiagram(
        edges: List<DiagramEdge>,
        nodePositions: Map<String, Pair<Float, Float>> = emptyMap(),
        standaloneNodes: List<String> = emptyList(),
        edgeCurveOffsets: Map<Int, Pair<Float, Float>> = emptyMap(),
    ): String {
        val sb = StringBuilder()
        sb.appendLine("```diagram")
        val validEdges = edges.filter { it.from.trim().isNotEmpty() && it.to.trim().isNotEmpty() }
        val connectedNodes = validEdges.flatMap { listOf(it.from.trim(), it.to.trim()) }.toSet()
        validEdges.forEach { edge ->
            val from = edge.from.trim()
            val to = edge.to.trim()
            val lbl = edge.label.trim()
            if (lbl.isNotEmpty()) {
                sb.appendLine("$from -> $to : $lbl")
            } else {
                sb.appendLine("$from -> $to")
            }
        }
        standaloneNodes.map { it.trim() }.filter { it.isNotEmpty() && it !in connectedNodes }.distinct().forEach { node ->
            sb.appendLine("@node $node")
        }
        nodePositions.forEach { (name, pos) ->
            val clean = name.trim()
            if (clean.isNotEmpty()) {
                val xStr = String.format(java.util.Locale.US, "%.3f", pos.first.coerceIn(0.06f, 0.94f))
                val yStr = String.format(java.util.Locale.US, "%.3f", pos.second.coerceIn(0.08f, 0.92f))
                sb.appendLine("@pos $clean = $xStr, $yStr")
            }
        }
        edgeCurveOffsets.forEach { (idx, off) ->
            if (idx in validEdges.indices && (kotlin.math.abs(off.first) > 0.005f || kotlin.math.abs(off.second) > 0.005f)) {
                val dxStr = String.format(java.util.Locale.US, "%.3f", off.first.coerceIn(-0.45f, 0.45f))
                val dyStr = String.format(java.util.Locale.US, "%.3f", off.second.coerceIn(-0.45f, 0.45f))
                sb.appendLine("@edge $idx = $dxStr, $dyStr")
            }
        }
        sb.append("```")
        return sb.toString()
    }

    fun serializeLatexBlock(expression: String): String {
        val trimmed = expression.trim()
        return "$$\n$trimmed\n$$"
    }

    private fun parseUncached(source: String): MarkdownDocument {
        val lines = source.lines()
        val citations = linkedMapOf<String, List<MarkdownInline>>()
        val blocks = parseBlockLines(lines, 0, citations)
        val wordCount = countWordsFast(source)
        val lineCount = if (source.isEmpty()) 0 else lines.size
        return MarkdownDocument(
            blocks = blocks,
            citations = citations,
            totalWords = wordCount,
            totalLines = lineCount,
        )
    }

    private fun countWordsFast(source: String): Int {
        var count = 0
        var inWord = false
        for (i in 0 until source.length) {
            if (source[i].isWhitespace()) {
                inWord = false
            } else if (!inWord) {
                inWord = true
                count++
            }
        }
        return count
    }

    fun computeSyntaxSpans(raw: String): List<SyntaxSpan> {
        if (raw.isEmpty()) return emptyList()
        val spans = ArrayList<SyntaxSpan>(64)
        SYNTAX_HEADING_REGEX.findAll(raw).forEach { m ->
            spans.add(SyntaxSpan(m.range.first, m.range.last + 1, SyntaxTokenType.HEADING))
        }
        SYNTAX_CODE_REGEX.findAll(raw).forEach { m ->
            spans.add(SyntaxSpan(m.range.first, m.range.last + 1, SyntaxTokenType.CODE))
        }
        SYNTAX_LATEX_REGEX.findAll(raw).forEach { m ->
            spans.add(SyntaxSpan(m.range.first, m.range.last + 1, SyntaxTokenType.LATEX))
        }
        SYNTAX_LINK_REGEX.findAll(raw).forEach { m ->
            spans.add(SyntaxSpan(m.range.first, m.range.last + 1, SyntaxTokenType.LINK))
        }
        return spans
    }

    fun findMatchingBlockIndices(document: MarkdownDocument, query: String): List<Int> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val indices = mutableListOf<Int>()
        document.blocks.forEachIndexed { idx, block ->
            if (blockTextContains(block, q)) indices.add(idx)
        }
        return indices
    }

    private fun blockTextContains(block: MarkdownBlock, query: String): Boolean = when (block) {
        is MarkdownBlock.Heading -> inlinesText(block.inlines).contains(query, ignoreCase = true)
        is MarkdownBlock.Paragraph -> inlinesText(block.inlines).contains(query, ignoreCase = true)
        is MarkdownBlock.CodeBlock -> block.code.contains(query, ignoreCase = true)
        is MarkdownBlock.LatexBlock -> block.expression.contains(query, ignoreCase = true)
        is MarkdownBlock.ChartBlock -> block.raw.contains(query, ignoreCase = true)
        is MarkdownBlock.DiagramBlock -> block.raw.contains(query, ignoreCase = true)
        is MarkdownBlock.TableBlock -> block.headers.any { inlinesText(it).contains(query, ignoreCase = true) } ||
            block.rows.any { row -> row.any { inlinesText(it).contains(query, ignoreCase = true) } }
        is MarkdownBlock.Blockquote -> block.blocks.any { blockTextContains(it, query) }
        is MarkdownBlock.BulletList -> block.items.any { inlinesText(it).contains(query, ignoreCase = true) }
        is MarkdownBlock.OrderedList -> block.items.any { inlinesText(it).contains(query, ignoreCase = true) }
        is MarkdownBlock.TaskList -> block.items.any { inlinesText(it.inlines).contains(query, ignoreCase = true) }
        is MarkdownBlock.ImageBlock -> block.alt.contains(query, ignoreCase = true)
        is MarkdownBlock.CitationDefinition -> inlinesText(block.inlines).contains(query, ignoreCase = true)
        is MarkdownBlock.HorizontalRule -> false
    }

    private fun inlinesText(inlines: List<MarkdownInline>): String = buildString {
        inlines.forEach { inline ->
            when (inline) {
                is MarkdownInline.PlainText -> append(inline.text)
                is MarkdownInline.Bold -> append(inlinesText(inline.children))
                is MarkdownInline.Italic -> append(inlinesText(inline.children))
                is MarkdownInline.BoldItalic -> append(inlinesText(inline.children))
                is MarkdownInline.Strikethrough -> append(inlinesText(inline.children))
                is MarkdownInline.Highlight -> append(inlinesText(inline.children))
                is MarkdownInline.InlineCode -> append(inline.code)
                is MarkdownInline.InlineLatex -> append(inline.expression)
                is MarkdownInline.Link -> append(inline.label)
                is MarkdownInline.WikiLink -> append(inline.display)
                is MarkdownInline.CitationRef -> append("[${inline.id}]")
                is MarkdownInline.TagToken -> append(inline.tag)
                is MarkdownInline.InlineImage -> append(inline.alt)
            }
        }
    }

    private fun parseBlockLines(
        lines: List<String>,
        baseLineOffset: Int,
        citations: MutableMap<String, List<MarkdownInline>>,
    ): List<MarkdownBlock> {
        val blocks = mutableListOf<MarkdownBlock>()
        var i = 0

        while (i < lines.size) {
            val rawLine = lines[i]
            val trimmed = rawLine.trim()
            val absLine = baseLineOffset + i

            if (trimmed.isEmpty()) {
                i++
                continue
            }

            // Fenced code / chart / diagram block
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                val fence = trimmed.take(3)
                val lang = trimmed.drop(3).trim().lowercase()
                val codeLines = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith(fence)) {
                    codeLines.add(lines[i])
                    i++
                }
                if (i < lines.size) i++
                val endAbsLine = baseLineOffset + (i - 1).coerceAtLeast(0)
                val body = codeLines.joinToString("\n")
                when (lang) {
                    "chart", "graph" -> blocks.add(
                        MarkdownBlock.ChartBlock(
                            spec = ChartEngine.parseChart(body),
                            raw = body,
                            lineIndex = absLine,
                            endLineIndex = endAbsLine,
                        )
                    )
                    "diagram", "flow" -> {
                        val dSpec = ChartEngine.parseDiagramSpec(body)
                        blocks.add(
                            MarkdownBlock.DiagramBlock(
                                edges = dSpec.edges,
                                raw = body,
                                lineIndex = absLine,
                                endLineIndex = endAbsLine,
                                nodePositions = dSpec.nodePositions,
                                standaloneNodes = dSpec.standaloneNodes,
                                edgeCurveOffsets = dSpec.edgeCurveOffsets,
                            )
                        )
                    }
                    else -> blocks.add(MarkdownBlock.CodeBlock(lang, body, absLine))
                }
                continue
            }

            // Block LaTeX $$ ... $$
            if (trimmed.startsWith("$$")) {
                if (trimmed.length >= 4 && trimmed.endsWith("$$")) {
                    val expr = trimmed.removePrefix("$$").removeSuffix("$$").trim()
                    blocks.add(MarkdownBlock.LatexBlock(expr, absLine, absLine))
                    i++
                } else {
                    val mathLines = mutableListOf<String>()
                    val firstTail = trimmed.removePrefix("$$").trim()
                    if (firstTail.isNotEmpty()) mathLines.add(firstTail)
                    i++
                    while (i < lines.size && !lines[i].trim().endsWith("$$")) {
                        mathLines.add(lines[i])
                        i++
                    }
                    if (i < lines.size) {
                        val endTail = lines[i].trim().removeSuffix("$$").trim()
                        if (endTail.isNotEmpty()) mathLines.add(endTail)
                        i++
                    }
                    val endAbsLine = baseLineOffset + (i - 1).coerceAtLeast(0)
                    blocks.add(MarkdownBlock.LatexBlock(mathLines.joinToString("\n"), absLine, endAbsLine))
                }
                continue
            }

            // Horizontal rule
            if (HR_REGEX.matches(trimmed)) {
                blocks.add(MarkdownBlock.HorizontalRule(absLine))
                i++
                continue
            }

            // Heading
            val headingMatch = HEADING_REGEX.matchEntire(trimmed)
            if (headingMatch != null) {
                val level = headingMatch.groupValues[1].length
                val text = headingMatch.groupValues[2].trim()
                blocks.add(MarkdownBlock.Heading(level, parseInlines(text), absLine))
                i++
                continue
            }

            // Citation definition [^id]: text
            val citationMatch = CITATION_DEF_REGEX.matchEntire(trimmed)
            if (citationMatch != null) {
                val id = citationMatch.groupValues[1].trim()
                val text = citationMatch.groupValues[2].trim()
                val inlines = parseInlines(text)
                citations[id] = inlines
                blocks.add(MarkdownBlock.CitationDefinition(id, inlines, absLine))
                i++
                continue
            }

            // Standalone image line ![alt](uri)
            val imageMatch = STANDALONE_IMAGE_REGEX.matchEntire(trimmed)
            if (imageMatch != null) {
                blocks.add(
                    MarkdownBlock.ImageBlock(
                        alt = imageMatch.groupValues[1],
                        uri = imageMatch.groupValues[2].trim(),
                        lineIndex = absLine,
                    )
                )
                i++
                continue
            }

            // Blockquote
            if (trimmed.startsWith(">")) {
                val quoteLines = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    quoteLines.add(lines[i].trim().removePrefix(">").trimStart())
                    i++
                }
                val inner = parseBlockLines(quoteLines, absLine, citations)
                blocks.add(MarkdownBlock.Blockquote(inner, absLine))
                continue
            }

            // Markdown table
            if (i + 1 < lines.size && lines[i].contains("|") && TABLE_SEPARATOR_REGEX.matches(lines[i + 1].trim())) {
                val rawHeaderCells = splitTableRow(lines[i])
                val headerCells = rawHeaderCells.map { parseInlines(it) }
                val alignments = splitTableRow(lines[i + 1]).map { parseAlignment(it) }
                val rawRows = mutableListOf<List<String>>()
                val rows = mutableListOf<List<List<MarkdownInline>>>()
                i += 2
                while (i < lines.size && lines[i].contains("|") && lines[i].trim().isNotEmpty()) {
                    val rawRow = splitTableRow(lines[i])
                    rawRows.add(rawRow)
                    rows.add(rawRow.map { parseInlines(it) })
                    i++
                }
                val endAbsLine = baseLineOffset + (i - 1).coerceAtLeast(0)
                blocks.add(
                    MarkdownBlock.TableBlock(
                        headers = headerCells,
                        alignments = alignments,
                        rows = rows,
                        lineIndex = absLine,
                        endLineIndex = endAbsLine,
                        rawHeaders = rawHeaderCells,
                        rawRows = rawRows,
                    )
                )
                continue
            }

            // Task list
            if (TASK_ITEM_REGEX.matches(rawLine)) {
                val items = mutableListOf<TaskListItem>()
                while (i < lines.size) {
                    val m = TASK_ITEM_REGEX.matchEntire(lines[i]) ?: break
                    val checked = m.groupValues[1].equals("x", ignoreCase = true)
                    val text = m.groupValues.getOrElse(2) { "" }.trim()
                    items.add(TaskListItem(checked, parseInlines(text), baseLineOffset + i))
                    i++
                }
                blocks.add(MarkdownBlock.TaskList(items, absLine))
                continue
            }

            // Bullet list
            if (BULLET_ITEM_REGEX.matches(rawLine)) {
                val items = mutableListOf<List<MarkdownInline>>()
                while (i < lines.size && !TASK_ITEM_REGEX.matches(lines[i])) {
                    val m = BULLET_ITEM_REGEX.matchEntire(lines[i]) ?: break
                    items.add(parseInlines(m.groupValues[1].trim()))
                    i++
                }
                blocks.add(MarkdownBlock.BulletList(items, absLine))
                continue
            }

            // Ordered list
            val orderedFirst = ORDERED_ITEM_REGEX.matchEntire(rawLine)
            if (orderedFirst != null) {
                val startNum = orderedFirst.groupValues[1].toIntOrNull() ?: 1
                val items = mutableListOf<List<MarkdownInline>>()
                while (i < lines.size) {
                    val m = ORDERED_ITEM_REGEX.matchEntire(lines[i]) ?: break
                    items.add(parseInlines(m.groupValues[2].trim()))
                    i++
                }
                blocks.add(MarkdownBlock.OrderedList(items, startNum, absLine))
                continue
            }

            // Paragraph (merge consecutive plain text lines)
            val paraLines = mutableListOf<String>()
            while (i < lines.size) {
                val cur = lines[i]
                val curTrim = cur.trim()
                if (curTrim.isEmpty() ||
                    curTrim.startsWith("#") ||
                    curTrim.startsWith("```") ||
                    curTrim.startsWith("~~~") ||
                    curTrim.startsWith("$$") ||
                    curTrim.startsWith(">") ||
                    HR_REGEX.matches(curTrim) ||
                    CITATION_DEF_REGEX.matches(curTrim) ||
                    STANDALONE_IMAGE_REGEX.matches(curTrim) ||
                    TASK_ITEM_REGEX.matches(cur) ||
                    BULLET_ITEM_REGEX.matches(cur) ||
                    ORDERED_ITEM_REGEX.matches(cur) ||
                    (i + 1 < lines.size && cur.contains("|") && TABLE_SEPARATOR_REGEX.matches(lines[i + 1].trim()))
                ) {
                    break
                }
                paraLines.add(curTrim)
                i++
            }
            if (paraLines.isNotEmpty()) {
                blocks.add(MarkdownBlock.Paragraph(parseInlines(paraLines.joinToString(" ")), absLine))
            } else {
                i++
            }
        }
        return blocks
    }

    fun parseInlines(text: String): List<MarkdownInline> {
        val result = mutableListOf<MarkdownInline>()
        val buffer = StringBuilder()
        var i = 0

        fun flushPlain() {
            if (buffer.isNotEmpty()) {
                result.add(MarkdownInline.PlainText(buffer.toString()))
                buffer.clear()
            }
        }

        while (i < text.length) {
            val ch = text[i]

            // Escaped character
            if (ch == '\\' && i + 1 < text.length) {
                buffer.append(text[i + 1])
                i += 2
                continue
            }

            // Inline image ![alt](url)
            if (ch == '!' && i + 1 < text.length && text[i + 1] == '[') {
                val closeBracket = text.indexOf(']', i + 2)
                if (closeBracket != -1 && closeBracket + 1 < text.length && text[closeBracket + 1] == '(') {
                    val closeParen = text.indexOf(')', closeBracket + 2)
                    if (closeParen != -1) {
                        flushPlain()
                        val alt = text.substring(i + 2, closeBracket)
                        val url = text.substring(closeBracket + 2, closeParen)
                        result.add(MarkdownInline.InlineImage(alt, url))
                        i = closeParen + 1
                        continue
                    }
                }
            }

            // Wikilink [[target]] or [[target|display]]
            if (text.startsWith("[[", i)) {
                val close = text.indexOf("]]", i + 2)
                if (close != -1) {
                    flushPlain()
                    val inner = text.substring(i + 2, close)
                    val parts = inner.split("|", limit = 2)
                    val target = parts[0].trim()
                    val display = if (parts.size == 2) parts[1].trim() else target
                    result.add(MarkdownInline.WikiLink(target, display))
                    i = close + 2
                    continue
                }
            }

            // Citation ref [^id]
            if (text.startsWith("[^", i)) {
                val close = text.indexOf(']', i + 2)
                if (close != -1) {
                    val id = text.substring(i + 2, close).trim()
                    if (id.isNotEmpty() && !id.contains(' ')) {
                        flushPlain()
                        result.add(MarkdownInline.CitationRef(id))
                        i = close + 1
                        continue
                    }
                }
            }

            // Standard link [label](url)
            if (ch == '[') {
                val closeBracket = text.indexOf(']', i + 1)
                if (closeBracket != -1 && closeBracket + 1 < text.length && text[closeBracket + 1] == '(') {
                    val closeParen = text.indexOf(')', closeBracket + 2)
                    if (closeParen != -1) {
                        flushPlain()
                        val label = text.substring(i + 1, closeBracket)
                        val url = text.substring(closeBracket + 2, closeParen)
                        result.add(MarkdownInline.Link(label, url))
                        i = closeParen + 1
                        continue
                    }
                }
            }

            // Inline code `code`
            if (ch == '`') {
                val close = text.indexOf('`', i + 1)
                if (close != -1) {
                    flushPlain()
                    result.add(MarkdownInline.InlineCode(text.substring(i + 1, close)))
                    i = close + 1
                    continue
                }
            }

            // Inline LaTeX $...$
            if (ch == '$' && !(i + 1 < text.length && text[i + 1] == '$')) {
                val nextChar = text.getOrNull(i + 1)
                if (nextChar != null && !nextChar.isWhitespace() && nextChar != '$') {
                    val close = findClosingSingleDollar(text, i + 1)
                    if (close != -1 && !text[close - 1].isWhitespace()) {
                        val expr = text.substring(i + 1, close).trim()
                        if (expr.isNotEmpty() && !expr.contains('\n')) {
                            flushPlain()
                            result.add(MarkdownInline.InlineLatex(expr))
                            i = close + 1
                            continue
                        }
                    }
                }
            }

            // BoldItalic ***...***
            if (text.startsWith("***", i)) {
                val close = text.indexOf("***", i + 3)
                if (close != -1) {
                    flushPlain()
                    result.add(MarkdownInline.BoldItalic(parseInlines(text.substring(i + 3, close))))
                    i = close + 3
                    continue
                }
            }

            // Bold **...** or __...__
            if (text.startsWith("**", i) || text.startsWith("__", i)) {
                val delim = text.substring(i, i + 2)
                val close = text.indexOf(delim, i + 2)
                if (close != -1) {
                    flushPlain()
                    result.add(MarkdownInline.Bold(parseInlines(text.substring(i + 2, close))))
                    i = close + 2
                    continue
                }
            }

            // Strikethrough ~~...~~
            if (text.startsWith("~~", i)) {
                val close = text.indexOf("~~", i + 2)
                if (close != -1) {
                    flushPlain()
                    result.add(MarkdownInline.Strikethrough(parseInlines(text.substring(i + 2, close))))
                    i = close + 2
                    continue
                }
            }

            // Highlight ==...==
            if (text.startsWith("==", i)) {
                val close = text.indexOf("==", i + 2)
                if (close != -1) {
                    flushPlain()
                    result.add(MarkdownInline.Highlight(parseInlines(text.substring(i + 2, close))))
                    i = close + 2
                    continue
                }
            }

            // Italic *...* or _..._
            if (ch == '*' || ch == '_') {
                val prevIsWord = i > 0 && text[i - 1].isLetterOrDigit()
                val canStart = if (ch == '_') !prevIsWord else true
                if (canStart) {
                    val close = text.indexOf(ch, i + 1)
                    if (close != -1 && close > i + 1 && !text[i + 1].isWhitespace()) {
                        val nextIsWord = close + 1 < text.length && text[close + 1].isLetterOrDigit()
                        val canClose = if (ch == '_') !nextIsWord else true
                        if (canClose) {
                            flushPlain()
                            result.add(MarkdownInline.Italic(parseInlines(text.substring(i + 1, close))))
                            i = close + 1
                            continue
                        }
                    }
                }
            }

            // Inline #tag
            if (ch == '#' && (i == 0 || text[i - 1].isWhitespace())) {
                val m = TAG_INLINE_REGEX.matchAt(text, i)
                if (m != null) {
                    flushPlain()
                    result.add(MarkdownInline.TagToken(m.value))
                    i += m.value.length
                    continue
                }
            }

            buffer.append(ch)
            i++
        }

        flushPlain()
        return result
    }

    private fun findClosingSingleDollar(text: String, start: Int): Int {
        var idx = start
        while (idx < text.length) {
            if (text[idx] == '\\') {
                idx += 2
                continue
            }
            if (text[idx] == '$') return idx
            idx++
        }
        return -1
    }

    private fun splitTableRow(line: String): List<String> {
        val trimmed = line.trim().removePrefix("|").removeSuffix("|")
        return trimmed.split("|").map { it.trim() }
    }

    private fun parseAlignment(cell: String): TableAlign {
        val t = cell.trim()
        val left = t.startsWith(":")
        val right = t.endsWith(":")
        return when {
            left && right -> TableAlign.CENTER
            right -> TableAlign.RIGHT
            else -> TableAlign.LEFT
        }
    }

    private val HR_REGEX = Regex("""^(?:-{3,}|\*{3,}|_{3,})$""")
    private val HEADING_REGEX = Regex("""^(#{1,6})\s+(.+)$""")
    private val CITATION_DEF_REGEX = Regex("""^\[\^([^\]]+)]:\s*(.+)$""")
    private val STANDALONE_IMAGE_REGEX = Regex("""^!\[([^\]]*)]\(([^)]+)\)$""")
    private val TABLE_SEPARATOR_REGEX = Regex("""^\|?\s*:?-{2,}:?\s*(?:\|\s*:?-{2,}:?\s*)+\|?$""")
    private val TASK_ITEM_REGEX = Regex("""^\s*[-*+]\s+\[([ xX])](?:\s+(.*))?$""")
    private val BULLET_ITEM_REGEX = Regex("""^\s*[-*+]\s+(.+)$""")
    private val ORDERED_ITEM_REGEX = Regex("""^\s*(\d+)\.\s+(.+)$""")
    private val TAG_INLINE_REGEX = Regex("""#[a-zA-Z][a-zA-Z0-9_-]{0,31}""")
    private val TASK_UNCHECKED_REGEX = Regex("""^(\s*(?:>\s*)*[-*+]\s+)\[\s](\s*.*)$""")
    private val TASK_CHECKED_REGEX = Regex("""^(\s*(?:>\s*)*[-*+]\s+)\[[xX]](\s*.*)$""")
    private val QUOTE_PREFIX_REGEX = Regex("""^(\s*(?:>\s*)+)""")
    private val SYNTAX_HEADING_REGEX = Regex("""(?m)^#{1,6}\s+.*$""")
    private val SYNTAX_CODE_REGEX = Regex("""`[^`\n]+`""")
    private val SYNTAX_LATEX_REGEX = Regex("""\$\$[\s\S]*?\$\$|\$[^$\n]+\$""")
    private val SYNTAX_LINK_REGEX = Regex("""\[\[[^\]]+]]|\[\^[^\]]+]""")
}
