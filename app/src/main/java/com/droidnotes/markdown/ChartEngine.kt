package com.droidnotes.markdown

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

object ChartEngine {
    fun parseChart(raw: String): ChartSpec {
        var type = ChartType.BAR
        var title = ""
        val points = mutableListOf<ChartPoint>()
        var inData = false

        for (rawLine in raw.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val lower = line.lowercase()
            when {
                lower.startsWith("type:") -> {
                    val value = line.substringAfter(":").trim().lowercase()
                    type = when (value) {
                        "line" -> ChartType.LINE
                        "pie", "donut" -> ChartType.PIE
                        "scatter" -> ChartType.SCATTER
                        else -> ChartType.BAR
                    }
                    inData = false
                }
                lower.startsWith("title:") -> {
                    title = line.substringAfter(":").trim()
                    inData = false
                }
                lower.startsWith("data:") -> {
                    inData = true
                }
                else -> {
                    val parts = if (line.contains(",")) line.split(",") else line.split(":")
                    if (parts.size >= 2) {
                        val first = parts[0].trim()
                        val second = parts[1].trim().toFloatOrNull()
                        if (second != null) {
                            val rawColor = parts.getOrNull(2)?.trim()
                            points.add(
                                ChartPoint(
                                    label = first,
                                    value = second,
                                    xValue = first.toFloatOrNull(),
                                    colorHex = normalizeColorHex(rawColor),
                                )
                            )
                        } else if (!inData && parts.size == 2) {
                            // Ignore metadata lines
                        }
                    }
                }
            }
        }
        return ChartSpec(type = type, title = title, points = points)
    }

    val DEFAULT_CHART_COLOR_HEXES: List<String> = listOf(
        "#22D3EE",
        "#34D399",
        "#FBBF24",
        "#F87171",
        "#A78BFA",
        "#F472B6",
        "#60A5FA",
        "#A3E635",
        "#FB923C",
        "#E5E5E5",
    )

    fun defaultColorHex(index: Int): String =
        DEFAULT_CHART_COLOR_HEXES[index.mod(DEFAULT_CHART_COLOR_HEXES.size)]

    fun normalizeColorHex(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.trim().removePrefix("#").uppercase()
        if (cleaned.length != 6 || cleaned.any { it !in '0'..'9' && it !in 'A'..'F' }) return null
        return "#$cleaned"
    }

    fun hexToComposeColor(hex: String?, fallbackIndex: Int): Color {
        val valid = normalizeColorHex(hex) ?: defaultColorHex(fallbackIndex)
        val rgb = valid.substring(1).toLongOrNull(16) ?: 0x22D3EEL
        return Color(0xFF000000L or rgb)
    }

    data class DiagramSpec(
        val edges: List<DiagramEdge>,
        val nodePositions: Map<String, Pair<Float, Float>>,
        val standaloneNodes: List<String>,
        val edgeCurveOffsets: Map<Int, Pair<Float, Float>>,
    )

