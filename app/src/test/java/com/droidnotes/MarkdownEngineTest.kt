package com.droidnotes

import com.droidnotes.markdown.ChartType
import com.droidnotes.markdown.HtmlClipboardConverter
import com.droidnotes.markdown.LatexEngine
import com.droidnotes.markdown.MarkdownBlock
import com.droidnotes.markdown.MarkdownInline
import com.droidnotes.markdown.MarkdownParser
import com.droidnotes.markdown.TableAlign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownEngineTest {

    @Test
    fun parsesHeadingsTablesTasksLatexChartsAndCitations() {
        val source = """
            # Title Note
            Text with **bold**, ${'$'}x^2 + y^2${'$'}, [[Target Note]], and ref[^1].

            - [ ] Unchecked item
            - [x] Done item

            | Key | Val |
            | :--- | ---: |
            | A | 10 |

            ```chart
            type: bar
            title: Demo
            data:
            Alpha, 12
            Beta, 24
            ```

            ```diagram
            A -> B : link
            ```

            $$\frac{a}{b} = \sqrt{c}$$

            [^1]: Source Citation
        """.trimIndent()

        val doc = MarkdownParser.parse(source)
        assertTrue(doc.blocks.any { it is MarkdownBlock.Heading && it.level == 1 })
        assertTrue(doc.blocks.any { it is MarkdownBlock.TaskList && it.items.size == 2 })
        val table = doc.blocks.filterIsInstance<MarkdownBlock.TableBlock>().first()
        assertEquals(listOf(TableAlign.LEFT, TableAlign.RIGHT), table.alignments)
        val chart = doc.blocks.filterIsInstance<MarkdownBlock.ChartBlock>().first()
        assertEquals(ChartType.BAR, chart.spec.type)
        assertEquals(2, chart.spec.points.size)
        assertTrue(doc.blocks.any { it is MarkdownBlock.DiagramBlock && it.edges.size == 1 })
        assertTrue(doc.blocks.any { it is MarkdownBlock.LatexBlock })
        assertTrue(doc.citations.containsKey("1"))

        val para = doc.blocks.filterIsInstance<MarkdownBlock.Paragraph>().first()
        assertTrue(para.inlines.any { it is MarkdownInline.WikiLink && it.target == "Target Note" })
        assertTrue(para.inlines.any { it is MarkdownInline.InlineLatex })
    }

    @Test
    fun togglesTaskCheckboxAccurately() {
        val src = "- [ ] First\n- [x] Second"
        val toggled = MarkdownParser.toggleTaskCheckboxInSource(src, 0)
        assertEquals("- [x] First\n- [x] Second", toggled)
    }

    @Test
    fun convertsHtmlAndLatex() {
        val md = HtmlClipboardConverter.htmlToMarkdown("<h2>Hello</h2><p>This is <b>bold</b> and <a href=\"https://a.com\">link</a></p>")
        assertTrue(md.contains("## Hello"))
        assertTrue(md.contains("**bold**"))
        assertTrue(md.contains("[link](https://a.com)"))

        val mathText = LatexEngine.toReadableString("\\frac{\\alpha}{\\beta} + \\sqrt{x}")
        assertTrue(mathText.contains("α"))
        assertTrue(mathText.contains("β"))
        assertTrue(mathText.contains("√"))
    }

    @Test
    fun countsLinesComputesSyntaxSpansAndMatchesPreviewBlocks() {
        val src = "# Heading\nPlain line with keyword\n- [ ] Task keyword\n\nFourth paragraph keyword"
        val doc = MarkdownParser.parse(src)
        assertEquals(5, doc.totalLines)

        val spans = MarkdownParser.computeSyntaxSpans(src)
        assertTrue(spans.isNotEmpty())

        val blockIndices = MarkdownParser.findMatchingBlockIndices(doc, "keyword")
        assertEquals(3, blockIndices.size)
        assertEquals(1, blockIndices[0])
        assertEquals(2, blockIndices[1])
        assertEquals(3, blockIndices[2])
    }

    @Test
    fun tracksBlockEndLinesAndReplacesScopedBlocksCleanly() {
        val source = """
            # Header

            | Key | Val |
            | :--- | ---: |
            | A | 10 |

            ```chart
            type: bar
            title: Demo
            data:
            One, 5
            ```

            Footer text
        """.trimIndent()

        val doc = MarkdownParser.parse(source)
        val table = doc.blocks.filterIsInstance<MarkdownBlock.TableBlock>().first()
        assertEquals(2, table.lineIndex)
        assertEquals(4, table.endLineIndex)

        val updatedTableMd = MarkdownParser.serializeTable(
            headers = listOf("Metric", "Score"),
            alignments = table.alignments,
            rows = listOf(listOf("Speed", "99"), listOf("Memory", "12")),
        )
        val replaced = MarkdownParser.replaceLineRange(
            source = source,
            startLineIndex = table.lineIndex,
            endLineIndex = table.endLineIndex,
            newBlockMarkdown = updatedTableMd,
        )

        val reparsed = MarkdownParser.parse(replaced)
        val newTable = reparsed.blocks.filterIsInstance<MarkdownBlock.TableBlock>().first()
        assertEquals(listOf("Metric", "Score"), newTable.rawHeaders)
        assertEquals(2, newTable.rows.size)
        assertTrue(replaced.contains("# Header"))
        assertTrue(replaced.contains("Footer text"))
        assertTrue(replaced.contains("type: bar"))
    }

    @Test
    fun roundTripsDiagramPositionsCurvesAndStandaloneNodes() {
        val md = MarkdownParser.serializeDiagram(
            edges = listOf(com.droidnotes.markdown.DiagramEdge("Input", "Process", "data")),
            nodePositions = mapOf(
                "Input" to (0.20f to 0.30f),
                "Process" to (0.75f to 0.65f),
                "Orphan" to (0.50f to 0.85f),
            ),
            standaloneNodes = listOf("Orphan"),
            edgeCurveOffsets = mapOf(0 to (0.12f to -0.18f)),
        )

        val doc = MarkdownParser.parse(md)
        val diag = doc.blocks.filterIsInstance<MarkdownBlock.DiagramBlock>().first()
        assertEquals(1, diag.edges.size)
        assertEquals("Input", diag.edges[0].from)
        assertEquals("Process", diag.edges[0].to)
        assertEquals("data", diag.edges[0].label)
        assertEquals(listOf("Orphan"), diag.standaloneNodes)
        assertEquals(0.20f, diag.nodePositions["Input"]!!.first, 0.01f)
        assertEquals(0.65f, diag.nodePositions["Process"]!!.second, 0.01f)
        assertEquals(0.12f, diag.edgeCurveOffsets[0]!!.first, 0.01f)
        assertEquals(-0.18f, diag.edgeCurveOffsets[0]!!.second, 0.01f)
    }

    @Test
    fun parsesNaturalUnicodeMathObjectsDirectly() {
        val readable = LatexEngine.toReadableString("∑_{i=1}^{n} β_i + ∫_{0}^{1} √{x} + lim_{x → ∞} sin(x) + \\binom{n}{k}")
        assertTrue(readable.contains("∑"))
        assertTrue(readable.contains("β"))
        assertTrue(readable.contains("∫"))
        assertTrue(readable.contains("√"))
        assertTrue(readable.contains("lim"))
        assertTrue(readable.contains("sin"))
        assertTrue(readable.contains("∞"))
    }

    @Test
    fun roundTripsChartPointColors() {
        val md = """
            ```chart
            type: bar
            title: Colored
            data:
            Alpha, 10, #22D3EE
            Beta, 25, #F87171
            ```
        """.trimIndent()
        val doc = MarkdownParser.parse(md)
        val chart = doc.blocks.filterIsInstance<MarkdownBlock.ChartBlock>().first()
        assertEquals(2, chart.spec.points.size)
        assertEquals("#22D3EE", chart.spec.points[0].colorHex)
        assertEquals("#F87171", chart.spec.points[1].colorHex)

        val serialized = MarkdownParser.serializeChart(chart.spec)
        assertTrue(serialized.contains("Alpha, 10, #22D3EE"))
        assertTrue(serialized.contains("Beta, 25, #F87171"))
    }

    @Test
    fun calculatorMathCursorStepsAndBackspacesCleanly() {
        val expr = "\\frac{a}{b}"
        // Walking right from 0 should step into numerator (6), past a (7), into denominator (9), past b (10), end (11)
        val s1 = com.droidnotes.ui.editor.MathCalculatorEngine.moveRight(expr, 0)
        assertEquals(6, s1)
        val s2 = com.droidnotes.ui.editor.MathCalculatorEngine.moveRight(expr, s1)
        assertEquals(7, s2)
        val s3 = com.droidnotes.ui.editor.MathCalculatorEngine.moveRight(expr, s2)
        assertEquals(9, s3)
        val s4 = com.droidnotes.ui.editor.MathCalculatorEngine.moveRight(expr, s3)
        assertEquals(10, s4)
        val s5 = com.droidnotes.ui.editor.MathCalculatorEngine.moveRight(expr, s4)
        assertEquals(11, s5)

        // Smart backspace at end of filled fraction steps inside before '}'
        val (afterStepIn, posIn) = com.droidnotes.ui.editor.MathCalculatorEngine.smartBackspace(expr, 11)
        assertEquals(expr, afterStepIn)
        assertEquals(10, posIn)

        // Smart backspace inside empty fraction removes entire empty template
        val (cleared, posCleared) = com.droidnotes.ui.editor.MathCalculatorEngine.smartBackspace("\\frac{}{}", 6)
        assertEquals("", cleared)
        assertEquals(0, posCleared)
    }

    @Test
    fun markdownTextOpsHandlesListsIndentMoveRemoveParagraphAndQuote() {
        val base = androidx.compose.ui.text.input.TextFieldValue(
            text = "First\nSecond\n\nPara2Line1\nPara2Line2",
            selection = androidx.compose.ui.text.TextRange(0),
        )
        val bulleted = com.droidnotes.ui.editor.MarkdownTextOps.toggleBulletList(base)
        assertTrue(bulleted.text.startsWith("- First\nSecond"))
        val unbulleted = com.droidnotes.ui.editor.MarkdownTextOps.toggleBulletList(bulleted)
        assertEquals(base.text, unbulleted.text)

        val numbered = com.droidnotes.ui.editor.MarkdownTextOps.toggleNumberedList(base)
        assertTrue(numbered.text.startsWith("1. First\nSecond"))
        val unnumbered = com.droidnotes.ui.editor.MarkdownTextOps.toggleNumberedList(numbered)
        assertEquals(base.text, unnumbered.text)

        val indented = com.droidnotes.ui.editor.MarkdownTextOps.indentLines(bulleted, "  ")
        assertTrue(indented.text.startsWith("  - First"))
        val unindented = com.droidnotes.ui.editor.MarkdownTextOps.unindentLines(indented, 2)
        assertEquals(bulleted.text, unindented.text)

        val quoted = com.droidnotes.ui.editor.MarkdownTextOps.toggleBlockquote(base)
        assertTrue(quoted.text.startsWith("> First"))

        val movedDown = com.droidnotes.ui.editor.MarkdownTextOps.moveLinesDown(base)
        assertTrue(movedDown.text.startsWith("Second\nFirst"))
        val movedBackUp = com.droidnotes.ui.editor.MarkdownTextOps.moveLinesUp(movedDown)
        assertEquals(base.text, movedBackUp.text)

        val removedLine = com.droidnotes.ui.editor.MarkdownTextOps.removeLines(base)
        assertTrue(removedLine.text.startsWith("Second\n"))

        val deletedPara = com.droidnotes.ui.editor.MarkdownTextOps.deleteParagraph(base)
        assertEquals("Para2Line1\nPara2Line2", deletedPara.text)
    }
}
