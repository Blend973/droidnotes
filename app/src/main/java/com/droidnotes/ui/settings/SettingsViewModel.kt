package com.droidnotes.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.droidnotes.data.db.EditorViewMode
import com.droidnotes.data.prefs.EditorPreferences
import com.droidnotes.data.prefs.PreferencesRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val preferences: EditorPreferences = EditorPreferences(),
)

class SettingsViewModel(
    private val preferencesRepository: PreferencesRepository,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = preferencesRepository.preferences
        .map { prefs -> SettingsUiState(preferences = prefs) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SettingsUiState(),
        )

    fun setEditorFontSize(size: Float) {
        viewModelScope.launch { preferencesRepository.updateEditorFontSize(size) }
    }

    fun setPreviewFontSize(size: Float) {
        viewModelScope.launch { preferencesRepository.updatePreviewFontSize(size) }
    }

    fun setLineSpacing(multiplier: Float) {
        viewModelScope.launch { preferencesRepository.updateLineSpacing(multiplier) }
    }

    fun setTabSize(size: Int) {
        viewModelScope.launch { preferencesRepository.updateTabSize(size) }
    }

    fun setAutoSaveEnabled(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.updateAutoSaveEnabled(enabled) }
    }

    fun setShowLineNumbers(show: Boolean) {
        viewModelScope.launch { preferencesRepository.updateShowLineNumbers(show) }
    }

    fun setShowLineCount(show: Boolean) {
        viewModelScope.launch { preferencesRepository.updateShowLineCount(show) }
    }

    fun setShowWordCount(show: Boolean) {
        viewModelScope.launch { preferencesRepository.updateShowWordCount(show) }
    }

    fun setShowCharCount(show: Boolean) {
        viewModelScope.launch { preferencesRepository.updateShowCharCount(show) }
    }

    fun setShowNotePreview(show: Boolean) {
        viewModelScope.launch { preferencesRepository.updateShowNotePreview(show) }
    }

    fun setDefaultViewMode(mode: EditorViewMode) {
        viewModelScope.launch { preferencesRepository.updateDefaultViewMode(mode) }
    }

    fun setRenderLatex(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.updateRenderLatex(enabled) }
    }

    fun setRenderCharts(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.updateRenderCharts(enabled) }
    }

    class Factory(
        private val preferencesRepository: PreferencesRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(preferencesRepository) as T
        }
    }
}
