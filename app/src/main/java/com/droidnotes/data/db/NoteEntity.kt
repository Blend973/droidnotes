package com.droidnotes.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "notes",
    indices = [
        Index(value = ["title"]),
        Index(value = ["updatedAt"]),
        Index(value = ["isBookmarked"]),
    ]
)
data class NoteEntity(
    @PrimaryKey val id: String,
    val title: String,
    val content: String,
    val isBookmarked: Boolean = false,
    val templateId: String? = null,
    val tagsCsv: String = "",
    val lineCount: Int = 0,
    val wordCount: Int = 0,
    val charCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun tagList(): List<String> =
        if (tagsCsv.isBlank()) emptyList()
        else tagsCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}

@Fts4(contentEntity = NoteEntity::class)
@Entity(tableName = "notes_fts")
data class NoteFtsEntity(
    val title: String,
    val content: String,
)

@Entity(
    tableName = "note_links",
    primaryKeys = ["sourceNoteId", "targetTitle"],
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceNoteId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index(value = ["targetTitle"])]
)
data class NoteLinkEntity(
    val sourceNoteId: String,
    val targetTitle: String,
)

@Entity(
    tableName = "note_images",
    primaryKeys = ["noteId", "imageName"],
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index(value = ["imageName"])]
)
data class NoteImageEntity(
    val noteId: String,
    val imageName: String,
)

enum class SortOrder(val label: String) {
    UPDATED_DESC("Recently modified"),
    CREATED_DESC("Newest created"),
    TITLE_ASC("Title A–Z"),
    TITLE_DESC("Title Z–A"),
    WORDS_DESC("Longest first"),
}

enum class EditorViewMode(val label: String) {
    EDIT("Edit"),
    PREVIEW("Preview"),
    SPLIT_ON_WIDE("Split on wide"),
}
