package com.droidnotes.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

object NoIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = EmptyDrawNode()
    override fun equals(other: Any?): Boolean = other === NoIndication
    override fun hashCode(): Int = 0

    private class EmptyDrawNode : Modifier.Node(), DrawModifierNode {
        override fun ContentDrawScope.draw() {
            drawContent()
        }
    }
}

class FocusedBoundsRegistry {
    private val activeBounds = mutableMapOf<Any, Rect>()

    fun register(key: Any, rect: Rect) {
        activeBounds[key] = rect
    }

    fun unregister(key: Any) {
        activeBounds.remove(key)
    }

    fun isInsideAnyFocused(point: Offset): Boolean {
        return activeBounds.values.any { it.contains(point) }
    }

    fun hasFocused(): Boolean = activeBounds.isNotEmpty()
}

val LocalFocusedBoundsRegistry = compositionLocalOf { FocusedBoundsRegistry() }

@Composable
fun FocusDropContainer(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val registry = remember { FocusedBoundsRegistry() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    CompositionLocalProvider(LocalFocusedBoundsRegistry provides registry) {
        Box(
            modifier = modifier.pointerInput(registry) {
                awaitPointerEventScope {
                    var downPos: Offset? = null
                    while (true) {
                        // Observe after children so clicks on buttons/toolbar chips are already
                        // consumed and must not clear the text field focus or hide the keyboard.
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        val change = event.changes.firstOrNull() ?: continue
                        if (change.isConsumed) {
                            downPos = null
                            continue
                        }
                        when (event.type) {
                            PointerEventType.Press -> {
                                downPos = change.position
                            }
                            PointerEventType.Release -> {
                                val start = downPos
                                downPos = null
                                if (start != null && registry.hasFocused()) {
                                    val moved = (change.position - start).getDistance()
                                    if (moved < 24f && !registry.isInsideAnyFocused(change.position)) {
                                        focusManager.clearFocus(force = true)
                                        keyboard?.hide()
                                    }
                                }
                            }
                            else -> Unit
                        }
                    }
                }
            }
        ) {
            content()
        }
    }
}

private class FocusBoundsHolder(
    var isFocused: Boolean = false,
    var lastBounds: Rect? = null,
)

@Composable
fun Modifier.trackFocusBounds(): Modifier {
    val registry = LocalFocusedBoundsRegistry.current
    val key = remember { Any() }
    val holder = remember { FocusBoundsHolder() }

    DisposableEffect(registry, key) {
        onDispose { registry.unregister(key) }
    }

    return this
        .onGloballyPositioned { coords ->
            val bounds = coords.boundsInRoot()
            holder.lastBounds = bounds
            if (holder.isFocused) {
                registry.register(key, bounds)
            }
        }
        .onFocusChanged { state ->
            holder.isFocused = state.isFocused
            if (state.isFocused) {
                holder.lastBounds?.let { registry.register(key, it) }
            } else {
                registry.unregister(key)
            }
        }
}

@Composable
fun OledSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clickable(
                interactionSource = null,
                indication = NoIndication,
                onClick = { onCheckedChange(!checked) },
            )
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .width(46.dp)
                .height(26.dp)
                .background(
                    color = if (checked) Color.White else Color(0xFF141414),
                    shape = RoundedCornerShape(4.dp),
                )
                .border(
                    width = 1.dp,
                    color = if (checked) Color.White else Color(0xFF404040),
                    shape = RoundedCornerShape(4.dp),
                )
                .padding(3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (checked) Arrangement.End else Arrangement.Start,
        ) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .background(
                        color = if (checked) Color.Black else Color(0xFFA3A3A3),
                        shape = RoundedCornerShape(2.dp),
                    )
            )
        }
    }
}

@Composable
fun OledConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    dismissLabel: String = "Cancel",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
    confirmTag: String = "dialog_confirm",
    dismissTag: String = "dialog_cancel",
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
                .padding(horizontal = 24.dp)
                .widthIn(max = 440.dp),
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.testTag(dismissTag),
                    ) {
                        Text(dismissLabel, color = Color(0xFFD4D4D4))
                    }
                    if (secondaryActionLabel != null && onSecondaryAction != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = onSecondaryAction,
                            border = BorderStroke(1.dp, Color.White),
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Text(secondaryActionLabel, color = Color.White)
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onConfirm,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color.Black,
                        ),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.testTag(confirmTag),
                    ) {
                        Text(confirmLabel)
                    }
                }
            }
        }
    }
}

@Composable
fun OledPopupMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (!expanded) return
    Popup(
        alignment = Alignment.TopEnd,
        offset = IntOffset(0, 48),
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            color = Color(0xFF0A0A0A),
            contentColor = Color.White,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier
                .width(IntrinsicSize.Max)
                .widthIn(max = 260.dp),
        ) {
            Column(
                modifier = Modifier
                    .width(IntrinsicSize.Max)
                    .padding(vertical = 4.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
fun OledPopupMenuItem(
    text: String,
    onClick: () -> Unit,
    testTag: String = "",
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clickable(
                interactionSource = null,
                indication = NoIndication,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .let { if (testTag.isNotEmpty()) it.testTag(testTag) else it },
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
        )
    }
}
