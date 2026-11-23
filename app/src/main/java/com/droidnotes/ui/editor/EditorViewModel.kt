package com.droidnotes.ui.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.droidnotes.data.db.EditorViewMode
import com.droidnotes.data.prefs.EditorPreferences
import com.droidnotes.data.prefs.PreferencesRepository
import com.droidnotes.data.repo.NotesRepository
import com.droidnotes.export.NoteExporter
import com.droidnotes.markdown.HtmlClipboardConverter
import com.droidnotes.markdown.MarkdownDocument
import com.droidnotes.markdown.MarkdownParser
import com.droidnotes.markdown.SyntaxSpan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class SaveStatus {
    SAVED,
    SAVING,
    UNSAVED,
}

enum class ActivePaneMode {
    EDIT,
    PREVIEW,
    SPLIT,
}

data class EditorUiState(
    val noteId: String = "",
    val title: String = "",
    val contentField: TextFieldValue = TextFieldValue(""),
    val isBookmarked: Boolean = false,
    val templateId: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val lineCount: Int = 0,
    val wordCount: Int = 0,
    val charCount: Int = 0,
    val saveStatus: SaveStatus = SaveStatus.SAVED,
    val hasSessionChanges: Boolean = false,
    val hasUnsavedChanges: Boolean = false,
    val parsedDocument: MarkdownDocument = MarkdownDocument(emptyList(), emptyMap(), 0, 0),
    val syntaxSpans: List<SyntaxSpan> = emptyList(),
    val preferences: EditorPreferences = EditorPreferences(),
    val activePaneMode: ActivePaneMode = ActivePaneMode.EDIT,
    val showFindBar: Boolean = false,
    val showReplaceBar: Boolean = false,
    val findQuery: String = "",
    val replaceQuery: String = "",
    val findMatches: List<Int> = emptyList(),
    val findMatchesQuery: String = "",
    val findMatchesContent: String = "",
    val previewMatchBlockIndices: List<Int> = emptyList(),
    val activeMatchIndex: Int = -1,
    val findNavigationSeq: Int = 0,
    val statusBanner: String? = null,
    val allNoteTitles: List<String> = emptyList(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val isLoaded: Boolean = false,
    val isDeleted: Boolean = false,
)

class EditorViewModel(
    private val noteId: String,
    private val repository: NotesRepository,
    private val preferencesRepository: PreferencesRepository,
    private val exporter: NoteExporter,
    private val initialFindQuery: String = "",
    private val initialFindOffset: Int = -1,
) : ViewModel() {

    private data class InternalDraft(
        val title: String = "",
        val contentField: TextFieldValue = TextFieldValue(""),
        val baselineTitle: String = "",
        val baselineContent: String = "",
        val isBookmarked: Boolean = false,
        val templateId: String? = null,
        val createdAt: Long = 0L,
        val updatedAt: Long = 0L,
        val lineCount: Int = 0,
        val saveStatus: SaveStatus = SaveStatus.SAVED,
        val parsedDocument: MarkdownDocument = MarkdownDocument(emptyList(), emptyMap(), 0, 0),
        val syntaxSpans: List<SyntaxSpan> = emptyList(),
        val paneOverride: ActivePaneMode? = null,
        val showFindBar: Boolean = false,
        val showReplaceBar: Boolean = false,
        val findQuery: String = "",
        val replaceQuery: String = "",
        val findMatches: List<Int> = emptyList(),
        val findMatchesQuery: String = "",
        val findMatchesContent: String = "",
        val previewMatchBlockIndices: List<Int> = emptyList(),
        val activeMatchIndex: Int = -1,
        val findNavigationSeq: Int = 0,
        val statusBanner: String? = null,
        val canUndo: Boolean = false,
        val canRedo: Boolean = false,
        val isLoaded: Boolean = false,
        val isDeleted: Boolean = false,
    )

    private val draft = MutableStateFlow(InternalDraft())
    private val undoStack = ArrayDeque<TextFieldValue>()
    private val redoStack = ArrayDeque<TextFieldValue>()
    private var lastUndoPushMs = 0L
    private var autoSaveJob: Job? = null
    private var parseJob: Job? = null
    private var findJob: Job? = null
    private var findRevision = 0L
    private var dirty = false

    private val noteTitlesFlow = repository.observeNoteTitles()
        .map { list -> list.map { it.title } }

    val uiState: StateFlow<EditorUiState> = combine(
        draft,
        preferencesRepository.preferences,
        noteTitlesFlow,
    ) { d, prefs, titles ->
        val defaultMode = when (prefs.defaultViewMode) {
            EditorViewMode.EDIT -> ActivePaneMode.EDIT
            EditorViewMode.PREVIEW -> ActivePaneMode.PREVIEW
            EditorViewMode.SPLIT_ON_WIDE -> ActivePaneMode.SPLIT
        }
        val hasSessionChanges = d.isLoaded &&
            (d.title != d.baselineTitle || d.contentField.text != d.baselineContent)
        EditorUiState(
            noteId = noteId,
            title = d.title,
            contentField = d.contentField,
            isBookmarked = d.isBookmarked,
            templateId = d.templateId,
            createdAt = d.createdAt,
            updatedAt = d.updatedAt,
            lineCount = d.lineCount,
            wordCount = d.parsedDocument.totalWords,
            charCount = d.contentField.text.length,
            saveStatus = d.saveStatus,
            hasSessionChanges = hasSessionChanges,
            hasUnsavedChanges = d.saveStatus != SaveStatus.SAVED,
            parsedDocument = d.parsedDocument,
            syntaxSpans = d.syntaxSpans,
            preferences = prefs,
            activePaneMode = d.paneOverride ?: defaultMode,
            showFindBar = d.showFindBar,
            showReplaceBar = d.showReplaceBar,
            findQuery = d.findQuery,
            replaceQuery = d.replaceQuery,
            findMatches = d.findMatches,
            findMatchesQuery = d.findMatchesQuery,
            findMatchesContent = d.findMatchesContent,
            previewMatchBlockIndices = d.previewMatchBlockIndices,
            activeMatchIndex = d.activeMatchIndex,
            findNavigationSeq = d.findNavigationSeq,
            statusBanner = d.statusBanner,
            allNoteTitles = titles,
            canUndo = d.canUndo,
            canRedo = d.canRedo,
            isLoaded = d.isLoaded,
            isDeleted = d.isDeleted,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = EditorUiState(noteId = noteId),
    )

    init {
        viewModelScope.launch {
            val note = repository.getNoteById(noteId)
            if (note != null) {
                val trimmedQuery = initialFindQuery.trim()
                val loaded = withContext(Dispatchers.Default) {
                    val doc = MarkdownParser.parse(note.content)
                    val spans = MarkdownParser.computeSyntaxSpans(note.content)
                    val lines = doc.totalLines
                    val matches = if (trimmedQuery.isNotEmpty()) {
                        findMatchOffsets(note.content, trimmedQuery)
                    } else {
                        emptyList()
                    }
                    val blockMatches = if (trimmedQuery.isNotEmpty()) {
                        MarkdownParser.findMatchingBlockIndices(doc, trimmedQuery)
                    } else {
                        emptyList()
                    }
                    InitialLoadData(doc, spans, lines, matches, blockMatches)
                }
                val initialMatchIndex = when {
                    loaded.matches.isEmpty() -> -1
                    initialFindOffset >= 0 -> loaded.matches.indexOfFirst { it >= initialFindOffset }
                        .takeIf { it >= 0 } ?: 0
                    else -> 0
                }
                val initialSelection = if (initialMatchIndex >= 0) {
                    val start = loaded.matches[initialMatchIndex]
                    val end = (start + trimmedQuery.length).coerceAtMost(note.content.length)
                    TextRange(start, end)
                } else {
                    // Start cursor at position 0 (top of note) so BasicTextField does NOT auto-scroll
                    // to the bottom of a long note when the editor first opens.
                    TextRange(0)
                }
                draft.update {
                    it.copy(
                        title = note.title,
                        contentField = TextFieldValue(note.content, initialSelection),
                        baselineTitle = note.title,
                        baselineContent = note.content,
                        isBookmarked = note.isBookmarked,
                        templateId = note.templateId,
                        createdAt = note.createdAt,
                        updatedAt = note.updatedAt,
                        lineCount = loaded.lineCount,
                        parsedDocument = loaded.doc,
                        syntaxSpans = loaded.spans,
                        showFindBar = trimmedQuery.isNotEmpty(),
                        findQuery = trimmedQuery,
                        findMatches = loaded.matches,
                        findMatchesQuery = trimmedQuery,
                        findMatchesContent = note.content,
                        previewMatchBlockIndices = loaded.blockMatches,
                        activeMatchIndex = initialMatchIndex,
                        findNavigationSeq = if (initialMatchIndex >= 0) 1 else 0,
                        isLoaded = true,
                    )
                }
            } else {
                draft.update { it.copy(isLoaded = true, isDeleted = true) }
            }
        }
    }

    fun onTitleChange(newTitle: String) {
        val autoSave = uiState.value.preferences.autoSaveEnabled
        draft.update {
            it.copy(
                title = newTitle,
                saveStatus = if (autoSave) SaveStatus.SAVING else SaveStatus.UNSAVED,
            )
        }
        onDraftMutated()
    }

    fun onContentChange(newValue: TextFieldValue, forceUndoSnapshot: Boolean = false) {
        val prev = draft.value.contentField
        val textChanged = newValue.text != prev.text
        if (textChanged) {
            pushUndoIfNeeded(prev, newValue, forceSnapshot = forceUndoSnapshot)
        }
        applyContentField(newValue, textChanged)
    }

    private fun pushUndoIfNeeded(prev: TextFieldValue, next: TextFieldValue, forceSnapshot: Boolean) {
        val now = System.currentTimeMillis()
        val lenDiff = kotlin.math.abs(next.text.length - prev.text.length)
        val insertedBoundary = next.text.length > prev.text.length &&
            next.selection.start > 0 &&
            next.text.getOrNull(next.selection.start - 1)?.let { it == '\n' || it == ' ' } == true
        val shouldPush = forceSnapshot ||
            undoStack.isEmpty() ||
            lenDiff > 1 ||
            insertedBoundary ||
            (now - lastUndoPushMs) >= 350L
        if (shouldPush && undoStack.lastOrNull()?.text != prev.text) {
            if (undoStack.size >= 100) undoStack.removeFirst()
            undoStack.addLast(prev)
            lastUndoPushMs = now
        }
        redoStack.clear()
    }

    private fun applyContentField(newValue: TextFieldValue, textChanged: Boolean) {
        val autoSave = uiState.value.preferences.autoSaveEnabled
        draft.update {
            it.copy(
                contentField = newValue,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
                saveStatus = when {
                    !textChanged -> it.saveStatus
                    autoSave -> SaveStatus.SAVING
                    else -> SaveStatus.UNSAVED
                },
            )
        }
        if (textChanged) {
            findRevision++
            draft.update { it.copy(findMatches = emptyList(), previewMatchBlockIndices = emptyList(), activeMatchIndex = -1, findMatchesQuery = "", findMatchesContent = "") }
            scheduleParseAndHighlights(newValue.text, draft.value.findQuery)
            onDraftMutated()
        }
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        val current = draft.value.contentField
        val previous = undoStack.removeLast()
        if (redoStack.size >= 100) redoStack.removeFirst()
        redoStack.addLast(current)
        lastUndoPushMs = 0L
        applyContentField(previous, textChanged = previous.text != current.text)
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val current = draft.value.contentField
        val restored = redoStack.removeLast()
        if (undoStack.size >= 100) undoStack.removeFirst()
        undoStack.addLast(current)
        lastUndoPushMs = 0L
        applyContentField(restored, textChanged = restored.text != current.text)
    }

    private fun applyToolbarOp(transform: (TextFieldValue) -> TextFieldValue) {
        val current = draft.value.contentField
        val updated = transform(current)
        if (updated != current) {
            onContentChange(updated, forceUndoSnapshot = true)
        }
    }

    fun addBulletItem() = applyToolbarOp { MarkdownTextOps.addBulletItem(it) }

    fun addNumberedItem() = applyToolbarOp { MarkdownTextOps.addNumberedItem(it) }

    fun indentListItem() {
        val spaces = " ".repeat(uiState.value.preferences.tabSize.coerceAtLeast(2))
        applyToolbarOp { MarkdownTextOps.indentLines(it, spaces) }
    }

    fun unindentListItem() {
        val tabSize = uiState.value.preferences.tabSize.coerceAtLeast(2)
        applyToolbarOp { MarkdownTextOps.unindentLines(it, tabSize) }
    }

    fun deleteParagraph() = applyToolbarOp { MarkdownTextOps.deleteParagraph(it) }

    fun moveLineUp() = applyToolbarOp { MarkdownTextOps.moveLinesUp(it) }

    fun moveLineDown() = applyToolbarOp { MarkdownTextOps.moveLinesDown(it) }

    fun removeLine() = applyToolbarOp { MarkdownTextOps.removeLines(it) }

    fun addQuoteItem() = applyToolbarOp { MarkdownTextOps.addQuoteItem(it) }

    fun setPaneMode(mode: ActivePaneMode) {
        draft.update { it.copy(paneOverride = mode) }
    }

    fun toggleBookmark() {
        val current = draft.value.isBookmarked
        draft.update { it.copy(isBookmarked = !current) }
        viewModelScope.launch {
            repository.toggleBookmark(noteId, current)
        }
    }

    fun toggleTaskAtLine(lineIndex: Int) {
        viewModelScope.launch {
            val current = draft.value.contentField
            val updatedText = withContext(Dispatchers.Default) {
                MarkdownParser.toggleTaskCheckboxInSource(current.text, lineIndex)
            }
            if (updatedText != current.text) {
                onContentChange(current.copy(text = updatedText))
            }
        }
    }

    fun applyScopedBlockEdit(
        startLineIndex: Int,
        endLineIndex: Int,
        newBlockMarkdown: String,
    ) {
        viewModelScope.launch {
            val current = draft.value.contentField
            val updatedText = withContext(Dispatchers.Default) {
                MarkdownParser.replaceLineRange(
                    source = current.text,
                    startLineIndex = startLineIndex,
                    endLineIndex = endLineIndex,
                    newBlockMarkdown = newBlockMarkdown,
                )
            }
            if (updatedText != current.text) {
                val safeCursor = current.selection.end.coerceIn(0, updatedText.length)
                onContentChange(TextFieldValue(text = updatedText, selection = TextRange(safeCursor)))
            }
        }
    }

    fun toggleFindBar() {
        val nextOpen = !draft.value.showFindBar
        draft.update {
            it.copy(
                showFindBar = nextOpen,
                showReplaceBar = if (nextOpen) it.showReplaceBar else false,
                findQuery = if (nextOpen) it.findQuery else "",
                replaceQuery = if (nextOpen) it.replaceQuery else "",
                findMatches = if (nextOpen) it.findMatches else emptyList(),
                previewMatchBlockIndices = if (nextOpen) it.previewMatchBlockIndices else emptyList(),
                activeMatchIndex = if (nextOpen) it.activeMatchIndex else -1,
            )
        }
    }

    fun onFindQueryChange(query: String) {
        val normalized = query.trim()
        draft.update { it.copy(findQuery = query, findMatches = emptyList(), previewMatchBlockIndices = emptyList(), activeMatchIndex = -1, findMatchesQuery = "", findMatchesContent = "") }
        recomputeFindMatches(draft.value.contentField.text, draft.value.parsedDocument, normalized)
    }

    fun toggleReplaceBar() {
        draft.update { it.copy(showReplaceBar = !it.showReplaceBar) }
    }

    fun onReplaceQueryChange(query: String) {
        draft.update { it.copy(replaceQuery = query) }
    }

    fun replaceCurrent() {
        val d = draft.value
        val query = d.findQuery.trim()
        if (query.isEmpty() || d.findMatchesQuery != query || d.findMatchesContent != d.contentField.text) return
        val start = d.findMatches.getOrNull(d.activeMatchIndex) ?: return
        if (!d.contentField.text.regionMatches(start, query, 0, query.length, ignoreCase = true)) return
        val text = d.contentField.text
        val end = start + query.length
        val newText = text.replaceRange(start, end, d.replaceQuery)
        val nextStart = start + d.replaceQuery.length
        val cursor = nextStart.coerceAtMost(newText.length)
        onContentChange(TextFieldValue(newText, TextRange(cursor)), forceUndoSnapshot = true)
        draft.update { it.copy(activeMatchIndex = -1) }
        scheduleParseAndHighlights(newText, query, preferredOffset = nextStart)
    }

    fun replaceAll() {
        val d = draft.value
        val query = d.findQuery.trim()
        if (query.isEmpty() || d.findMatchesQuery != query || d.findMatchesContent != d.contentField.text) return
        val matches = findMatchOffsets(d.contentField.text, query)
        if (matches.isEmpty()) return
        val count = matches.size
        val cursor = d.contentField.selection.start.coerceIn(0, d.contentField.text.length)
        val newText = d.contentField.text.replace(query, d.replaceQuery, ignoreCase = true)
        val adjustedCursor = (cursor + matches.takeWhile { it < cursor }.sumOf { d.replaceQuery.length - query.length }).coerceIn(0, newText.length)
        onContentChange(TextFieldValue(newText, TextRange(adjustedCursor)), forceUndoSnapshot = true)
        draft.update { it.copy(statusBanner = "Replaced $count occurrence(s)") }
        scheduleParseAndHighlights(newText, query, preferredOffset = matches.firstOrNull())
    }

    fun stepFindMatch(delta: Int) {
        val d = draft.value
        val matches = d.findMatches
        if (matches.isEmpty()) return
        val current = d.activeMatchIndex
        val nextIdx = if (current < 0) 0 else (current + delta + matches.size) % matches.size
        val startOffset = matches[nextIdx]
        val endOffset = (startOffset + d.findQuery.trim().length).coerceAtMost(d.contentField.text.length)
        draft.update {
            it.copy(
                activeMatchIndex = nextIdx,
                findNavigationSeq = it.findNavigationSeq + 1,
                contentField = it.contentField.copy(selection = TextRange(startOffset, endOffset)),
            )
        }
    }

    fun wrapSelection(prefix: String, suffix: String = prefix, placeholder: String = "") {
        val field = draft.value.contentField
        val text = field.text
        val start = field.selection.min.coerceIn(0, text.length)
        val end = field.selection.max.coerceIn(start, text.length)
        val selected = text.substring(start, end)
        val inserted = if (selected.isNotEmpty()) "$prefix$selected$suffix" else "$prefix$placeholder$suffix"
        val newText = text.replaceRange(start, end, inserted)
        val cursorStart = start + prefix.length
        val cursorEnd = if (selected.isNotEmpty()) cursorStart + selected.length else cursorStart + placeholder.length
        onContentChange(TextFieldValue(newText, TextRange(cursorStart, cursorEnd)), forceUndoSnapshot = true)
    }

    /**
     * Inserts a snippet at the current selection. When [cursorMarker] is present in the
     * snippet, the marker is removed and the cursor is placed exactly where it was, so
     * inserted templates can start empty with the caret ready for typing.
     */
    fun insertSnippetAtCursor(snippet: String, cursorMarker: String? = null) {
        val field = draft.value.contentField
        val text = field.text
        val start = field.selection.min.coerceIn(0, text.length)
        val end = field.selection.max.coerceIn(start, text.length)
        val needsLeadingNewline = snippet.startsWith("\n") && start > 0 && text[start - 1] != '\n'
        val snippetWithMarker = if (needsLeadingNewline) snippet else snippet.trimStart('\n')
        val markerIndex = if (cursorMarker != null) {
            snippetWithMarker.indexOf(cursorMarker).takeIf { it >= 0 }
        } else {
            null
        }
        val finalSnippet = if (markerIndex != null && cursorMarker != null) {
            snippetWithMarker.removeRange(markerIndex, markerIndex + cursorMarker.length)
        } else {
            snippetWithMarker
        }
        val cursor = if (markerIndex != null) start + markerIndex else start + finalSnippet.length
        val newText = text.replaceRange(start, end, finalSnippet)
        onContentChange(TextFieldValue(newText, TextRange(cursor, cursor)), forceUndoSnapshot = true)
    }

    fun insertPickedImage(uri: Uri) {
        viewModelScope.launch {
            val savedUri = repository.saveImageFromUri(uri)
            if (savedUri != null) {
                insertSnippetAtCursor("\n![image]($savedUri)\n")
                draft.update { it.copy(statusBanner = "Image inserted") }
            } else {
                draft.update { it.copy(statusBanner = "Failed to load image") }
            }
        }
    }

    fun handleSmartPaste(clipboardManager: ClipboardManager, contentResolver: ContentResolver) {
        val clip = clipboardManager.primaryClip ?: return
        if (clip.itemCount == 0) return
        val item = clip.getItemAt(0)
        val hasHtmlMime = clip.description.hasMimeType("text/html")
        val itemUri = item.uri
        val htmlText = item.htmlText
        val plain = item.text?.toString()

        viewModelScope.launch {
            if (itemUri != null) {
                val isImageMime = withContext(Dispatchers.IO) {
                    contentResolver.getType(itemUri).orEmpty().startsWith("image/")
                }
                if (isImageMime) {
                    insertPickedImage(itemUri)
                    return@launch
                }
                val savedUri = withContext(Dispatchers.IO) {
                    runCatching {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        contentResolver.openInputStream(itemUri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                        val sample = NotesRepository.calculateInSampleSize(bounds.outWidth, bounds.outHeight, 2048, 2048)
                        val decoded = contentResolver.openInputStream(itemUri)?.use {
                            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                        }
                        if (decoded != null) {
                            try {
                                repository.saveBitmapImage(decoded)
                            } finally {
                                decoded.recycle()
                            }
                        } else null
                    }.getOrNull()
                }
                if (savedUri != null) {
                    insertSnippetAtCursor("\n![pasted_image]($savedUri)\n")
                    draft.update { it.copy(statusBanner = "Pasted clipboard image") }
                    return@launch
                }
            }

            if (!htmlText.isNullOrBlank() && hasHtmlMime) {
                val converted = withContext(Dispatchers.Default) {
                    HtmlClipboardConverter.htmlToMarkdown(htmlText)
                }
                if (converted.isNotBlank()) {
                    insertSnippetAtCursor(converted)
                    draft.update { it.copy(statusBanner = "Pasted rich text as Markdown") }
                    return@launch
                }
            }

            if (!plain.isNullOrEmpty()) {
                insertSnippetAtCursor(plain)
            }
        }
    }

    fun copyAsRichHtml(clipboardManager: ClipboardManager) {
        val d = draft.value
        viewModelScope.launch {
            val html = withContext(Dispatchers.Default) {
                HtmlClipboardConverter.markdownToHtml(d.title, d.parsedDocument)
            }
            val clip = ClipData.newHtmlText(d.title, d.contentField.text, html)
            clipboardManager.setPrimaryClip(clip)
            draft.update { it.copy(statusBanner = "Copied as Rich Text") }
        }
    }

    fun copyAsMarkdown(clipboardManager: ClipboardManager) {
        val d = draft.value
        val clip = ClipData.newPlainText(d.title, d.contentField.text)
        clipboardManager.setPrimaryClip(clip)
        draft.update { it.copy(statusBanner = "Copied Markdown") }
    }

    fun exportMarkdown(uri: Uri) {
        viewModelScope.launch {
            flushSaveNow()
            val res = exporter.exportMarkdown(uri, draft.value.contentField.text)
            draft.update {
                it.copy(statusBanner = if (res.isSuccess) "Exported Markdown (.md)" else "Export failed")
            }
        }
    }

    fun exportHtml(uri: Uri) {
        viewModelScope.launch {
            flushSaveNow()
            val res = exporter.exportHtml(uri, draft.value.title, draft.value.contentField.text)
            draft.update {
                it.copy(statusBanner = if (res.isSuccess) "Exported HTML (.html)" else "Export failed")
            }
        }
    }

    fun exportPdf(uri: Uri) {
        viewModelScope.launch {
            flushSaveNow()
            val res = exporter.exportPdf(uri, draft.value.title, draft.value.contentField.text)
            draft.update {
                it.copy(statusBanner = if (res.isSuccess) "Exported PDF (.pdf)" else "PDF export failed")
            }
        }
    }

    fun openOrCreateWikiLink(targetTitle: String, onOpenNote: (String) -> Unit) {
        viewModelScope.launch {
            if (uiState.value.preferences.autoSaveEnabled) {
                flushSaveNow()
            }
            val existing = repository.findNoteByTitle(targetTitle)
            if (existing != null) {
                onOpenNote(existing.id)
            } else {
                val created = repository.createNote(
                    title = targetTitle,
                    content = "# $targetTitle\n\nLinked from [[${draft.value.title}]].\n",
                )
                onOpenNote(created.id)
            }
        }
    }

    fun saveManually() {
        viewModelScope.launch {
            flushSaveNow(updateBaseline = true)
            draft.update { it.copy(statusBanner = "Note saved") }
        }
    }

    fun discardEdits(onAfterDiscard: (() -> Unit)? = null) {
        autoSaveJob?.cancel()
        parseJob?.cancel()
        dirty = false
        val d = draft.value
        viewModelScope.launch {
            val baseTitle = d.baselineTitle
            val baseContent = d.baselineContent
            val computed = withContext(Dispatchers.Default) {
                val doc = MarkdownParser.parse(baseContent)
                val spans = MarkdownParser.computeSyntaxSpans(baseContent)
                Triple(doc, spans, doc.totalLines)
            }
            // Revert DB to baseline in case auto-save already wrote intermediate edits
            repository.saveNote(
                id = noteId,
                title = baseTitle,
                content = baseContent,
                isBookmarked = d.isBookmarked,
                templateId = d.templateId,
                createdAt = d.createdAt,
            )
            undoStack.clear()
            redoStack.clear()
            lastUndoPushMs = 0L
            draft.update {
                it.copy(
                    title = baseTitle,
                    contentField = TextFieldValue(baseContent, TextRange(baseContent.length)),
                    lineCount = computed.third,
                    parsedDocument = computed.first,
                    syntaxSpans = computed.second,
                    canUndo = false,
                    canRedo = false,
                    saveStatus = SaveStatus.SAVED,
                    statusBanner = if (onAfterDiscard == null) "Edits discarded" else null,
                )
            }
            onAfterDiscard?.invoke()
        }
    }

    fun deleteCurrentNote(onDeleted: () -> Unit) {
        autoSaveJob?.cancel()
        dirty = false
        viewModelScope.launch {
            repository.deleteNote(noteId)
            draft.update { it.copy(isDeleted = true) }
            onDeleted()
        }
    }

    fun resolveImageFile(uriString: String): File? = repository.resolveLocalImageFile(uriString)

    fun clearBanner() {
        draft.update { it.copy(statusBanner = null) }
    }

    fun flushSaveIfAutoSaveEnabled() {
        if (!dirty || !uiState.value.preferences.autoSaveEnabled) return
        viewModelScope.launch { flushSaveNow() }
    }

    suspend fun flushSaveNow(updateBaseline: Boolean = false) {
        autoSaveJob?.cancel()
        val d = draft.value
        if (!d.isLoaded || d.isDeleted) return
        dirty = false
        val saved = repository.saveNote(
            id = noteId,
            title = d.title,
            content = d.contentField.text,
            isBookmarked = d.isBookmarked,
            templateId = d.templateId,
            createdAt = d.createdAt,
        )
        draft.update {
            it.copy(
                title = saved.title,
                updatedAt = saved.updatedAt,
                baselineTitle = if (updateBaseline) saved.title else it.baselineTitle,
                baselineContent = if (updateBaseline) saved.content else it.baselineContent,
                saveStatus = SaveStatus.SAVED,
            )
        }
    }

    private fun onDraftMutated() {
        dirty = true
        autoSaveJob?.cancel()
        if (!uiState.value.preferences.autoSaveEnabled) return
        autoSaveJob = viewModelScope.launch {
            delay(600L)
            flushSaveNow()
        }
    }

    private data class InitialLoadData(
        val doc: MarkdownDocument,
        val spans: List<SyntaxSpan>,
        val lineCount: Int,
        val matches: List<Int>,
        val blockMatches: List<Int>,
    )

    private fun findMatchOffsets(text: String, query: String): List<Int> {
        val indices = mutableListOf<Int>()
        var pos = text.indexOf(query, 0, ignoreCase = true)
        val step = query.length.coerceAtLeast(1)
        while (pos >= 0) {
            indices.add(pos)
            pos = text.indexOf(query, pos + step, ignoreCase = true)
        }
        return indices
    }

    private fun scheduleParseAndHighlights(content: String, query: String, preferredOffset: Int? = null) {
        val revision = ++findRevision
        parseJob?.cancel()
        findJob?.cancel()
        parseJob = viewModelScope.launch(Dispatchers.Default) {
            delay(60L)
            val parsed = MarkdownParser.parse(content)
            val spans = MarkdownParser.computeSyntaxSpans(content)
            val lines = parsed.totalLines
            val q = query.trim()
            val matches = if (q.isNotEmpty()) findMatchOffsets(content, q) else emptyList()
            val blockMatches = if (q.isNotEmpty()) {
                MarkdownParser.findMatchingBlockIndices(parsed, q)
            } else {
                emptyList()
            }
            if (revision != findRevision || draft.value.contentField.text != content || draft.value.findQuery.trim() != q) return@launch
            draft.update {
                val nextActive = if (matches.isEmpty()) -1 else preferredOffset?.let { offset ->
                    matches.indexOfFirst { it >= offset }.takeIf { it >= 0 }
                } ?: 0
                it.copy(
                    parsedDocument = parsed,
                    syntaxSpans = spans,
                    lineCount = lines,
                    findMatches = matches,
                    findMatchesQuery = q,
                    findMatchesContent = content,
                    previewMatchBlockIndices = blockMatches,
                    activeMatchIndex = nextActive,
                    contentField = if (preferredOffset != null && nextActive >= 0) {
                        it.contentField.copy(selection = TextRange(matches[nextActive], (matches[nextActive] + q.length).coerceAtMost(content.length)))
                    } else it.contentField,
                    findNavigationSeq = if (preferredOffset != null && nextActive >= 0) it.findNavigationSeq + 1 else it.findNavigationSeq,
                )
            }
        }
    }

    private fun recomputeFindMatches(text: String, doc: MarkdownDocument, query: String, preferredOffset: Int? = null) {
        val revision = ++findRevision
        findJob?.cancel()
        val q = query.trim()
        if (q.isEmpty()) {
            if (draft.value.findMatches.isNotEmpty() || draft.value.previewMatchBlockIndices.isNotEmpty()) {
                draft.update {
                    it.copy(
                        findMatches = emptyList(),
                        previewMatchBlockIndices = emptyList(),
                        activeMatchIndex = -1,
                    )
                }
            }
            return
        }
        findJob = viewModelScope.launch(Dispatchers.Default) {
            val indices = findMatchOffsets(text, q)
            val blockMatches = MarkdownParser.findMatchingBlockIndices(doc, q)
            if (revision != findRevision || draft.value.contentField.text != text || draft.value.findQuery.trim() != q) return@launch
            draft.update {
                it.copy(
                    findMatches = indices,
                    findMatchesQuery = q,
                    findMatchesContent = text,
                    previewMatchBlockIndices = blockMatches,
                    activeMatchIndex = if (indices.isEmpty()) -1 else (preferredOffset?.let { offset -> indices.indexOfFirst { it >= offset }.takeIf { it >= 0 } } ?: 0),
                    findNavigationSeq = if (indices.isEmpty()) it.findNavigationSeq else it.findNavigationSeq + 1,
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        autoSaveJob?.cancel()
        parseJob?.cancel()
        findJob?.cancel()
    }

    class Factory(
        private val noteId: String,
        private val repository: NotesRepository,
        private val preferencesRepository: PreferencesRepository,
        private val exporter: NoteExporter,
        private val initialFindQuery: String = "",
        private val initialFindOffset: Int = -1,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return EditorViewModel(
                noteId = noteId,
                repository = repository,
                preferencesRepository = preferencesRepository,
                exporter = exporter,
                initialFindQuery = initialFindQuery,
                initialFindOffset = initialFindOffset,
            ) as T
        }
    }
}