    fun parseDiagramSpec(raw: String): DiagramSpec {
        val edges = mutableListOf<DiagramEdge>()
        val nodePositions = linkedMapOf<String, Pair<Float, Float>>()
        val standaloneNodes = mutableListOf<String>()
        val edgeCurveOffsets = linkedMapOf<Int, Pair<Float, Float>>()

        for (rawLine in raw.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("@pos ", ignoreCase = true) -> {
                    val body = line.drop(5).trim()
                    val eqIdx = body.lastIndexOf('=')
                    if (eqIdx > 0) {
                        val name = body.substring(0, eqIdx).trim()
                        val coords = body.substring(eqIdx + 1).split(",")
                        if (name.isNotEmpty() && coords.size >= 2) {
                            val nx = coords[0].trim().toFloatOrNull()
                            val ny = coords[1].trim().toFloatOrNull()
                            if (nx != null && ny != null) {
                                nodePositions[name] = nx.coerceIn(0.06f, 0.94f) to ny.coerceIn(0.08f, 0.92f)
                            }
                        }
                    }
                }
                line.startsWith("@node ", ignoreCase = true) -> {
                    val name = line.drop(6).trim()
                    if (name.isNotEmpty() && name !in standaloneNodes) {
                        standaloneNodes.add(name)
                    }
                }
                line.startsWith("@edge ", ignoreCase = true) -> {
                    val body = line.drop(6).trim()
                    val eqIdx = body.indexOf('=')
                    if (eqIdx > 0) {
                        val edgeIdx = body.substring(0, eqIdx).trim().toIntOrNull()
                        val coords = body.substring(eqIdx + 1).split(",")
                        if (edgeIdx != null && edgeIdx >= 0 && coords.size >= 2) {
                            val dx = coords[0].trim().toFloatOrNull()
                            val dy = coords[1].trim().toFloatOrNull()
                            if (dx != null && dy != null) {
                                edgeCurveOffsets[edgeIdx] = dx.coerceIn(-0.45f, 0.45f) to dy.coerceIn(-0.45f, 0.45f)
                            }
                        }
                    }
                }
                line.contains("->") -> {
                    val parts = line.split("->", limit = 2)
                    val from = parts[0].trim()
                    val right = parts[1].trim()
                    val (to, label) = if (right.contains(":")) {
                        val rParts = right.split(":", limit = 2)
                        rParts[0].trim() to rParts[1].trim()
                    } else {
                        right to ""
                    }
                    if (from.isNotEmpty() && to.isNotEmpty()) {
                        edges.add(DiagramEdge(from = from, to = to, label = label))
                    }
                }
            }
        }
        return DiagramSpec(
            edges = edges,
            nodePositions = nodePositions,
            standaloneNodes = standaloneNodes,
            edgeCurveOffsets = edgeCurveOffsets,
        )
    }

    fun computeDefaultNormalizedPositions(nodes: List<String>, edgeCount: Int): Map<String, Pair<Float, Float>> {
        val n = nodes.size
        if (n == 0) return emptyMap()
        val result = linkedMapOf<String, Pair<Float, Float>>()
        if (n <= 4 && edgeCount <= n) {
            val stepY = 0.80f / max(1, n)
            nodes.forEachIndexed { idx, name ->
                val nx = if (idx % 2 == 0) 0.34f else 0.66f
                val ny = (0.14f + stepY * (idx + 0.5f)).coerceIn(0.12f, 0.88f)
                result[name] = nx to ny
            }
        } else {
            nodes.forEachIndexed { idx, name ->
                val angle = -PI / 2.0 + (2.0 * PI * idx / n)
                val nx = (0.5f + 0.34f * cos(angle)).toFloat().coerceIn(0.10f, 0.90f)
                val ny = (0.5f + 0.34f * sin(angle)).toFloat().coerceIn(0.12f, 0.88f)
                result[name] = nx to ny
            }
        }
        return result
    }
}



private val CHART_LABEL_STYLE = TextStyle(color = Color(0xFFA3A3A3), fontSize = 10.sp)
private val CHART_VALUE_STYLE = TextStyle(color = Color(0xFFFFFFFF), fontSize = 10.sp, fontWeight = FontWeight.Medium)
private val DIAGRAM_NODE_STYLE = TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
private val DIAGRAM_EDGE_STYLE = TextStyle(color = Color(0xFFA3A3A3), fontSize = 9.sp)

