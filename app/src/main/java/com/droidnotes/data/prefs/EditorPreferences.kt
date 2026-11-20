package com.droidnotes.data.prefs

import com.droidnotes.data.db.EditorViewMode
import com.droidnotes.data.db.SortOrder

data class EditorPreferences(
    val editorFontSizeSp: Float = 15f,
    val previewFontSizeSp: Float = 16f,
    val lineSpacingMultiplier: Float = 1.45f,
    val tabSize: Int = 2,
    val autoSaveEnabled: Boolean = true,
    val showLineNumbers: Boolean = true,
    val showLineCount: Boolean = true,
    val showWordCount: Boolean = true,
    val showCharCount: Boolean = true,
    val showNotePreview: Boolean = true,
    val defaultViewMode: EditorViewMode = EditorViewMode.EDIT,
    val renderLatex: Boolean = true,
    val renderCharts: Boolean = true,
    val defaultSortOrder: SortOrder = SortOrder.UPDATED_DESC,
)
