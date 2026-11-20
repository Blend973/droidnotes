package com.droidnotes.ui.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Pure Kotlin line/paragraph editing operations for the Markdown formatting toolbar.
 */
object MarkdownTextOps {
    private val BULLET_REGEX = Regex("""^(\s*)[-*+]\s+(.*)$""")
    private val NUMBERED_REGEX = Regex("""^(\s*)\d+\.\s+(.*)$""")
    private val BLOCKQUOTE_REGEX = Regex("""^(\s*)>\s?(.*)$""")

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

    fun toggleBulletList(field: TextFieldValue): TextFieldValue {
        val spans = splitLineSpans(field.text)
        val range = selectedLineRange(spans, field.selection)
        val lines = spans.map { it.text }.toMutableList()

        val allBulleted = range.all { BULLET_REGEX.matches(lines[it]) }
        var firstDelta = 0
        var totalDelta = 0

        for (i in range) {
            val line = lines[i]
            val updated = if (allBulleted) {
                val m = BULLET_REGEX.matchEntire(line)!!
                m.groupValues[1] + m.groupValues[2]
            } else {
                val numMatch = NUMBERED_REGEX.matchEntire(line)
                if (numMatch != null) {
                    "${numMatch.groupValues[1]}- ${numMatch.groupValues[2]}"
                } else {
                    val indent = line.takeWhile { it == ' ' || it == '\t' }
                    val rest = line.drop(indent.length)
                    "$indent- $rest"
                }
            }
            val delta = updated.length - line.length
            if (i == range.first) firstDelta = delta
            totalDelta += delta
            lines[i] = updated
        }

        val newText = rebuildFromLines(lines)
        val newSel = adjustSelection(field.selection, newText.length, firstDelta, totalDelta)
        return TextFieldValue(newText, newSel)
    }

    fun toggleNumberedList(field: TextFieldValue): TextFieldValue {
        val spans = splitLineSpans(field.text)
        val range = selectedLineRange(spans, field.selection)
        val lines = spans.map { it.text }.toMutableList()

        val allNumbered = range.all { NUMBERED_REGEX.matches(lines[it]) }
        var firstDelta = 0
        var totalDelta = 0

        for ((offsetIdx, i) in range.withIndex()) {
            val line = lines[i]
            val updated = if (allNumbered) {
                val m = NUMBERED_REGEX.matchEntire(line)!!
                m.groupValues[1] + m.groupValues[2]
            } else {
                val numPrefix = "${offsetIdx + 1}. "
                val bulletMatch = BULLET_REGEX.matchEntire(line)
                if (bulletMatch != null) {
                    "${bulletMatch.groupValues[1]}$numPrefix${bulletMatch.groupValues[2]}"
                } else {
                    val indent = line.takeWhile { it == ' ' || it == '\t' }
                    val rest = line.drop(indent.length)
                    "$indent$numPrefix$rest"
                }
            }
            val delta = updated.length - line.length
            if (i == range.first) firstDelta = delta
            totalDelta += delta
            lines[i] = updated
        }

        val newText = rebuildFromLines(lines)
        val newSel = adjustSelection(field.selection, newText.length, firstDelta, totalDelta)
        return TextFieldValue(newText, newSel)
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

    fun toggleBlockquote(field: TextFieldValue): TextFieldValue {
        val spans = splitLineSpans(field.text)
        val range = selectedLineRange(spans, field.selection)
        val lines = spans.map { it.text }.toMutableList()

        val allQuoted = range.all { BLOCKQUOTE_REGEX.matches(lines[it]) }
        var firstDelta = 0
        var totalDelta = 0

        for (i in range) {
            val line = lines[i]
            val updated = if (allQuoted) {
                val m = BLOCKQUOTE_REGEX.matchEntire(line)!!
                m.groupValues[1] + m.groupValues[2]
            } else {
                "> $line"
            }
            val delta = updated.length - line.length
            if (i == range.first) firstDelta = delta
            totalDelta += delta
            lines[i] = updated
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
