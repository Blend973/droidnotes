package com.droidnotes.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.droidnotes.markdown.ChartCanvasView
import com.droidnotes.markdown.ChartPoint
import com.droidnotes.markdown.ChartSpec
import com.droidnotes.markdown.ChartType
import com.droidnotes.markdown.MarkdownBlock
import com.droidnotes.markdown.MarkdownParser
import com.droidnotes.markdown.TableAlign
import com.droidnotes.ui.components.trackFocusBounds
import com.droidnotes.ui.theme.JetBrainsMonoFamily

@Composable
fun BlockScopeEditorDialog(
    block: MarkdownBlock,
    onDismiss: () -> Unit,
    onApply: (startLineIndex: Int, endLineIndex: Int, newBlockMarkdown: String) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            color = Color(0xFF0A0A0A),
            contentColor = Color.White,
            border = BorderStroke(1.dp, Color.White),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp)
                .widthIn(max = 560.dp)
                .heightIn(max = 660.dp),
        ) {
            when (block) {
                is MarkdownBlock.TableBlock -> TableScopeEditorContent(
                    block = block,
                    onDismiss = onDismiss,
                    onApply = { md -> onApply(block.lineIndex, block.endLineIndex, md) },
                )
                is MarkdownBlock.ChartBlock -> ChartScopeEditorContent(
                    block = block,
                    onDismiss = onDismiss,
                    onApply = { md -> onApply(block.lineIndex, block.endLineIndex, md) },
                )
                is MarkdownBlock.DiagramBlock -> DiagramScopeEditorContent(
                    block = block,
                    onDismiss = onDismiss,
                    onApply = { md -> onApply(block.lineIndex, block.endLineIndex, md) },
                )
                is MarkdownBlock.LatexBlock -> LatexScopeEditorContent(
                    block = block,
                    onDismiss = onDismiss,
                    onApply = { md -> onApply(block.lineIndex, block.endLineIndex, md) },
                )
                else -> Unit
            }
        }
    }
}

@Composable
internal fun ScopeHeader(
    title: String,
    subtitle: String,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.minimumInteractiveComponentSize(),
        ) {
            Icon(Icons.Outlined.Close, contentDescription = "Close editor", tint = Color.White)
        }
    }
}

@Composable
internal fun ScopeFooter(
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmTag: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = onDismiss,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shape = RoundedCornerShape(6.dp),
        ) {
            Text("Cancel", color = Color(0xFFD4D4D4))
        }
        Spacer(modifier = Modifier.width(8.dp))
        Button(
            onClick = onConfirm,
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.testTag(confirmTag),
        ) {
            Text(confirmLabel)
        }
    }
}

