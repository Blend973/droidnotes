package com.droidnotes.ui.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Pure Kotlin line/paragraph editing operations for the Markdown formatting toolbar.
 */
object MarkdownTextOps {
    private val NUMBERED_REGEX = Regex("""^(\s*)(\d+)\.\s+(.*)$""")

    private data class LineSpan(val start: Int, val end: Int, val text: String)

    private fun splitLineSpans(text: String): List<LineSpan> {
        val spans = mutableListOf<LineSpan>()
        var lineStart = 0
        for (i in text.indices) {
            if (text[i] == '\n') {
                spans.add(LineSpan(lineStart, i, text.substring(lineStart, i)))
                lineStart = i + 1
            }
        }
        spans.add(LineSpan(lineStart, text.length, text.substring(lineStart)))
        return spans
    }

    private fun selectedLineRange(spans: List<LineSpan>, selection: TextRange): IntRange {
        val selStart = selection.min.coerceIn(0, spans.last().end)
        val selEnd = selection.max.coerceIn(selStart, spans.last().end)
        val firstLine = spans.indexOfLast { it.start <= selStart }.coerceAtLeast(0)
        val effectiveEnd = if (selEnd > selStart && selEnd > 0) selEnd - 1 else selEnd
        val lastLine = spans.indexOfLast { it.start <= effectiveEnd }.coerceAtLeast(firstLine)
        return firstLine..lastLine
    }

    private fun rebuildFromLines(lines: List<String>): String = lines.joinToString("\n")

    private fun lineStartOffset(lines: List<String>, lineIdx: Int): Int {
        var acc = 0
        for (i in 0 until lineIdx.coerceIn(0, lines.size)) {
            acc += lines[i].length + 1
        }
        return acc
    }

    /** Inserts a new list-item line after the line containing the caret. */
    private fun insertListItem(field: TextFieldValue, prefix: String): TextFieldValue {
        val text = field.text
        val pos = field.selection.max.coerceIn(0, text.length)
        val lineEnd = text.indexOf('\n', pos).let { if (it < 0) text.length else it }
        val snippet = "\n$prefix"
        val newText = text.substring(0, lineEnd) + snippet + text.substring(lineEnd)
        return TextFieldValue(newText, TextRange(lineEnd + snippet.length))
    }

    fun addBulletItem(field: TextFieldValue): TextFieldValue =
        insertListItem(field, "- ")

    fun addQuoteItem(field: TextFieldValue): TextFieldValue =
        insertListItem(field, "> ")

    fun addNumberedItem(field: TextFieldValue): TextFieldValue {
        val spans = splitLineSpans(field.text)
        val pos = field.selection.max.coerceIn(0, field.text.length)
        val currentLine = spans.indexOfLast { it.start <= pos }.coerceAtLeast(0)
        var number = 1
        var indent = ""
        for (i in currentLine downTo 0) {
            val line = spans[i].text
            if (line.isBlank()) continue
            val match = NUMBERED_REGEX.matchEntire(line)
            if (match != null) {
                number = (match.groupValues[2].toIntOrNull() ?: 0) + 1
                indent = match.groupValues[1]
            }
            break
        }
        return insertListItem(field, "$indent$number. ")
    }

    fun indentLines(field: TextFieldValue, indentUnit: String = "  "): TextFieldValue {
        val unit = indentUnit.ifEmpty { "  " }
        val spans = splitLineSpans(field.text)
        val range = selectedLineRange(spans, field.selection)
        val lines = spans.map { it.text }.toMutableList()

        for (i in range) {
            lines[i] = unit + lines[i]
        }

        val newText = rebuildFromLines(lines)
        val firstDelta = unit.length
        val totalDelta = unit.length * (range.last - range.first + 1)
        val newSel = adjustSelection(field.selection, newText.length, firstDelta, totalDelta)
        return TextFieldValue(newText, newSel)
    }

    fun unindentLines(field: TextFieldValue, tabSize: Int = 2): TextFieldValue {
        val step = tabSize.coerceAtLeast(1)
        val spans = splitLineSpans(field.text)
        val range = selectedLineRange(spans, field.selection)
        val lines = spans.map { it.text }.toMutableList()

        var firstDelta = 0
        var totalDelta = 0

        for (i in range) {
            val line = lines[i]
            val removed = when {
                line.startsWith("\t") -> 1
                else -> {
                    var spaces = 0
                    while (spaces < step && spaces < line.length && line[spaces] == ' ') spaces++
                    spaces
                }
            }
            lines[i] = line.drop(removed)
            val delta = -removed
            if (i == range.first) firstDelta = delta
            totalDelta += delta
        }

        val newText = rebuildFromLines(lines)
        val newSel = adjustSelection(field.selection, newText.length, firstDelta, totalDelta)
        return TextFieldValue(newText, newSel)
    }

