package com.droidnotes.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY isBookmarked DESC, updatedAt DESC")
    fun observeAllNotes(): Flow<List<NoteEntity>>

    @Query(
        """
        SELECT notes.* FROM notes
        JOIN notes_fts ON notes.rowid = notes_fts.rowid
        WHERE notes_fts MATCH :ftsQuery
        ORDER BY notes.isBookmarked DESC, notes.updatedAt DESC
        """
    )
    fun searchNotesFts(ftsQuery: String): Flow<List<NoteEntity>>

    @Query(
        """
        SELECT * FROM notes
        WHERE title LIKE '%' || :query || '%' ESCAPE '\'
           OR content LIKE '%' || :query || '%' ESCAPE '\'
        ORDER BY isBookmarked DESC, updatedAt DESC
        """
    )
    fun searchNotesLike(query: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
    suspend fun getNoteById(id: String): NoteEntity?

    @Query("SELECT * FROM notes WHERE LOWER(title) = LOWER(:title) LIMIT 1")
    suspend fun findNoteByTitle(title: String): NoteEntity?

    @Query("SELECT id, title FROM notes ORDER BY title ASC")
    fun observeNoteTitles(): Flow<List<NoteTitleProjection>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNote(note: NoteEntity)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteNoteById(id: String)

    @Query("DELETE FROM notes WHERE id IN (:ids)")
    suspend fun deleteNotesByIds(ids: List<String>)

    @Query("UPDATE notes SET isBookmarked = :bookmarked, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setBookmarked(id: String, bookmarked: Boolean, updatedAt: Long)

    @Query("SELECT * FROM note_links")
    fun observeAllLinks(): Flow<List<NoteLinkEntity>>

    @Query("DELETE FROM note_links WHERE sourceNoteId = :noteId")
    suspend fun deleteLinksForNote(noteId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLinks(links: List<NoteLinkEntity>)

    @Transaction
    suspend fun replaceLinksForNote(noteId: String, links: List<NoteLinkEntity>) {
        deleteLinksForNote(noteId)
        if (links.isNotEmpty()) {
            insertLinks(links)
        }
    }
}

data class NoteTitleProjection(
    val id: String,
    val title: String,
)
