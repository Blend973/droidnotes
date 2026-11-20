package com.droidnotes.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.droidnotes.DroidNotesApp
import com.droidnotes.R
import com.droidnotes.export.NoteExporter
import com.droidnotes.ui.components.FocusDropContainer
import com.droidnotes.ui.editor.EditorScreen
import com.droidnotes.ui.editor.EditorViewModel
import com.droidnotes.ui.graph.GraphScreen
import com.droidnotes.ui.graph.GraphViewModel
import com.droidnotes.ui.library.LibraryScreen
import com.droidnotes.ui.library.LibraryViewModel
import com.droidnotes.ui.settings.SettingsScreen
import com.droidnotes.ui.settings.SettingsViewModel
import kotlinx.coroutines.launch

const val ROUTE_NOTES = "notes"
const val ROUTE_GRAPH = "graph"
const val ROUTE_SETTINGS = "settings"
const val ROUTE_EDITOR = "editor/{noteId}?query={query}&offset={offset}"

private data class TopDestination(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String,
)

private val TOP_DESTINATIONS = listOf(
    TopDestination(
        route = ROUTE_NOTES,
        labelRes = R.string.nav_notes,
        selectedIcon = Icons.Filled.Description,
        unselectedIcon = Icons.Outlined.Description,
        testTag = "nav_notes_tab",
    ),
    TopDestination(
        route = ROUTE_GRAPH,
        labelRes = R.string.nav_graph,
        selectedIcon = Icons.Filled.Hub,
        unselectedIcon = Icons.Outlined.Hub,
        testTag = "nav_graph_tab",
    ),
    TopDestination(
        route = ROUTE_SETTINGS,
        labelRes = R.string.nav_settings,
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings,
        testTag = "nav_settings_tab",
    ),
)

private val TOP_DESTINATION_ROUTES = TOP_DESTINATIONS.map { it.route }.toSet()

@Composable
fun DroidNotesAppShell() {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as DroidNotesApp }
    val exporter = remember(app) { NoteExporter(app, app.notesRepository) }
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showChrome = currentRoute in TOP_DESTINATION_ROUTES
    val scope = rememberCoroutineScope()

    val libraryViewModel: LibraryViewModel = viewModel(
        factory = LibraryViewModel.Factory(app.notesRepository, app.preferencesRepository, exporter)
    )
    val graphViewModel: GraphViewModel = viewModel(
        factory = GraphViewModel.Factory(app.notesRepository)
    )
    val settingsViewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(app.preferencesRepository)
    )

    fun navigateTop(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun openEditor(noteId: String, keywordQuery: String = "", offset: Int = -1) {
        val route = if (keywordQuery.isNotBlank()) {
            val encodedQuery = android.net.Uri.encode(keywordQuery)
            "editor/$noteId?query=$encodedQuery&offset=$offset"
        } else {
            "editor/$noteId"
        }
        navController.navigate(route) {
            launchSingleTop = true
        }
    }

    FocusDropContainer(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
        val useRail = maxWidth >= 700.dp

        Scaffold(
            containerColor = Color.Black,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (showChrome && !useRail) {
                    Column {
                        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline)
                        NavigationBar(
                            containerColor = Color.Black,
                            contentColor = Color.White,
                            windowInsets = WindowInsets.navigationBars,
                        ) {
                            TOP_DESTINATIONS.forEach { dest ->
                                val selected = currentRoute == dest.route
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { navigateTop(dest.route) },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                            contentDescription = stringResource(dest.labelRes),
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = stringResource(dest.labelRes),
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = Color.Black,
                                        selectedTextColor = Color.White,
                                        indicatorColor = Color.White,
                                        unselectedIconColor = Color(0xFF737373),
                                        unselectedTextColor = Color(0xFF737373),
                                    ),
                                    modifier = Modifier.testTag(dest.testTag),
                                )
                            }
                        }
                    }
                }
            },
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                if (showChrome && useRail) {
                    NavigationRail(
                        containerColor = Color.Black,
                        contentColor = Color.White,
                        modifier = Modifier
                            .fillMaxHeight()
                            .statusBarsPadding(),
                    ) {
                        TOP_DESTINATIONS.forEach { dest ->
                            val selected = currentRoute == dest.route
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTop(dest.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = stringResource(dest.labelRes),
                                    )
                                },
                                label = {
                                    Text(
                                        text = stringResource(dest.labelRes),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = Color.Black,
                                    selectedTextColor = Color.White,
                                    indicatorColor = Color.White,
                                    unselectedIconColor = Color(0xFF737373),
                                    unselectedTextColor = Color(0xFF737373),
                                ),
                                modifier = Modifier.testTag(dest.testTag),
                            )
                        }
                    }
                    VerticalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline)
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .let { if (showChrome) it.statusBarsPadding() else it },
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = ROUTE_NOTES,
                        enterTransition = { EnterTransition.None },
                        exitTransition = { ExitTransition.None },
                        popEnterTransition = { EnterTransition.None },
                        popExitTransition = { ExitTransition.None },
                    ) {
                        composable(ROUTE_NOTES) {
                            LibraryScreen(
                                viewModel = libraryViewModel,
                                onOpenNote = { noteId -> openEditor(noteId) },
                                onOpenNoteAtKeyword = { noteId, query, offset ->
                                    openEditor(noteId, query, offset)
                                },
                            )
                        }

                        composable(ROUTE_GRAPH) {
                            GraphScreen(
                                viewModel = graphViewModel,
                                onOpenNote = { noteId -> openEditor(noteId) },
                                onCreateGhostNote = { title ->
                                    scope.launch {
                                        val created = app.notesRepository.createNote(
                                            title = title,
                                            content = "# $title\n\n",
                                        )
                                        openEditor(created.id)
                                    }
                                },
                            )
                        }

                        composable(ROUTE_SETTINGS) {
                            SettingsScreen(viewModel = settingsViewModel)
                        }

                        composable(
                            route = ROUTE_EDITOR,
                            arguments = listOf(
                                navArgument("noteId") { type = NavType.StringType },
                                navArgument("query") {
                                    type = NavType.StringType
                                    defaultValue = ""
                                },
                                navArgument("offset") {
                                    type = NavType.IntType
                                    defaultValue = -1
                                },
                            ),
                        ) { entry ->
                            val noteId = entry.arguments?.getString("noteId") ?: return@composable
                            val initialQuery = entry.arguments?.getString("query").orEmpty()
                            val initialOffset = entry.arguments?.getInt("offset") ?: -1
                            val editorViewModel: EditorViewModel = viewModel(
                                key = "editor_${noteId}_${initialQuery}_$initialOffset",
                                factory = EditorViewModel.Factory(
                                    noteId = noteId,
                                    repository = app.notesRepository,
                                    preferencesRepository = app.preferencesRepository,
                                    exporter = exporter,
                                    initialFindQuery = initialQuery,
                                    initialFindOffset = initialOffset,
                                ),
                            )
                            EditorScreen(
                                viewModel = editorViewModel,
                                onNavigateBack = { navController.popBackStack() },
                                onOpenNote = { targetId ->
                                    openEditor(targetId)
                                },
                                onFilterByTag = { tag ->
                                    libraryViewModel.selectTag(tag)
                                    navController.popBackStack(ROUTE_NOTES, inclusive = false)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
    }
}