    fun moveLinesUp(field: TextFieldValue): TextFieldValue {
        val spans = splitLineSpans(field.text)
        val range = selectedLineRange(spans, field.selection)
        if (range.first <= 0) return field

        val lines = spans.map { it.text }.toMutableList()
        val aboveLine = lines.removeAt(range.first - 1)
        lines.add(range.last, aboveLine)

        val shift = -(aboveLine.length + 1)
        val newText = rebuildFromLines(lines)
        val newStart = (field.selection.min + shift).coerceIn(0, newText.length)
        val newEnd = (field.selection.max + shift).coerceIn(newStart, newText.length)
        return TextFieldValue(newText, TextRange(newStart, newEnd))
    }

    fun moveLinesDown(field: TextFieldValue): TextFieldValue {
        val spans = splitLineSpans(field.text)
        val range = selectedLineRange(spans, field.selection)
        if (range.last >= spans.lastIndex) return field

        val lines = spans.map { it.text }.toMutableList()
        val belowLine = lines.removeAt(range.last + 1)
        lines.add(range.first, belowLine)

        val shift = belowLine.length + 1
        val newText = rebuildFromLines(lines)
        val newStart = (field.selection.min + shift).coerceIn(0, newText.length)
        val newEnd = (field.selection.max + shift).coerceIn(newStart, newText.length)
        return TextFieldValue(newText, TextRange(newStart, newEnd))
    }

    fun removeLines(field: TextFieldValue): TextFieldValue {
        val spans = splitLineSpans(field.text)
        val range = selectedLineRange(spans, field.selection)
        val lines = spans.map { it.text }.toMutableList()
        for (i in range.last downTo range.first) {
            lines.removeAt(i)
        }
        if (lines.isEmpty()) {
            return TextFieldValue("", TextRange(0))
        }
        val newText = rebuildFromLines(lines)
        val targetLine = range.first.coerceIn(0, lines.lastIndex)
        val cursor = lineStartOffset(lines, targetLine).coerceIn(0, newText.length)
        return TextFieldValue(newText, TextRange(cursor))
    }

    fun deleteParagraph(field: TextFieldValue): TextFieldValue {
        val spans = splitLineSpans(field.text)
        val range = selectedLineRange(spans, field.selection)
        val lines = spans.map { it.text }.toMutableList()
        if (lines.isEmpty()) return field

        var startLine = range.first
        var endLine = range.last

        if (lines[startLine].isNotBlank() || lines[endLine].isNotBlank()) {
            while (startLine > 0 && lines[startLine - 1].isNotBlank()) startLine--
            while (endLine < lines.lastIndex && lines[endLine + 1].isNotBlank()) endLine++
            // Also consume one adjacent blank separator line so deletion leaves clean spacing
            if (endLine + 1 < lines.size && lines[endLine + 1].isBlank()) {
                endLine++
            } else if (startLine > 0 && lines[startLine - 1].isBlank()) {
                startLine--
            }
        }

        for (i in endLine downTo startLine) {
            lines.removeAt(i)
        }
        if (lines.isEmpty()) {
            return TextFieldValue("", TextRange(0))
        }
        val newText = rebuildFromLines(lines)
        val targetLine = startLine.coerceIn(0, lines.lastIndex)
        val cursor = lineStartOffset(lines, targetLine).coerceIn(0, newText.length)
        return TextFieldValue(newText, TextRange(cursor))
    }

    private fun adjustSelection(
        selection: TextRange,
        maxLen: Int,
        firstDelta: Int,
        totalDelta: Int,
    ): TextRange {
        return if (selection.collapsed) {
            val pos = (selection.start + firstDelta).coerceIn(0, maxLen)
            TextRange(pos)
        } else {
            val start = (selection.min + firstDelta).coerceIn(0, maxLen)
            val end = (selection.max + totalDelta).coerceIn(start, maxLen)
            TextRange(start, end)
        }
    }
}
