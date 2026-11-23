package com.droidnotes.markdown

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.droidnotes.data.prefs.EditorPreferences
import com.droidnotes.data.repo.NotesRepository
import com.droidnotes.ui.theme.JetBrainsMonoFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun MarkdownPreview(
    document: MarkdownDocument,
    preferences: EditorPreferences,
    searchQuery: String,
    resolveImageFile: (String) -> File?,
    onToggleTask: (Int) -> Unit,
    onWikiLinkClick: (String) -> Unit,
    onTagClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    onEditBlock: ((MarkdownBlock) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val citationBlockIndexMap = remember(document) {
        buildMap {
            document.blocks.forEachIndexed { index, block ->
                if (block is MarkdownBlock.CitationDefinition) {
                    put(block.id, index)
                }
            }
        }
    }

    if (document.blocks.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Nothing to preview yet. Switch to Edit to start writing Markdown.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(
            items = document.blocks,
            key = { index, block -> "${index}_${block.lineIndex}_${block::class.simpleName}" },
        ) { _, block ->
            MarkdownBlockView(
                block = block,
                preferences = preferences,
                searchQuery = searchQuery,
                resolveImageFile = resolveImageFile,
                onToggleTask = onToggleTask,
                onWikiLinkClick = onWikiLinkClick,
                onTagClick = onTagClick,
                onCitationClick = { id ->
                    val targetIdx = citationBlockIndexMap[id]
                    if (targetIdx != null) {
                        scope.launch { listState.scrollToItem(targetIdx) }
                    }
                },
                onEditBlock = onEditBlock,
            )
        }
        item {
            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}

@Composable
private fun MarkdownBlockView(
    block: MarkdownBlock,
    preferences: EditorPreferences,
    searchQuery: String,
    resolveImageFile: (String) -> File?,
    onToggleTask: (Int) -> Unit,
    onWikiLinkClick: (String) -> Unit,
    onTagClick: (String) -> Unit,
    onCitationClick: (String) -> Unit,
    onEditBlock: ((MarkdownBlock) -> Unit)? = null,
) {
    val baseSize = preferences.previewFontSizeSp
    val lineHeightSp = (baseSize * preferences.lineSpacingMultiplier).sp
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current

    when (block) {
        is MarkdownBlock.Heading -> {
            val scale = when (block.level) {
                1 -> 1.65f
                2 -> 1.38f
                3 -> 1.18f
                4 -> 1.06f
                else -> 0.98f
            }
            val text = rememberInlineAnnotatedString(
                inlines = block.inlines,
                searchQuery = searchQuery,
                onLinkClick = { url -> runCatching { uriHandler.openUri(url) } },
                onWikiLinkClick = onWikiLinkClick,
                onCitationClick = onCitationClick,
                onTagClick = onTagClick,
            )
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = (baseSize * scale).sp,
                        lineHeight = (baseSize * scale * 1.25f).sp,
                        fontWeight = if (block.level <= 2) FontWeight.Bold else FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                )
                if (block.level <= 2) {
                    Spacer(modifier = Modifier.height(6.dp))
                    HorizontalDivider(thickness = 1.dp, color = Color(0xFF262626))
                }
            }
        }

        is MarkdownBlock.Paragraph -> {
            val text = rememberInlineAnnotatedString(
                inlines = block.inlines,
                searchQuery = searchQuery,
                onLinkClick = { url -> runCatching { uriHandler.openUri(url) } },
                onWikiLinkClick = onWikiLinkClick,
                onCitationClick = onCitationClick,
                onTagClick = onTagClick,
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = baseSize.sp,
                    lineHeight = lineHeightSp,
                ),
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        is MarkdownBlock.TreeBlock -> {
            // Tree art keeps its original line breaks and is never wrapped. A horizontal
            // scroll lets long branches be read without distorting the shape.
            val text = rememberSearchHighlightedPlainText(
                text = block.lines.joinToString("\n"),
                query = searchQuery,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = (baseSize - 1f).coerceAtLeast(10f).sp,
                        lineHeight = lineHeightSp,
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                    softWrap = false,
                )
            }
        }

        is MarkdownBlock.CodeBlock -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0A0A0A), RoundedCornerShape(6.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF141414))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = block.language.ifBlank { "code" }.uppercase(),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box(
                        modifier = Modifier
                            .clickable { clipboard.setText(AnnotatedString(block.code)) }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = "Copy code",
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp),
                ) {
                    val codeText = rememberSearchHighlightedPlainText(block.code, searchQuery)
                    Text(
                        text = codeText,
                        style = TextStyle(
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = (baseSize - 2f).coerceAtLeast(11f).sp,
                            lineHeight = ((baseSize - 2f).coerceAtLeast(11f) * 1.45f).sp,
                            color = Color(0xFFE5E5E5),
                        ),
                    )
                }
            }
        }

        is MarkdownBlock.LatexBlock -> {
            EditablePreviewWrapper(
                label = "MATH",
                canEdit = onEditBlock != null,
                onEdit = { onEditBlock?.invoke(block) },
            ) {
                if (preferences.renderLatex) {
                    LatexBlockView(
                        expression = block.expression,
                        fontSizeSp = baseSize,
                    )
                } else {
                    Text(
                        text = "$$ ${block.expression} $$",
                        style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = baseSize.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        is MarkdownBlock.ChartBlock -> {
            EditablePreviewWrapper(
                label = "CHART · ${block.spec.type.name}",
                canEdit = onEditBlock != null,
                onEdit = { onEditBlock?.invoke(block) },
            ) {
                if (preferences.renderCharts) {
                    ChartCanvasView(spec = block.spec)
                } else {
                    Text(
                        text = block.raw,
                        style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = (baseSize - 2f).sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        is MarkdownBlock.DiagramBlock -> {
            EditablePreviewWrapper(
                label = "DIAGRAM",
                canEdit = onEditBlock != null,
                onEdit = { onEditBlock?.invoke(block) },
            ) {
                if (preferences.renderCharts) {
                    DiagramCanvasView(
                        edges = block.edges,
                        nodePositions = block.nodePositions,
                        standaloneNodes = block.standaloneNodes,
                        edgeCurveOffsets = block.edgeCurveOffsets,
                    )
                } else {
                    Text(
                        text = block.raw,
                        style = TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = (baseSize - 2f).sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        is MarkdownBlock.TableBlock -> {
            val colCount = maxOf(
                block.headers.size,
                block.rows.maxOfOrNull { it.size } ?: 0,
            ).coerceAtLeast(1)

            val textMeasurer = rememberTextMeasurer()
            val density = LocalDensity.current
            val headerFontSp = (baseSize - 1.5f).coerceAtLeast(11f).sp
            val bodyFontSp = (baseSize - 2f).coerceAtLeast(11f).sp
            val headerMeasureStyle = remember(headerFontSp) {
                TextStyle(
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = headerFontSp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            val bodyMeasureStyle = remember(bodyFontSp) {
                TextStyle(
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = bodyFontSp,
                )
            }
            // Compute uniform per-column widths across header + all rows efficiently:
            // Since table cells use monospace JetBrains Mono, compute width using sample character measurement
            // rather than invoking heavy text layout passes on hundreds of cells during composition.
            // Key on the cell content rather than the block instance: the parser creates new
            // block objects on every reparse, but unchanged cell lists compare equal, so the
            // width scan is reused instead of rescanning every row on each edit.
            val colWidths = remember(
                block.rawHeaders,
                block.rawRows,
                colCount,
                headerMeasureStyle,
                bodyMeasureStyle,
                density,
            ) {
                val minW = 92.dp
                val maxW = 220.dp
                val cellPadDp = 26.dp
                val sampleCharPx = maxOf(
                    textMeasurer.measure("M", headerMeasureStyle).size.width,
                    textMeasurer.measure("M", bodyMeasureStyle).size.width,
                    1
                )
                List(colCount) { c ->
                    var maxChars = 0
                    val rawHeader = block.rawHeaders.getOrNull(c)
                        ?: plainTextOfInlines(block.headers.getOrNull(c).orEmpty())
                    if (rawHeader.isNotEmpty()) {
                        maxChars = maxOf(maxChars, rawHeader.length)
                    }
                    block.rows.forEachIndexed { rIdx, rowCells ->
                        val rawCell = block.rawRows.getOrNull(rIdx)?.getOrNull(c)
                            ?: plainTextOfInlines(rowCells.getOrNull(c).orEmpty())
                        if (rawCell.isNotEmpty()) {
                            maxChars = maxOf(maxChars, rawCell.length)
                        }
                    }
                    val widestPx = maxChars * sampleCharPx
                    val neededDp = with(density) { widestPx.toDp() } + cellPadDp
                    neededDp.coerceIn(minW, maxW)
                }
            }
            val totalTableWidth = remember(colWidths) {
                colWidths.fold(0.dp) { acc, w -> acc + w }
            }

            EditablePreviewWrapper(
                label = "TABLE (${block.rows.size}×$colCount)",
                canEdit = onEditBlock != null,
                onEdit = { onEditBlock?.invoke(block) },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                ) {
                    Column(
                        modifier = Modifier
                            .width(totalTableWidth)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                            .clip(RoundedCornerShape(6.dp)),
                    ) {
                        // Header row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min)
                                .background(Color(0xFF141414))
                                .drawBehind {
                                    // Separators are drawn in the draw phase on the Row, using its final
                                    // height, so they follow tall cells without extra layout nodes.
                                    var x = 0f
                                    for (c in 0 until colCount - 1) {
                                        x += colWidths[c].toPx()
                                        drawLine(
                                            color = Color(0xFF262626),
                                            start = Offset(x, 0f),
                                            end = Offset(x, size.height),
                                            strokeWidth = 1.dp.toPx(),
                                        )
                                    }
                                },
                        ) {
                            for (c in 0 until colCount) {
                                val cellInlines = block.headers.getOrNull(c).orEmpty()
                                val cellText = rememberInlineAnnotatedString(
                                    inlines = cellInlines,
                                    searchQuery = searchQuery,
                                    onLinkClick = { url -> runCatching { uriHandler.openUri(url) } },
                                    onWikiLinkClick = onWikiLinkClick,
                                    onCitationClick = onCitationClick,
                                    onTagClick = onTagClick,
                                )
                                Box(
                                    modifier = Modifier
                                        .width(colWidths[c])
                                        .fillMaxHeight()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = cellText,
                                        softWrap = true,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontFamily = JetBrainsMonoFamily,
                                            fontSize = headerFontSp,
                                            fontWeight = FontWeight.SemiBold,
                                            textAlign = TextAlign.Center,
                                        ),
                                        color = Color.White,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }

                        block.rows.forEachIndexed { rIdx, rowCells ->
                            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(IntrinsicSize.Min)
                                    .background(
                                        if (rIdx % 2 == 0) Color.Black else Color(0xFF080808)
                                    )
                                    .drawBehind {
                                        var x = 0f
                                        for (c in 0 until colCount - 1) {
                                            x += colWidths[c].toPx()
                                            drawLine(
                                                color = Color(0xFF1E1E1E),
                                                start = Offset(x, 0f),
                                                end = Offset(x, size.height),
                                                strokeWidth = 1.dp.toPx(),
                                            )
                                        }
                                    },
                            ) {
                                for (c in 0 until colCount) {
                                    val cellInlines = rowCells.getOrNull(c).orEmpty()
                                    val cellText = rememberInlineAnnotatedString(
                                        inlines = cellInlines,
                                        searchQuery = searchQuery,
                                        onLinkClick = { url -> runCatching { uriHandler.openUri(url) } },
                                        onWikiLinkClick = onWikiLinkClick,
                                        onCitationClick = onCitationClick,
                                        onTagClick = onTagClick,
                                    )
                                    Box(
                                        modifier = Modifier
                                            .width(colWidths[c])
                                            .fillMaxHeight()
                                            .padding(horizontal = 12.dp, vertical = 9.dp),
                                        contentAlignment = Alignment.CenterStart,
                                    ) {
                                        Text(
                                            text = cellText,
                                            softWrap = true,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontFamily = JetBrainsMonoFamily,
                                                fontSize = bodyFontSp,
                                                textAlign = TextAlign.Start,
                                            ),
                                            color = Color(0xFFD4D4D4),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        is MarkdownBlock.Blockquote -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .background(Color(0xFF0A0A0A), RoundedCornerShape(4.dp))
                    .padding(vertical = 8.dp, horizontal = 10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(Color.White)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    block.blocks.forEach { inner ->
                        MarkdownBlockView(
                            block = inner,
                            preferences = preferences,
                            searchQuery = searchQuery,
                            resolveImageFile = resolveImageFile,
                            onToggleTask = onToggleTask,
                            onWikiLinkClick = onWikiLinkClick,
                            onTagClick = onTagClick,
                            onCitationClick = onCitationClick,
                            onEditBlock = onEditBlock,
                        )
                    }
                }
            }
        }

        is MarkdownBlock.BulletList -> {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                block.items.forEach { itemInlines ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = baseSize.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 10.dp),
                        )
                        val text = rememberInlineAnnotatedString(
                            inlines = itemInlines,
                            searchQuery = searchQuery,
                            onLinkClick = { url -> runCatching { uriHandler.openUri(url) } },
                            onWikiLinkClick = onWikiLinkClick,
                            onCitationClick = onCitationClick,
                            onTagClick = onTagClick,
                        )
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = baseSize.sp,
                                lineHeight = lineHeightSp,
                            ),
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                }
            }
        }

        is MarkdownBlock.OrderedList -> {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                block.items.forEachIndexed { idx, itemInlines ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "${block.startNumber + idx}.",
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = (baseSize - 1f).sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 10.dp),
                        )
                        val text = rememberInlineAnnotatedString(
                            inlines = itemInlines,
                            searchQuery = searchQuery,
                            onLinkClick = { url -> runCatching { uriHandler.openUri(url) } },
                            onWikiLinkClick = onWikiLinkClick,
                            onCitationClick = onCitationClick,
                            onTagClick = onTagClick,
                        )
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = baseSize.sp,
                                lineHeight = lineHeightSp,
                            ),
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                }
            }
        }

        is MarkdownBlock.TaskList -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                block.items.forEach { task ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .clickable { onToggleTask(task.sourceLineIndex) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (task.checked) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                            contentDescription = if (task.checked) "Completed task" else "Incomplete task",
                            tint = if (task.checked) Color.White else Color(0xFFA3A3A3),
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        val text = rememberInlineAnnotatedString(
                            inlines = task.inlines,
                            searchQuery = searchQuery,
                            onLinkClick = { url -> runCatching { uriHandler.openUri(url) } },
                            onWikiLinkClick = onWikiLinkClick,
                            onCitationClick = onCitationClick,
                            onTagClick = onTagClick,
                        )
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = baseSize.sp,
                                lineHeight = lineHeightSp,
                                textDecoration = if (task.checked) TextDecoration.LineThrough else TextDecoration.None,
                            ),
                            color = if (task.checked) Color(0xFF737373) else MaterialTheme.colorScheme.onBackground,
                        )
                    }
                }
            }
        }

        is MarkdownBlock.ImageBlock -> {
            MarkdownLocalImage(
                uri = block.uri,
                alt = block.alt,
                resolveImageFile = resolveImageFile,
            )
        }

        is MarkdownBlock.HorizontalRule -> {
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }

        is MarkdownBlock.CitationDefinition -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0A0A0A), RoundedCornerShape(4.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = "[${block.id}]",
                    style = TextStyle(
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = (baseSize - 2f).coerceAtLeast(11f).sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = Color.White,
                    modifier = Modifier.padding(end = 8.dp),
                )
                val text = rememberInlineAnnotatedString(
                    inlines = block.inlines,
                    searchQuery = searchQuery,
                    onLinkClick = { url -> runCatching { uriHandler.openUri(url) } },
                    onWikiLinkClick = onWikiLinkClick,
                    onCitationClick = onCitationClick,
                    onTagClick = onTagClick,
                )
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = (baseSize - 1f).sp),
                    color = Color(0xFFD4D4D4),
                )
            }
        }
    }
}

