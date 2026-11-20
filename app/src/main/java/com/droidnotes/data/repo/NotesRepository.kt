package com.droidnotes.data.repo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.droidnotes.data.db.NoteDao
import com.droidnotes.data.db.NoteEntity
import com.droidnotes.data.db.NoteLinkEntity
import com.droidnotes.data.db.NoteTitleProjection
import com.droidnotes.data.templates.NoteTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class NotesRepository(
    private val context: Context,
    private val noteDao: NoteDao,
) {
    // Persistent app-data directory (filesDir, never cacheDir) so clearing cache preserves notes & media.
    private val mediaDir: File by lazy {
        File(context.filesDir, "images").apply { if (!exists()) mkdirs() }
    }

    fun observeAllNotes(): Flow<List<NoteEntity>> = noteDao.observeAllNotes()

    fun observeAllLinks(): Flow<List<NoteLinkEntity>> = noteDao.observeAllLinks()

    fun observeNoteTitles(): Flow<List<NoteTitleProjection>> = noteDao.observeNoteTitles()

    fun searchNotes(rawQuery: String): Flow<List<NoteEntity>> {
        val trimmed = rawQuery.trim()
        if (trimmed.isEmpty()) return noteDao.observeAllNotes()

        val ftsTokens = FTS_TOKEN_REGEX
            .findAll(trimmed)
            .map { "\"${it.value}\"*" }
            .toList()

        val hasSpecialSyntax = trimmed.any { !it.isLetterOrDigit() && !it.isWhitespace() && it != '_' }
        return if (ftsTokens.isNotEmpty() && !hasSpecialSyntax) {
            noteDao.searchNotesFts(ftsTokens.joinToString(" "))
        } else {
            val escaped = trimmed
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_")
            noteDao.searchNotesLike(escaped)
        }
    }

    suspend fun getNoteById(id: String): NoteEntity? = withContext(Dispatchers.IO) {
        noteDao.getNoteById(id)
    }

    suspend fun findNoteByTitle(title: String): NoteEntity? = withContext(Dispatchers.IO) {
        noteDao.findNoteByTitle(title.trim())
    }

    suspend fun createNote(
        title: String = "Untitled Note",
        content: String = "",
        templateId: String? = null,
    ): NoteEntity = withContext(Dispatchers.Default) {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val derived = deriveTitleFromContent(content)
        val finalTitle = if (title.isBlank() || (title == "Untitled Note" && derived != "Untitled Note")) derived else title
        val note = buildUpdatedEntity(
            id = id,
            title = finalTitle,
            content = content,
            isBookmarked = false,
            templateId = templateId,
            createdAt = now,
            updatedAt = now,
        )
        withContext(Dispatchers.IO) {
            noteDao.upsertNote(note)
            syncLinks(note.id, note.content)
        }
        note
    }

    suspend fun createFromTemplate(template: NoteTemplate): NoteEntity {
        return createNote(
            title = template.defaultTitle,
            content = template.content,
            templateId = template.id,
        )
    }

    suspend fun saveNote(
        id: String,
        title: String,
        content: String,
        isBookmarked: Boolean,
        templateId: String?,
        createdAt: Long,
    ): NoteEntity = withContext(Dispatchers.Default) {
        val now = System.currentTimeMillis()
        val derived = deriveTitleFromContent(content)
        val resolvedTitle = if (title.isBlank() || (title == "Untitled Note" && derived != "Untitled Note")) derived else title
        val updated = buildUpdatedEntity(
            id = id,
            title = resolvedTitle,
            content = content,
            isBookmarked = isBookmarked,
            templateId = templateId,
            createdAt = createdAt,
            updatedAt = now,
        )
        withContext(Dispatchers.IO) {
            noteDao.upsertNote(updated)
            syncLinks(updated.id, updated.content)
        }
        updated
    }

    suspend fun toggleBookmark(id: String, currentlyBookmarked: Boolean) {
        withContext(Dispatchers.IO) {
            noteDao.setBookmarked(id, !currentlyBookmarked, System.currentTimeMillis())
        }
    }

    suspend fun deleteNote(id: String) {
        withContext(Dispatchers.IO) {
            noteDao.deleteNoteById(id)
        }
    }

    suspend fun deleteNotes(ids: List<String>) {
        if (ids.isEmpty()) return
        withContext(Dispatchers.IO) {
            ids.chunked(500).forEach { batch ->
                noteDao.deleteNotesByIds(batch)
            }
        }
    }

    suspend fun saveImageFromUri(sourceUri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val fileName = "img_${UUID.randomUUID()}.png"
            val destFile = File(mediaDir, fileName)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            }
            val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, 2048, 2048)
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = context.contentResolver.openInputStream(sourceUri)?.use { input ->
                BitmapFactory.decodeStream(input, null, decodeOpts)
            }
            if (bitmap != null) {
                try {
                    FileOutputStream(destFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                    }
                } finally {
                    bitmap.recycle()
                }
            } else {
                context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    FileOutputStream(destFile).use { out -> input.copyTo(out) }
                } ?: return@runCatching null
            }
            "local://images/$fileName"
        }.getOrNull()
    }

    suspend fun saveBitmapImage(bitmap: Bitmap): String? = withContext(Dispatchers.IO) {
        runCatching {
            val fileName = "clip_${UUID.randomUUID()}.png"
            val destFile = File(mediaDir, fileName)
            val maxDim = maxOf(bitmap.width, bitmap.height)
            val scaled = if (maxDim > 2048) {
                val ratio = 2048f / maxDim
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * ratio).toInt().coerceAtLeast(1),
                    (bitmap.height * ratio).toInt().coerceAtLeast(1),
                    true,
                )
            } else {
                bitmap
            }
            try {
                FileOutputStream(destFile).use { out ->
                    scaled.compress(Bitmap.CompressFormat.PNG, 90, out)
                }
            } finally {
                if (scaled !== bitmap) scaled.recycle()
            }
            "local://images/$fileName"
        }.getOrNull()
    }

    fun resolveLocalImageFile(uriString: String): File? {
        if (uriString.startsWith("local://images/")) {
            val name = uriString.removePrefix("local://images/").substringAfterLast('/')
            val file = File(mediaDir, name)
            return if (file.exists()) file else null
        }
        val direct = File(uriString)
        return if (direct.exists()) direct else null
    }

    private suspend fun syncLinks(noteId: String, content: String) {
        val links = extractWikiLinks(content).map { target ->
            NoteLinkEntity(sourceNoteId = noteId, targetTitle = target)
        }
        noteDao.replaceLinksForNote(noteId, links)
    }

    private fun buildUpdatedEntity(
        id: String,
        title: String,
        content: String,
        isBookmarked: Boolean,
        templateId: String?,
        createdAt: Long,
        updatedAt: Long,
    ): NoteEntity {
        val tags = extractTags(content)
        val lines = countLines(content)
        val words = countWords(content)
        return NoteEntity(
            id = id,
            title = title,
            content = content,
            isBookmarked = isBookmarked,
            templateId = templateId,
            tagsCsv = tags.joinToString(","),
            lineCount = lines,
            wordCount = words,
            charCount = content.length,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    companion object {
        private val WIKILINK_REGEX = Regex("""\[\[([^\[\]\n|#]+?)(?:\|[^\[\]\n]+)?]]""")
        private val TAG_REGEX = Regex("""(?:^|\s)#([a-zA-Z][a-zA-Z0-9_-]{0,31})""")
        private val CODE_FENCE_REGEX = Regex("""```[\s\S]*?```""")
        private val FTS_TOKEN_REGEX = Regex("[\\p{L}\\p{N}_]+")

        fun extractWikiLinks(content: String): List<String> {
            return WIKILINK_REGEX.findAll(content)
                .map { it.groupValues[1].trim() }
                .filter { it.isNotEmpty() }
                .distinctBy { it.lowercase() }
                .toList()
        }

        fun extractTags(content: String): List<String> {
            val withoutCode = CODE_FENCE_REGEX.replace(content, " ")
            return TAG_REGEX.findAll(withoutCode)
                .map { it.groupValues[1].lowercase() }
                .distinct()
                .toList()
        }

        fun countLines(content: String): Int {
            if (content.isEmpty()) return 0
            return content.count { it == '\n' } + 1
        }

        fun countWords(content: String): Int {
            var count = 0
            var inWord = false
            for (i in 0 until content.length) {
                if (content[i].isWhitespace()) {
                    inWord = false
                } else if (!inWord) {
                    inWord = true
                    count++
                }
            }
            return count
        }

        fun deriveTitleFromContent(content: String): String {
            val firstHeading = content.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("#") }
                ?.trimStart('#')
                ?.trim()
            if (!firstHeading.isNullOrBlank()) return firstHeading.take(80)
            val firstLine = content.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            return firstLine?.take(60) ?: "Untitled Note"
        }

        fun calculateInSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
            var inSampleSize = 1
            if (height > reqHeight || width > reqWidth) {
                val halfHeight = height / 2
                val halfWidth = width / 2
                while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                    inSampleSize *= 2
                }
            }
            return inSampleSize.coerceAtLeast(1)
        }
    }
}