@Composable
private fun TableScopeEditorContent(
    block: MarkdownBlock.TableBlock,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit,
) {
    val initialCols = maxOf(
        block.rawHeaders.size,
        block.headers.size,
        block.alignments.size,
        block.rawRows.maxOfOrNull { it.size } ?: 0,
    ).coerceAtLeast(1)

    val headers = remember(block.lineIndex, block.endLineIndex) {
        mutableStateListOf<String>().apply {
            for (c in 0 until initialCols) {
                add(block.rawHeaders.getOrNull(c) ?: "Column ${c + 1}")
            }
        }
    }
    val alignments = remember(block.lineIndex, block.endLineIndex) {
        mutableStateListOf<TableAlign>().apply {
            for (c in 0 until initialCols) {
                add(block.alignments.getOrNull(c) ?: TableAlign.LEFT)
            }
        }
    }
    val rows = remember(block.lineIndex, block.endLineIndex) {
        mutableStateListOf<List<String>>().apply {
            block.rawRows.forEach { srcRow ->
                add(List(initialCols) { c -> srcRow.getOrNull(c).orEmpty() })
            }
        }
    }

    Column(modifier = Modifier.padding(16.dp)) {
        ScopeHeader(
            title = "Edit Table Scope",
            subtitle = "Lines ${block.lineIndex + 1}–${block.endLineIndex + 1} · ${rows.size} rows × ${headers.size} cols",
            onDismiss = onDismiss,
        )
        Spacer(modifier = Modifier.height(10.dp))

        // Structural controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SmallScopeActionButton("+ Row") {
                rows.add(List(headers.size) { "" })
            }
            SmallScopeActionButton("- Row", enabled = rows.isNotEmpty()) {
                if (rows.isNotEmpty()) rows.removeAt(rows.lastIndex)
            }
            SmallScopeActionButton("+ Col") {
                val newIdx = headers.size + 1
                headers.add("Col $newIdx")
                alignments.add(TableAlign.LEFT)
                for (r in rows.indices) {
                    rows[r] = rows[r] + ""
                }
            }
            SmallScopeActionButton("- Col", enabled = headers.size > 1) {
                if (headers.size > 1) {
                    headers.removeAt(headers.lastIndex)
                    alignments.removeAt(alignments.lastIndex)
                    for (r in rows.indices) {
                        rows[r] = if (rows[r].isNotEmpty()) rows[r].dropLast(1) else rows[r]
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                .horizontalScroll(rememberScrollState())
                .verticalScroll(rememberScrollState()),
        ) {
            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Alignments row
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (c in headers.indices) {
                        val align = alignments[c]
                        Surface(
                            onClick = {
                                alignments[c] = when (align) {
                                    TableAlign.LEFT -> TableAlign.CENTER
                                    TableAlign.CENTER -> TableAlign.RIGHT
                                    TableAlign.RIGHT -> TableAlign.LEFT
                                }
                            },
                            color = Color(0xFF141414),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.width(120.dp),
                        ) {
                            Text(
                                text = "Align: ${align.name}",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                color = Color(0xFFA3A3A3),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                        }
                    }
                }

                // Headers row
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (c in headers.indices) {
                        ScopeCellInput(
                            value = headers[c],
                            onValueChange = { next -> headers[c] = next },
                            placeholder = "Header",
                            isHeader = true,
                            modifier = Modifier
                                .width(120.dp)
                                .testTag("table_header_$c"),
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                // Body rows (immutable row replacement ensures immediate recomposition & save)
                for (rIdx in rows.indices) {
                    val rowSnapshot = rows[rIdx]
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (c in headers.indices) {
                            ScopeCellInput(
                                value = rowSnapshot.getOrNull(c).orEmpty(),
                                onValueChange = { next ->
                                    val latestRow = rows[rIdx]
                                    rows[rIdx] = List(headers.size) { col ->
                                        if (col == c) next else latestRow.getOrNull(col).orEmpty()
                                    }
                                },
                                placeholder = "Cell",
                                isHeader = false,
                                modifier = Modifier
                                    .width(120.dp)
                                    .testTag("table_cell_${rIdx}_$c"),
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
        ScopeFooter(
            confirmLabel = "Apply Table",
            onDismiss = onDismiss,
            onConfirm = {
                onApply(
                    MarkdownParser.serializeTable(
                        headers = headers.toList(),
                        alignments = alignments.toList(),
                        rows = rows.toList(),
                    )
                )
            },
            confirmTag = "scope_apply_table",
        )
    }
}

@Composable
private fun ChartScopeEditorContent(
    block: MarkdownBlock.ChartBlock,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit,
) {
    var chartType by remember(block.lineIndex, block.endLineIndex) { mutableStateOf(block.spec.type) }
    var title by remember(block.lineIndex, block.endLineIndex) { mutableStateOf(block.spec.title) }
    val pointRows = remember(block.lineIndex, block.endLineIndex) {
        mutableStateListOf<Triple<String, String, String>>().apply {
            if (block.spec.points.isEmpty()) {
                add(Triple("Item A", "10", com.droidnotes.markdown.ChartEngine.defaultColorHex(0)))
            } else {
                block.spec.points.forEachIndexed { idx, pt ->
                    val valStr = if (pt.value % 1f == 0f) pt.value.toInt().toString() else pt.value.toString()
                    val colorHex = com.droidnotes.markdown.ChartEngine.normalizeColorHex(pt.colorHex)
                        ?: com.droidnotes.markdown.ChartEngine.defaultColorHex(idx)
                    add(Triple(pt.label, valStr, colorHex))
                }
            }
        }
    }
    var pickingColorForIndex by remember { mutableStateOf<Int?>(null) }

    val liveSpec = remember(chartType, title, pointRows.toList()) {
        val parsedPoints = pointRows.mapNotNull { (lbl, valStr, colorHex) ->
            val num = valStr.trim().toFloatOrNull() ?: return@mapNotNull null
            ChartPoint(
                label = lbl.trim().ifBlank { "Item" },
                value = num,
                xValue = lbl.trim().toFloatOrNull(),
                colorHex = colorHex,
            )
        }
        ChartSpec(type = chartType, title = title, points = parsedPoints)
    }

    Column(modifier = Modifier.padding(16.dp)) {
        ScopeHeader(
            title = "Edit Chart Scope",
            subtitle = "Lines ${block.lineIndex + 1}–${block.endLineIndex + 1}",
            onDismiss = onDismiss,
        )
        Spacer(modifier = Modifier.height(8.dp))

        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ChartCanvasView(spec = liveSpec)

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ChartType.entries.forEach { type ->
                    val selected = chartType == type
                    Surface(
                        onClick = { chartType = type },
                        color = if (selected) Color.White else Color.Black,
                        contentColor = if (selected) Color.Black else Color.White,
                        border = BorderStroke(1.dp, if (selected) Color.White else MaterialTheme.colorScheme.outline),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.minimumInteractiveComponentSize(),
                    ) {
                        Text(
                            text = type.name,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            ScopeCellInput(
                value = title,
                onValueChange = { title = it },
                placeholder = "Chart title (optional)",
                isHeader = false,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "DATA SERIES (TAP COLOR SWATCH TO PICK)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SmallScopeActionButton("+ Add Point") {
                    val nextColor = com.droidnotes.markdown.ChartEngine.defaultColorHex(pointRows.size)
                    pointRows.add(Triple("Label", "25", nextColor))
                }
            }

            pointRows.forEachIndexed { idx, (lbl, valStr, colorHex) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clickable { pickingColorForIndex = idx }
                            .testTag("chart_point_color_$idx"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .background(
                                    com.droidnotes.markdown.ChartEngine.hexToComposeColor(colorHex, idx),
                                    RoundedCornerShape(4.dp),
                                )
                                .border(1.dp, Color.White, RoundedCornerShape(4.dp)),
                        )
                    }
                    ScopeCellInput(
                        value = lbl,
                        onValueChange = { next ->
                            val cur = pointRows[idx]
                            pointRows[idx] = Triple(next, cur.second, cur.third)
                        },
                        placeholder = "Label",
                        isHeader = false,
                        modifier = Modifier.weight(1f),
                    )
                    ScopeCellInput(
                        value = valStr,
                        onValueChange = { next ->
                            val cur = pointRows[idx]
                            pointRows[idx] = Triple(cur.first, next, cur.third)
                        },
                        placeholder = "Value",
                        isHeader = false,
                        modifier = Modifier.width(78.dp),
                    )
                    IconButton(
                        onClick = { if (pointRows.size > 1) pointRows.removeAt(idx) },
                        modifier = Modifier.minimumInteractiveComponentSize(),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteOutline,
                            contentDescription = "Remove data point",
                            tint = if (pointRows.size > 1) Color.White else Color(0xFF404040),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        ScopeFooter(
            confirmLabel = "Apply Chart",
            onDismiss = onDismiss,
            onConfirm = { onApply(MarkdownParser.serializeChart(liveSpec)) },
            confirmTag = "scope_apply_chart",
        )
    }

    val colorIdx = pickingColorForIndex
    if (colorIdx != null && colorIdx in pointRows.indices) {
        val row = pointRows[colorIdx]
        ChartColorPickerDialog(
            initialHex = row.third,
            pointLabel = row.first,
            onDismiss = { pickingColorForIndex = null },
            onColorSelected = { selectedHex ->
                val cur = pointRows[colorIdx]
                pointRows[colorIdx] = Triple(cur.first, cur.second, selectedHex)
                pickingColorForIndex = null
            },
        )
    }
}

@Composable
internal fun ScopeCellInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    isHeader: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(if (isHeader) Color(0xFF141414) else Color.Black, RoundedCornerShape(4.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF525252),
                maxLines = 1,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            cursorBrush = SolidColor(Color.White),
            textStyle = TextStyle(
                color = Color.White,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 12.sp,
                fontWeight = if (isHeader) FontWeight.SemiBold else FontWeight.Normal,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .trackFocusBounds(),
        )
    }
}

@Composable
internal fun SmallScopeActionButton(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        onClick = { if (enabled) onClick() },
        color = Color(0xFF141414),
        border = BorderStroke(1.dp, if (enabled) Color.White else MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier.minimumInteractiveComponentSize(),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) Color.White else Color(0xFF525252),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
        )
    }
}
