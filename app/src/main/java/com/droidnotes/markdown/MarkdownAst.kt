package com.droidnotes.markdown

enum class TableAlign {
    LEFT,
    CENTER,
    RIGHT,
}

data class ChartPoint(
    val label: String,
    val value: Float,
    val xValue: Float? = null,
    val colorHex: String? = null,
)

enum class ChartType {
    BAR,
    LINE,
    PIE,
    SCATTER,
}

data class ChartSpec(
    val type: ChartType,
    val title: String,
    val points: List<ChartPoint>,
)

data class DiagramEdge(
    val from: String,
    val to: String,
    val label: String = "",
)

enum class SyntaxTokenType {
    HEADING,
    CODE,
    LATEX,
    LINK,
}

data class SyntaxSpan(
    val start: Int,
    val end: Int,
    val type: SyntaxTokenType,
)

data class MarkdownDocument(
    val blocks: List<MarkdownBlock>,
    val citations: Map<String, List<MarkdownInline>>,
    val totalWords: Int,
    val totalLines: Int = 0,
)

sealed interface MarkdownBlock {
    val lineIndex: Int

    data class Heading(
        val level: Int,
        val inlines: List<MarkdownInline>,
        override val lineIndex: Int,
    ) : MarkdownBlock

    data class Paragraph(
        val inlines: List<MarkdownInline>,
        override val lineIndex: Int,
    ) : MarkdownBlock

    data class CodeBlock(
        val language: String,
        val code: String,
        override val lineIndex: Int,
    ) : MarkdownBlock

    data class LatexBlock(
        val expression: String,
        override val lineIndex: Int,
        val endLineIndex: Int = lineIndex,
    ) : MarkdownBlock

    data class ChartBlock(
        val spec: ChartSpec,
        val raw: String,
        override val lineIndex: Int,
        val endLineIndex: Int = lineIndex,
    ) : MarkdownBlock

    data class DiagramBlock(
        val edges: List<DiagramEdge>,
        val raw: String,
        override val lineIndex: Int,
        val endLineIndex: Int = lineIndex,
        val nodePositions: Map<String, Pair<Float, Float>> = emptyMap(),
        val standaloneNodes: List<String> = emptyList(),
        val edgeCurveOffsets: Map<Int, Pair<Float, Float>> = emptyMap(),
    ) : MarkdownBlock

    data class TableBlock(
        val headers: List<List<MarkdownInline>>,
        val alignments: List<TableAlign>,
        val rows: List<List<List<MarkdownInline>>>,
        override val lineIndex: Int,
        val endLineIndex: Int = lineIndex,
        val rawHeaders: List<String> = emptyList(),
        val rawRows: List<List<String>> = emptyList(),
    ) : MarkdownBlock

    /** Preformatted tree/ASCII-art lines whose shape must be preserved (no wrapping). */
    data class TreeBlock(
        val lines: List<String>,
        override val lineIndex: Int,
    ) : MarkdownBlock

    data class Blockquote(
        val blocks: List<MarkdownBlock>,
        override val lineIndex: Int,
    ) : MarkdownBlock

    data class BulletList(
        val items: List<List<MarkdownInline>>,
        override val lineIndex: Int,
    ) : MarkdownBlock

    data class OrderedList(
        val items: List<List<MarkdownInline>>,
        val startNumber: Int,
        override val lineIndex: Int,
    ) : MarkdownBlock

    data class TaskList(
        val items: List<TaskListItem>,
        override val lineIndex: Int,
    ) : MarkdownBlock

    data class ImageBlock(
        val alt: String,
        val uri: String,
        override val lineIndex: Int,
    ) : MarkdownBlock

    data class HorizontalRule(
        override val lineIndex: Int,
    ) : MarkdownBlock

    data class CitationDefinition(
        val id: String,
        val inlines: List<MarkdownInline>,
        override val lineIndex: Int,
    ) : MarkdownBlock
}

data class TaskListItem(
    val checked: Boolean,
    val inlines: List<MarkdownInline>,
    val sourceLineIndex: Int,
)

sealed interface MarkdownInline {
    data class PlainText(val text: String) : MarkdownInline
    data class Bold(val children: List<MarkdownInline>) : MarkdownInline
    data class Italic(val children: List<MarkdownInline>) : MarkdownInline
    data class BoldItalic(val children: List<MarkdownInline>) : MarkdownInline
    data class Strikethrough(val children: List<MarkdownInline>) : MarkdownInline
    data class Highlight(val children: List<MarkdownInline>) : MarkdownInline
    data class InlineCode(val code: String) : MarkdownInline
    data class InlineLatex(val expression: String) : MarkdownInline
    data class Link(val label: String, val url: String) : MarkdownInline
    data class WikiLink(val target: String, val display: String) : MarkdownInline
    data class CitationRef(val id: String) : MarkdownInline
    data class TagToken(val tag: String) : MarkdownInline
    data class InlineImage(val alt: String, val uri: String) : MarkdownInline
}
