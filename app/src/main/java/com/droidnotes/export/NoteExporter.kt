package com.droidnotes.export

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.droidnotes.data.repo.NotesRepository
import com.droidnotes.markdown.HtmlClipboardConverter
import com.droidnotes.markdown.LatexEngine
import com.droidnotes.markdown.MarkdownBlock
import com.droidnotes.markdown.MarkdownInline
import com.droidnotes.markdown.MarkdownParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

enum class ExportFormat { MARKDOWN, HTML, PDF }

class NoteExporter(
    private val context: Context,
    private val repository: NotesRepository,
) {
    suspend fun exportMarkdown(uri: Uri, content: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
            } ?: error("Unable to open output stream")
        }
    }

    suspend fun exportHtml(uri: Uri, title: String, content: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val doc = withContext(Dispatchers.Default) { MarkdownParser.parse(content) }
            val html = HtmlClipboardConverter.markdownToHtml(title, doc)
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(html.toByteArray(Charsets.UTF_8))
            } ?: error("Unable to open output stream")
        }
    }

    suspend fun exportPdf(uri: Uri, title: String, content: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val doc = withContext(Dispatchers.Default) { MarkdownParser.parse(content) }
            val pdf = PdfDocument()
            val pageW = 595
            val pageH = 842
            val margin = 44f
            val contentW = (pageW - margin * 2).toInt()

            val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 11f
                typeface = Typeface.SANS_SERIF
            }
            val monoPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.DKGRAY
                textSize = 9.5f
                typeface = Typeface.MONOSPACE
            }
            val headingPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
            val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.LTGRAY
                strokeWidth = 1f
            }
            val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(244, 244, 244)
                style = Paint.Style.FILL
            }

            var pageNumber = 1
            var page = pdf.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNumber).create())
            var canvas: Canvas = page.canvas
            var y = margin

            fun newPage() {
                pdf.finishPage(page)
                pageNumber++
                page = pdf.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNumber).create())
                canvas = page.canvas
                y = margin
            }

            fun ensureSpace(needed: Float) {
                if (y > margin && y + needed > pageH - margin) {
                    newPage()
                }
            }

            fun drawWrappedText(text: String, paint: TextPaint, indent: Float = 0f, extraBottom: Float = 8f) {
                val width = (contentW - indent).toInt().coerceAtLeast(80)
                val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(2f, 1.15f)
                    .build()
                ensureSpace(layout.height + extraBottom)
                canvas.save()
                canvas.translate(margin + indent, y)
                layout.draw(canvas)
                canvas.restore()
                y += layout.height + extraBottom
            }

            try {
                doc.blocks.forEach { block ->
                    when (block) {
                        is MarkdownBlock.Heading -> {
                            headingPaint.textSize = when (block.level) {
                                1 -> 19f
                                2 -> 15.5f
                                3 -> 13f
                                else -> 11.5f
                            }
                            drawWrappedText(inlinesToPlainText(block.inlines), headingPaint, extraBottom = 6f)
                            if (block.level <= 2) {
                                canvas.drawLine(margin, y - 2f, pageW - margin, y - 2f, linePaint)
                                y += 4f
                            }
                        }
                        is MarkdownBlock.Paragraph -> {
                            drawWrappedText(inlinesToPlainText(block.inlines), bodyPaint)
                        }
                        is MarkdownBlock.CodeBlock -> {
                            val codeText = block.code.ifBlank { " " }
                            val layout = StaticLayout.Builder.obtain(codeText, 0, codeText.length, monoPaint, contentW - 16)
                                .setLineSpacing(1f, 1.1f)
                                .build()
                            ensureSpace(layout.height + 20f)
                            canvas.drawRect(margin, y, pageW - margin, y + layout.height + 12f, boxPaint)
                            canvas.save()
                            canvas.translate(margin + 8f, y + 6f)
                            layout.draw(canvas)
                            canvas.restore()
                            y += layout.height + 20f
                        }
                        is MarkdownBlock.LatexBlock -> {
                            val math = LatexEngine.toReadableString(block.expression)
                            drawWrappedText(math, monoPaint, indent = 12f, extraBottom = 10f)
                        }
                        is MarkdownBlock.ChartBlock -> {
                            val summary = buildString {
                                if (block.spec.title.isNotBlank()) appendLine(block.spec.title)
                                block.spec.points.forEach { appendLine("• ${it.label}: ${it.value}") }
                            }.trim()
                            drawWrappedText(summary, monoPaint, indent = 8f, extraBottom = 10f)
                        }
                        is MarkdownBlock.DiagramBlock -> {
                            val summary = block.edges.joinToString("\n") {
                                val lbl = if (it.label.isNotBlank()) " (${it.label})" else ""
                                "${it.from} -> ${it.to}$lbl"
                            }
                            drawWrappedText(summary, monoPaint, indent = 8f, extraBottom = 10f)
                        }
                        is MarkdownBlock.TableBlock -> {
                            val headerLine = block.headers.joinToString(" | ") { inlinesToPlainText(it) }
                            drawWrappedText(headerLine, headingPaint.apply { textSize = 10.5f }, extraBottom = 4f)
                            block.rows.forEach { row ->
                                drawWrappedText(row.joinToString(" | ") { inlinesToPlainText(it) }, monoPaint, extraBottom = 4f)
                            }
                            y += 4f
                        }
                        is MarkdownBlock.Blockquote -> {
                            block.blocks.forEach { inner ->
                                if (inner is MarkdownBlock.Paragraph) {
                                    drawWrappedText("“${inlinesToPlainText(inner.inlines)}”", bodyPaint, indent = 14f)
                                }
                            }
                        }
                        is MarkdownBlock.BulletList -> {
                            block.items.forEach { item ->
                                drawWrappedText("• ${inlinesToPlainText(item)}", bodyPaint, indent = 10f, extraBottom = 4f)
                            }
                        }
                        is MarkdownBlock.OrderedList -> {
                            block.items.forEachIndexed { idx, item ->
                                drawWrappedText("${block.startNumber + idx}. ${inlinesToPlainText(item)}", bodyPaint, indent = 10f, extraBottom = 4f)
                            }
                        }
                        is MarkdownBlock.TaskList -> {
                            block.items.forEach { task ->
                                val prefix = if (task.checked) "[x]" else "[ ]"
                                drawWrappedText("$prefix ${inlinesToPlainText(task.inlines)}", bodyPaint, indent = 10f, extraBottom = 4f)
                            }
                        }
                        is MarkdownBlock.ImageBlock -> {
                            val file = repository.resolveLocalImageFile(block.uri)
                            val bmp = file?.let { f ->
                                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeFile(f.absolutePath, bounds)
                                val sample = NotesRepository.calculateInSampleSize(bounds.outWidth, bounds.outHeight, 1024, 1024)
                                BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                            }
                            if (bmp != null) {
                                try {
                                    val maxH = 180f
                                    val scale = minOf(contentW.toFloat() / bmp.width, maxH / bmp.height, 1f)
                                    val drawW = (bmp.width * scale).toInt()
                                    val drawH = (bmp.height * scale).toInt()
                                    ensureSpace(drawH + 16f)
                                    canvas.drawBitmap(
                                        bmp,
                                        null,
                                        Rect(margin.toInt(), y.toInt(), margin.toInt() + drawW, y.toInt() + drawH),
                                        null,
                                    )
                                    y += drawH + 12f
                                } finally {
                                    bmp.recycle()
                                }
                            } else {
                                drawWrappedText("[Image: ${block.alt.ifBlank { block.uri }}]", monoPaint)
                            }
                        }
                        is MarkdownBlock.TreeBlock -> {
                            // Keep the art intact: draw each line verbatim in monospace.
                            block.lines.forEach { line ->
                                ensureSpace(14f)
                                canvas.drawText(line, margin + 8f, y + 11f, monoPaint)
                                y += 14f
                            }
                        }
                        is MarkdownBlock.HorizontalRule -> {
                            ensureSpace(14f)
                            canvas.drawLine(margin, y + 4f, pageW - margin, y + 4f, linePaint)
                            y += 12f
                        }
                        is MarkdownBlock.CitationDefinition -> {
                            drawWrappedText("[${block.id}] ${inlinesToPlainText(block.inlines)}", monoPaint, extraBottom = 6f)
                        }
                    }
                }

                pdf.finishPage(page)
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    pdf.writeTo(out)
                } ?: error("Unable to open PDF output stream")
            } finally {
                pdf.close()
            }
        }
    }

    suspend fun importMarkdownOrText(uris: List<Uri>): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        uris.mapNotNull { uri ->
            runCatching {
                // Import the file's own name as the note title so it doesn't need renaming.
                // Fall back to the first heading/line only when the provider gives no name.
                val baseTitle = resolveDisplayName(uri)
                    ?.substringBeforeLast('.')
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                val text = context.contentResolver.openInputStream(uri)?.use { stream ->
                    BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
                } ?: return@runCatching null
                val title = baseTitle ?: NotesRepository.deriveTitleFromContent(text)
                title to text
            }.getOrNull()
        }
    }

    /**
     * Creates one document per note inside the folder the user picked, so each note is
     * exported as its own file instead of being merged into a single file.
     */
    suspend fun exportNotesToTree(
        treeUri: Uri,
        notes: List<Pair<String, String>>,
        format: ExportFormat,
    ): Int = withContext(Dispatchers.IO) {
        // OpenDocumentTree returns a tree URI, but createDocument needs a document URI
        // pointing at that directory. Convert once before creating any files.
        val parentDocumentUri = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
        }.getOrNull() ?: return@withContext 0

        val mimeType = when (format) {
            ExportFormat.MARKDOWN -> "text/markdown"
            ExportFormat.HTML -> "text/html"
            ExportFormat.PDF -> "application/pdf"
        }
        val extension = when (format) {
            ExportFormat.MARKDOWN -> "md"
            ExportFormat.HTML -> "html"
            ExportFormat.PDF -> "pdf"
        }
        var written = 0
        for ((title, content) in notes) {
            runCatching {
                val displayName = "${safeFileName(title)}.$extension"
                val docUri = DocumentsContract.createDocument(
                    context.contentResolver,
                    parentDocumentUri,
                    mimeType,
                    displayName,
                ) ?: error("Unable to create document: $displayName")
                when (format) {
                    ExportFormat.MARKDOWN -> writeText(docUri, content)
                    ExportFormat.HTML -> writeText(
                        docUri,
                        HtmlClipboardConverter.markdownToHtml(title, MarkdownParser.parse(content)),
                    )
                    ExportFormat.PDF -> {
                        val result = exportPdf(docUri, title, content)
                        if (result.isFailure) throw result.exceptionOrNull() ?: IllegalStateException("PDF export failed")
                    }
                }
                written++
            }
        }
        written
    }

    private fun writeText(uri: Uri, text: String) {
        val out = context.contentResolver.openOutputStream(uri) ?: error("Unable to open output stream")
        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    private fun safeFileName(name: String): String =
        name.trim()
            .replace(Regex("""[^a-zA-Z0-9._-]+"""), "_")
            .trim('.')
            .take(80)
            .ifBlank { "note" }

    private fun resolveDisplayName(uri: Uri): String? {
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }

    private fun inlinesToPlainText(inlines: List<MarkdownInline>): String = inlines.joinToString("") { inline ->
        when (inline) {
            is MarkdownInline.PlainText -> inline.text
            is MarkdownInline.Bold -> inlinesToPlainText(inline.children)
            is MarkdownInline.Italic -> inlinesToPlainText(inline.children)
            is MarkdownInline.BoldItalic -> inlinesToPlainText(inline.children)
            is MarkdownInline.Strikethrough -> inlinesToPlainText(inline.children)
            is MarkdownInline.Highlight -> inlinesToPlainText(inline.children)
            is MarkdownInline.InlineCode -> inline.code
            is MarkdownInline.InlineLatex -> LatexEngine.toReadableString(inline.expression)
            is MarkdownInline.Link -> "${inline.label} (${inline.url})"
            is MarkdownInline.WikiLink -> inline.display
            is MarkdownInline.CitationRef -> "[${inline.id}]"
            is MarkdownInline.TagToken -> inline.tag
            is MarkdownInline.InlineImage -> "[${inline.alt}]"
        }
    }
}
