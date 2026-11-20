package com.droidnotes

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.droidnotes.data.db.AppDatabase
import com.droidnotes.data.db.NoteEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DroidNotesRobolectricTest {

    @Test
    fun verifiesAppNameAndRoomPersistence() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("DroidNotes", context.getString(R.string.app_name))

        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = db.noteDao()

        dao.upsertNote(
            NoteEntity(
                id = "note-1",
                title = "Quantum Notes",
                content = "Wavefunction collapse\nand [[Linear Algebra]].",
                isBookmarked = true,
                lineCount = 2,
                wordCount = 6,
                charCount = 46,
            )
        )
        dao.upsertNote(
            NoteEntity(
                id = "note-2",
                title = "Delete Batch Target",
                content = "Single line",
                lineCount = 1,
                wordCount = 2,
                charCount = 11,
            )
        )

        val all = dao.observeAllNotes().first()
        assertEquals(2, all.size)
        assertEquals("Quantum Notes", all.first { it.id == "note-1" }.title)
        assertEquals(2, all.first { it.id == "note-1" }.lineCount)
        assertEquals(true, all.first { it.id == "note-1" }.isBookmarked)

        val fts = dao.searchNotesFts("\"Wavefunction\"*").first()
        assertEquals(1, fts.size)

        dao.deleteNotesByIds(listOf("note-2"))
        val remaining = dao.observeAllNotes().first()
        assertEquals(1, remaining.size)
        assertEquals("note-1", remaining[0].id)
        db.close()
    }
}
