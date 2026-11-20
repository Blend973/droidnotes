package com.droidnotes.ui.graph

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidnotes.R
import com.droidnotes.ui.components.NoIndication
import com.droidnotes.ui.components.trackFocusBounds
import com.droidnotes.ui.theme.JetBrainsMonoFamily
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

@Composable
fun GraphScreen(
    viewModel: GraphViewModel,
    onOpenNote: (String) -> Unit,
    onCreateGhostNote: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val densityObj = LocalDensity.current
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var zoom by remember { mutableFloatStateOf(0.8f) }
    var hasAutoFitted by remember { mutableStateOf(false) }
    var showHeader by remember { mutableStateOf(true) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var headerHeightPx by remember { mutableFloatStateOf(0f) }
    var filterInput by remember { mutableStateOf(state.searchFilter) }
    val textMeasurer = rememberTextMeasurer(cacheSize = 512)
    val ghostDashEffect = remember { PathEffect.dashPathEffect(floatArrayOf(5f, 5f)) }

    // Sync local filterInput if the ViewModel's searchFilter is changed externally
    LaunchedEffect(state.searchFilter) {
        if (filterInput != state.searchFilter) {
            filterInput = state.searchFilter
        }
    }

    fun computeFitZoom(nodes: List<GraphNode>, size: IntSize): Float {
        if (nodes.isEmpty() || size.width <= 0 || size.height <= 0) return 0.75f
        val d = densityObj.density
        var maxAbsX = 48f
        var maxAbsY = 48f
        nodes.forEach { n ->
            maxAbsX = max(maxAbsX, abs(n.x) + 44f)
            maxAbsY = max(maxAbsY, abs(n.y) + 34f)
        }
        val availW = (size.width / d - 32f).coerceAtLeast(120f)
        val availH = ((size.height - headerHeightPx) / d - 48f).coerceAtLeast(160f)
        val fitX = availW / (maxAbsX * 2f)
        val fitY = availH / (maxAbsY * 2f)
        return min(fitX, fitY).coerceIn(0.32f, 0.95f)
    }

    LaunchedEffect(state.nodes.size, canvasSize) {
        if (!hasAutoFitted && state.nodes.isNotEmpty() && canvasSize.width > 0 && canvasSize.height > 0) {
            zoom = computeFitZoom(state.nodes, canvasSize)
            panOffset = Offset(0f, headerHeightPx * 0.35f)
            hasAutoFitted = true
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .clipToBounds()
            .onSizeChanged { canvasSize = it },
    ) {
        // Layer 1 (Bottom): Graph Canvas or Empty State — ALWAYS underneath header & overlays
        if (state.nodes.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 120.dp, start = 28.dp, end = 28.dp, bottom = 28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Hub,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(44.dp),
                    )
                    Text(
                        text = stringResource(R.string.graph_empty_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                    )
                    Text(
                        text = stringResource(R.string.graph_empty_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            val nodeMap = remember(state.nodes) { state.nodes.associateBy { it.id } }
            val adjacentNodeIds = remember(state.edges, state.selectedNodeId) {
                val sel = state.selectedNodeId ?: return@remember emptySet<String>()
                buildSet {
                    add(sel)
                    state.edges.forEach { e ->
                        if (e.fromId == sel) add(e.toId)
                        else if (e.toId == sel) add(e.fromId)
                    }
                }
            }
            val query = state.searchFilter.trim().lowercase()
            val latestNodes by rememberUpdatedState(state.nodes)
            val latestPan by rememberUpdatedState(panOffset)
            val latestZoom by rememberUpdatedState(zoom)
            val latestShowHeader by rememberUpdatedState(showHeader)
            val latestHeaderHeight by rememberUpdatedState(headerHeightPx)

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .pointerInput(Unit) {
                        var upwardDragAccum = 0f
                        detectTransformGestures { centroid, pan, gestureZoom, _ ->
                            val currentZoom = zoom
                            val nextZoom = (currentZoom * gestureZoom).coerceIn(0.2f, 4.0f)
                            val zoomRatio = nextZoom / currentZoom
                            val center = Offset(size.width / 2f, size.height / 2f)
                            panOffset = (panOffset + center - centroid) * zoomRatio + centroid - center + pan
                            zoom = nextZoom

                            // Hide header when dragging upward or dragging toward top edge
                            if (latestShowHeader) {
                                if (pan.y < 0f) {
                                    upwardDragAccum += -pan.y
                                } else if (pan.y > 4f) {
                                    upwardDragAccum = 0f
                                }
                                val nearTopEdge = centroid.y <= (latestHeaderHeight + 44.dp.toPx()) && pan.y < -1f
                                if (nearTopEdge || upwardDragAccum >= 22.dp.toPx()) {
                                    showHeader = false
                                    upwardDragAccum = 0f
                                }
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures { tapOffset ->
                            if (!latestShowHeader) {
                                showHeader = true
                            }
                            val cx = size.width / 2f + latestPan.x
                            val cy = size.height / 2f + latestPan.y
                            val scale = density * latestZoom
                            val hitRadius = max(24.dp.toPx(), 16.dp.toPx() * latestZoom)
                            val tapped = latestNodes.firstOrNull { node ->
                                val sx = cx + node.x * scale
                                val sy = cy + node.y * scale
                                hypot(tapOffset.x - sx, tapOffset.y - sy) <= hitRadius
                            }
                            // Tapping the already-selected node deselects it (toggles inspector off)
                            val nextSelection = if (tapped?.id != null && tapped.id == viewModel.uiState.value.selectedNodeId) {
                                null
                            } else {
                                tapped?.id
                            }
                            viewModel.selectNode(nextSelection)
                        }
                    }
                    .testTag("knowledge_graph_canvas"),
            ) {
                val cx = size.width / 2f + panOffset.x
                val cy = size.height / 2f + panOffset.y
                val scale = density * zoom
                val nodeScale = zoom.coerceIn(0.45f, 2.4f)
                // Quantize labelScale into discrete buckets to ensure textMeasurer hits cache during gestures
                val labelScale = (kotlin.math.round(zoom.coerceIn(0.6f, 1.8f) * 6f) / 6f)
                val selectedId = state.selectedNodeId
                val cullMargin = 80.dp.toPx()
                val viewW = size.width
                val viewH = size.height
                val edgeStrokeAdj = 1.8.dp.toPx() * zoom.coerceIn(0.6f, 1.6f)
                val edgeStrokeNorm = 0.9.dp.toPx() * zoom.coerceIn(0.6f, 1.6f)

                // Draw edges (skip edges fully outside viewport)
                state.edges.forEach { edge ->
                    val a = nodeMap[edge.fromId] ?: return@forEach
                    val b = nodeMap[edge.toId] ?: return@forEach
                    val ax = cx + a.x * scale
                    val ay = cy + a.y * scale
                    val bx = cx + b.x * scale
                    val by = cy + b.y * scale
                    if ((ax < -cullMargin && bx < -cullMargin) ||
                        (ax > viewW + cullMargin && bx > viewW + cullMargin) ||
                        (ay < -cullMargin && by < -cullMargin) ||
                        (ay > viewH + cullMargin && by > viewH + cullMargin)
                    ) {
                        return@forEach
                    }
                    val isAdjacent = selectedId != null && (edge.fromId == selectedId || edge.toId == selectedId)
                    val isDimmed = selectedId != null && !isAdjacent
                    drawLine(
                        color = when {
                            isAdjacent -> Color.White
                            isDimmed -> Color(0xFF1F1F1F)
                            else -> Color(0xFF404040)
                        },
                        start = Offset(ax, ay),
                        end = Offset(bx, by),
                        strokeWidth = if (isAdjacent) edgeStrokeAdj else edgeStrokeNorm,
                    )
                }

                // Draw nodes & non-overlapping labels scaled with pinch zoom
                val labelFontSize = (8.8f * labelScale).sp
                val normalLabelStyle = TextStyle(
                    color = Color(0xFFA3A3A3),
                    fontSize = labelFontSize,
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Normal,
                )
                val matchLabelStyle = TextStyle(
                    color = Color.White,
                    fontSize = labelFontSize,
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Normal,
                )
                val selectedLabelStyle = TextStyle(
                    color = Color.White,
                    fontSize = labelFontSize,
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                )
                val labelPadX = 3.dp.toPx()
                val labelPadY = 2.dp.toPx()
                val labelGapY = 2.5.dp.toPx()
                val bookmarkRingGap = 2.5.dp.toPx() * nodeScale
                val oneDpPx = 1.dp.toPx()
                val ghostStroke = Stroke(width = 1.2.dp.toPx(), pathEffect = ghostDashEffect)
                val bookmarkStroke = Stroke(width = oneDpPx)

                val placedLabelBoxes = ArrayList<Rect>(state.nodes.size)
                state.nodes.forEach { node ->
                    val sx = cx + node.x * scale
                    val sy = cy + node.y * scale
                    if (sx < -cullMargin || sx > viewW + cullMargin || sy < -cullMargin || sy > viewH + cullMargin) {
                        return@forEach
                    }
                    val center = Offset(sx, sy)
                    val baseRadiusPx = (3.8.dp.toPx() + node.degree * 0.75.dp.toPx()).coerceAtMost(8.8.dp.toPx())
                    val radius = baseRadiusPx * nodeScale
                    val matchesFilter = query.isNotEmpty() && node.label.contains(query, ignoreCase = true)
                    val isSelected = node.id == selectedId
                    val isDimmed = (query.isNotEmpty() && !matchesFilter) ||
                        (selectedId != null && node.id !in adjacentNodeIds)

                    when (node.type) {
                        GraphNodeType.NOTE -> {
                            drawCircle(
                                color = when {
                                    isSelected || matchesFilter -> Color.White
                                    isDimmed -> Color(0xFF333333)
                                    else -> Color(0xFFE5E5E5)
                                },
                                radius = radius,
                                center = center,
                            )
                            if (node.isBookmarked) {
                                drawCircle(
                                    color = Color.White,
                                    radius = radius + bookmarkRingGap,
                                    center = center,
                                    style = bookmarkStroke,
                                )
                            }
                        }
                        GraphNodeType.GHOST_NOTE -> {
                            drawCircle(
                                color = Color.Black,
                                radius = radius,
                                center = center,
                            )
                            drawCircle(
                                color = if (isDimmed) Color(0xFF333333) else Color(0xFFA3A3A3),
                                radius = radius,
                                center = center,
                                style = ghostStroke,
                            )
                        }
                        GraphNodeType.TAG -> {
                            drawCircle(
                                color = if (isDimmed) Color(0xFF262626) else Color(0xFF666666),
                                radius = radius * 0.82f,
                                center = center,
                            )
                        }
                    }

                    if (!isDimmed || isSelected || matchesFilter) {
                        if (!isSelected && !matchesFilter && placedLabelBoxes.size >= 40) {
                            // Cap background label placement density to prevent O(N^2) collision lags on high node counts
                            return@forEach
                        }
                        val labelStyle = when {
                            isSelected -> selectedLabelStyle
                            matchesFilter -> matchLabelStyle
                            else -> normalLabelStyle
                        }
                        val tm = textMeasurer.measure(node.label.take(22), labelStyle)
                        val left = center.x - tm.size.width / 2f
                        val top = center.y + radius + labelGapY
                        val box = Rect(
                            left = left - labelPadX,
                            top = top - labelPadY,
                            right = left + tm.size.width + labelPadX,
                            bottom = top + tm.size.height + labelPadY,
                        )
                        val overlapsExisting = !isSelected && !matchesFilter && placedLabelBoxes.any { it.overlaps(box) }
                        if (!overlapsExisting) {
                            placedLabelBoxes.add(box)
                            drawText(
                                tm,
                                topLeft = Offset(left, top),
                            )
                        }
                    }
                }
            }

            // Selected Node Inspector Card (overlaps above graph at bottom)
            // Only show for NOTE and GHOST_NOTE; TAG nodes have no actionable note to open.
            val selectedNode = state.nodes.firstOrNull { it.id == state.selectedNodeId }
            if (selectedNode != null && selectedNode.type != GraphNodeType.TAG) {
                val connectedCount = state.edges.count {
                    it.fromId == selectedNode.id || it.toId == selectedNode.id
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(14.dp)
                        .background(Color(0xFF0A0A0A), RoundedCornerShape(8.dp))
                        .border(1.dp, Color.White, RoundedCornerShape(8.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = selectedNode.label,
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                        )
                        Text(
                            text = "${selectedNode.type.name.lowercase().replace('_', ' ')} · $connectedCount connection(s)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    when (selectedNode.type) {
                        GraphNodeType.NOTE -> Button(
                            onClick = { selectedNode.noteId?.let(onOpenNote) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White,
                                contentColor = Color.Black,
                            ),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.testTag("graph_open_note_button"),
                        ) {
                            Text("Open")
                        }
                        GraphNodeType.GHOST_NOTE -> Button(
                            onClick = { onCreateGhostNote(selectedNode.label) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White,
                                contentColor = Color.Black,
                            ),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.testTag("graph_create_ghost_button"),
                        ) {
                            Text("Create")
                        }
                        GraphNodeType.TAG -> Unit
                    }
                }
            }
        }

        // Layer 2 (Top Overlay): Header & Filter Bar — overlaps graph objects underneath; hides on top-edge drag
        if (showHeader) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Color.Black)
                    .clickable(interactionSource = null, indication = NoIndication, onClick = {})
                    .onSizeChanged { headerHeightPx = it.height.toFloat() },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(
                            text = "Knowledge Graph",
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color.White,
                        )
                        Text(
                            text = "${state.totalNotes} notes · ${state.totalConnections} links",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Surface(
                            onClick = viewModel::toggleShowTags,
                            color = if (state.showTags) Color.White else Color(0xFF0A0A0A),
                            contentColor = if (state.showTags) Color.Black else Color.White,
                            border = BorderStroke(
                                1.dp,
                                if (state.showTags) Color.White else MaterialTheme.colorScheme.outline,
                            ),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("graph_toggle_tags"),
                        ) {
                            Text(
                                text = "#Tags",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                        IconButton(
                            onClick = {
                                zoom = computeFitZoom(state.nodes, canvasSize)
                                panOffset = Offset(0f, headerHeightPx * 0.35f)
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("graph_reset_view"),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.CenterFocusStrong,
                                contentDescription = "Reset graph view",
                                tint = Color.White,
                            )
                        }
                    }
                }

                // Filter bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .background(Color(0xFF0A0A0A), RoundedCornerShape(6.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (filterInput.isEmpty()) {
                            Text(
                                text = "Highlight nodes by title or #tag…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        BasicTextField(
                            value = filterInput,
                            onValueChange = { next ->
                                filterInput = next
                                viewModel.onFilterChange(next)
                            },
                            singleLine = true,
                            cursorBrush = SolidColor(Color.White),
                            textStyle = TextStyle(color = Color.White, fontFamily = JetBrainsMonoFamily, fontSize = 13.sp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .trackFocusBounds()
                                .testTag("graph_filter_input"),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}
