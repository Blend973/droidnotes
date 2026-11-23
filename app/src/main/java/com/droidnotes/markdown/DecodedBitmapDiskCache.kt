package com.droidnotes.markdown

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Disk-backed cache for downsampled, decoded images. Android still needs a decoded Bitmap
 * in memory to draw, but this avoids decoding the original file again on every composition.
 */
object DecodedBitmapDiskCache {
    private val lock = Any()

    @Volatile
    private var directory: File? = null

    fun configure(cacheDir: File) {
        synchronized(lock) {
            runCatching { cacheDir.mkdirs() }
            directory = cacheDir
        }
    }

    fun get(key: String): Bitmap? {
        val file = fileFor(key) ?: return null
        if (!file.isFile) return null

        val bitmap = runCatching {
            BitmapFactory.decodeFile(file.absolutePath)
        }.getOrNull()

        if (bitmap == null) {
            file.delete()
            return null
        }

        runCatching { file.setLastModified(System.currentTimeMillis()) }
        return bitmap
    }

    fun put(key: String, bitmap: Bitmap) {
        val dir = directory ?: return
        val target = fileForKey(key, dir)

        synchronized(lock) {
            val temp = File(dir, "${target.name}.tmp")
            runCatching {
                dir.mkdirs()
                FileOutputStream(temp).use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        "Unable to encode decoded bitmap for cache"
                    }
                }
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
                prune(dir)
            }.onFailure {
                temp.delete()
            }
        }
    }

    fun remove(key: String) {
        synchronized(lock) {
            fileFor(key)?.delete()
        }
    }

    private fun prune(dir: File) {
        val files = dir.listFiles { file ->
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

    private fun fileFor(key: String): File? {
        val dir = directory ?: return null
        return fileForKey(key, dir)
    }

    private fun fileForKey(key: String, dir: File): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
        val name = digest.joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        return File(dir, "$FILE_PREFIX$name$FILE_SUFFIX")
    }

    private const val FILE_PREFIX = "img_v1_"
    private const val FILE_SUFFIX = ".png"
    private const val MAX_BYTES = 32L * 1024L * 1024L
    private const val MAX_FILES = 64
}
