/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.content.Context
import android.net.Uri
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
private const val MAX_EXPORT_THREADS = 32

/**
 * Copies fully downloaded songs out of the private Media3 download cache into a
 * user-chosen public folder. The output is always the original container format
 * (typically WebM/Opus) without re-encoding.
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

    /** Extension that reflects the container the server delivered. */
    suspend fun suggestedExtension(songId: String): String {
        val format = database.format(songId).first()
        return when {
            format?.mimeType?.contains("webm") == true -> "webm"
            format?.mimeType?.contains("mp4") == true -> "m4a"
            else -> "webm"
        }
    }

    /** Checks whether a song is fully downloaded and cached. */
    fun isCached(songId: String): Boolean = cachedLength(songId) > 0L

    /** Estimates the size in bytes of a song from its FormatEntity (may be 0 if unknown). */
    suspend fun estimatedSize(songId: String): Long {
        val format = database.format(songId).first()
        return format?.contentLength?.takeIf { it > 0L } ?: cachedLength(songId)
    }

    /**
     * Exports the cached song to a file identified by [outputUri] obtained via
     * ACTION_CREATE_DOCUMENT. [threads] parallel workers split the initial cache
     * read. Reports progress in [onProgress] between 0f and 1f.
     */
    suspend fun exportSong(
        songId: String,
        outputUri: Uri,
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

                context.contentResolver.openOutputStream(outputUri, "wt")?.use { output ->
                    tempFile.inputStream().use { input -> input.copyTo(output) }
                } ?: throw IOException("Unable to open the destination file")

                onProgress(1f)
            } finally {
                tempFile.delete()
            }
        }
    }

    /**
     * Returns the list of song IDs in a playlist, in order.
     */
    suspend fun playlistSongIds(playlistId: String): List<String> {
        return database.playlistSongs(playlistId).first().map { it.song.id }
    }

    /**
     * Returns the display name (title) of a song, or its ID as fallback.
     */
    suspend fun songTitle(songId: String): String {
        return database.song(songId).first()?.title ?: songId
    }

    /**
     * Exports all cached songs in a playlist to a folder chosen via
     * ACTION_OPEN_DOCUMENT_TREE. [threads] controls parallelism per song.
     * Reports progress via [onSongProgress] (songIndex, totalSongs, fileProgress).
     * Returns the number of songs successfully exported.
     */
    suspend fun exportPlaylist(
        playlistId: String,
        folderUri: Uri,
        threads: Int,
        onSongProgress: (songIndex: Int, totalSongs: Int, fileProgress: Float) -> Unit,
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val songIds = playlistSongIds(playlistId)
            require(songIds.isNotEmpty()) { "Playlist is empty" }

            var exportedCount = 0
            songIds.forEachIndexed { index, songId ->
                onSongProgress(index + 1, songIds.size, 0f)

                if (!isCached(songId)) {
                    // Skip songs that aren't downloaded
                    onSongProgress(index + 1, songIds.size, 1f)
                    return@forEachIndexed
                }

                val title = songTitle(songId)
                val ext = suggestedExtension(songId)
                val safeName = title.replace(Regex("""[\\/:*?"<>|]"""), "_")

                val fileUri = createFileInTree(folderUri, "$safeName.$ext", "audio/*")
                    ?: throw IOException("Failed to create file: $safeName.$ext")

                exportSong(
                    songId = songId,
                    outputUri = fileUri,
                    threads = threads,
                    onProgress = { onSongProgress(index + 1, songIds.size, it) },
                ).getOrThrow()
                exportedCount++
            }
            exportedCount
        }
    }

    /**
     * Creates a new file inside a DocumentsProvider tree identified by [treeUri].
     */
    private fun createFileInTree(treeUri: Uri, displayName: String, mimeType: String): Uri? {
        return try {
            android.provider.DocumentsContract.createDocument(
                context.contentResolver,
                treeUri,
                mimeType,
                displayName,
            )
        } catch (e: Exception) {
            null
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
                    val fraction = written.addAndGet(count.toLong()).toFloat() / total.toFloat()
                    onProgress(fraction)
                }
            }
        }
    }

    private fun writeFully(channel: FileChannel, buffer: ByteBuffer, destination: Long) {
        var target = destination
        while (buffer.hasRemaining()) {
            target += channel.write(buffer, target)
        }
    }
}
