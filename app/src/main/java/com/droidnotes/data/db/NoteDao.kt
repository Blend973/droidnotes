package com.droidnotes.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** SQLite's default host parameter limit is 999; stay well below it. */
private const val SQLITE_CHUNK_SIZE = 500

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY isBookmarked DESC, updatedAt DESC")
    fun observeAllNotes(): Flow<List<NoteEntity>>

    @Query("SELECT COUNT(*) FROM notes")
    suspend fun countNotes(): Int

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

    @Query("SELECT imageName FROM note_images WHERE noteId = :noteId")
    suspend fun getImageNamesForNote(noteId: String): List<String>

    @Query("SELECT DISTINCT imageName FROM note_images WHERE noteId IN (:noteIds)")
    suspend fun getImageNamesForNotes(noteIds: List<String>): List<String>

    @Query("SELECT DISTINCT imageName FROM note_images WHERE imageName IN (:imageNames)")
    suspend fun getImageNamesIn(imageNames: List<String>): List<String>

    @Query("SELECT DISTINCT imageName FROM note_images")
    suspend fun getAllImageNames(): List<String>

    @Query("DELETE FROM note_images WHERE noteId = :noteId")
    suspend fun deleteImagesForNote(noteId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertImages(images: List<NoteImageEntity>)

    /**
     * Writes the note and its derived relations in one transaction. Returns image names that
     * the note no longer references and that no other note references either.
     */
    @Transaction
    suspend fun upsertNoteWithRelations(
        note: NoteEntity,
        links: List<NoteLinkEntity>,
        imageNames: List<String>,
    ): List<String> {
        val oldImages = getImageNamesForNote(note.id).toSet()
        val newImages = imageNames.toSet()

        upsertNote(note)
        deleteLinksForNote(note.id)
        if (links.isNotEmpty()) {
            insertLinks(links)
        }
        deleteImagesForNote(note.id)
        if (newImages.isNotEmpty()) {
            insertImages(newImages.map { NoteImageEntity(note.id, it) })
        }

        val removed = oldImages - newImages
        if (removed.isEmpty()) return emptyList()

        val stillUsed = removed.chunked(SQLITE_CHUNK_SIZE)
            .flatMap { getImageNamesIn(it) }
            .toSet()
        return (removed - stillUsed).toList()
    }

    /**
     * Deletes notes and their relations in one transaction, returning image files that became
     * unreferenced as a result.
     */
    @Transaction
    suspend fun deleteNotesAndCollectOrphanedImages(ids: List<String>): List<String> {
        val removed = ids.chunked(SQLITE_CHUNK_SIZE)
            .flatMap { getImageNamesForNotes(it) }
            .toSet()

        ids.chunked(SQLITE_CHUNK_SIZE).forEach { deleteNotesByIds(it) }

        if (removed.isEmpty()) return emptyList()

        val stillUsed = removed.chunked(SQLITE_CHUNK_SIZE)
            .flatMap { getImageNamesIn(it) }
            .toSet()
        return (removed - stillUsed).toList()
    }
}

data class NoteTitleProjection(
    val id: String,
    val title: String,
)
