package com.droidnotes

import android.app.Application
import com.droidnotes.data.db.AppDatabase
import com.droidnotes.data.prefs.PreferencesRepository
import com.droidnotes.data.repo.NotesRepository
import com.droidnotes.markdown.DecodedBitmapDiskCache
import com.droidnotes.markdown.MarkdownParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class DroidNotesApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    val notesRepository: NotesRepository by lazy { NotesRepository(this, database.noteDao()) }
    val preferencesRepository: PreferencesRepository by lazy { PreferencesRepository(this) }

    override fun onCreate() {
        super.onCreate()
        MarkdownParser.configure(File(cacheDir, "markdown_ast"))
        DecodedBitmapDiskCache.configure(File(cacheDir, "decoded_images"))
        appScope.launch { notesRepository.cleanupOrphanedMedia() }
    }
}
