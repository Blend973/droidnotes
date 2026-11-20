package com.droidnotes.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.droidnotes.data.db.EditorViewMode
import com.droidnotes.data.db.SortOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "droidnotes_prefs")

class PreferencesRepository(private val context: Context) {
    private object Keys {
        val EDITOR_FONT_SIZE = floatPreferencesKey("editor_font_size")
        val PREVIEW_FONT_SIZE = floatPreferencesKey("preview_font_size")
        val LINE_SPACING = floatPreferencesKey("line_spacing")
        val TAB_SIZE = intPreferencesKey("tab_size")
        val AUTOSAVE_ENABLED = booleanPreferencesKey("autosave_enabled")
        val SHOW_LINE_NUMBERS = booleanPreferencesKey("show_line_numbers")
        val SHOW_LINE_COUNT = booleanPreferencesKey("show_line_count")
        val SHOW_WORD_COUNT = booleanPreferencesKey("show_word_count")
        val SHOW_CHAR_COUNT = booleanPreferencesKey("show_char_count")
        val SHOW_NOTE_PREVIEW = booleanPreferencesKey("show_note_preview")
        val DEFAULT_VIEW_MODE = stringPreferencesKey("default_view_mode")
        val RENDER_LATEX = booleanPreferencesKey("render_latex")
        val RENDER_CHARTS = booleanPreferencesKey("render_charts")
        val DEFAULT_SORT = stringPreferencesKey("default_sort")
    }

    val preferences: Flow<EditorPreferences> = context.dataStore.data.map { prefs ->
        EditorPreferences(
            editorFontSizeSp = prefs[Keys.EDITOR_FONT_SIZE] ?: 15f,
            previewFontSizeSp = prefs[Keys.PREVIEW_FONT_SIZE] ?: 16f,
            lineSpacingMultiplier = prefs[Keys.LINE_SPACING] ?: 1.45f,
            tabSize = prefs[Keys.TAB_SIZE] ?: 2,
            autoSaveEnabled = prefs[Keys.AUTOSAVE_ENABLED] ?: true,
            showLineNumbers = prefs[Keys.SHOW_LINE_NUMBERS] ?: true,
            showLineCount = prefs[Keys.SHOW_LINE_COUNT] ?: true,
            showWordCount = prefs[Keys.SHOW_WORD_COUNT] ?: true,
            showCharCount = prefs[Keys.SHOW_CHAR_COUNT] ?: true,
            showNotePreview = prefs[Keys.SHOW_NOTE_PREVIEW] ?: true,
            defaultViewMode = prefs[Keys.DEFAULT_VIEW_MODE]?.let { name ->
                EditorViewMode.entries.firstOrNull { it.name == name }
            } ?: EditorViewMode.EDIT,
            renderLatex = prefs[Keys.RENDER_LATEX] ?: true,
            renderCharts = prefs[Keys.RENDER_CHARTS] ?: true,
            defaultSortOrder = prefs[Keys.DEFAULT_SORT]?.let { name ->
                SortOrder.entries.firstOrNull { it.name == name }
            } ?: SortOrder.UPDATED_DESC,
        )
    }

    suspend fun updateEditorFontSize(sizeSp: Float) {
        context.dataStore.edit { it[Keys.EDITOR_FONT_SIZE] = sizeSp.coerceIn(12f, 24f) }
    }

    suspend fun updatePreviewFontSize(sizeSp: Float) {
        context.dataStore.edit { it[Keys.PREVIEW_FONT_SIZE] = sizeSp.coerceIn(12f, 24f) }
    }

    suspend fun updateLineSpacing(multiplier: Float) {
        context.dataStore.edit { it[Keys.LINE_SPACING] = multiplier.coerceIn(1.1f, 2.0f) }
    }

    suspend fun updateTabSize(size: Int) {
        context.dataStore.edit { it[Keys.TAB_SIZE] = if (size == 4) 4 else 2 }
    }

    suspend fun updateAutoSaveEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTOSAVE_ENABLED] = enabled }
    }

    suspend fun updateShowLineNumbers(show: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_LINE_NUMBERS] = show }
    }

    suspend fun updateShowLineCount(show: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_LINE_COUNT] = show }
    }

    suspend fun updateShowWordCount(show: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_WORD_COUNT] = show }
    }

    suspend fun updateShowCharCount(show: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_CHAR_COUNT] = show }
    }

    suspend fun updateShowNotePreview(show: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_NOTE_PREVIEW] = show }
    }

    suspend fun updateDefaultViewMode(mode: EditorViewMode) {
        context.dataStore.edit { it[Keys.DEFAULT_VIEW_MODE] = mode.name }
    }

    suspend fun updateRenderLatex(enabled: Boolean) {
        context.dataStore.edit { it[Keys.RENDER_LATEX] = enabled }
    }

    suspend fun updateRenderCharts(enabled: Boolean) {
        context.dataStore.edit { it[Keys.RENDER_CHARTS] = enabled }
    }

    suspend fun updateDefaultSortOrder(sort: SortOrder) {
        context.dataStore.edit { it[Keys.DEFAULT_SORT] = sort.name }
    }
}
