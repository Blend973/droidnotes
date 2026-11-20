package com.droidnotes

import android.app.Application
import com.droidnotes.data.db.AppDatabase
import com.droidnotes.data.prefs.PreferencesRepository
import com.droidnotes.data.repo.NotesRepository

class DroidNotesApp : Application() {
    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    val notesRepository: NotesRepository by lazy { NotesRepository(this, database.noteDao()) }
    val preferencesRepository: PreferencesRepository by lazy { PreferencesRepository(this) }
}