@Composable
fun ChartCanvasView(
    spec: ChartSpec,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val linePath = remember { Path() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF0A0A0A), RoundedCornerShape(6.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
            .padding(14.dp),
    ) {
        if (spec.title.isNotEmpty()) {
            Text(
                text = spec.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.height(10.dp))
        }

        if (spec.points.isEmpty()) {
            Text(
                text = "Chart block has no valid numeric data rows (use: Label, 42).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return
        }

        when (spec.type) {
            ChartType.BAR -> {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                ) {
                    val leftPad = 36.dp.toPx()
                    val bottomPad = 28.dp.toPx()
                    val topPad = 18.dp.toPx()
                    val plotW = (size.width - leftPad - 8.dp.toPx()).coerceAtLeast(10f)
                    val plotH = (size.height - topPad - bottomPad).coerceAtLeast(10f)
                    val maxVal = max(1f, spec.points.maxOf { it.value })

                    // Grid lines
                    for (i in 0..3) {
                        val y = topPad + plotH * (i / 3f)
                        drawLine(
                            color = Color(0xFF222222),
                            start = Offset(leftPad, y),
                            end = Offset(leftPad + plotW, y),
                            strokeWidth = 1.dp.toPx(),
                        )
                        val tick = ((1f - i / 3f) * maxVal).let { if (it >= 10f) it.toInt().toString() else String.format(java.util.Locale.US, "%.1f", it) }
                        val measured = textMeasurer.measure(tick, CHART_LABEL_STYLE)
                        drawText(measured, topLeft = Offset(2.dp.toPx(), y - measured.size.height / 2f))
                    }

                    val count = spec.points.size
                    val slotW = plotW / count
                    val barW = (slotW * 0.56f).coerceAtLeast(6f)
                    spec.points.forEachIndexed { idx, pt ->
                        val ratio = (pt.value / maxVal).coerceIn(0f, 1f)
                        val barH = plotH * ratio
                        val x = leftPad + idx * slotW + (slotW - barW) / 2f
                        val y = topPad + plotH - barH

                        drawRect(
                            color = ChartEngine.hexToComposeColor(pt.colorHex, idx),
                            topLeft = Offset(x, y),
                            size = Size(barW, barH),
                        )

                        val valText = if (pt.value % 1f == 0f) pt.value.toInt().toString() else String.format(java.util.Locale.US, "%.1f", pt.value)
                        val vm = textMeasurer.measure(valText, CHART_VALUE_STYLE)
                        drawText(
                            vm,
                            topLeft = Offset(
                                (x + barW / 2f - vm.size.width / 2f).coerceIn(leftPad, size.width - vm.size.width),
                                (y - vm.size.height - 2.dp.toPx()).coerceAtLeast(0f),
                            )
                        )

                        val lm = textMeasurer.measure(pt.label.take(9), CHART_LABEL_STYLE)
                        drawText(
                            lm,
                            topLeft = Offset(
                                (x + barW / 2f - lm.size.width / 2f).coerceIn(0f, size.width - lm.size.width),
                                topPad + plotH + 6.dp.toPx(),
                            )
                        )
                    }
                }
            }

            ChartType.LINE -> {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                ) {
                    val leftPad = 36.dp.toPx()
                    val bottomPad = 28.dp.toPx()
                    val topPad = 16.dp.toPx()
                    val plotW = (size.width - leftPad - 12.dp.toPx()).coerceAtLeast(10f)
                    val plotH = (size.height - topPad - bottomPad).coerceAtLeast(10f)
                    val minVal = min(0f, spec.points.minOf { it.value })
                    val maxVal = max(minVal + 1f, spec.points.maxOf { it.value })
                    val range = maxVal - minVal

                    for (i in 0..3) {
                        val y = topPad + plotH * (i / 3f)
                        drawLine(
                            color = Color(0xFF222222),
                            start = Offset(leftPad, y),
                            end = Offset(leftPad + plotW, y),
                            strokeWidth = 1.dp.toPx(),
                        )
                        val tickVal = maxVal - (i / 3f) * range
                        val tick = if (tickVal >= 10f) tickVal.toInt().toString() else String.format(java.util.Locale.US, "%.1f", tickVal)
                        val measured = textMeasurer.measure(tick, CHART_LABEL_STYLE)
                        drawText(measured, topLeft = Offset(2.dp.toPx(), y - measured.size.height / 2f))
                    }

                    val stepX = if (spec.points.size > 1) plotW / (spec.points.size - 1) else 0f
                    val coords = spec.points.mapIndexed { idx, pt ->
                        val x = leftPad + idx * stepX
                        val norm = (pt.value - minVal) / range
                        val y = topPad + plotH * (1f - norm)
                        Offset(x, y)
                    }

                    if (coords.size >= 2) {
                        linePath.reset()
                        linePath.moveTo(coords.first().x, coords.first().y)
                        for (i in 1 until coords.size) linePath.lineTo(coords[i].x, coords[i].y)
                        drawPath(linePath, color = Color(0xFF737373), style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round))
                    }

                    coords.forEachIndexed { idx, offset ->
                        val pt = spec.points[idx]
                        val pointColor = ChartEngine.hexToComposeColor(pt.colorHex, idx)
                        drawCircle(Color.Black, radius = 6.dp.toPx(), center = offset)
                        drawCircle(pointColor, radius = 4.5.dp.toPx(), center = offset)
                        val lm = textMeasurer.measure(pt.label.take(8), CHART_LABEL_STYLE)
                        drawText(
                            lm,
                            topLeft = Offset(
                                (offset.x - lm.size.width / 2f).coerceIn(0f, size.width - lm.size.width),
                                topPad + plotH + 6.dp.toPx(),
                            )
                        )
                    }
                }
            }

            ChartType.PIE -> {
                val total = spec.points.sumOf { max(0f, it.value).toDouble() }.toFloat().coerceAtLeast(1f)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Canvas(modifier = Modifier.size(140.dp)) {
                        var startAngle = -90f
                        val diameter = min(size.width, size.height)
                        val strokeW = diameter * 0.28f
                        val arcSize = Size(diameter - strokeW, diameter - strokeW)
                        val topLeft = Offset(strokeW / 2f, strokeW / 2f)

                        spec.points.forEachIndexed { idx, pt ->
                            val sweep = (max(0f, pt.value) / total) * 360f
                            if (sweep > 0.05f) {
                                drawArc(
                                    color = ChartEngine.hexToComposeColor(pt.colorHex, idx),
                                    startAngle = startAngle,
                                    sweepAngle = (sweep - 1.5f).coerceAtLeast(0.5f),
                                    useCenter = false,
                                    topLeft = topLeft,
                                    size = arcSize,
                                    style = Stroke(width = strokeW),
                                )
                            }
                            startAngle += sweep
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        spec.points.forEachIndexed { idx, pt ->
                            val pct = ((max(0f, pt.value) / total) * 100f).toInt()
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(
                                            ChartEngine.hexToComposeColor(pt.colorHex, idx),
                                            RoundedCornerShape(2.dp),
                                        )
                                )
                                Text(
                                    text = "${pt.label} ($pct%)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onBackground,
                                )
                            }
                        }
                    }
                }
            }

            ChartType.SCATTER -> {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                ) {
                    val leftPad = 36.dp.toPx()
                    val bottomPad = 24.dp.toPx()
                    val topPad = 14.dp.toPx()
                    val plotW = (size.width - leftPad - 12.dp.toPx()).coerceAtLeast(10f)
                    val plotH = (size.height - topPad - bottomPad).coerceAtLeast(10f)

                    val xs = spec.points.mapIndexed { idx, p -> p.xValue ?: idx.toFloat() }
                    val minX = xs.minOrNull() ?: 0f
                    val maxX = max(minX + 1f, xs.maxOrNull() ?: 1f)
                    val minY = min(0f, spec.points.minOf { it.value })
                    val maxY = max(minY + 1f, spec.points.maxOf { it.value })

                    drawLine(Color(0xFF333333), Offset(leftPad, topPad), Offset(leftPad, topPad + plotH), 1.dp.toPx())
                    drawLine(Color(0xFF333333), Offset(leftPad, topPad + plotH), Offset(leftPad + plotW, topPad + plotH), 1.dp.toPx())

                    spec.points.forEachIndexed { idx, pt ->
                        val xv = xs[idx]
                        val x = leftPad + ((xv - minX) / (maxX - minX)) * plotW
                        val y = topPad + (1f - (pt.value - minY) / (maxY - minY)) * plotH
                        drawCircle(
                            color = ChartEngine.hexToComposeColor(pt.colorHex, idx),
                            radius = 5.dp.toPx(),
                            center = Offset(x, y),
                        )
                    }

                    val minLabel = textMeasurer.measure(minX.toInt().toString(), CHART_LABEL_STYLE)
                    val maxLabel = textMeasurer.measure(maxX.toInt().toString(), CHART_LABEL_STYLE)
                    drawText(minLabel, topLeft = Offset(leftPad, topPad + plotH + 4.dp.toPx()))
                    drawText(maxLabel, topLeft = Offset(leftPad + plotW - maxLabel.size.width, topPad + plotH + 4.dp.toPx()))
                }
            }
        }
    }
}

