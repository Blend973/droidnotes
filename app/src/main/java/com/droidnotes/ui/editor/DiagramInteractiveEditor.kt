package com.droidnotes.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droidnotes.markdown.ChartEngine
import com.droidnotes.markdown.DiagramEdge
import com.droidnotes.markdown.MarkdownBlock
import com.droidnotes.markdown.MarkdownParser
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private enum class DiagramEditMode { ARRANGE, LINK }
private enum class DragTargetType { NONE, NODE, EDGE }

private val EDITOR_NODE_STYLE = TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
private val EDITOR_SELECTED_NODE_STYLE = TextStyle(color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
private val EDITOR_EDGE_STYLE = TextStyle(color = Color(0xFFA3A3A3), fontSize = 9.sp)

@Composable
internal fun DiagramScopeEditorContent(
    block: MarkdownBlock.DiagramBlock,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit,
) {
    val edgeRows = remember(block.lineIndex, block.endLineIndex) {
        mutableStateListOf<DiagramEdge>().apply {
            if (block.edges.isEmpty() && block.standaloneNodes.isEmpty()) {
                add(DiagramEdge("Source", "Target", "flow"))
            } else {
                addAll(block.edges)
            }
        }
    }
    val standaloneNodes = remember(block.lineIndex, block.endLineIndex) {
        mutableStateListOf<String>().apply {
            addAll(block.standaloneNodes)
        }
    }
    val nodePositions = remember(block.lineIndex, block.endLineIndex) {
        mutableStateMapOf<String, Pair<Float, Float>>().apply {
            val initialNodes = (edgeRows.flatMap { listOf(it.from, it.to) } + standaloneNodes + block.nodePositions.keys)
                .filter { it.isNotBlank() }
                .distinct()
            val defaults = ChartEngine.computeDefaultNormalizedPositions(initialNodes, edgeRows.size)
            initialNodes.forEach { n ->
                put(n, block.nodePositions[n] ?: defaults[n] ?: (0.5f to 0.5f))
            }
        }
    }
    val edgeCurveOffsets = remember(block.lineIndex, block.endLineIndex) {
        mutableStateMapOf<Int, Pair<Float, Float>>().apply {
            putAll(block.edgeCurveOffsets)
        }
    }

    var mode by remember { mutableStateOf(DiagramEditMode.ARRANGE) }
    var selectedNode by remember { mutableStateOf<String?>(null) }
    var linkFromPrevious by remember { mutableStateOf(true) }
    var newNodeName by remember { mutableStateOf("") }

    // Drag gesture state
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var dragType by remember { mutableStateOf(DragTargetType.NONE) }
    var draggingNode by remember { mutableStateOf<String?>(null) }
    var draggingEdgeIdx by remember { mutableStateOf(-1) }
    var linkStartNode by remember { mutableStateOf<String?>(null) }
    var linkDragCursor by remember { mutableStateOf<Offset?>(null) }

    val validEdges = edgeRows.filter { it.from.isNotBlank() && it.to.isNotBlank() }
    val allNodes = (validEdges.flatMap { listOf(it.from, it.to) } + standaloneNodes)
        .filter { it.isNotBlank() }
        .distinct()

    // Ensure every node always has a normalized position (never mutate SnapshotStateMap in composition)
    LaunchedEffect(allNodes) {
        if (allNodes.any { !nodePositions.containsKey(it) }) {
            val defaultsNow = ChartEngine.computeDefaultNormalizedPositions(allNodes, validEdges.size)
            allNodes.forEach { n ->
                if (!nodePositions.containsKey(n)) {
                    nodePositions[n] = defaultsNow[n] ?: (0.5f to 0.5f)
                }
            }
        }
    }

    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val edgePath = remember { Path() }
    val linkDashEffect = remember { PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) }

    fun nodePixelCenter(name: String, w: Float, h: Float): Offset {
        val norm = nodePositions[name] ?: (0.5f to 0.5f)
        val padX = with(density) { 28.dp.toPx() }
        val padY = with(density) { 18.dp.toPx() }
        return Offset(
            (norm.first * w).coerceIn(padX, (w - padX).coerceAtLeast(padX)),
            (norm.second * h).coerceIn(padY, (h - padY).coerceAtLeast(padY)),
        )
    }

    fun findNodeAt(tap: Offset, w: Float, h: Float): String? {
        if (w <= 0f || h <= 0f) return null
        val extra = with(density) { 12.dp.toPx() }
        for (name in allNodes.asReversed()) {
            val center = nodePixelCenter(name, w, h)
            val tm = textMeasurer.measure(name.take(18), EDITOR_NODE_STYLE)
            val halfW = (tm.size.width + with(density) { 20.dp.toPx() }) / 2f + extra
            val halfH = (tm.size.height + with(density) { 12.dp.toPx() }) / 2f + extra
            if (tap.x in (center.x - halfW)..(center.x + halfW) &&
                tap.y in (center.y - halfH)..(center.y + halfH)
            ) {
                return name
            }
        }
        return null
    }

    fun findEdgeHandleAt(tap: Offset, w: Float, h: Float): Int {
        if (w <= 0f || h <= 0f) return -1
        val hitRadius = with(density) { 24.dp.toPx() }
        val inset = with(density) { 22.dp.toPx() }
        var bestIdx = -1
        var bestDist = hitRadius
        validEdges.forEachIndexed { idx, edge ->
            val p1 = nodePixelCenter(edge.from, w, h)
            val p2 = nodePixelCenter(edge.to, w, h)
            val curveOff = edgeCurveOffsets[idx]
            val midBase = Offset((p1.x + p2.x) / 2f, (p1.y + p2.y) / 2f)
            val control = if (curveOff != null && (curveOff.first != 0f || curveOff.second != 0f)) {
                Offset(midBase.x + curveOff.first * w, midBase.y + curveOff.second * h)
            } else {
                midBase
            }
            val startAngle = atan2((control.y - p1.y).toDouble(), (control.x - p1.x).toDouble())
            val endAngle = atan2((p2.y - control.y).toDouble(), (p2.x - control.x).toDouble())
            val start = Offset(p1.x + (inset * cos(startAngle)).toFloat(), p1.y + (inset * sin(startAngle)).toFloat())
            val end = Offset(p2.x - (inset * cos(endAngle)).toFloat(), p2.y - (inset * sin(endAngle)).toFloat())
            val mid = Offset(
                0.25f * start.x + 0.5f * control.x + 0.25f * end.x,
                0.25f * start.y + 0.5f * control.y + 0.25f * end.y,
            )
            val d = hypot((tap.x - mid.x).toDouble(), (tap.y - mid.y).toDouble()).toFloat()
            if (d <= bestDist) {
                bestDist = d
                bestIdx = idx
            }
        }
        return bestIdx
    }

    fun addEdgeBetween(from: String, to: String) {
        if (from.isBlank() || to.isBlank() || from == to) return
        val exists = edgeRows.any { it.from == from && it.to == to }
        if (!exists) {
            edgeRows.add(DiagramEdge(from = from, to = to, label = ""))
        }
    }

    fun removeEdgeAt(index: Int) {
        if (index !in edgeRows.indices) return
        val target = edgeRows[index]
        val validIndex = if (target.from.isNotBlank() && target.to.isNotBlank()) {
            validEdges.indexOfFirst { it === target }
        } else -1
        edgeRows.removeAt(index)
        if (validIndex >= 0) {
            val shifted = linkedMapOf<Int, Pair<Float, Float>>()
            edgeCurveOffsets.forEach { (k, v) ->
                when {
                    k < validIndex -> shifted[k] = v
                    k > validIndex -> shifted[k - 1] = v
                }
            }
            edgeCurveOffsets.clear()
            edgeCurveOffsets.putAll(shifted)
        }
    }

    Column(modifier = Modifier.padding(16.dp)) {
        ScopeHeader(
            title = "Edit Diagram Scope",
            subtitle = "Lines ${block.lineIndex + 1}–${block.endLineIndex + 1} · ${allNodes.size} nodes · ${validEdges.size} edges",
            onDismiss = onDismiss,
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Mode bar: Arrange (drag nodes & arrow handles) vs Link (drag or tap between nodes)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DiagramModeButton(
                label = "Arrange Nodes & Arrows",
                selected = mode == DiagramEditMode.ARRANGE,
                onClick = {
                    mode = DiagramEditMode.ARRANGE
                    linkStartNode = null
                    linkDragCursor = null
                },
                modifier = Modifier.weight(1f),
            )
            DiagramModeButton(
                label = "Link Nodes",
                selected = mode == DiagramEditMode.LINK,
                onClick = {
                    mode = DiagramEditMode.LINK
                    linkStartNode = null
                    linkDragCursor = null
                },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Interactive draggable diagram canvas (outside verticalScroll so drag never fights scroll)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(236.dp)
                .background(Color.Black, RoundedCornerShape(6.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                .clipToBounds()
                .onSizeChanged { canvasSize = it }
                .pointerInput(mode, allNodes.size, validEdges.size) {
                    detectTapGestures { tap ->
                        val w = canvasSize.width.toFloat()
                        val h = canvasSize.height.toFloat()
                        val hitNode = findNodeAt(tap, w, h)
                        if (mode == DiagramEditMode.LINK) {
                            if (hitNode != null) {
                                val src = linkStartNode
                                if (src == null) {
                                    linkStartNode = hitNode
                                    selectedNode = hitNode
                                } else if (src == hitNode) {
                                    linkStartNode = null
                                } else {
                                    addEdgeBetween(src, hitNode)
                                    linkStartNode = null
                                    selectedNode = hitNode
                                }
                            } else {
                                linkStartNode = null
                            }
                        } else {
                            selectedNode = hitNode
                        }
                    }
                }
                .pointerInput(mode, allNodes.size, validEdges.size) {
                    detectDragGestures(
                        onDragStart = { startOffset ->
                            val w = canvasSize.width.toFloat()
                            val h = canvasSize.height.toFloat()
                            val hitNode = findNodeAt(startOffset, w, h)
                            if (mode == DiagramEditMode.LINK) {
                                if (hitNode != null) {
                                    linkStartNode = hitNode
                                    linkDragCursor = startOffset
                                    selectedNode = hitNode
                                }
                            } else {
                                if (hitNode != null) {
                                    dragType = DragTargetType.NODE
                                    draggingNode = hitNode
                                    selectedNode = hitNode
                                } else {
                                    val hitEdge = findEdgeHandleAt(startOffset, w, h)
                                    if (hitEdge >= 0) {
                                        dragType = DragTargetType.EDGE
                                        draggingEdgeIdx = hitEdge
                                    } else {
                                        dragType = DragTargetType.NONE
                                    }
                                }
                            }
                        },
                        onDragEnd = {
                            if (mode == DiagramEditMode.LINK) {
                                val src = linkStartNode
                                val endPos = linkDragCursor
                                if (src != null && endPos != null) {
                                    val target = findNodeAt(endPos, canvasSize.width.toFloat(), canvasSize.height.toFloat())
                                    if (target != null && target != src) {
                                        addEdgeBetween(src, target)
                                        selectedNode = target
                                    }
                                }
                                linkStartNode = null
                                linkDragCursor = null
                            }
                            dragType = DragTargetType.NONE
                            draggingNode = null
                            draggingEdgeIdx = -1
                        },
                        onDragCancel = {
                            dragType = DragTargetType.NONE
                            draggingNode = null
                            draggingEdgeIdx = -1
                            linkStartNode = null
                            linkDragCursor = null
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val w = canvasSize.width.toFloat()
                            val h = canvasSize.height.toFloat()
                            if (w <= 0f || h <= 0f) return@detectDragGestures
                            val pos = change.position
                            if (mode == DiagramEditMode.LINK) {
                                if (linkStartNode != null) {
                                    linkDragCursor = pos
                                }
                            } else {
                                when (dragType) {
                                    DragTargetType.NODE -> {
                                        val name = draggingNode ?: return@detectDragGestures
                                        val nx = (pos.x / w).coerceIn(0.08f, 0.92f)
                                        val ny = (pos.y / h).coerceIn(0.10f, 0.90f)
                                        nodePositions[name] = nx to ny
                                    }
                                    DragTargetType.EDGE -> {
                                        val eIdx = draggingEdgeIdx
                                        val edge = validEdges.getOrNull(eIdx) ?: return@detectDragGestures
                                        val p1 = nodePixelCenter(edge.from, w, h)
                                        val p2 = nodePixelCenter(edge.to, w, h)
                                        val midBase = Offset((p1.x + p2.x) / 2f, (p1.y + p2.y) / 2f)
                                        // Make Bézier midpoint pass directly under finger: control = 2*pos - midBase
                                        val desiredCtrlX = 2f * pos.x - midBase.x
                                        val desiredCtrlY = 2f * pos.y - midBase.y
                                        val dx = ((desiredCtrlX - midBase.x) / w).coerceIn(-0.45f, 0.45f)
                                        val dy = ((desiredCtrlY - midBase.y) / h).coerceIn(-0.45f, 0.45f)
                                        edgeCurveOffsets[eIdx] = dx to dy
                                    }
                                    DragTargetType.NONE -> Unit
                                }
                            }
                        },
                    )
                },
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val inset = 22.dp.toPx()
                val handleRadius = 4.5.dp.toPx()

                // Draw edges
                validEdges.forEachIndexed { edgeIdx, edge ->
                    val p1 = nodePixelCenter(edge.from, size.width, size.height)
                    val p2 = nodePixelCenter(edge.to, size.width, size.height)
                    val curveOff = edgeCurveOffsets[edgeIdx]
                    val midBase = Offset((p1.x + p2.x) / 2f, (p1.y + p2.y) / 2f)
                    val control = if (curveOff != null && (curveOff.first != 0f || curveOff.second != 0f)) {
                        Offset(midBase.x + curveOff.first * size.width, midBase.y + curveOff.second * size.height)
                    } else {
                        midBase
                    }

                    val startAngle = atan2((control.y - p1.y).toDouble(), (control.x - p1.x).toDouble())
                    val endAngle = atan2((p2.y - control.y).toDouble(), (p2.x - control.x).toDouble())
                    val start = Offset(p1.x + (inset * cos(startAngle)).toFloat(), p1.y + (inset * sin(startAngle)).toFloat())
                    val end = Offset(p2.x - (inset * cos(endAngle)).toFloat(), p2.y - (inset * sin(endAngle)).toFloat())

                    val isActiveEdge = draggingEdgeIdx == edgeIdx
                    edgePath.reset()
                    edgePath.moveTo(start.x, start.y)
                    edgePath.quadraticTo(control.x, control.y, end.x, end.y)
                    drawPath(
                        edgePath,
                        color = if (isActiveEdge) Color.White else Color(0xFF737373),
                        style = Stroke(width = if (isActiveEdge) 2.dp.toPx() else 1.5.dp.toPx()),
                    )

                    val arrowLen = 7.dp.toPx()
                    val a1 = endAngle + Math.toRadians(150.0)
                    val a2 = endAngle - Math.toRadians(150.0)
                    val arrowColor = if (isActiveEdge) Color.White else Color(0xFFCCCCCC)
                    drawLine(
                        arrowColor,
                        end,
                        Offset(end.x + (arrowLen * cos(a1)).toFloat(), end.y + (arrowLen * sin(a1)).toFloat()),
                        strokeWidth = 1.5.dp.toPx(),
                    )
                    drawLine(
                        arrowColor,
                        end,
                        Offset(end.x + (arrowLen * cos(a2)).toFloat(), end.y + (arrowLen * sin(a2)).toFloat()),
                        strokeWidth = 1.5.dp.toPx(),
                    )

                    val mid = Offset(
                        0.25f * start.x + 0.5f * control.x + 0.25f * end.x,
                        0.25f * start.y + 0.5f * control.y + 0.25f * end.y,
                    )

                    if (edge.label.isNotEmpty()) {
                        val lm = textMeasurer.measure(edge.label.take(16), EDITOR_EDGE_STYLE)
                        drawRect(
                            Color.Black,
                            topLeft = Offset(mid.x - lm.size.width / 2f - 3f, mid.y - lm.size.height - 8f),
                            size = Size(lm.size.width + 6f, lm.size.height + 4f),
                        )
                        drawText(lm, topLeft = Offset(mid.x - lm.size.width / 2f, mid.y - lm.size.height - 6f))
                    }

                    // Draggable arrow bend handle in Arrange mode
                    if (mode == DiagramEditMode.ARRANGE) {
                        drawCircle(
                            color = Color.Black,
                            radius = handleRadius + 1.5.dp.toPx(),
                            center = mid,
                        )
                        drawCircle(
                            color = if (isActiveEdge) Color.White else Color(0xFFA3A3A3),
                            radius = handleRadius,
                            center = mid,
                            style = Stroke(width = 1.3.dp.toPx()),
                        )
                    }
                }

                // Live linking preview line
                val srcName = linkStartNode
                val cursor = linkDragCursor
                if (mode == DiagramEditMode.LINK && srcName != null && cursor != null) {
                    val p1 = nodePixelCenter(srcName, size.width, size.height)
                    drawLine(
                        color = Color.White,
                        start = p1,
                        end = cursor,
                        strokeWidth = 1.8.dp.toPx(),
                        pathEffect = linkDashEffect,
                    )
                }

                // Draw nodes
                allNodes.forEach { name ->
                    val center = nodePixelCenter(name, size.width, size.height)
                    val isSelected = name == selectedNode || name == linkStartNode
                    val style = if (isSelected) EDITOR_SELECTED_NODE_STYLE else EDITOR_NODE_STYLE
                    val tm = textMeasurer.measure(name.take(18), style)
                    val boxW = tm.size.width + 20.dp.toPx()
                    val boxH = tm.size.height + 12.dp.toPx()
                    val topLeft = Offset(
                        (center.x - boxW / 2f).coerceIn(2f, (size.width - boxW - 2f).coerceAtLeast(2f)),
                        (center.y - boxH / 2f).coerceIn(2f, (size.height - boxH - 2f).coerceAtLeast(2f)),
                    )
                    drawRoundRect(
                        color = if (isSelected) Color.White else Color(0xFF141414),
                        topLeft = topLeft,
                        size = Size(boxW, boxH),
                        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                    )
                    drawRoundRect(
                        color = Color.White,
                        topLeft = topLeft,
                        size = Size(boxW, boxH),
                        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                        style = Stroke(width = if (isSelected) 2.dp.toPx() else 1.dp.toPx()),
                    )
                    drawText(tm, topLeft = Offset(topLeft.x + 10.dp.toPx(), topLeft.y + 6.dp.toPx()))
                }
            }

            // Subtle corner hint inside canvas
            Text(
                text = if (mode == DiagramEditMode.ARRANGE) {
                    "Drag nodes or arrow ○ handles"
                } else if (linkStartNode != null) {
                    "Tap or drop on target node from \"$linkStartNode\""
                } else {
                    "Drag or tap source node -> target node"
                },
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF737373),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .background(Color(0xCC000000))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Scrollable controls below the interactive canvas
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Add New Node bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ScopeCellInput(
                    value = newNodeName,
                    onValueChange = { newNodeName = it },
                    placeholder = "New node name",
                    isHeader = false,
                    modifier = Modifier.weight(1f),
                )
                SmallScopeActionButton(
                    label = "+ Node",
                    enabled = newNodeName.trim().isNotEmpty(),
                ) {
                    val trimmed = newNodeName.trim()
                    if (trimmed.isNotEmpty()) {
                        if (trimmed !in allNodes) {
                            standaloneNodes.add(trimmed)
                            val slot = allNodes.size
                            val nx = (0.22f + ((slot * 29) % 60) / 100f).coerceIn(0.14f, 0.86f)
                            val ny = (0.22f + ((slot * 37) % 58) / 100f).coerceIn(0.16f, 0.84f)
                            nodePositions[trimmed] = nx to ny
                        }
                        selectedNode = trimmed
                        newNodeName = ""
                    }
                }
            }

            // Quick link selected/new node with previous/existing nodes
            val activeNode = selectedNode
            if (activeNode != null && activeNode in allNodes) {
                val otherNodes = allNodes.filter { it != activeNode }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF101010), RoundedCornerShape(6.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (linkFromPrevious) {
                                "Link previous -> $activeNode"
                            } else {
                                "Link $activeNode -> node"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SmallScopeActionButton(label = "Flip Dir") {
                                linkFromPrevious = !linkFromPrevious
                            }
                            SmallScopeActionButton(
                                label = "Del Node",
                                enabled = allNodes.size > 1,
                            ) {
                                standaloneNodes.remove(activeNode)
                                nodePositions.remove(activeNode)
                                for (idx in edgeRows.indices.reversed()) {
                                    if (edgeRows[idx].from == activeNode || edgeRows[idx].to == activeNode) {
                                        removeEdgeAt(idx)
                                    }
                                }
                                selectedNode = null
                            }
                        }
                    }

                    if (otherNodes.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            otherNodes.forEach { other ->
                                val chipLabel = if (linkFromPrevious) "$other → $activeNode" else "$activeNode → $other"
                                Surface(
                                    onClick = {
                                        if (linkFromPrevious) {
                                            addEdgeBetween(other, activeNode)
                                        } else {
                                            addEdgeBetween(activeNode, other)
                                        }
                                    },
                                    color = Color.Black,
                                    border = BorderStroke(1.dp, Color.White),
                                    shape = RoundedCornerShape(4.dp),
                                ) {
                                    Text(
                                        text = chipLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Edges header + add edge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "EDGES (FROM → TO : LABEL)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SmallScopeActionButton("+ Add Edge") {
                    val lastNode = allNodes.lastOrNull() ?: "Node"
                    val nextNode = "Node ${allNodes.size + 1}"
                    if (!nodePositions.containsKey(nextNode)) {
                        val slot = allNodes.size
                        val nx = (0.22f + ((slot * 29) % 60) / 100f).coerceIn(0.14f, 0.86f)
                        val ny = (0.22f + ((slot * 37) % 58) / 100f).coerceIn(0.16f, 0.84f)
                        nodePositions[nextNode] = nx to ny
                    }
                    edgeRows.add(DiagramEdge(lastNode, nextNode, ""))
                }
            }

            edgeRows.forEachIndexed { idx, edge ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ScopeCellInput(
                        value = edge.from,
                        onValueChange = { next ->
                            val old = edgeRows[idx].from
                            if (old != next && nodePositions.containsKey(old) && !nodePositions.containsKey(next) && next.isNotBlank()) {
                                nodePositions[next] = nodePositions[old]!!
                            }
                            edgeRows[idx] = edgeRows[idx].copy(from = next)
                        },
                        placeholder = "From",
                        isHeader = false,
                        modifier = Modifier.weight(1f),
                    )
                    ScopeCellInput(
                        value = edge.to,
                        onValueChange = { next ->
                            val old = edgeRows[idx].to
                            if (old != next && nodePositions.containsKey(old) && !nodePositions.containsKey(next) && next.isNotBlank()) {
                                nodePositions[next] = nodePositions[old]!!
                            }
                            edgeRows[idx] = edgeRows[idx].copy(to = next)
                        },
                        placeholder = "To",
                        isHeader = false,
                        modifier = Modifier.weight(1f),
                    )
                    ScopeCellInput(
                        value = edge.label,
                        onValueChange = { next -> edgeRows[idx] = edgeRows[idx].copy(label = next) },
                        placeholder = "Label",
                        isHeader = false,
                        modifier = Modifier.weight(0.85f),
                    )
                    IconButton(
                        onClick = { removeEdgeAt(idx) },
                        modifier = Modifier.minimumInteractiveComponentSize(),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteOutline,
                            contentDescription = "Remove edge",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        ScopeFooter(
            confirmLabel = "Apply Diagram",
            onDismiss = onDismiss,
            onConfirm = {
                val edgeNodes = validEdges.flatMap { listOf(it.from, it.to) }.toSet()
                val keptStandalone = standaloneNodes.filter { it.isNotBlank() && it !in edgeNodes }.distinct()
                val activeNodeSet = edgeNodes + keptStandalone
                val keptPositions = linkedMapOf<String, Pair<Float, Float>>()
                activeNodeSet.forEach { n ->
                    nodePositions[n]?.let { keptPositions[n] = it }
                }
                val keptCurves = linkedMapOf<Int, Pair<Float, Float>>()
                validEdges.indices.forEach { idx ->
                    edgeCurveOffsets[idx]?.let { (dx, dy) ->
                        if (kotlin.math.abs(dx) > 0.004f || kotlin.math.abs(dy) > 0.004f) {
                            keptCurves[idx] = dx to dy
                        }
                    }
                }
                onApply(
                    MarkdownParser.serializeDiagram(
                        edges = validEdges,
                        nodePositions = keptPositions,
                        standaloneNodes = keptStandalone,
                        edgeCurveOffsets = keptCurves,
                    )
                )
            },
            confirmTag = "scope_apply_diagram",
        )
    }
}

@Composable
private fun DiagramModeButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        color = if (selected) Color.White else Color(0xFF141414),
        contentColor = if (selected) Color.Black else Color.White,
        border = BorderStroke(1.dp, if (selected) Color.White else MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(4.dp),
        modifier = modifier.minimumInteractiveComponentSize(),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) Color.Black else Color.White,
                maxLines = 1,
            )
        }
    }
}
