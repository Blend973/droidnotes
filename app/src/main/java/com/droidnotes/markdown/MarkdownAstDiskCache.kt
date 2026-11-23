package com.droidnotes.markdown

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Disk-backed cache for parsed Markdown documents. The cache lives under the app's cacheDir
 * and can be evicted by Android at any time, so a miss simply reparses the source.
 */
internal class MarkdownAstDiskCache(private val directory: File) {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private val lock = Any()

    fun get(source: String): MarkdownDocument? {
        val file = fileFor(source)
        if (!file.isFile) return null

        val entry = runCatching {
            json.decodeFromString(CachedDocument.serializer(), file.readText())
        }.getOrElse {
            file.delete()
            return null
        }

        if (entry.version != CACHE_VERSION) {
            file.delete()
            return null
        }

        runCatching { file.setLastModified(System.currentTimeMillis()) }
        return entry.document
    }

    fun put(source: String, document: MarkdownDocument) {
        synchronized(lock) {
            val target = fileFor(source)
            val temp = File(directory, "${target.name}.tmp")
            runCatching {
                directory.mkdirs()
                val entry = CachedDocument(version = CACHE_VERSION, document = document)
                temp.writeText(json.encodeToString(CachedDocument.serializer(), entry))
                try {
                    Files.move(
                        temp.toPath(),
                        target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                } catch (_: Exception) {
                    Files.move(
                        temp.toPath(),
                        target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
                prune()
            }.onFailure {
                temp.delete()
            }
        }
    }

    private fun prune() {
        val files = directory.listFiles { file ->
            file.isFile && file.name.startsWith(FILE_PREFIX) && file.name.endsWith(FILE_SUFFIX)
        } ?: return

        var totalBytes = files.sumOf { it.length() }
        var count = files.size
        if (totalBytes <= MAX_BYTES && count <= MAX_FILES) return

        files.sortedBy { it.lastModified() }.forEach { file ->
            if (totalBytes <= MAX_BYTES && count <= MAX_FILES) return@forEach
            val length = file.length()
            if (file.delete()) {
                totalBytes -= length
                count--
            }
        }
    }

    private fun fileFor(source: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
        val name = digest.joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        return File(directory, "$FILE_PREFIX$name$FILE_SUFFIX")
    }

    @Serializable
    private data class CachedDocument(
        val version: Int,
        val document: MarkdownDocument,
    )

    private companion object {
        const val CACHE_VERSION = 1
        const val FILE_PREFIX = "ast_v1_"
        const val FILE_SUFFIX = ".json"
        const val MAX_BYTES = 16L * 1024L * 1024L
        const val MAX_FILES = 64
    }
}