@Composable
private fun EditablePreviewWrapper(
    label: String,
    canEdit: Boolean,
    onEdit: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (!canEdit) {
        content()
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                color = Color(0xFF737373),
            )
            Row(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .background(Color(0xFF141414), RoundedCornerShape(4.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                    .clickable(onClick = onEdit)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = "Edit $label",
                    tint = Color.White,
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    text = "Edit",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }
        }
        content()
    }
}

@Composable
private fun MarkdownLocalImage(
    uri: String,
    alt: String,
    resolveImageFile: (String) -> File?,
) {
    val context = LocalContext.current
    var showFullscreen by remember { mutableStateOf(false) }
    var zoomScale by remember { mutableStateOf(1f) }
    var panX by remember { mutableStateOf(0f) }
    var panY by remember { mutableStateOf(0f) }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = uri) {
        value = withContext(Dispatchers.IO) {
            DecodedBitmapDiskCache.get(uri)?.asImageBitmap() ?: runCatching {
                val file = resolveImageFile(uri)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                when {
                    file != null -> BitmapFactory.decodeFile(file.absolutePath, bounds)
                    uri.startsWith("content://") -> {
                        context.contentResolver.openInputStream(Uri.parse(uri))?.use {
                            BitmapFactory.decodeStream(it, null, bounds)
                        }
                    }
                }
                val sampleSize = NotesRepository.calculateInSampleSize(bounds.outWidth, bounds.outHeight, 1440, 1440)
                val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                val decoded = when {
                    file != null -> BitmapFactory.decodeFile(file.absolutePath, opts)
                    uri.startsWith("content://") -> {
                        context.contentResolver.openInputStream(Uri.parse(uri))?.use {
                            BitmapFactory.decodeStream(it, null, opts)
                        }
                    }
                    else -> null
                }
                decoded?.also { DecodedBitmapDiskCache.put(uri, it) }?.asImageBitmap()
            }.getOrNull()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF0A0A0A)),
    ) {
        val loaded = bitmap
        if (loaded != null) {
            Image(
                bitmap = loaded,
                contentDescription = alt.ifBlank { "Embedded note image" },
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .clickable {
                        zoomScale = 1f
                        panX = 0f
                        panY = 0f
                        showFullscreen = true
                    },
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.BrokenImage,
                    contentDescription = "Image unavailable",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = alt.ifBlank { uri },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (alt.isNotBlank()) {
            Text(
                text = alt,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }

    val fullImage = bitmap
    if (showFullscreen && fullImage != null) {
        Dialog(
            onDismissRequest = { showFullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                Image(
                    bitmap = fullImage,
                    contentDescription = alt.ifBlank { "Embedded note image" },
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = zoomScale,
                            scaleY = zoomScale,
                            translationX = panX,
                            translationY = panY,
                        )
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                zoomScale = (zoomScale * zoom).coerceIn(1f, 8f)
                                if (zoomScale == 1f) {
                                    panX = 0f
                                    panY = 0f
                                } else {
                                    panX += pan.x
                                    panY += pan.y
                                }
                            }
                        },
                )
                Row(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Close",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                        modifier = Modifier
                            .clickable {
                                showFullscreen = false
                                zoomScale = 1f
                                panX = 0f
                                panY = 0f
                            }
                            .padding(10.dp),
                    )
                    Text(
                        text = "Reset",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                        modifier = Modifier
                            .clickable {
                                zoomScale = 1f
                                panX = 0f
                                panY = 0f
                            }
                            .padding(10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberInlineAnnotatedString(
    inlines: List<MarkdownInline>,
    searchQuery: String,
    onLinkClick: (String) -> Unit,
    onWikiLinkClick: (String) -> Unit,
    onCitationClick: (String) -> Unit,
    onTagClick: (String) -> Unit,
): AnnotatedString {
    return remember(inlines, searchQuery) {
        val base = buildAnnotatedString {
            appendInlines(
                inlines = inlines,
                onLinkClick = onLinkClick,
                onWikiLinkClick = onWikiLinkClick,
                onCitationClick = onCitationClick,
                onTagClick = onTagClick,
            )
        }
        applySearchHighlight(base, searchQuery)
    }
}

private fun AnnotatedString.Builder.appendInlines(
    inlines: List<MarkdownInline>,
    onLinkClick: (String) -> Unit,
    onWikiLinkClick: (String) -> Unit,
    onCitationClick: (String) -> Unit,
    onTagClick: (String) -> Unit,
) {
    inlines.forEach { inline ->
        when (inline) {
            is MarkdownInline.PlainText -> append(inline.text)
            is MarkdownInline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                appendInlines(inline.children, onLinkClick, onWikiLinkClick, onCitationClick, onTagClick)
            }
            is MarkdownInline.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                appendInlines(inline.children, onLinkClick, onWikiLinkClick, onCitationClick, onTagClick)
            }
            is MarkdownInline.BoldItalic -> withStyle(
                SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
            ) {
                appendInlines(inline.children, onLinkClick, onWikiLinkClick, onCitationClick, onTagClick)
            }
            is MarkdownInline.Strikethrough -> withStyle(
                SpanStyle(textDecoration = TextDecoration.LineThrough, color = Color(0xFF737373))
            ) {
                appendInlines(inline.children, onLinkClick, onWikiLinkClick, onCitationClick, onTagClick)
            }
            is MarkdownInline.Highlight -> withStyle(
                SpanStyle(background = Color(0xFFE5E5E5), color = Color.Black, fontWeight = FontWeight.Medium)
            ) {
                appendInlines(inline.children, onLinkClick, onWikiLinkClick, onCitationClick, onTagClick)
            }
            is MarkdownInline.InlineCode -> withStyle(
                SpanStyle(
                    fontFamily = JetBrainsMonoFamily,
                    background = Color(0xFF1A1A1A),
                    color = Color(0xFFF5F5F5),
                )
            ) {
                append(" ${inline.code} ")
            }
            is MarkdownInline.InlineLatex -> withStyle(
                SpanStyle(
                    fontFamily = JetBrainsMonoFamily,
                    background = Color(0xFF141414),
                    color = Color.White,
                    fontStyle = FontStyle.Italic,
                )
            ) {
                append(" ${LatexEngine.toReadableString(inline.expression)} ")
            }
            is MarkdownInline.Link -> {
                pushLink(
                    LinkAnnotation.Clickable(
                        tag = "url:${inline.url}",
                        styles = TextLinkStyles(
                            style = SpanStyle(
                                color = Color.White,
                                textDecoration = TextDecoration.Underline,
                                fontWeight = FontWeight.Medium,
                            )
                        ),
                        linkInteractionListener = { onLinkClick(inline.url) },
                    )
                )
                append(inline.label)
                pop()
            }
            is MarkdownInline.WikiLink -> {
                pushLink(
                    LinkAnnotation.Clickable(
                        tag = "wiki:${inline.target}",
                        styles = TextLinkStyles(
                            style = SpanStyle(
                                color = Color.White,
                                background = Color(0xFF1C1C1C),
                                textDecoration = TextDecoration.Underline,
                                fontWeight = FontWeight.SemiBold,
                            )
                        ),
                        linkInteractionListener = { onWikiLinkClick(inline.target) },
                    )
                )
                append("[[${inline.display}]]")
                pop()
            }
            is MarkdownInline.CitationRef -> {
                pushLink(
                    LinkAnnotation.Clickable(
                        tag = "cite:${inline.id}",
                        styles = TextLinkStyles(
                            style = SpanStyle(
                                fontFamily = JetBrainsMonoFamily,
                                color = Color.White,
                                baselineShift = BaselineShift.Superscript,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        ),
                        linkInteractionListener = { onCitationClick(inline.id) },
                    )
                )
                append("[${inline.id}]")
                pop()
            }
            is MarkdownInline.TagToken -> {
                pushLink(
                    LinkAnnotation.Clickable(
                        tag = "tag:${inline.tag}",
                        styles = TextLinkStyles(
                            style = SpanStyle(
                                fontFamily = JetBrainsMonoFamily,
                                color = Color(0xFFD4D4D4),
                                background = Color(0xFF1A1A1A),
                            )
                        ),
                        linkInteractionListener = { onTagClick(inline.tag.removePrefix("#")) },
                    )
                )
                append(" ${inline.tag} ")
                pop()
            }
            is MarkdownInline.InlineImage -> {
                withStyle(SpanStyle(fontFamily = JetBrainsMonoFamily, color = Color(0xFFA3A3A3))) {
                    append("[image: ${inline.alt.ifBlank { inline.uri }}]")
                }
            }
        }
    }
}

private fun plainTextOfInlines(inlines: List<MarkdownInline>): String = buildString {
    inlines.forEach { inline ->
        when (inline) {
            is MarkdownInline.PlainText -> append(inline.text)
            is MarkdownInline.Bold -> append(plainTextOfInlines(inline.children))
            is MarkdownInline.Italic -> append(plainTextOfInlines(inline.children))
            is MarkdownInline.BoldItalic -> append(plainTextOfInlines(inline.children))
            is MarkdownInline.Strikethrough -> append(plainTextOfInlines(inline.children))
            is MarkdownInline.Highlight -> append(plainTextOfInlines(inline.children))
            is MarkdownInline.InlineCode -> append(inline.code)
            is MarkdownInline.InlineLatex -> append(LatexEngine.toReadableString(inline.expression))
            is MarkdownInline.Link -> append(inline.label)
            is MarkdownInline.WikiLink -> append("[[${inline.display}]]")
            is MarkdownInline.CitationRef -> append("[${inline.id}]")
            is MarkdownInline.TagToken -> append(inline.tag)
            is MarkdownInline.InlineImage -> append(inline.alt.ifBlank { inline.uri })
        }
    }
}

@Composable
fun rememberSearchHighlightedPlainText(text: String, query: String): AnnotatedString {
    return remember(text, query) {
        applySearchHighlight(AnnotatedString(text), query)
    }
}

private val SEARCH_HIGHLIGHT_STYLE = SpanStyle(
    background = Color.White,
    color = Color.Black,
    fontWeight = FontWeight.Bold,
)

private val SEARCH_INACTIVE_STYLE = SpanStyle(
    background = Color(0xFF525252),
    color = Color.White,
    fontWeight = FontWeight.Bold,
)

private val SYNTAX_HEADING_STYLE = SpanStyle(color = Color.White, fontWeight = FontWeight.Bold)
private val SYNTAX_CODE_STYLE = SpanStyle(background = Color(0xFF1A1A1A), color = Color(0xFFE5E5E5))
private val SYNTAX_LATEX_STYLE = SpanStyle(
    background = Color(0xFF141414),
    color = Color(0xFFD4D4D4),
    fontStyle = FontStyle.Italic,
)
private val SYNTAX_LINK_STYLE = SpanStyle(
    color = Color.White,
    textDecoration = TextDecoration.Underline,
)

fun applySearchHighlight(source: AnnotatedString, query: String): AnnotatedString {
    val q = query.trim()
    if (q.isEmpty()) return source
    val raw = source.text
    val builder = AnnotatedString.Builder(source)
    var index = raw.indexOf(q, startIndex = 0, ignoreCase = true)
    val step = q.length.coerceAtLeast(1)
    while (index >= 0) {
        builder.addStyle(
            style = SEARCH_HIGHLIGHT_STYLE,
            start = index,
            end = index + q.length,
        )
        index = raw.indexOf(q, startIndex = index + step, ignoreCase = true)
    }
    return builder.toAnnotatedString()
}

class MarkdownSyntaxVisualTransformation(
    private val syntaxSpans: List<SyntaxSpan>,
    private val searchQuery: String,
    private val activeMatchStart: Int = -1,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val len = raw.length
        val builder = AnnotatedString.Builder(raw)

        syntaxSpans.forEach { span ->
            val start = span.start.coerceIn(0, len)
            val end = span.end.coerceIn(start, len)
            if (start < end) {
                val style = when (span.type) {
                    SyntaxTokenType.HEADING -> SYNTAX_HEADING_STYLE
                    SyntaxTokenType.CODE -> SYNTAX_CODE_STYLE
                    SyntaxTokenType.LATEX -> SYNTAX_LATEX_STYLE
                    SyntaxTokenType.LINK -> SYNTAX_LINK_STYLE
                }
                builder.addStyle(style, start, end)
            }
        }

        // Search highlights
        val q = searchQuery.trim()
        if (q.isNotEmpty()) {
            var idx = raw.indexOf(q, 0, ignoreCase = true)
            val step = q.length.coerceAtLeast(1)
            while (idx >= 0) {
                val isActive = idx == activeMatchStart
                builder.addStyle(
                    if (isActive) SEARCH_HIGHLIGHT_STYLE else SEARCH_INACTIVE_STYLE,
                    idx,
                    (idx + q.length).coerceAtMost(len),
                )
                idx = raw.indexOf(q, idx + step, ignoreCase = true)
            }
        }

        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}
