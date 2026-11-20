package com.droidnotes.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.droidnotes.markdown.ChartEngine
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val QUICK_PICKER_SWATCHES = listOf(
    "#22D3EE", "#38BDF8", "#60A5FA", "#818CF8",
    "#A78BFA", "#E879F9", "#F472B6", "#FB7185",
    "#F87171", "#FB923C", "#FBBF24", "#FACC15",
    "#A3E635", "#4ADE80", "#34D399", "#2DD4BF",
    "#FFFFFF", "#D4D4D4", "#A3A3A3", "#737373",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChartColorPickerDialog(
    initialHex: String,
    pointLabel: String,
    onDismiss: () -> Unit,
    onColorSelected: (String) -> Unit,
) {
    val startHex = remember(initialHex) {
        ChartEngine.normalizeColorHex(initialHex) ?: "#22D3EE"
    }
    val initialHsv = remember(startHex) { hexToHsv(startHex) }
    var hue by remember(startHex) { mutableFloatStateOf(initialHsv[0]) }
    var sat by remember(startHex) { mutableFloatStateOf(initialHsv[1]) }
    var value by remember(startHex) { mutableFloatStateOf(initialHsv[2]) }
    var hexInput by remember(startHex) { mutableStateOf(startHex) }

    val currentHex = remember(hue, sat, value) { hsvToHex(hue, sat, value) }
    val currentColor = remember(currentHex) { ChartEngine.hexToComposeColor(currentHex, 0) }
    val pureHueColor = remember(hue) { hsvToComposeColor(hue, 1f, 1f) }

    var svSize by remember { mutableStateOf(IntSize.Zero) }
    var hueSize by remember { mutableStateOf(IntSize.Zero) }

    fun syncFromHsv(h: Float, s: Float, v: Float) {
        hue = h.coerceIn(0f, 360f)
        sat = s.coerceIn(0f, 1f)
        value = v.coerceIn(0f, 1f)
        hexInput = hsvToHex(hue, sat, value)
    }

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
                .padding(horizontal = 20.dp)
                .widthIn(max = 420.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ScopeHeader(
                    title = "Pick Point Color",
                    subtitle = pointLabel.ifBlank { "Data point" },
                    onDismiss = onDismiss,
                )

                // 2D Saturation x Brightness (Value) picker
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(176.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                        .onSizeChanged { svSize = it }
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                val w = svSize.width.toFloat().coerceAtLeast(1f)
                                val h = svSize.height.toFloat().coerceAtLeast(1f)
                                syncFromHsv(hue, offset.x / w, 1f - offset.y / h)
                            }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                val w = svSize.width.toFloat().coerceAtLeast(1f)
                                val h = svSize.height.toFloat().coerceAtLeast(1f)
                                syncFromHsv(hue, change.position.x / w, 1f - change.position.y / h)
                            }
                        },
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(Color.White, pureHueColor),
                            )
                        )
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black),
                            )
                        )
                        val cx = (sat * size.width).coerceIn(0f, size.width)
                        val cy = ((1f - value) * size.height).coerceIn(0f, size.height)
                        drawCircle(
                            color = Color.Black,
                            radius = 8.dp.toPx(),
                            center = Offset(cx, cy),
                            style = Stroke(width = 2.5.dp.toPx()),
                        )
                        drawCircle(
                            color = Color.White,
                            radius = 6.5.dp.toPx(),
                            center = Offset(cx, cy),
                            style = Stroke(width = 1.8.dp.toPx()),
                        )
                    }
                }

                // Hue spectrum bar
                val hueStops = remember {
                    listOf(
                        Color(0xFFFF0000),
                        Color(0xFFFFFF00),
                        Color(0xFF00FF00),
                        Color(0xFF00FFFF),
                        Color(0xFF0000FF),
                        Color(0xFFFF00FF),
                        Color(0xFFFF0000),
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(30.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                        .onSizeChanged { hueSize = it }
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                val w = hueSize.width.toFloat().coerceAtLeast(1f)
                                syncFromHsv((offset.x / w) * 360f, sat, value)
                            }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                val w = hueSize.width.toFloat().coerceAtLeast(1f)
                                syncFromHsv((change.position.x / w) * 360f, sat, value)
                            }
                        },
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawRect(brush = Brush.horizontalGradient(colors = hueStops))
                        val hx = ((hue / 360f) * size.width).coerceIn(3.dp.toPx(), size.width - 3.dp.toPx())
                        drawLine(
                            color = Color.Black,
                            start = Offset(hx, 0f),
                            end = Offset(hx, size.height),
                            strokeWidth = 5.dp.toPx(),
                        )
                        drawLine(
                            color = Color.White,
                            start = Offset(hx, 0f),
                            end = Offset(hx, size.height),
                            strokeWidth = 2.5.dp.toPx(),
                        )
                    }
                }

                // Live preview + editable hex input
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(currentColor, RoundedCornerShape(6.dp))
                            .border(1.dp, Color.White, RoundedCornerShape(6.dp))
                    )
                    ScopeCellInput(
                        value = hexInput,
                        onValueChange = { next ->
                            hexInput = next.take(7)
                            val parsed = ChartEngine.normalizeColorHex(next)
                            if (parsed != null) {
                                val hsv = hexToHsv(parsed)
                                hue = hsv[0]
                                sat = hsv[1]
                                value = hsv[2]
                            }
                        },
                        placeholder = "#22D3EE",
                        isHeader = false,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("chart_color_hex_input"),
                    )
                }

                // Quick color swatches
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    QUICK_PICKER_SWATCHES.forEach { swatchHex ->
                        val isSelected = currentHex.equals(swatchHex, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .background(
                                    ChartEngine.hexToComposeColor(swatchHex, 0),
                                    RoundedCornerShape(4.dp),
                                )
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) Color.White else Color(0xFF404040),
                                    shape = RoundedCornerShape(4.dp),
                                )
                                .clickable {
                                    val hsv = hexToHsv(swatchHex)
                                    syncFromHsv(hsv[0], hsv[1], hsv[2])
                                },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))
                ScopeFooter(
                    confirmLabel = "Select Color",
                    onDismiss = onDismiss,
                    onConfirm = { onColorSelected(currentHex) },
                    confirmTag = "chart_color_confirm",
                )
            }
        }
    }
}

