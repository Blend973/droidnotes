package com.droidnotes.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidnotes.data.db.EditorViewMode
import com.droidnotes.ui.components.OledConfirmDialog
import com.droidnotes.ui.components.OledSwitch

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val prefs = state.preferences
    var pendingAutoSaveToggle by remember { mutableStateOf<Boolean?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
        )

        // Typography & Spacing
        SectionCard(title = "Markdown Typography") {
            SliderRow(
                label = "Editor font size",
                valueLabel = "${prefs.editorFontSizeSp.toInt()} sp",
                value = prefs.editorFontSizeSp,
                range = 12f..24f,
                steps = 11,
                onValueChange = viewModel::setEditorFontSize,
                testTag = "slider_editor_font_size",
            )
            SliderRow(
                label = "Preview font size",
                valueLabel = "${prefs.previewFontSizeSp.toInt()} sp",
                value = prefs.previewFontSizeSp,
                range = 12f..24f,
                steps = 11,
                onValueChange = viewModel::setPreviewFontSize,
                testTag = "slider_preview_font_size",
            )
            SliderRow(
                label = "Line spacing",
                valueLabel = String.format(java.util.Locale.US, "%.2fx", prefs.lineSpacingMultiplier),
                value = prefs.lineSpacingMultiplier,
                range = 1.1f..2.0f,
                steps = 8,
                onValueChange = viewModel::setLineSpacing,
                testTag = "slider_line_spacing",
            )

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Tab indentation",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(2, 4).forEach { spaces ->
                    OptionChip(
                        label = "$spaces spaces",
                        selected = prefs.tabSize == spaces,
                        onClick = { viewModel.setTabSize(spaces) },
                        testTag = "tab_size_$spaces",
                    )
                }
            }
        }

        // Editor & Auto-Save Behavior
        SectionCard(title = "Editor & Auto-Save") {
            Text(
                text = "Default editor layout",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EditorViewMode.entries.forEach { mode ->
                    OptionChip(
                        label = mode.label,
                        selected = prefs.defaultViewMode == mode,
                        onClick = { viewModel.setDefaultViewMode(mode) },
                        testTag = "default_view_${mode.name.lowercase()}",
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            ToggleRow(
                title = "Auto-save edits",
                subtitle = "Automatically persist note edits while typing and on exit",
                checked = prefs.autoSaveEnabled,
                onCheckedChange = { target -> pendingAutoSaveToggle = target },
                testTag = "toggle_auto_save",
            )
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline)
            ToggleRow(
                title = "Show editor line numbers",
                subtitle = "Monospace line gutter inside raw Markdown editor",
                checked = prefs.showLineNumbers,
                onCheckedChange = viewModel::setShowLineNumbers,
                testTag = "toggle_line_numbers",
            )
        }

        // Notes List
        SectionCard(title = "Notes List") {
            ToggleRow(
                title = "Show note preview",
                subtitle = "Display content preview under note titles on the main screen",
                checked = prefs.showNotePreview,
                onCheckedChange = viewModel::setShowNotePreview,
                testTag = "toggle_note_preview",
            )
        }

        // Editor Counters
        SectionCard(title = "Editor Counters") {
            ToggleRow(
                title = "Show line count",
                subtitle = "Display live line count in the editor status bar",
                checked = prefs.showLineCount,
                onCheckedChange = viewModel::setShowLineCount,
                testTag = "toggle_line_count",
            )
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline)
            ToggleRow(
                title = "Show word count",
                subtitle = "Display live word count in the editor status bar",
                checked = prefs.showWordCount,
                onCheckedChange = viewModel::setShowWordCount,
                testTag = "toggle_word_count",
            )
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline)
            ToggleRow(
                title = "Show character count",
                subtitle = "Display live character count in the editor status bar",
                checked = prefs.showCharCount,
                onCheckedChange = viewModel::setShowCharCount,
                testTag = "toggle_char_count",
            )
        }

        // Rendering Engine
        SectionCard(title = "Rendering Engine") {
            ToggleRow(
                title = "Render LaTeX equations",
                subtitle = "Format \$...\$ and \$\$...\$\$ natively without WebView",
                checked = prefs.renderLatex,
                onCheckedChange = viewModel::setRenderLatex,
                testTag = "toggle_render_latex",
            )
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline)
            ToggleRow(
                title = "Render inline charts & diagrams",
                subtitle = "Draw ```chart and ```diagram blocks on native Canvas",
                checked = prefs.renderCharts,
                onCheckedChange = viewModel::setRenderCharts,
                testTag = "toggle_render_charts",
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }

    pendingAutoSaveToggle?.let { target ->
        OledConfirmDialog(
            title = if (target) "Enable Auto-Save?" else "Disable Auto-Save?",
            message = if (target) {
                "Notes will automatically save while editing. You can still use Discard Edits in the editor to revert session changes."
            } else {
                "Edits will require manual saving in the editor, and you will be prompted if you leave with unsaved changes."
            },
            confirmLabel = if (target) "Enable" else "Disable",
            onConfirm = {
                viewModel.setAutoSaveEnabled(target)
                pendingAutoSaveToggle = null
            },
            onDismiss = { pendingAutoSaveToggle = null },
            confirmTag = "confirm_autosave_toggle",
            dismissTag = "cancel_autosave_toggle",
        )
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0A0A0A), RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .padding(14.dp),
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun SliderRow(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    testTag: String,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium, color = Color.White)
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color(0xFF262626),
            ),
            modifier = Modifier.testTag(testTag),
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, color = Color.White)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OledSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag),
        )
    }
}

@Composable
private fun OptionChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String,
) {
    Surface(
        onClick = onClick,
        color = if (selected) Color.White else Color.Black,
        contentColor = if (selected) Color.Black else Color.White,
        border = BorderStroke(1.dp, if (selected) Color.White else MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .testTag(testTag),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}
