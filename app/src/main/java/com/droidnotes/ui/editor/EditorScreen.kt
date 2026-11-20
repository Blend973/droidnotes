package com.droidnotes.ui.editor

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidnotes.R
import com.droidnotes.markdown.MarkdownBlock
import com.droidnotes.markdown.MarkdownPreview
import com.droidnotes.markdown.MarkdownSyntaxVisualTransformation
import com.droidnotes.ui.components.NoIndication
import com.droidnotes.ui.components.OledConfirmDialog
import com.droidnotes.ui.components.OledPopupMenu
import com.droidnotes.ui.components.OledPopupMenuItem
import com.droidnotes.ui.components.trackFocusBounds
import com.droidnotes.ui.theme.JetBrainsMonoFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val SAFE_FILENAME_REGEX = Regex("""[^a-zA-Z0-9_-]+""")
private const val CURSOR_MARKER = "⟦cursor⟧"

@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    onNavigateBack: () -> Unit,
    onOpenNote: (String) -> Unit,
    onFilterByTag: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var showMoreMenu by remember { mutableStateOf(false) }
    var showWikiPicker by remember { mutableStateOf(false) }
    var editingBlock by remember { mutableStateOf<MarkdownBlock?>(null) }
    var confirmDiscardDialog by remember { mutableStateOf(false) }
    var confirmDeleteDialog by remember { mutableStateOf(false) }
    var confirmExitUnsavedDialog by remember { mutableStateOf(false) }
    val findFocusRequester = remember { FocusRequester() }

    // Editing/find stays immediate, but preview highlighting is debounced so typing in the
    // find bar doesn't rebuild AnnotatedString for every table cell on every keystroke.
    var previewFindQuery by remember { mutableStateOf(state.findQuery) }
    LaunchedEffect(state.findQuery) {
        if (state.findQuery.isEmpty()) {
            previewFindQuery = ""
        } else {
            delay(120L)
            previewFindQuery = state.findQuery
        }
    }

    // Hoisted scroll states keyed on noteId so switching Edit <-> Preview preserves exact scroll position!
    val editorScrollState = rememberScrollState()
    val previewListState = rememberLazyListState()
    var editorTextLayout by remember { mutableStateOf<TextLayoutResult?>(null) }

    val clipboardManager = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri != null) viewModel.insertPickedImage(uri)
    }

    val exportMdLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri: Uri? -> if (uri != null) viewModel.exportMarkdown(uri) }

    val exportHtmlLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/html"),
    ) { uri: Uri? -> if (uri != null) viewModel.exportHtml(uri) }

    val exportPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri: Uri? -> if (uri != null) viewModel.exportPdf(uri) }

    val safeFilenameBase = remember(state.title) {
        state.title.trim().replace(SAFE_FILENAME_REGEX, "_").ifBlank { "note" }
    }

    fun requestNavigateBack() {
        if (state.preferences.autoSaveEnabled) {
            scope.launch {
                viewModel.flushSaveNow()
                onNavigateBack()
            }
        } else if (state.hasUnsavedChanges) {
            confirmExitUnsavedDialog = true
        } else {
            onNavigateBack()
        }
    }

    // Immediate auto-save flush on lifecycle pause/stop (only when auto-save is enabled)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                viewModel.flushSaveIfAutoSaveEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.flushSaveIfAutoSaveEnabled()
        }
    }

    BackHandler {
        when {
            showWikiPicker -> showWikiPicker = false
            editingBlock != null -> editingBlock = null
            showMoreMenu -> showMoreMenu = false
            else -> requestNavigateBack()
        }
    }

    // Scroll to active Find match in Editor and/or Preview whenever user steps Up/Down, updates query, or opens with keyword
    LaunchedEffect(state.findNavigationSeq, editorTextLayout) {
        if (state.findNavigationSeq <= 0) return@LaunchedEffect
        val matchIdx = state.activeMatchIndex
        if (matchIdx < 0) return@LaunchedEffect

        val charOffset = state.findMatches.getOrNull(matchIdx)
        val layout = editorTextLayout
        if (charOffset != null && layout != null && charOffset <= layout.layoutInput.text.length) {
            val line = layout.getLineForOffset(charOffset)
            val lineTop = layout.getLineTop(line).toInt()
            val targetScroll = (lineTop - 120).coerceIn(0, editorScrollState.maxValue)
            editorScrollState.scrollTo(targetScroll)
        }

        val blockMatches = state.previewMatchBlockIndices
        if (blockMatches.isNotEmpty()) {
            val totalMatches = state.findMatches.size.coerceAtLeast(1)
            val blockIdxPos = ((matchIdx.toFloat() / totalMatches) * blockMatches.size)
                .toInt()
                .coerceIn(0, blockMatches.lastIndex)
            previewListState.scrollToItem(blockMatches[blockIdxPos])
        }
    }

    LaunchedEffect(state.statusBanner) {
        if (state.statusBanner != null) {
            delay(2000L)
            viewModel.clearBanner()
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val isWide = maxWidth >= 700.dp
            val effectiveMode = if (!isWide && state.activePaneMode == ActivePaneMode.SPLIT) {
                ActivePaneMode.EDIT
            } else {
                state.activePaneMode
            }

            Column(modifier = Modifier.fillMaxSize()) {
                // Top Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = ::requestNavigateBack,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("editor_back_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            tint = Color.White,
                        )
                    }

                    // Editable Title
                    Box(modifier = Modifier.weight(1f)) {
                        if (state.title.isEmpty()) {
                            Text(
                                text = "Untitled Note",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        BasicTextField(
                            value = state.title,
                            onValueChange = viewModel::onTitleChange,
                            singleLine = true,
                            cursorBrush = SolidColor(Color.White),
                            textStyle = MaterialTheme.typography.titleMedium.copy(color = Color.White),
                            modifier = Modifier
                                .fillMaxWidth()
                                .trackFocusBounds()
                                .testTag("editor_title_input"),
                        )
                    }

                    // Save status badge
                    val statusLabel = when (state.saveStatus) {
                        SaveStatus.SAVING -> stringResource(R.string.status_saving)
                        SaveStatus.UNSAVED -> "UNSAVED"
                        SaveStatus.SAVED -> stringResource(R.string.status_saved)
                    }
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.saveStatus == SaveStatus.SAVED) Color(0xFF737373) else Color.White,
                        modifier = Modifier
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                            .testTag("editor_save_status"),
                    )

                    if (!state.preferences.autoSaveEnabled && state.hasUnsavedChanges) {
                        IconButton(
                            onClick = viewModel::saveManually,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("editor_manual_save_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Save,
                                contentDescription = "Save note",
                                tint = Color.White,
                            )
                        }
                    }

                    if (state.hasSessionChanges) {
                        IconButton(
                            onClick = { confirmDiscardDialog = true },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("editor_discard_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Undo,
                                contentDescription = "Discard edits",
                                tint = Color.White,
                            )
                        }
                    }

                    IconButton(
                        onClick = viewModel::toggleFindBar,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("editor_find_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = stringResource(R.string.action_find_in_note),
                            tint = if (state.showFindBar) Color.White else Color(0xFFA3A3A3),
                        )
                    }

                    IconButton(
                        onClick = viewModel::toggleBookmark,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("editor_bookmark_button"),
                    ) {
                        Icon(
                            imageVector = if (state.isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                            contentDescription = stringResource(R.string.action_bookmark),
                            tint = if (state.isBookmarked) Color.White else Color(0xFFA3A3A3),
                        )
                    }

                    Box {
                        IconButton(
                            onClick = { showMoreMenu = true },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("editor_more_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.MoreVert,
                                contentDescription = stringResource(R.string.action_more_options),
                                tint = Color.White,
                            )
                        }
                        OledPopupMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false },
                        ) {
                            if (state.hasSessionChanges) {
                                OledPopupMenuItem(
                                    text = "Discard Edits",
                                    onClick = {
                                        showMoreMenu = false
                                        confirmDiscardDialog = true
                                    },
                                    testTag = "menu_discard_edits",
                                )
                            }
                            OledPopupMenuItem(
                                text = "Copy as Markdown",
                                onClick = {
                                    showMoreMenu = false
                                    viewModel.copyAsMarkdown(clipboardManager)
                                },
                                testTag = "menu_copy_markdown",
                            )
                            OledPopupMenuItem(
                                text = "Copy as Rich Text (HTML)",
                                onClick = {
                                    showMoreMenu = false
                                    viewModel.copyAsRichHtml(clipboardManager)
                                },
                                testTag = "menu_copy_rich",
                            )
                            OledPopupMenuItem(
                                text = "Smart Paste (Image / Rich)",
                                onClick = {
                                    showMoreMenu = false
                                    viewModel.handleSmartPaste(clipboardManager, context.contentResolver)
                                },
                                testTag = "menu_smart_paste",
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                            OledPopupMenuItem(
                                text = "Export Markdown (.md)",
                                onClick = {
                                    showMoreMenu = false
                                    exportMdLauncher.launch("$safeFilenameBase.md")
                                },
                                testTag = "menu_export_md",
                            )
                            OledPopupMenuItem(
                                text = "Export HTML (.html)",
                                onClick = {
                                    showMoreMenu = false
                                    exportHtmlLauncher.launch("$safeFilenameBase.html")
                                },
                                testTag = "menu_export_html",
                            )
                            OledPopupMenuItem(
                                text = "Export PDF (.pdf)",
                                onClick = {
                                    showMoreMenu = false
                                    exportPdfLauncher.launch("$safeFilenameBase.pdf")
                                },
                                testTag = "menu_export_pdf",
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                            OledPopupMenuItem(
                                text = "Delete Note",
                                onClick = {
                                    showMoreMenu = false
                                    confirmDeleteDialog = true
                                },
                                testTag = "menu_delete_note",
                            )
                        }
                    }
                }

                // Mode Switcher & Configurable Metrics Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ModeChip(
                            label = "Edit",
                            selected = effectiveMode == ActivePaneMode.EDIT,
                            onClick = { viewModel.setPaneMode(ActivePaneMode.EDIT) },
                            testTag = "mode_edit_chip",
                        )
                        ModeChip(
                            label = "Preview",
                            selected = effectiveMode == ActivePaneMode.PREVIEW,
                            onClick = { viewModel.setPaneMode(ActivePaneMode.PREVIEW) },
                            testTag = "mode_preview_chip",
                        )
                        if (isWide) {
                            ModeChip(
                                label = "Split",
                                selected = effectiveMode == ActivePaneMode.SPLIT,
                                onClick = { viewModel.setPaneMode(ActivePaneMode.SPLIT) },
                                testTag = "mode_split_chip",
                            )
                        }
                    }

                    val metricsParts = buildList {
                        if (state.preferences.showLineCount) add("${state.lineCount}L")
                        if (state.preferences.showWordCount) add("${state.wordCount}W")
                        if (state.preferences.showCharCount) add("${state.charCount}C")
                    }
                    if (metricsParts.isNotEmpty()) {
                        Text(
                            text = metricsParts.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("editor_metrics_text"),
                        )
                    }
                }

                // Find & Highlight Bar
                if (state.showFindBar) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0A0A0A))
                            .border(1.dp, MaterialTheme.colorScheme.outline)
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            if (state.findQuery.isEmpty()) {
                                Text(
                                    text = "Find & highlight in note…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            BasicTextField(
                                value = state.findQuery,
                                onValueChange = viewModel::onFindQueryChange,
                                singleLine = true,
                                cursorBrush = SolidColor(Color.White),
                                textStyle = TextStyle(
                                    color = Color.White,
                                    fontFamily = JetBrainsMonoFamily,
                                    fontSize = 13.sp,
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(findFocusRequester)
                                    .trackFocusBounds()
                                    .onPreviewKeyEvent { event ->
                                        if (event.type == KeyEventType.KeyDown) {
                                            when (event.key) {
                                                Key.DirectionDown, Key.Enter -> {
                                                    viewModel.stepFindMatch(1)
                                                    true
                                                }
                                                Key.DirectionUp -> {
                                                    viewModel.stepFindMatch(-1)
                                                    true
                                                }
                                                else -> false
                                            }
                                        } else false
                                    }
                                    .testTag("find_in_note_input"),
                            )
                        }
                        IconButton(
                            onClick = {
                                viewModel.onFindQueryChange("")
                                findFocusRequester.requestFocus()
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("find_clear_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = "Clear find query",
                                tint = Color.White,
                            )
                        }
                        val countLabel = if (state.findMatches.isEmpty()) {
                            "0/0"
                        } else {
                            "${state.activeMatchIndex + 1}/${state.findMatches.size}"
                        }
                        Text(
                            text = countLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                        IconButton(
                            onClick = { viewModel.stepFindMatch(-1) },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("find_prev_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.KeyboardArrowUp,
                                contentDescription = "Previous match",
                                tint = Color.White,
                            )
                        }
                        IconButton(
                            onClick = { viewModel.stepFindMatch(1) },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("find_next_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.KeyboardArrowDown,
                                contentDescription = "Next match",
                                tint = Color.White,
                            )
                        }
                        IconButton(
                            onClick = viewModel::toggleFindBar,
                            modifier = Modifier.minimumInteractiveComponentSize(),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = "Close find",
                                tint = Color.White,
                            )
                        }
                    }
                }

                state.statusBanner?.let { banner ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White)
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = banner,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.Black,
                        )
                    }
                }

                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline)

                // Main Content Area (Edit / Preview / Side-by-Side Split)
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (effectiveMode) {
                        ActivePaneMode.EDIT -> RawMarkdownEditorPane(
                            state = state,
                            scrollState = editorScrollState,
                            textLayout = editorTextLayout,
                            onTextLayout = { editorTextLayout = it },
                            onContentChange = viewModel::onContentChange,
                            modifier = Modifier.fillMaxSize(),
                        )
                        ActivePaneMode.PREVIEW -> MarkdownPreview(
                            document = state.parsedDocument,
                            preferences = state.preferences,
                            searchQuery = previewFindQuery,
                            resolveImageFile = viewModel::resolveImageFile,
                            onToggleTask = viewModel::toggleTaskAtLine,
                            onWikiLinkClick = { target ->
                                viewModel.openOrCreateWikiLink(target, onOpenNote)
                            },
                            onTagClick = { tag ->
                                scope.launch {
                                    if (state.preferences.autoSaveEnabled) {
                                        viewModel.flushSaveNow()
                                    }
                                    onFilterByTag(tag)
                                }
                            },
                            listState = previewListState,
                            onEditBlock = { editingBlock = it },
                            modifier = Modifier.fillMaxSize(),
                        )
                        ActivePaneMode.SPLIT -> Row(modifier = Modifier.fillMaxSize()) {
                            RawMarkdownEditorPane(
                                state = state,
                                scrollState = editorScrollState,
                                textLayout = editorTextLayout,
                                onTextLayout = { editorTextLayout = it },
                                onContentChange = viewModel::onContentChange,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                            )
                            VerticalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline)
                            MarkdownPreview(
                                document = state.parsedDocument,
                                preferences = state.preferences,
                                searchQuery = previewFindQuery,
                                resolveImageFile = viewModel::resolveImageFile,
                                onToggleTask = viewModel::toggleTaskAtLine,
                                onWikiLinkClick = { target ->
                                    viewModel.openOrCreateWikiLink(target, onOpenNote)
                                },
                                onTagClick = { tag ->
                                    scope.launch {
                                        if (state.preferences.autoSaveEnabled) {
                                            viewModel.flushSaveNow()
                                        }
                                        onFilterByTag(tag)
                                    }
                                },
                                listState = previewListState,
                                onEditBlock = { editingBlock = it },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                            )
                        }
                    }
                }

                // Markdown Formatting Toolbar (uniform padding & spacing across every tool)
                if (effectiveMode != ActivePaneMode.PREVIEW) {
                    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline)
                    MarkdownFormattingBar(
                        tabSpaces = " ".repeat(state.preferences.tabSize),
                        canUndo = state.canUndo,
                        canRedo = state.canRedo,
                        onUndo = viewModel::undo,
                        onRedo = viewModel::redo,
                        onToggleBulletList = viewModel::toggleBulletList,
                        onToggleNumberedList = viewModel::toggleNumberedList,
                        onIndent = viewModel::indentListItem,
                        onUnindent = viewModel::unindentListItem,
                        onToggleBlockquote = viewModel::toggleBlockquote,
                        onMoveLineUp = viewModel::moveLineUp,
                        onMoveLineDown = viewModel::moveLineDown,
                        onRemoveLine = viewModel::removeLine,
                        onDeleteParagraph = viewModel::deleteParagraph,
                        onInsertSnippet = { snippet, marker -> viewModel.insertSnippetAtCursor(snippet, marker) },
                        onWrapSelection = { pre, suf, ph -> viewModel.wrapSelection(pre, suf, ph) },
                        onOpenWikiPicker = { showWikiPicker = true },
                        onPickImage = {
                            photoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                    )
                }
            }
        }
    }

    if (showWikiPicker) {
        Dialog(
            onDismissRequest = { showWikiPicker = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                color = Color(0xFF0A0A0A),
                contentColor = Color.White,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .widthIn(max = 460.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Link Note [[Wikilink]]", style = MaterialTheme.typography.titleMedium, color = Color.White)
                        IconButton(
                            onClick = { showWikiPicker = false },
                            modifier = Modifier.minimumInteractiveComponentSize(),
                        ) {
                            Icon(Icons.Outlined.Close, contentDescription = "Close", tint = Color.White)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn(
                        modifier = Modifier.height(260.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(state.allNoteTitles) { noteTitle ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color.Black, RoundedCornerShape(6.dp))
                                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                                    .clickable {
                                        showWikiPicker = false
                                        viewModel.insertSnippetAtCursor("[[$noteTitle]]")
                                    }
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                            ) {
                                Text(
                                    text = "[[$noteTitle]]",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    editingBlock?.let { activeBlock ->
        BlockScopeEditorDialog(
            block = activeBlock,
            onDismiss = { editingBlock = null },
            onApply = { startLine, endLine, updatedMarkdown ->
                editingBlock = null
                viewModel.applyScopedBlockEdit(startLine, endLine, updatedMarkdown)
            },
        )
    }

    if (confirmDiscardDialog) {
        OledConfirmDialog(
            title = "Discard Edits?",
            message = "Revert this note to how it was when you opened it, discarding all edits from this session?",
            confirmLabel = "Discard",
            onConfirm = {
                confirmDiscardDialog = false
                viewModel.discardEdits()
            },
            onDismiss = { confirmDiscardDialog = false },
            confirmTag = "confirm_discard_edits",
            dismissTag = "cancel_discard_edits",
        )
    }

    if (confirmExitUnsavedDialog) {
        OledConfirmDialog(
            title = "Unsaved Changes",
            message = "Auto-save is off and you have unsaved edits. Save before leaving?",
            confirmLabel = "Save & Exit",
            secondaryActionLabel = "Discard",
            onConfirm = {
                confirmExitUnsavedDialog = false
                scope.launch {
                    viewModel.flushSaveNow(updateBaseline = true)
                    onNavigateBack()
                }
            },
            onSecondaryAction = {
                confirmExitUnsavedDialog = false
                viewModel.discardEdits { onNavigateBack() }
            },
            onDismiss = { confirmExitUnsavedDialog = false },
            confirmTag = "confirm_save_and_exit",
            dismissTag = "cancel_exit_editor",
        )
    }

    if (confirmDeleteDialog) {
        OledConfirmDialog(
            title = "Delete Note?",
            message = "Permanently delete \"${state.title.ifBlank { "this note" }}\"? This cannot be undone.",
            confirmLabel = "Delete",
            onConfirm = {
                confirmDeleteDialog = false
                viewModel.deleteCurrentNote(onNavigateBack)
            },
            onDismiss = { confirmDeleteDialog = false },
            confirmTag = "confirm_delete_editor_note",
            dismissTag = "cancel_delete_editor_note",
        )
    }
}

@Composable
private fun ModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String,
) {
    Surface(
        onClick = onClick,
        color = if (selected) Color.White else Color(0xFF0A0A0A),
        contentColor = if (selected) Color.Black else Color(0xFFA3A3A3),
        border = BorderStroke(1.dp, if (selected) Color.White else MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(5.dp),
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .testTag(testTag),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun RawMarkdownEditorPane(
    state: EditorUiState,
    scrollState: ScrollState,
    textLayout: TextLayoutResult?,
    onTextLayout: (TextLayoutResult) -> Unit,
    onContentChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeOffset = state.findMatches.getOrNull(state.activeMatchIndex) ?: -1
    val visualTransformation = remember(state.syntaxSpans, state.findQuery, activeOffset) {
        MarkdownSyntaxVisualTransformation(state.syntaxSpans, state.findQuery, activeOffset)
    }
    val fontSize = state.preferences.editorFontSizeSp.sp
    val lineHeight = (state.preferences.editorFontSizeSp * state.preferences.lineSpacingMultiplier).sp
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = modifier.background(Color.Black),
    ) {
        val viewportHeightPx = with(density) { maxHeight.toPx() }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(vertical = 12.dp),
        ) {
            if (state.preferences.showLineNumbers) {
                EditorLineNumberGutter(
                    textLayout = textLayout,
                    scrollState = scrollState,
                    viewportHeightPx = viewportHeightPx,
                    gutterFontSize = (state.preferences.editorFontSizeSp - 2f).coerceAtLeast(10f).sp,
                    lineHeight = lineHeight,
                    topPaddingPx = with(density) { 12.dp.toPx() },
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 14.dp, start = if (state.preferences.showLineNumbers) 0.dp else 14.dp),
            ) {
                if (state.contentField.text.isEmpty()) {
                    Text(
                        text = "Write Markdown, \$LaTeX\$, [[wikilinks]], tables, or ```chart blocks…",
                        style = TextStyle(
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = fontSize,
                            lineHeight = lineHeight,
                            color = Color(0xFF525252),
                        ),
                    )
                }
                BasicTextField(
                    value = state.contentField,
                    onValueChange = onContentChange,
                    onTextLayout = onTextLayout,
                    visualTransformation = visualTransformation,
                    cursorBrush = SolidColor(Color.White),
                    textStyle = TextStyle(
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = fontSize,
                        lineHeight = lineHeight,
                        color = Color(0xFFF5F5F5),
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .trackFocusBounds()
                        .testTag("editor_markdown_input"),
                )
            }
        }
    }
}

@Composable
private fun EditorLineNumberGutter(
    textLayout: TextLayoutResult?,
    scrollState: ScrollState,
    viewportHeightPx: Float,
    gutterFontSize: TextUnit,
    lineHeight: TextUnit,
    topPaddingPx: Float,
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer(cacheSize = 256)
    val gutterTextStyle = remember(gutterFontSize) {
        TextStyle(
            fontFamily = JetBrainsMonoFamily,
            fontSize = gutterFontSize,
            color = Color(0xFF525252),
        )
    }

    // Map visual lines to logical lines so wrapped lines don't increment line numbers
    val logicalLineMap = remember(textLayout) {
        if (textLayout == null || textLayout.lineCount <= 0) {
            emptyMap()
        } else {
            val text = textLayout.layoutInput.text.text
            val map = HashMap<Int, Int>(textLayout.lineCount)
            var currentLogicalLine = 1
            for (v in 0 until textLayout.lineCount) {
                val start = textLayout.getLineStart(v)
                if (start == 0 || (start - 1 < text.length && text[start - 1] == '\n')) {
                    map[v] = currentLogicalLine
                    currentLogicalLine++
                }
            }
            map
        }
    }

    val totalLogicalLines = (logicalLineMap.values.maxOrNull() ?: 1).coerceAtLeast(1)
    val digitCount = totalLogicalLines.toString().length.coerceAtLeast(2)
    val digitsWidthPx = remember(gutterTextStyle, digitCount) {
        textMeasurer.measure("8".repeat(digitCount), style = gutterTextStyle).size.width
    }
    // Canvas onDraw can run every frame while scrolling. Cache the measured line-number
    // layouts instead of re-measuring them on each draw.
    val gutterLayoutCache = remember(gutterTextStyle, totalLogicalLines) {
        mutableMapOf<Int, TextLayoutResult>()
    }
    val startPadDp = 10.dp
    val endPadDp = 10.dp
    val gutterWidthDp = with(density) { digitsWidthPx.toDp() } + startPadDp + endPadDp
    val gutterHeightDp = if (textLayout != null && textLayout.lineCount > 0) {
        val lastBottom = textLayout.getLineBottom(textLayout.lineCount - 1)
        val totalHeightPx = maxOf(textLayout.size.height.toFloat(), lastBottom)
        with(density) { totalHeightPx.toDp() }
    } else {
        with(density) { lineHeight.toDp() }
    }

    Canvas(
        modifier = Modifier
            .width(gutterWidthDp)
            .height(gutterHeightDp)
            .testTag("editor_line_number_gutter"),
    ) {
        val startPadPx = startPadDp.toPx()
        val endPadPx = endPadDp.toPx()
        val layout = textLayout
        if (layout == null || layout.lineCount <= 0) {
            val measured = textMeasurer.measure("1", style = gutterTextStyle)
            val x = (size.width - endPadPx - measured.size.width).coerceAtLeast(startPadPx)
            drawText(textLayoutResult = measured, topLeft = Offset(x, 0f))
            return@Canvas
        }

        val totalLines = layout.lineCount
        val firstVis: Int
        val lastVis: Int
        if (viewportHeightPx > 0f && totalLines > 1) {
            val bufferPx = 320f
            val visibleTop = (scrollState.value - topPaddingPx - bufferPx).coerceAtLeast(0f)
            val visibleBottom = (scrollState.value - topPaddingPx + viewportHeightPx + bufferPx).coerceAtLeast(visibleTop)
            firstVis = layout.getLineForVerticalPosition(visibleTop).coerceIn(0, totalLines - 1)
            lastVis = layout.getLineForVerticalPosition(visibleBottom).coerceIn(firstVis, totalLines - 1)
        } else {
            firstVis = 0
            lastVis = totalLines - 1
        }

        for (v in firstVis..lastVis) {
            val logicalNum = logicalLineMap[v] ?: continue
            val measured = gutterLayoutCache.getOrPut(logicalNum) {
                textMeasurer.measure(
                    text = logicalNum.toString(),
                    style = gutterTextStyle,
                )
            }
            val x = (size.width - endPadPx - measured.size.width).coerceAtLeast(startPadPx)
            val lineTop = layout.getLineTop(v)
            val maxTop = (layout.getLineBottom(v) - measured.size.height).coerceAtLeast(lineTop)
            val y = (layout.getLineBaseline(v) - measured.firstBaseline).coerceIn(lineTop, maxTop)
            drawText(
                textLayoutResult = measured,
                topLeft = Offset(x, y),
            )
        }
    }
}

@Composable
private fun MarkdownFormattingBar(
    tabSpaces: String,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onToggleBulletList: () -> Unit,
    onToggleNumberedList: () -> Unit,
    onIndent: () -> Unit,
    onUnindent: () -> Unit,
    onToggleBlockquote: () -> Unit,
    onMoveLineUp: () -> Unit,
    onMoveLineDown: () -> Unit,
    onRemoveLine: () -> Unit,
    onDeleteParagraph: () -> Unit,
    onInsertSnippet: (String, String?) -> Unit,
    onWrapSelection: (String, String, String) -> Unit,
    onOpenWikiPicker: () -> Unit,
    onPickImage: () -> Unit,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0A0A0A))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolbarChip("Undo", enabled = canUndo, onClick = onUndo)
            ToolbarChip("Redo", enabled = canRedo, onClick = onRedo)
            ToolbarChip("Tab") { onInsertSnippet(tabSpaces, null) }
            ToolbarChip("• List", onClick = onToggleBulletList)
            ToolbarChip("1. List", onClick = onToggleNumberedList)
            ToolbarChip("Indent", onClick = onIndent)
            ToolbarChip("Unindent", onClick = onUnindent)
            ToolbarChip("> Quote", onClick = onToggleBlockquote)
            ToolbarChip("↑ Line", onClick = onMoveLineUp)
            ToolbarChip("↓ Line", onClick = onMoveLineDown)
            ToolbarChip("Del Line", onClick = onRemoveLine)
            ToolbarChip("Del ¶", onClick = onDeleteParagraph)
            ToolbarChip("H1") { onInsertSnippet("\n# ", null) }
            ToolbarChip("H2") { onInsertSnippet("\n## ", null) }
            ToolbarChip("B", bold = true) { onWrapSelection("**", "**", "") }
            ToolbarChip("I") { onWrapSelection("*", "*", "") }
            ToolbarChip("==") { onWrapSelection("==", "==", "") }
            ToolbarChip("`Code`") { onWrapSelection("`", "`", "") }
            // Block templates start empty; the marker places the caret inside the block.
            ToolbarChip("```") {
                onInsertSnippet("\n```\n${CURSOR_MARKER}\n```\n", CURSOR_MARKER)
            }
            ToolbarChip("Task") { onInsertSnippet("\n- [ ] ", null) }
            ToolbarChip("Table") {
                onInsertSnippet("\n| ${CURSOR_MARKER} |  |\n| --- | --- |\n", CURSOR_MARKER)
            }
            ToolbarChip("\$Math\$") { onWrapSelection("$", "$", "") }
            ToolbarChip("\$\$Block\$\$") {
                onInsertSnippet("\n$$\n${CURSOR_MARKER}\n$$\n", CURSOR_MARKER)
            }
            ToolbarChip("Link") { onWrapSelection("[", "]()", "") }
            ToolbarChip("[[Wiki]]") { onOpenWikiPicker() }
            ToolbarChip("[^Cite]") {
                onInsertSnippet("[^1]\n\n[^1]: ${CURSOR_MARKER}", CURSOR_MARKER)
            }
            ToolbarChip("Chart") {
                onInsertSnippet("\n```chart\n${CURSOR_MARKER}\n```\n", CURSOR_MARKER)
            }
            ToolbarChip("Diagram") {
                onInsertSnippet("\n```diagram\n${CURSOR_MARKER}\n```\n", CURSOR_MARKER)
            }
            ToolbarIconChip(
                icon = Icons.Outlined.Image,
                contentDescription = "Insert image",
                testTag = "toolbar_pick_image",
                onClick = onPickImage,
            )
        }
    }
}

@Composable
private fun ToolbarChip(
    label: String,
    bold: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(48.dp)
            .defaultMinSize(minWidth = 48.dp)
            .clickable(
                enabled = enabled,
                interactionSource = null,
                indication = NoIndication,
                onClick = onClick,
            )
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .height(36.dp)
                .defaultMinSize(minWidth = 48.dp)
                .background(Color(0xFF141414), RoundedCornerShape(4.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
                ),
                color = if (enabled) Color.White else Color(0xFF525252),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ToolbarIconChip(
    icon: ImageVector,
    contentDescription: String,
    testTag: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(48.dp)
            .defaultMinSize(minWidth = 48.dp)
            .clickable(
                interactionSource = null,
                indication = NoIndication,
                onClick = onClick,
            )
            .padding(vertical = 6.dp)
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .height(36.dp)
                .defaultMinSize(minWidth = 48.dp)
                .background(Color(0xFF141414), RoundedCornerShape(4.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
