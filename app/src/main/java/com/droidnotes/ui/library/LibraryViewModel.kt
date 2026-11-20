package com.droidnotes.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.droidnotes.data.db.NoteEntity
import com.droidnotes.data.db.SortOrder
import com.droidnotes.data.prefs.PreferencesRepository
import com.droidnotes.data.repo.NotesRepository
import com.droidnotes.data.templates.NoteTemplate
import com.droidnotes.data.templates.NoteTemplates
import com.droidnotes.export.NoteExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class KeywordMatchLine(
    val lineNumber: Int,
    val lineText: String,
    val charOffset: Int,
)

data class NoteKeywordMatchGroup(
    val note: NoteEntity,
    val matchCount: Int,
    val lineMatches: List<KeywordMatchLine>,
    val tags: List<String>,
)

data class NoteSummaryItem(
    val note: NoteEntity,
    val snippet: String,
    val tags: List<String>,
)

data class LibraryUiState(
    val notes: List<NoteSummaryItem> = emptyList(),
    val keywordResults: List<NoteKeywordMatchGroup> = emptyList(),
    val totalKeywordMatches: Int = 0,
    val keywordSearchMode: Boolean = false,
    val allTags: List<String> = emptyList(),
    val searchQuery: String = "",
    val sortOrder: SortOrder = SortOrder.UPDATED_DESC,
    val bookmarkedOnly: Boolean = false,
    val selectedTag: String? = null,
    val isSelectionMode: Boolean = false,
    val selectedNoteIds: Set<String> = emptySet(),
    val showNotePreview: Boolean = true,
    val statusMessage: String? = null,
    val isLoading: Boolean = true,
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val repository: NotesRepository,
    private val preferencesRepository: PreferencesRepository,
    private val exporter: NoteExporter,
) : ViewModel() {

    private val searchQuery = MutableStateFlow("")
    private val keywordSearchMode = MutableStateFlow(false)
    private val sortOrderOverride = MutableStateFlow<SortOrder?>(null)
    private val bookmarkedOnly = MutableStateFlow(false)
    private val selectedTag = MutableStateFlow<String?>(null)
    private val isSelectionMode = MutableStateFlow(false)
    private val selectedNoteIds = MutableStateFlow<Set<String>>(emptySet())
    private val statusMessage = MutableStateFlow<String?>(null)

    private val debouncedSearchQuery = searchQuery.debounce(120L)

    private val matchingNotesFlow = debouncedSearchQuery
        .flatMapLatest { query -> repository.searchNotes(query) }

    private data class ComputedLibraryContent(
        val notes: List<NoteSummaryItem>,
        val visibleIds: Set<String>,
        val keywordResults: List<NoteKeywordMatchGroup>,
        val totalKeywordMatches: Int,
        val keywordSearchMode: Boolean,
        val allTags: List<String>,
        val query: String,
        val sortOrder: SortOrder,
        val bookmarkedOnly: Boolean,
        val selectedTag: String?,
        val showNotePreview: Boolean,
    )

    private val contentFlow = combine(
        matchingNotesFlow,
        repository.observeAllNotes(),
        preferencesRepository.preferences,
        combine(
            debouncedSearchQuery,
            keywordSearchMode,
            sortOrderOverride,
            bookmarkedOnly,
            selectedTag,
        ) { q, kwMode, sort, bm, tag ->
            FilterParams(q, kwMode, sort, bm, tag)
        },
    ) { matched, allNotes, prefs, params ->
        val activeSort = params.sort ?: prefs.defaultSortOrder
        val allTags = allNotes.flatMap { it.tagList() }.distinct().sorted()
        val trimmedQuery = params.query.trim()

        val sourceNotes = if (params.keywordSearchMode && trimmedQuery.isNotEmpty()) {
            allNotes.filter { note ->
                note.title.contains(trimmedQuery, ignoreCase = true) ||
                    note.content.contains(trimmedQuery, ignoreCase = true)
            }
        } else {
            matched
        }

        val filtered = sourceNotes.asSequence()
            .filter { !params.bookmarkedOnly || it.isBookmarked }
            .filter { params.selectedTag == null || params.selectedTag in it.tagList() }
            .toList()

        val sorted = when (activeSort) {
            SortOrder.UPDATED_DESC -> filtered.sortedWith(
                compareByDescending<NoteEntity> { it.isBookmarked }.thenByDescending { it.updatedAt }
            )
            SortOrder.CREATED_DESC -> filtered.sortedWith(
                compareByDescending<NoteEntity> { it.isBookmarked }.thenByDescending { it.createdAt }
            )
            SortOrder.TITLE_ASC -> filtered.sortedWith(
                compareByDescending<NoteEntity> { it.isBookmarked }.thenBy { it.title.lowercase() }
            )
            SortOrder.TITLE_DESC -> filtered.sortedWith(
                compareByDescending<NoteEntity> { it.isBookmarked }.thenByDescending { it.title.lowercase() }
            )
            SortOrder.WORDS_DESC -> filtered.sortedWith(
                compareByDescending<NoteEntity> { it.isBookmarked }.thenByDescending { it.wordCount }
            )
        }

        val items = sorted.map { note ->
            NoteSummaryItem(
                note = note,
                snippet = if (prefs.showNotePreview) extractSnippet(note.content, trimmedQuery) else "",
                tags = note.tagList(),
            )
        }

        val keywordGroups = if (params.keywordSearchMode && trimmedQuery.isNotEmpty()) {
            sorted.mapNotNull { note ->
                buildKeywordMatchGroup(note, trimmedQuery)
            }
        } else {
            emptyList()
        }
        val totalMatches = keywordGroups.sumOf { it.matchCount }

        ComputedLibraryContent(
            notes = items,
            visibleIds = items.mapTo(HashSet(items.size)) { it.note.id },
            keywordResults = keywordGroups,
            totalKeywordMatches = totalMatches,
            keywordSearchMode = params.keywordSearchMode,
            allTags = allTags,
            query = params.query,
            sortOrder = activeSort,
            bookmarkedOnly = params.bookmarkedOnly,
            selectedTag = params.selectedTag,
            showNotePreview = prefs.showNotePreview,
        )
    }.flowOn(Dispatchers.Default)

    val uiState: StateFlow<LibraryUiState> = combine(
        contentFlow,
        searchQuery,
        isSelectionMode,
        selectedNoteIds,
        statusMessage,
    ) { content, rawQuery, selMode, selIds, msg ->
        val validSelection = if (selIds.isEmpty()) emptySet() else selIds.intersect(content.visibleIds)
        LibraryUiState(
            notes = content.notes,
            keywordResults = content.keywordResults,
            totalKeywordMatches = content.totalKeywordMatches,
            keywordSearchMode = content.keywordSearchMode,
            allTags = content.allTags,
            searchQuery = rawQuery,
            sortOrder = content.sortOrder,
            bookmarkedOnly = content.bookmarkedOnly,
            selectedTag = content.selectedTag,
            isSelectionMode = selMode,
            selectedNoteIds = validSelection,
            showNotePreview = content.showNotePreview,
            statusMessage = msg,
            isLoading = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = LibraryUiState(),
    )

    fun onSearchQueryChange(query: String) {
        searchQuery.value = query
    }

    fun toggleKeywordSearchMode() {
        keywordSearchMode.value = !keywordSearchMode.value
    }

    fun onSortOrderSelected(sort: SortOrder) {
        sortOrderOverride.value = sort
        viewModelScope.launch {
            preferencesRepository.updateDefaultSortOrder(sort)
        }
    }

    fun toggleBookmarkedFilter() {
        bookmarkedOnly.value = !bookmarkedOnly.value
    }

    fun selectTag(tag: String?) {
        selectedTag.value = if (selectedTag.value == tag) null else tag
    }

    fun enterSelectionMode(initialNoteId: String? = null) {
        isSelectionMode.value = true
        if (initialNoteId != null) {
            selectedNoteIds.update { it + initialNoteId }
        }
    }

    fun toggleNoteSelection(noteId: String) {
        selectedNoteIds.update { current ->
            if (noteId in current) current - noteId else current + noteId
        }
    }

    fun selectAllVisible() {
        val visibleIds = uiState.value.notes.map { it.note.id }.toSet()
        if (visibleIds.isEmpty()) return
        isSelectionMode.value = true
        selectedNoteIds.update { current ->
            if (current.size == visibleIds.size && current.containsAll(visibleIds)) {
                emptySet()
            } else {
                visibleIds
            }
        }
    }

    fun clearSelection() {
        isSelectionMode.value = false
        selectedNoteIds.value = emptySet()
    }

    fun deleteSelectedNotes() {
        val ids = selectedNoteIds.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repository.deleteNotes(ids)
            clearSelection()
            statusMessage.value = "Deleted ${ids.size} note(s)"
        }
    }

    fun toggleBookmark(note: NoteEntity) {
        viewModelScope.launch {
            repository.toggleBookmark(note.id, note.isBookmarked)
        }
    }

    fun deleteNote(id: String) {
        viewModelScope.launch {
            repository.deleteNote(id)
            statusMessage.value = "Note deleted"
        }
    }

    fun createBlankNote(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val created = repository.createNote(title = "Untitled Note", content = "")
            onCreated(created.id)
        }
    }

    fun createFromTemplate(template: NoteTemplate, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val created = repository.createFromTemplate(template)
            onCreated(created.id)
        }
    }

    fun loadSyntaxGuide(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val existing = repository.findNoteByTitle(NoteTemplates.syntaxGuideTemplate.defaultTitle)
            if (existing != null) {
                onCreated(existing.id)
            } else {
                val created = repository.createFromTemplate(NoteTemplates.syntaxGuideTemplate)
                onCreated(created.id)
            }
        }
    }

    fun importFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val imported = exporter.importMarkdownOrText(uris)
            imported.forEach { (title, content) ->
                repository.createNote(title = title, content = content)
            }
            statusMessage.value = if (imported.isEmpty()) {
                "No readable Markdown or text files found"
            } else {
                "Imported ${imported.size} note(s)"
            }
        }
    }

    fun clearStatusMessage() {
        statusMessage.value = null
    }

    private fun buildKeywordMatchGroup(note: NoteEntity, query: String): NoteKeywordMatchGroup? {
        var totalCount = 0
        var titlePos = note.title.indexOf(query, 0, ignoreCase = true)
        while (titlePos >= 0) {
            totalCount++
            titlePos = note.title.indexOf(query, titlePos + query.length.coerceAtLeast(1), ignoreCase = true)
        }

        val linesList = mutableListOf<KeywordMatchLine>()
        val content = note.content
        var lineStart = 0
        var lineNumber = 1
        while (lineStart <= content.length) {
            val nl = content.indexOf('\n', lineStart)
            val lineEnd = if (nl == -1) content.length else nl
            val line = content.substring(lineStart, lineEnd)

            var matchInLine = line.indexOf(query, 0, ignoreCase = true)
            if (matchInLine >= 0) {
                var firstMatchIdx = matchInLine
                while (matchInLine >= 0) {
                    totalCount++
                    matchInLine = line.indexOf(query, matchInLine + query.length.coerceAtLeast(1), ignoreCase = true)
                }
                if (linesList.size < 40) {
                    val snippet = if (line.length <= 140) {
                        line.trim()
                    } else {
                        val s = (firstMatchIdx - 36).coerceAtLeast(0)
                        val e = (firstMatchIdx + query.length + 72).coerceAtMost(line.length)
                        val pre = if (s > 0) "…" else ""
                        val suf = if (e < line.length) "…" else ""
                        pre + line.substring(s, e).trim() + suf
                    }
                    linesList.add(
                        KeywordMatchLine(
                            lineNumber = lineNumber,
                            lineText = snippet.ifEmpty { line.trim() },
                            charOffset = lineStart + firstMatchIdx,
                        )
                    )
                }
            }
            if (nl == -1) break
            lineStart = nl + 1
            lineNumber++
        }

        if (totalCount == 0) return null
        return NoteKeywordMatchGroup(
            note = note,
            matchCount = totalCount,
            lineMatches = linesList,
            tags = note.tagList(),
        )
    }

    private fun extractSnippet(content: String, trimmedQuery: String): String {
        if (content.isBlank()) return "Empty note"
        if (trimmedQuery.isEmpty()) {
            val head = collapseWhitespace(content.take(NOTE_PREVIEW_MAX_CHARS * 3))
            if (head.isEmpty()) return "Empty note"
            return if (head.length > NOTE_PREVIEW_MAX_CHARS) {
                head.take(NOTE_PREVIEW_MAX_CHARS).trimEnd() + "…"
            } else {
                head
            }
        }
        val idx = content.indexOf(trimmedQuery, ignoreCase = true)
        if (idx < 0) {
            val head = collapseWhitespace(content.take(NOTE_PREVIEW_MAX_CHARS * 3))
            if (head.isEmpty()) return "Empty note"
            return if (head.length > NOTE_PREVIEW_MAX_CHARS) {
                head.take(NOTE_PREVIEW_MAX_CHARS).trimEnd() + "…"
            } else {
                head
            }
        }
        val lead = (NOTE_PREVIEW_MAX_CHARS / 3).coerceAtLeast(24)
        val start = (idx - lead).coerceAtLeast(0)
        val end = (start + NOTE_PREVIEW_MAX_CHARS + 32).coerceAtMost(content.length)
        val collapsed = collapseWhitespace(content.substring(start, end))
        val trimmed = if (collapsed.length > NOTE_PREVIEW_MAX_CHARS) {
            collapsed.take(NOTE_PREVIEW_MAX_CHARS).trimEnd()
        } else {
            collapsed
        }
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < content.length || collapsed.length > NOTE_PREVIEW_MAX_CHARS) "…" else ""
        return prefix + trimmed + suffix
    }

    private companion object {
        const val NOTE_PREVIEW_MAX_CHARS = 100
    }

    private fun collapseWhitespace(raw: String): String {
        val sb = StringBuilder(raw.length)
        var prevWs = true
        for (i in raw.indices) {
            val ch = raw[i]
            if (ch.isWhitespace()) {
                if (!prevWs) {
                    sb.append(' ')
                    prevWs = true
                }
            } else {
                sb.append(ch)
                prevWs = false
            }
        }
        if (sb.isNotEmpty() && sb[sb.lastIndex] == ' ') {
            sb.setLength(sb.length - 1)
        }
        return sb.toString()
    }

    private data class FilterParams(
        val query: String,
        val keywordSearchMode: Boolean,
        val sort: SortOrder?,
        val bookmarkedOnly: Boolean,
        val selectedTag: String?,
    )

    class Factory(
        private val repository: NotesRepository,
        private val preferencesRepository: PreferencesRepository,
        private val exporter: NoteExporter,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return LibraryViewModel(repository, preferencesRepository, exporter) as T
        }
    }
}