@Composable
fun DiagramCanvasView(
    edges: List<DiagramEdge>,
    modifier: Modifier = Modifier,
    nodePositions: Map<String, Pair<Float, Float>> = emptyMap(),
    standaloneNodes: List<String> = emptyList(),
    edgeCurveOffsets: Map<Int, Pair<Float, Float>> = emptyMap(),
) {
    val nodes = remember(edges, standaloneNodes, nodePositions) {
        (edges.flatMap { listOf(it.from, it.to) } + standaloneNodes + nodePositions.keys).distinct()
    }
    val defaults = remember(nodes, edges.size) {
        ChartEngine.computeDefaultNormalizedPositions(nodes, edges.size)
    }
    val textMeasurer = rememberTextMeasurer()
    val edgePath = remember { Path() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF0A0A0A), RoundedCornerShape(6.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
            .padding(14.dp),
    ) {
        if (nodes.isEmpty()) {
            Text(
                text = "Diagram block expects edges like: Source -> Target : Label",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return
        }

        val heightDp = (nodes.size * 58).coerceIn(150, 320).dp
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(heightDp)
        ) {
            val positions = mutableMapOf<String, Offset>()
            nodes.forEach { name ->
                val norm = nodePositions[name] ?: defaults[name] ?: (0.5f to 0.5f)
                positions[name] = Offset(
                    (norm.first * size.width).coerceIn(28.dp.toPx(), (size.width - 28.dp.toPx()).coerceAtLeast(28.dp.toPx())),
                    (norm.second * size.height).coerceIn(18.dp.toPx(), (size.height - 18.dp.toPx()).coerceAtLeast(18.dp.toPx())),
                )
            }

            // Draw edges first
            edges.forEachIndexed { edgeIdx, edge ->
                val p1 = positions[edge.from] ?: return@forEachIndexed
                val p2 = positions[edge.to] ?: return@forEachIndexed
                val curveOff = edgeCurveOffsets[edgeIdx]
                val midBase = Offset((p1.x + p2.x) / 2f, (p1.y + p2.y) / 2f)
                val control = if (curveOff != null && (curveOff.first != 0f || curveOff.second != 0f)) {
                    Offset(midBase.x + curveOff.first * size.width, midBase.y + curveOff.second * size.height)
                } else {
                    midBase
                }

                val startAngle = atan2((control.y - p1.y).toDouble(), (control.x - p1.x).toDouble())
                val endAngle = atan2((p2.y - control.y).toDouble(), (p2.x - control.x).toDouble())
                val inset = 22.dp.toPx()
                val start = Offset(
                    p1.x + (inset * cos(startAngle)).toFloat(),
                    p1.y + (inset * sin(startAngle)).toFloat(),
                )
                val end = Offset(
                    p2.x - (inset * cos(endAngle)).toFloat(),
                    p2.y - (inset * sin(endAngle)).toFloat(),
                )

                edgePath.reset()
                edgePath.moveTo(start.x, start.y)
                edgePath.quadraticTo(control.x, control.y, end.x, end.y)
                drawPath(edgePath, color = Color(0xFF737373), style = Stroke(width = 1.5.dp.toPx()))

                // Arrowhead aligned with end tangent
                val arrowLen = 7.dp.toPx()
                val a1 = endAngle + Math.toRadians(150.0)
                val a2 = endAngle - Math.toRadians(150.0)
                drawLine(
                    Color(0xFFCCCCCC),
                    end,
                    Offset(end.x + (arrowLen * cos(a1)).toFloat(), end.y + (arrowLen * sin(a1)).toFloat()),
                    strokeWidth = 1.5.dp.toPx(),
                )
                drawLine(
                    Color(0xFFCCCCCC),
                    end,
                    Offset(end.x + (arrowLen * cos(a2)).toFloat(), end.y + (arrowLen * sin(a2)).toFloat()),
                    strokeWidth = 1.5.dp.toPx(),
                )

                if (edge.label.isNotEmpty()) {
                    val lm = textMeasurer.measure(edge.label.take(18), DIAGRAM_EDGE_STYLE)
                    // Quadratic Bezier point at t=0.5
                    val mid = Offset(
                        0.25f * start.x + 0.5f * control.x + 0.25f * end.x,
                        0.25f * start.y + 0.5f * control.y + 0.25f * end.y,
                    )
                    drawRect(
                        Color(0xFF0A0A0A),
                        topLeft = Offset(mid.x - lm.size.width / 2f - 3f, mid.y - lm.size.height / 2f - 2f),
                        size = Size(lm.size.width + 6f, lm.size.height + 4f),
                    )
                    drawText(lm, topLeft = Offset(mid.x - lm.size.width / 2f, mid.y - lm.size.height / 2f))
                }
            }

            // Draw nodes
            nodes.forEach { name ->
                val center = positions[name] ?: return@forEach
                val tm = textMeasurer.measure(name.take(18), DIAGRAM_NODE_STYLE)
                val boxW = tm.size.width + 20.dp.toPx()
                val boxH = tm.size.height + 12.dp.toPx()
                val topLeft = Offset(
                    (center.x - boxW / 2f).coerceIn(2f, (size.width - boxW - 2f).coerceAtLeast(2f)),
                    (center.y - boxH / 2f).coerceIn(2f, (size.height - boxH - 2f).coerceAtLeast(2f)),
                )
                drawRoundRect(
                    color = Color(0xFF141414),
                    topLeft = topLeft,
                    size = Size(boxW, boxH),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                )
                drawRoundRect(
                    color = Color.White,
                    topLeft = topLeft,
                    size = Size(boxW, boxH),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx()),
                )
                drawText(tm, topLeft = Offset(topLeft.x + 10.dp.toPx(), topLeft.y + 6.dp.toPx()))
            }
        }
    }
}
