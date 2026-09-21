/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.media3.datasource.cache.Cache
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.di.DownloadCache
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

private const val EXPORT_BUFFER_SIZE = 64 * 1024
private const val MAX_EXPORT_THREADS = 8

/**
 * Copies fully downloaded songs out of the private Media3 download cache into a
 * user-chosen public folder. The cached fragments are concatenated in order into
 * a single file whose container matches what the servers actually delivered.
 */
@Singleton
class ExportUtil
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
    @DownloadCache private val downloadCache: Cache,
) {
    /** Total number of bytes cached for a song, or 0 when nothing is cached. */
    fun cachedLength(songId: String): Long =
        runCatching { downloadCache.getCachedLength(songId, 0, Long.MAX_VALUE) }.getOrDefault(0L)

    /** Extension that reflects the container actually stored for a downloaded song. */
    suspend fun suggestedExtension(songId: String): String = withContext(Dispatchers.IO) {
        val mimeType = database.format(songId).first()?.mimeType?.lowercase().orEmpty()
        when {
            mimeType.contains("webm") -> "webm"
            mimeType.contains("video/mp4") -> "mp4"
            mimeType.contains("audio/mp4") || mimeType.contains("audio/mpeg") -> "m4a"
            else -> "m4a"
        }
    }

    /**
     * Exports the cached song to [fileName] inside the folder identified by [treeUri]
     * (a Storage Access Framework tree). [threads] parallel workers split the copy.
     * Reports progress in [onProgress] between 0f and 1f.
     */
    suspend fun exportSong(
        songId: String,
        fileName: String,
        treeUri: Uri,
        threads: Int,
        onProgress: (Float) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val total = cachedLength(songId)
            require(total > 0L) { "Nothing cached for song $songId" }

            val tempFile = File.createTempFile("export_", ".tmp", context.cacheDir)
            try {
                RandomAccessFile(tempFile, "rw").use { raf ->
                    raf.setLength(total)
                    val channel = raf.channel
                    val written = AtomicLong(0L)
                    coroutineScope {
                        val count = threads.coerceIn(1, MAX_EXPORT_THREADS)
                        val chunkSize = total / count
                        (0 until count).map { threadIndex ->
                            async {
                                val start = threadIndex * chunkSize
                                val end =
                                    if (threadIndex == count - 1) {
                                        total
                                    } else {
                                        (threadIndex + 1) * chunkSize
                                    }
                                if (start < end) {
                                    copyCachedRange(
                                        songId,
                                        channel,
                                        start,
                                        end,
                                        written,
                                        total,
                                        onProgress,
                                    )
                                }
                            }
                        }.awaitAll()
                    }
                }

                val documentUri = createDocumentInTree(treeUri, "audio/*", fileName)
                    ?: throw IOException("Unable to create the destination file")

                context.contentResolver.openOutputStream(documentUri, "wt")?.use { output ->
                    tempFile.inputStream().use { input -> input.copyTo(output) }
                } ?: throw IOException("Unable to open the destination file")

                onProgress(1f)
            } finally {
                tempFile.delete()
            }
        }
    }

    private suspend fun copyCachedRange(
        songId: String,
        channel: FileChannel,
        start: Long,
        end: Long,
        written: AtomicLong,
        total: Long,
        onProgress: (Float) -> Unit,
    ) {
        var position = start
        while (position < end) {
            val span =
                downloadCache.startReadWriteNonBlocking(songId, position, end - position)
                    ?: throw IOException("Download cache is locked at byte $position")
            if (!span.isCached || span.file == null) {
                throw IOException("Download cache incomplete at byte $position")
            }
            val file = span.file
            val fileOffset = position - span.position
            val toRead = min(span.length - fileOffset, end - position).toInt()
            if (toRead <= 0) {
                throw IOException("Download cache incomplete at byte $position")
            }
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(fileOffset)
                var remaining = toRead
                val buffer = ByteArray(EXPORT_BUFFER_SIZE)
                while (remaining > 0) {
                    val count = raf.read(buffer, 0, min(EXPORT_BUFFER_SIZE, remaining))
                    if (count <= 0) break
                    writeFully(channel, ByteBuffer.wrap(buffer, 0, count), position)
                    position += count
                    remaining -= count
                    onProgress(written.addAndGet(count.toLong()).toFloat() / total.toFloat())
                }
            }
        }
    }

    private fun createDocumentInTree(treeUri: Uri, mimeType: String, displayName: String): Uri? {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= 37) {
            // Android 17 changed createDocument to take the tree Uri directly.
            return DocumentsContract.createDocument(resolver, treeUri, mimeType, displayName)
        }
        // Older Android versions expect the parent document id as a String, an overload
        // that compileSdk 37 no longer exposes, so it is invoked reflectively.
        return runCatching {
            val createDocument =
                DocumentsContract::class.java.getMethod(
                    "createDocument",
                    ContentResolver::class.java,
                    String::class.java,
                    String::class.java,
                    String::class.java,
                )
            createDocument.invoke(
                null,
                resolver,
                DocumentsContract.getTreeDocumentId(treeUri),
                mimeType,
                displayName,
            ) as Uri
        }.getOrNull()
    }

    private fun writeFully(channel: FileChannel, buffer: ByteBuffer, destination: Long) {
        var target = destination
        while (buffer.hasRemaining()) {
            target += channel.write(buffer, target)
        }
    }
}