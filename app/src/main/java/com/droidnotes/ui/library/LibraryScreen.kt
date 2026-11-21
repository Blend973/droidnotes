package com.droidnotes.ui.library

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.automirrored.outlined.ViewQuilt
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidnotes.R
import com.droidnotes.data.db.SortOrder
import com.droidnotes.data.templates.NoteTemplate
import com.droidnotes.data.templates.NoteTemplates
import com.droidnotes.export.ExportFormat
import com.droidnotes.markdown.applySearchHighlight
import com.droidnotes.ui.components.OledConfirmDialog
import com.droidnotes.ui.components.OledPopupMenu
import com.droidnotes.ui.components.OledPopupMenuItem
import com.droidnotes.ui.components.trackFocusBounds
import com.droidnotes.ui.theme.JetBrainsMonoFamily
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val NOTE_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d · HH:mm")

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenNote: (String) -> Unit,
    onOpenNoteAtKeyword: (String, String, Int) -> Unit = { id, _, _ -> onOpenNote(id) },
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // The search field is driven directly by ViewModel state (same pattern as the editor's
    // find bar). Keeping a second local TextFieldValue caused the cursor to jump when the
    // debounced/async state caught up with fast typing.
    val searchFocusRequester = remember { FocusRequester() }
    // Filtering is already debounced in the ViewModel; use the same cadence for expensive
    // text-highlight building so fast typing doesn't re-highlight every visible note card.
    var highlightQuery by remember { mutableStateOf(state.searchQuery) }
    LaunchedEffect(state.searchQuery) {
        if (state.searchQuery.isEmpty()) {
            highlightQuery = ""
        } else {
            delay(120L)
            highlightQuery = state.searchQuery
        }
    }
    var showSortMenu by remember { mutableStateOf(false) }
    var showExportMenu by remember { mutableStateOf(false) }
    var pendingExportFormat by remember { mutableStateOf<ExportFormat?>(null) }
    var showTemplateDialog by remember { mutableStateOf(false) }
    var pendingDeleteNoteId by remember { mutableStateOf<String?>(null) }
    var confirmBatchDelete by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) viewModel.importFiles(uris)
    }

    // Pick a destination folder once; the ViewModel then writes one file per note there.
    val exportFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        val format = pendingExportFormat
        if (treeUri != null && format != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            viewModel.exportSelectedToTree(treeUri, format)
        }
    }

    BackHandler(enabled = state.isSelectionMode) {
        viewModel.clearSelection()
    }

    LaunchedEffect(state.statusMessage) {
        if (state.statusMessage != null) {
            delay(2200L)
            viewModel.clearStatusMessage()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            if (!state.isSelectionMode) {
                FloatingActionButton(
                    onClick = { viewModel.createBlankNote(onOpenNote) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.testTag("fab_new_note"),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = stringResource(R.string.action_new_note),
                    )
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // Top Header (Normal vs Multi-Select Contextual Bar)
            if (state.isSelectionMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0A0A0A))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = viewModel::clearSelection,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("selection_cancel_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = "Exit selection",
                                tint = Color.White,
                            )
                        }
                        Text(
                            text = "${state.selectedNoteIds.size} selected",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            IconButton(
                                onClick = { showExportMenu = true },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("selection_export_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.FileUpload,
                                    contentDescription = "Export selected notes",
                                    tint = if (state.selectedNoteIds.isNotEmpty()) Color.White else Color(0xFF525252),
                                )
                            }
                            OledPopupMenu(
                                expanded = showExportMenu,
                                onDismissRequest = { showExportMenu = false },
                            ) {
                                OledPopupMenuItem(
                                    text = "Export Markdown (.md)",
                                    onClick = {
                                        showExportMenu = false
                                        pendingExportFormat = ExportFormat.MARKDOWN
                                        exportFolderLauncher.launch(null)
                                    },
                                    testTag = "selection_export_md",
                                )
                                OledPopupMenuItem(
                                    text = "Export HTML (.html)",
                                    onClick = {
                                        showExportMenu = false
                                        pendingExportFormat = ExportFormat.HTML
                                        exportFolderLauncher.launch(null)
                                    },
                                    testTag = "selection_export_html",
                                )
                                OledPopupMenuItem(
                                    text = "Export PDF (.pdf)",
                                    onClick = {
                                        showExportMenu = false
                                        pendingExportFormat = ExportFormat.PDF
                                        exportFolderLauncher.launch(null)
                                    },
                                    testTag = "selection_export_pdf",
                                )
                            }
                        }
                        IconButton(
                            onClick = viewModel::selectAllVisible,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("selection_select_all_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.SelectAll,
                                contentDescription = "Select all notes",
                                tint = Color.White,
                            )
                        }
                        IconButton(
                            onClick = {
                                if (state.selectedNoteIds.isNotEmpty()) confirmBatchDelete = true
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("selection_delete_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteOutline,
                                contentDescription = "Delete selected notes",
                                tint = if (state.selectedNoteIds.isNotEmpty()) Color.White else Color(0xFF525252),
                            )
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            text = "${state.notes.size} notes",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.notes.isNotEmpty()) {
                            IconButton(
                                onClick = { viewModel.enterSelectionMode(null) },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("multi_select_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Checklist,
                                    contentDescription = "Select notes",
                                    tint = MaterialTheme.colorScheme.onBackground,
                                )
                            }
                        }
                        IconButton(
                            onClick = { showTemplateDialog = true },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("templates_button"),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ViewQuilt,
                                contentDescription = stringResource(R.string.action_templates),
                                tint = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                        IconButton(
                            onClick = {
                                importLauncher.launch(arrayOf("text/markdown", "text/plain", "text/*", "*/*"))
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("import_notes_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.FileOpen,
                                contentDescription = stringResource(R.string.action_import),
                                tint = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                        Box {
                            IconButton(
                                onClick = { showSortMenu = true },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("sort_notes_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.Sort,
                                    contentDescription = "Sort notes",
                                    tint = MaterialTheme.colorScheme.onBackground,
                                )
                            }
                            OledPopupMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false },
                            ) {
                                SortOrder.entries.forEach { order ->
                                    OledPopupMenuItem(
                                        text = if (order == state.sortOrder) "● ${order.label}" else order.label,
                                        onClick = {
                                            showSortMenu = false
                                            viewModel.onSortOrderSelected(order)
                                        },
                                        testTag = "sort_option_${order.name.lowercase()}",
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Search Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .heightIn(min = 48.dp)
                    .background(Color(0xFF0A0A0A), RoundedCornerShape(6.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                    .padding(start = 12.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = "Search",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 8.dp),
                ) {
                    if (state.searchQuery.isEmpty()) {
                        Text(
                            text = stringResource(R.string.action_search_notes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    BasicTextField(
                        value = state.searchQuery,
                        onValueChange = viewModel::onSearchQueryChange,
                        singleLine = true,
                        cursorBrush = SolidColor(Color.White),
                        textStyle = TextStyle(
                            color = Color.White,
                            fontSize = 14.sp,
                            fontFamily = JetBrainsMonoFamily,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(searchFocusRequester)
                            .trackFocusBounds()
                            .testTag("search_notes_input"),
                    )
                }
                if (state.searchQuery.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            viewModel.onSearchQueryChange("")
                            searchFocusRequester.requestFocus()
                        },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("clear_search_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Clear search",
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                    Box(
                        modifier = Modifier
                            .height(48.dp)
                            .clickable(onClick = viewModel::toggleKeywordSearchMode)
                            .testTag("toggle_keyword_search"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (state.keywordSearchMode) "Files" else "Keyword",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                            color = Color.White,
                            modifier = Modifier
                                .background(Color(0xFF141414), RoundedCornerShape(4.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            // Filter Chips Row (Bookmarks + Tags)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChipMono(
                    label = "Bookmarked",
                    selected = state.bookmarkedOnly,
                    onClick = viewModel::toggleBookmarkedFilter,
                    testTag = "filter_bookmarked_chip",
                )
                state.allTags.forEach { tag ->
                    FilterChipMono(
                        label = "#$tag",
                        selected = state.selectedTag == tag,
                        onClick = { viewModel.selectTag(tag) },
                        testTag = "filter_tag_$tag",
                    )
                }
            }

            state.statusMessage?.let { banner ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .background(Color.White, RoundedCornerShape(4.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = banner,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.Black,
                    )
                }
            }

            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline)

            val isKeywordActive = state.keywordSearchMode && state.searchQuery.isNotBlank()

            if (isKeywordActive) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0A0A0A))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${state.totalKeywordMatches} match(es) across ${state.keywordResults.size} file(s)",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                        color = Color.White,
                        modifier = Modifier.testTag("keyword_search_summary"),
                    )
                }
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline)
            }

            if (isKeywordActive && state.keywordResults.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(40.dp),
                        )
                        Text(
                            text = "No keyword matches across notes",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                        )
                        Text(
                            text = "\"${state.searchQuery.trim()}\" was not found in any stored note.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else if (isKeywordActive) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        items = state.keywordResults,
                        key = { "kw_${it.note.id}" },
                    ) { group ->
                        KeywordMatchGroupCard(
                            group = group,
                            searchQuery = highlightQuery,
                            onOpenFile = {
                                onOpenNoteAtKeyword(group.note.id, state.searchQuery.trim(), -1)
                            },
                            onOpenMatchOffset = { offset ->
                                onOpenNoteAtKeyword(group.note.id, state.searchQuery.trim(), offset)
                            },
                        )
                    }
                }
            } else if (!state.isLoading && state.notes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(44.dp),
                        )
                        Text(
                            text = if (state.searchQuery.isNotBlank() || state.bookmarkedOnly || state.selectedTag != null) {
                                "No matching notes"
                            } else {
                                stringResource(R.string.empty_notes_title)
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            text = stringResource(R.string.empty_notes_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = { showTemplateDialog = true },
                                border = BorderStroke(1.dp, Color.White),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.testTag("empty_templates_button"),
                            ) {
                                Text("Browse Templates", color = Color.White)
                            }
                            OutlinedButton(
                                onClick = { viewModel.loadSyntaxGuide(onOpenNote) },
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.testTag("load_syntax_guide_button"),
                            ) {
                                Text("Syntax Guide", color = Color(0xFFD4D4D4))
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        items = state.notes,
                        key = { it.note.id },
                    ) { item ->
                        val isSelected = item.note.id in state.selectedNoteIds
                        NoteSummaryCard(
                            item = item,
                            searchQuery = highlightQuery,
                            showNotePreview = state.showNotePreview,
                            isSelectionMode = state.isSelectionMode,
                            isSelected = isSelected,
                            onClick = {
                                if (state.isSelectionMode) {
                                    viewModel.toggleNoteSelection(item.note.id)
                                } else {
                                    onOpenNote(item.note.id)
                                }
                            },
                            onLongClick = {
                                if (!state.isSelectionMode) {
                                    viewModel.enterSelectionMode(item.note.id)
                                } else {
                                    viewModel.toggleNoteSelection(item.note.id)
                                }
                            },
                            onToggleBookmark = { viewModel.toggleBookmark(item.note) },
                            onRequestDelete = { pendingDeleteNoteId = item.note.id },
                        )
                    }
                }
            }
        }
    }

    if (showTemplateDialog) {
        TemplatePickerDialog(
            onDismiss = { showTemplateDialog = false },
            onSelectTemplate = { template ->
                showTemplateDialog = false
                viewModel.createFromTemplate(template, onOpenNote)
            },
        )
    }

    pendingDeleteNoteId?.let { targetId ->
        val noteTitle = state.notes.firstOrNull { it.note.id == targetId }?.note?.title ?: "this note"
        OledConfirmDialog(
            title = "Delete Note?",
            message = "Permanently delete \"$noteTitle\"? This action cannot be undone.",
            confirmLabel = "Delete",
            onConfirm = {
                pendingDeleteNoteId = null
                viewModel.deleteNote(targetId)
            },
            onDismiss = { pendingDeleteNoteId = null },
            confirmTag = "confirm_delete_single_note",
            dismissTag = "cancel_delete_single_note",
        )
    }

    if (confirmBatchDelete) {
        val count = state.selectedNoteIds.size
        OledConfirmDialog(
            title = "Delete $count Note(s)?",
            message = "Permanently delete the $count selected note(s)? This action cannot be undone.",
            confirmLabel = "Delete ($count)",
            onConfirm = {
                confirmBatchDelete = false
                viewModel.deleteSelectedNotes()
            },
            onDismiss = { confirmBatchDelete = false },
            confirmTag = "confirm_delete_batch_notes",
            dismissTag = "cancel_delete_batch_notes",
        )
    }
}

@Composable
private fun FilterChipMono(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Box(
            modifier = Modifier
                .height(48.dp)
                .clickable(onClick = onClick)
                .testTag(testTag),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) Color.Black else Color(0xFFD4D4D4),
                modifier = Modifier
                    .background(
                        color = if (selected) Color.White else Color(0xFF0A0A0A),
                        shape = RoundedCornerShape(6.dp),
                    )
                    .border(
                        width = 1.dp,
                        color = if (selected) Color.White else MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(6.dp),
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

private val NOTE_CARD_PADDING_PREVIEW = 12.dp
private val NOTE_CARD_PADDING_COMPACT = 8.dp
private val NOTE_CARD_HEIGHT_PREVIEW = 128.dp
private val NOTE_CARD_HEIGHT_COMPACT = 76.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteSummaryCard(
    item: NoteSummaryItem,
    searchQuery: String,
    showNotePreview: Boolean,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleBookmark: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    val note = item.note
    val dateLabel = remember(note.updatedAt) {
        NOTE_DATE_FORMATTER.format(Instant.ofEpochMilli(note.updatedAt).atZone(ZoneId.systemDefault()))
    }
    val highlightedTitle = remember(note.title, searchQuery) {
        applySearchHighlight(AnnotatedString(note.title), searchQuery)
    }
    val cardPadding = if (showNotePreview) NOTE_CARD_PADDING_PREVIEW else NOTE_CARD_PADDING_COMPACT
    val cardHeight = if (showNotePreview) NOTE_CARD_HEIGHT_PREVIEW else NOTE_CARD_HEIGHT_COMPACT

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(cardHeight)
            .background(
                color = if (isSelected) Color(0xFF141414) else Color(0xFF0A0A0A),
                shape = RoundedCornerShape(8.dp),
            )
            .border(
                width = 1.dp,
                color = when {
                    isSelected -> Color.White
                    note.isBookmarked -> Color(0xFF525252)
                    else -> MaterialTheme.colorScheme.outline
                },
                shape = RoundedCornerShape(8.dp),
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(cardPadding)
            .testTag("note_card_${note.id}"),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (isSelectionMode) {
                Icon(
                    imageVector = if (isSelected) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                    contentDescription = if (isSelected) "Selected" else "Not selected",
                    tint = if (isSelected) Color.White else Color(0xFF737373),
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(20.dp),
                )
            }
            Text(
                text = highlightedTitle,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!isSelectionMode) {
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 32.dp, height = 28.dp)
                                .clickable(onClick = onToggleBookmark)
                                .testTag("bookmark_note_${note.id}"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = if (note.isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                                contentDescription = if (note.isBookmarked) {
                                    stringResource(R.string.action_remove_bookmark)
                                } else {
                                    stringResource(R.string.action_bookmark)
                                },
                                tint = if (note.isBookmarked) Color.White else Color(0xFF737373),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(width = 32.dp, height = 28.dp)
                                .clickable(onClick = onRequestDelete)
                                .testTag("delete_note_${note.id}"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteOutline,
                                contentDescription = "Delete note",
                                tint = Color(0xFF737373),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }

        if (showNotePreview) {
            val highlightedSnippet = remember(item.snippet, searchQuery) {
                applySearchHighlight(AnnotatedString(item.snippet), searchQuery)
            }
            Text(
                text = highlightedSnippet,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                item.tags.take(3).forEach { tag ->
                    Text(
                        text = "#$tag",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFD4D4D4),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .background(Color(0xFF141414), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Text(
                text = "${note.wordCount}w · $dateLabel",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF737373),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun KeywordMatchGroupCard(
    group: NoteKeywordMatchGroup,
    searchQuery: String,
    onOpenFile: () -> Unit,
    onOpenMatchOffset: (Int) -> Unit,
) {
    val note = group.note
    val highlightedTitle = remember(note.title, searchQuery) {
        applySearchHighlight(AnnotatedString(note.title), searchQuery)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0A0A0A), RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .testTag("keyword_file_card_${note.id}"),
    ) {
        // File header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF141414))
                .minimumInteractiveComponentSize()
                .clickable(onClick = onOpenFile)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Description,
                    contentDescription = null,
                    tint = Color(0xFFA3A3A3),
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = highlightedTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "${group.matchCount}",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                color = Color.Black,
                modifier = Modifier
                    .background(Color.White, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }

        if (group.lineMatches.isNotEmpty()) {
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline)
            group.lineMatches.forEachIndexed { idx, match ->
                val highlightedLine = remember(match.lineText, searchQuery) {
                    applySearchHighlight(AnnotatedString(match.lineText), searchQuery)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .clickable { onOpenMatchOffset(match.charOffset) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = "${match.lineNumber}",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                        color = Color(0xFF737373),
                        modifier = Modifier.padding(end = 10.dp, top = 2.dp),
                    )
                    Text(
                        text = highlightedLine,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
                        color = Color(0xFFD4D4D4),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (idx < group.lineMatches.lastIndex) {
                    HorizontalDivider(thickness = 1.dp, color = Color(0xFF1A1A1A))
                }
            }
        }
    }
}

@Composable
private fun TemplatePickerDialog(
    onDismiss: () -> Unit,
    onSelectTemplate: (NoteTemplate) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            color = Color(0xFF0A0A0A),
            contentColor = Color.White,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 24.dp)
                .widthIn(max = 520.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Start from Template",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White,
                        )
                        Text(
                            text = "Structured OLED layouts with Markdown, LaTeX, tables, and charts.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.minimumInteractiveComponentSize(),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Close templates",
                            tint = Color.White,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                LazyColumn(
                    modifier = Modifier.height(420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(NoteTemplates.all + NoteTemplates.syntaxGuideTemplate, key = { it.id }) { tpl ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.Black, RoundedCornerShape(8.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                                .clickable(onClick = { onSelectTemplate(tpl) })
                                .padding(14.dp)
                                .testTag("template_item_${tpl.id}"),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = tpl.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = tpl.category.uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFA3A3A3),
                                    modifier = Modifier
                                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = tpl.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
