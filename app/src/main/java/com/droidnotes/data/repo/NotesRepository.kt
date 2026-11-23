package com.droidnotes.data.repo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import com.droidnotes.data.db.LocalImageRefs
import com.droidnotes.data.db.NoteDao
import com.droidnotes.data.db.NoteEntity
import com.droidnotes.data.db.NoteLinkEntity
import com.droidnotes.data.db.NoteTitleProjection
import com.droidnotes.data.templates.NoteTemplate
import com.droidnotes.markdown.DecodedBitmapDiskCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
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
        val links = linksFor(note.id, note.content)
        val imageNames = LocalImageRefs.extractNames(note.content).toList()
        withContext(Dispatchers.IO) {
            val orphaned = noteDao.upsertNoteWithRelations(note, links, imageNames)
            deleteMedia(orphaned)
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
        val links = linksFor(updated.id, updated.content)
        val imageNames = LocalImageRefs.extractNames(updated.content).toList()
        withContext(Dispatchers.IO) {
            val orphaned = noteDao.upsertNoteWithRelations(updated, links, imageNames)
            deleteMedia(orphaned)
        }
        updated
    }

    suspend fun toggleBookmark(id: String, currentlyBookmarked: Boolean) {
        withContext(Dispatchers.IO) {
            noteDao.setBookmarked(id, !currentlyBookmarked, System.currentTimeMillis())
        }
    }

    suspend fun deleteNote(id: String) = deleteNotes(listOf(id))

    suspend fun deleteNotes(ids: List<String>) {
        if (ids.isEmpty()) return
        withContext(Dispatchers.IO) {
            val orphaned = noteDao.deleteNotesAndCollectOrphanedImages(ids)
            deleteMedia(orphaned)
        }
    }

    suspend fun saveImageFromUri(sourceUri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = context.contentResolver.openInputStream(sourceUri)?.use { it.readBytes() }
                ?: return@runCatching null
            val hash = sha256(bytes).take(16)
            val fileName = "img_$hash.jpg"
            val destFile = File(mediaDir, fileName)
            if (!destFile.exists()) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val sample = calculateInSampleSize(
                    bounds.outWidth,
                    bounds.outHeight,
                    MAX_IMAGE_DIMENSION,
                    MAX_IMAGE_DIMENSION,
                )
                if (sample == 1 && bytes.isJpeg()) {
                    FileOutputStream(destFile).use { it.write(bytes) }
                } else {
                    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                        ?: return@runCatching null
                    try {
                        FileOutputStream(destFile).use { it.write(jpegBytes(decoded)) }
                    } finally {
                        decoded.recycle()
                    }
                }
            }
            "local://images/$fileName"
        }.getOrNull()
    }

    suspend fun saveBitmapImage(bitmap: Bitmap): String? = withContext(Dispatchers.IO) {
        runCatching {
            val maxDim = maxOf(bitmap.width, bitmap.height)
            val scaled = if (maxDim > MAX_IMAGE_DIMENSION) {
                val ratio = MAX_IMAGE_DIMENSION.toFloat() / maxDim
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
                val encoded = jpegBytes(scaled)
                val fileName = "clip_${sha256(encoded).take(16)}.jpg"
                val destFile = File(mediaDir, fileName)
                if (!destFile.exists()) FileOutputStream(destFile).use { it.write(encoded) }
                "local://images/$fileName"
            } finally {
                if (scaled !== bitmap) scaled.recycle()
            }
        }.getOrNull()
    }

    private fun jpegBytes(bitmap: Bitmap): ByteArray {
        // JPEG has no alpha, so flatten onto the app's dark canvas before encoding.
        val flat = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.RGB_565)
        try {
            val canvas = Canvas(flat)
            canvas.drawColor(JPEG_BACKGROUND)
            canvas.drawBitmap(bitmap, 0f, 0f, null)
            val out = ByteArrayOutputStream()
            flat.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            return out.toByteArray()
        } finally {
            flat.recycle()
        }
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

    private fun linksFor(noteId: String, content: String): List<NoteLinkEntity> =
        extractWikiLinks(content).map { target ->
            NoteLinkEntity(sourceNoteId = noteId, targetTitle = target)
        }

    private fun deleteMedia(names: Collection<String>) {
        names.forEach { name ->
            runCatching { File(mediaDir, name).delete() }
            DecodedBitmapDiskCache.remove("local://images/$name")
        }
    }

    /** Deletes media files that are no longer referenced by any note. */
    suspend fun cleanupOrphanedMedia(minAgeMs: Long = ORPHAN_FILE_MIN_AGE_MS) {
        withContext(Dispatchers.IO) {
            val referenced = noteDao.getAllImageNames().toSet()
            // Safety valve: never mass-delete if the index is empty despite existing notes.
            if (referenced.isEmpty() && noteDao.countNotes() > 0) return@withContext

            val cutoff = System.currentTimeMillis() - minAgeMs
            val files = mediaDir.listFiles() ?: return@withContext
            files.forEach { file ->
                if (file.isFile && file.name !in referenced && file.lastModified() < cutoff) {
                    runCatching { file.delete() }
                    DecodedBitmapDiskCache.remove("local://images/${file.name}")
                }
            }
        }
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
        private const val MAX_IMAGE_DIMENSION = 1600
        private const val JPEG_QUALITY = 85
        private const val JPEG_BACKGROUND = 0xFF0A0A0A.toInt()
        private const val ORPHAN_FILE_MIN_AGE_MS = 60L * 60L * 1000L

        private val WIKILINK_REGEX = Regex("""\[\[([^\[\]\n|#]+?)(?:\|[^\[\]\n]+)?]]""")

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

        private fun ByteArray.isJpeg(): Boolean =
            size >= 2 && this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte()

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