private fun hsvToRgb(h: Float, s: Float, v: Float): Triple<Int, Int, Int> {
    val hue = ((h % 360f) + 360f) % 360f
    val c = v * s
    val x = c * (1f - abs((hue / 60f) % 2f - 1f))
    val m = v - c
    val (r1, g1, b1) = when {
        hue < 60f -> Triple(c, x, 0f)
        hue < 120f -> Triple(x, c, 0f)
        hue < 180f -> Triple(0f, c, x)
        hue < 240f -> Triple(0f, x, c)
        hue < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Triple(
        ((r1 + m) * 255f).roundToInt().coerceIn(0, 255),
        ((g1 + m) * 255f).roundToInt().coerceIn(0, 255),
        ((b1 + m) * 255f).roundToInt().coerceIn(0, 255),
    )
}

private fun hsvToComposeColor(h: Float, s: Float, v: Float): Color {
    val (r, g, b) = hsvToRgb(h, s, v)
    return Color(red = r, green = g, blue = b)
}

private fun hsvToHex(h: Float, s: Float, v: Float): String {
    val (r, g, b) = hsvToRgb(h, s, v)
    return String.format("#%02X%02X%02X", r, g, b)
}

private fun hexToHsv(hex: String): FloatArray {
    val clean = ChartEngine.normalizeColorHex(hex)?.substring(1) ?: "22D3EE"
    val rgb = clean.toIntOrNull(16) ?: 0x22D3EE
    val r = ((rgb shr 16) and 0xFF) / 255f
    val g = ((rgb shr 8) and 0xFF) / 255f
    val b = (rgb and 0xFF) / 255f
    val maxC = max(r, max(g, b))
    val minC = min(r, min(g, b))
    val delta = maxC - minC
    val h = when {
        delta == 0f -> 0f
        maxC == r -> 60f * (((g - b) / delta) % 6f)
        maxC == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }.let { if (it < 0f) it + 360f else it }
    val s = if (maxC == 0f) 0f else delta / maxC
    return floatArrayOf(h, s, maxC)
}
