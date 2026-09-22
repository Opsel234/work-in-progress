/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.menu

import android.content.Intent
import android.content.res.Configuration
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadService
import com.metrolist.innertube.YouTube
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalDownloadUtil
import com.metrolist.music.LocalExportUtil
import com.metrolist.music.LocalListenTogetherManager
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.db.entities.Playlist
import com.metrolist.music.db.entities.PlaylistSong
import com.metrolist.music.db.entities.Song
import com.metrolist.music.db.entities.SpeedDialItem
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.playback.ExoDownloadService
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.playback.queues.YouTubeQueue
import com.metrolist.music.ui.component.DefaultDialog
import com.metrolist.music.ui.component.Material3MenuGroup
import com.metrolist.music.ui.component.Material3MenuItemData
import com.metrolist.music.ui.component.NewAction
import com.metrolist.music.ui.component.NewActionGrid
import com.metrolist.music.ui.component.PlaylistListItem
import com.metrolist.music.ui.component.TextFieldDialog
import com.metrolist.music.ui.menu.ExportDialog
import com.metrolist.music.utils.PlaylistExporter
import com.metrolist.music.utils.getExportFileUri
import com.metrolist.music.utils.saveToPublicDocuments
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DecimalFormat
import java.time.LocalDateTime

@Composable
fun PlaylistMenu(
    playlist: Playlist,
    coroutineScope: CoroutineScope,
    onDismiss: () -> Unit,
    autoPlaylist: Boolean? = false,
    downloadPlaylist: Boolean? = false,
    songList: List<Song>? = emptyList(),
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val downloadUtil = LocalDownloadUtil.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val listenTogetherManager = LocalListenTogetherManager.current
    val isGuest = listenTogetherManager?.isInRoom == true && !listenTogetherManager.isHost
    val dbPlaylist by database.playlist(playlist.id).collectAsStateWithLifecycle(initialValue = playlist)
    var songs by remember {
        mutableStateOf(emptyList<Song>())
    }

    LaunchedEffect(Unit) {
        if (autoPlaylist == false) {
            database.playlistSongs(playlist.id).collect {
                songs = it.map(PlaylistSong::song)
            }
        } else {
            if (songList != null) {
                songs = songList
            }
        }
    }

    var downloadState by remember {
        mutableIntStateOf(Download.STATE_STOPPED)
    }

    val editable: Boolean = playlist.playlist.isEditable == true

    val isPinned by database.speedDialDao.isPinned(playlist.id).collectAsStateWithLifecycle(initialValue = false)

    var showExportDialog by remember { mutableStateOf(false) }
    var showAudioExportDialog by remember { mutableStateOf(false) }

    LaunchedEffect(songs) {
        if (songs.isEmpty()) return@LaunchedEffect
        downloadUtil.downloads.collect { downloads ->
            downloadState =
                if (songs.all { downloads[it.id]?.state == Download.STATE_COMPLETED }) {
                    Download.STATE_COMPLETED
                } else if (songs.all {
                        downloads[it.id]?.state == Download.STATE_QUEUED ||
                            downloads[it.id]?.state == Download.STATE_DOWNLOADING ||
                            downloads[it.id]?.state == Download.STATE_COMPLETED
                    }
                ) {
                    Download.STATE_DOWNLOADING
                } else {
                    Download.STATE_STOPPED
                }
        }
    }

    var showEditDialog by remember {
        mutableStateOf(false)
    }

    if (showEditDialog) {
        TextFieldDialog(
            icon = { Icon(painter = painterResource(R.drawable.edit), contentDescription = null) },
            title = { Text(text = stringResource(R.string.edit_playlist)) },
            onDismiss = { showEditDialog = false },
            initialTextFieldValue =
                TextFieldValue(
                    playlist.playlist.name,
                    TextRange(playlist.playlist.name.length),
                ),
            onDone = { name ->
                onDismiss()
                database.query {
                    update(
                        playlist.playlist.copy(
                            name = name,
                            lastUpdateTime = LocalDateTime.now(),
                        ),
                    )
                }
                coroutineScope.launch(Dispatchers.IO) {
                    playlist.playlist.browseId?.let { YouTube.renamePlaylist(it, name) }
                }
            },
        )
    }

    var showRemoveDownloadDialog by remember {
        mutableStateOf(false)
    }

    if (showRemoveDownloadDialog) {
        DefaultDialog(
            onDismiss = { showRemoveDownloadDialog = false },
            content = {
                Text(
                    text =
                        stringResource(
                            R.string.remove_download_playlist_confirm,
                            playlist.playlist.name,
                        ),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
            },
            buttons = {
                TextButton(
                    onClick = {
                        showRemoveDownloadDialog = false
                    },
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }

                TextButton(
                    onClick = {
                        showRemoveDownloadDialog = false
                        songs.forEach { song ->
                            DownloadService.sendRemoveDownload(
                                context,
                                ExoDownloadService::class.java,
                                song.id,
                                false,
                            )
                        }
                    },
                ) {
                    Text(text = stringResource(android.R.string.ok))
                }
            },
        )
    }

    var showDeletePlaylistDialog by remember {
        mutableStateOf(false)
    }

    if (showDeletePlaylistDialog) {
        DefaultDialog(
            onDismiss = { showDeletePlaylistDialog = false },
            content = {
                Text(
                    text = stringResource(R.string.delete_playlist_confirm, playlist.playlist.name),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
            },
            buttons = {
                TextButton(
                    onClick = {
                        showDeletePlaylistDialog = false
                    },
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }

                TextButton(
                    onClick = {
                        showDeletePlaylistDialog = false
                        onDismiss()
                        database.transaction {
                            // First toggle the like using the same logic as the like button
                            if (playlist.playlist.bookmarkedAt != null) {
                                // Using the same toggleLike() method that's used in the like button
                                update(playlist.playlist.toggleLike())
                            }
                            // Then delete the playlist
                            delete(playlist.playlist)
                        }

                        coroutineScope.launch(Dispatchers.IO) {
                            playlist.playlist.browseId?.let { YouTube.deletePlaylist(it) }
                        }
                    },
                ) {
                    Text(text = stringResource(android.R.string.ok))
                }
            },
        )
    }

    PlaylistListItem(
        playlist = playlist,
        trailingContent = {
            if (playlist.playlist.isEditable != true) {
                IconButton(
                    onClick = {
                        database.query {
                            dbPlaylist?.playlist?.toggleLike()?.let { update(it) }
                        }
                    },
                ) {
                    Icon(
                        painter =
                            painterResource(
                                if (dbPlaylist?.playlist?.bookmarkedAt !=
                                    null
                                ) {
                                    R.drawable.favorite
                                } else {
                                    R.drawable.favorite_border
                                },
                            ),
                        tint =
                            if (dbPlaylist?.playlist?.bookmarkedAt !=
                                null
                            ) {
                                MaterialTheme.colorScheme.error
                            } else {
                                LocalContentColor.current
                            },
                        contentDescription = null,
                    )
                }
            }
        },
    )

    HorizontalDivider()

    Spacer(modifier = Modifier.height(12.dp))

    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    LazyColumn(
        contentPadding =
            PaddingValues(
                start = 0.dp,
                top = 0.dp,
                end = 0.dp,
                bottom = 8.dp + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding(),
            ),
    ) {
        item {
            NewActionGrid(
                actions =
                    listOfNotNull(
                        if (!isGuest) {
                            NewAction(
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.play),
                                        contentDescription = null,
                                        modifier = Modifier.size(28.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                text = stringResource(R.string.play),
                                onClick = {
                                    onDismiss()
                                    if (songs.isNotEmpty()) {
                                        playerConnection.playQueue(
                                            ListQueue(
                                                title = playlist.playlist.name,
                                                items = songs.map(Song::toMediaItem),
                                            ),
                                        )
                                    }
                                },
                            )
                            NewAction(
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.shuffle),
                                        contentDescription = null,
                                        modifier = Modifier.size(28.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                text = stringResource(R.string.shuffle),
                                onClick = {
                                    onDismiss()
                                    if (songs.isNotEmpty()) {
                                        playerConnection.playQueue(
                                            ListQueue(
                                                title = playlist.playlist.name,
                                                items = songs.shuffled().map(Song::toMediaItem),
                                            ),
                                        )
                                    }
                                },
                            )
                        } else {
                            null
                        },
                        NewAction(
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.share),
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            text = stringResource(R.string.share),
                            onClick = {
                                onDismiss()
                                val intent =
                                    Intent().apply {
                                        action = Intent.ACTION_SEND
                                        type = "text/plain"
                                        putExtra(
                                            Intent.EXTRA_TEXT,
                                            "https://music.youtube.com/playlist?list=${dbPlaylist?.playlist?.browseId}",
                                        )
                                    }
                                context.startActivity(Intent.createChooser(intent, null))
                            },
                        ),
                    ),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp),
                columns = if (isGuest) 2 else 3,
            )
        }

        item {
            Material3MenuGroup(
                items =
                    buildList {
                        if (!isGuest) {
                            playlist.playlist.browseId?.let { browseId ->
                                add(
                                    Material3MenuItemData(
                                        title = { Text(text = stringResource(R.string.start_radio)) },
                                        description = { Text(text = stringResource(R.string.start_radio_desc)) },
                                        icon = {
                                            Icon(
                                                painter = painterResource(R.drawable.radio),
                                                contentDescription = null,
                                            )
                                        },
                                        onClick = {
                                            coroutineScope.launch(Dispatchers.IO) {
                                                YouTube.playlist(browseId).getOrNull()?.playlist?.let { playlistItem ->
                                                    playlistItem.radioEndpoint?.let { radioEndpoint ->
                                                        withContext(Dispatchers.Main) {
                                                            playerConnection.playQueue(YouTubeQueue(radioEndpoint))
                                                        }
                                                    }
                                                }
                                            }
                                            onDismiss()
                                        },
                                    ),
                                )
                            }
                        }
                        if (!isGuest) {
                            add(
                                Material3MenuItemData(
                                    title = { Text(text = stringResource(R.string.play_next)) },
                                    description = { Text(text = stringResource(R.string.play_next_desc)) },
                                    icon = {
                                        Icon(
                                            painter = painterResource(R.drawable.playlist_play),
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        coroutineScope.launch {
                                            playerConnection.playNext(songs.map { it.toMediaItem() })
                                        }
                                        onDismiss()
                                    },
                                ),
                            )
                        }
                        if (!isGuest) {
                            add(
                                Material3MenuItemData(
                                    title = { Text(text = stringResource(R.string.add_to_queue)) },
                                    description = { Text(text = stringResource(R.string.add_to_queue_desc)) },
                                    icon = {
                                        Icon(
                                            painter = painterResource(R.drawable.queue_music),
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        onDismiss()
                                        playerConnection.addToQueue(songs.map { it.toMediaItem() })
                                    },
                                ),
                            )
                        }
                    },
            )
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }

        item {
            Material3MenuGroup(
                items =
                    buildList {
                        if (editable && autoPlaylist != true && !isGuest) {
                            add(
                                Material3MenuItemData(
                                    title = { Text(text = stringResource(R.string.edit)) },
                                    description = { Text(text = stringResource(R.string.edit_desc)) },
                                    icon = {
                                        Icon(
                                            painter = painterResource(R.drawable.edit),
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        showEditDialog = true
                                    },
                                ),
                            )
                        }
                        add(
                            Material3MenuItemData(
                                title = {
                                    Text(
                                        text = if (isPinned) stringResource(R.string.unpin_from_speed_dial) else stringResource(R.string.pin_to_speed_dial),
                                    )
                                },
                                icon = {
                                    Icon(
                                        painter = painterResource(if (isPinned) R.drawable.remove else R.drawable.add),
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    coroutineScope.launch(Dispatchers.IO) {
                                        if (isPinned) {
                                            database.speedDialDao.delete(playlist.id)
                                        } else {
                                            database.speedDialDao.insert(
                                                SpeedDialItem(
                                                    id = playlist.id,
                                                    title = playlist.playlist.name,
                                                    subtitle = null,
                                                    subtitleIds = null,
                                                    thumbnailUrl = playlist.thumbnails.firstOrNull(),
                                                    type = "LOCAL_PLAYLIST",
                                                ),
                                            )
                                        }
                                    }
                                    onDismiss()
                                },
                            ),
                        )
                        if (downloadPlaylist != true) {
                            add(
                                when (downloadState) {
                                    Download.STATE_COMPLETED -> {
                                        Material3MenuItemData(
                                            title = {
                                                Text(
                                                    text = stringResource(R.string.remove_download),
                                                )
                                            },
                                            icon = {
                                                Icon(
                                                    painter = painterResource(R.drawable.offline),
                                                    contentDescription = null,
                                                )
                                            },
                                            onClick = {
                                                showRemoveDownloadDialog = true
                                            },
                                        )
                                    }

                                    Download.STATE_QUEUED, Download.STATE_DOWNLOADING -> {
                                        Material3MenuItemData(
                                            title = { Text(text = stringResource(R.string.downloading)) },
                                            icon = {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(24.dp),
                                                    strokeWidth = 2.dp,
                                                )
                                            },
                                            onClick = {
                                                showRemoveDownloadDialog = true
                                            },
                                        )
                                    }

                                    else -> {
                                        Material3MenuItemData(
                                            title = { Text(text = stringResource(R.string.action_download)) },
                                            description = { Text(text = stringResource(R.string.download_desc)) },
                                            icon = {
                                                Icon(
                                                    painter = painterResource(R.drawable.download),
                                                    contentDescription = null,
                                                )
                                            },
                                            onClick = {
                                                songs.forEach { downloadUtil.download(it) }
                                            },
                                        )
                                    }
                                },
                            )
                        }
                        // Export playlist (CSV/M3U)
                        add(
                            Material3MenuItemData(
                                title = { Text(text = stringResource(R.string.export_playlist)) },
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.share),
                                        contentDescription = null,
                                    )
                                },
                                onClick = { showExportDialog = true },
                            ),
                        )
                        // Export audio files
                        add(
                            Material3MenuItemData(
                                title = { Text(text = stringResource(R.string.playlist_export_to_file)) },
                                description = { Text(text = stringResource(R.string.playlist_export_subtitle)) },
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.download),
                                        contentDescription = null,
                                    )
                                },
                                onClick = { showAudioExportDialog = true },
                            ),
                        )
                        if (autoPlaylist != true && !isGuest) {
                            add(
                                Material3MenuItemData(
                                    title = { Text(text = stringResource(R.string.delete)) },
                                    description = { Text(text = stringResource(R.string.delete_desc)) },
                                    icon = {
                                        Icon(
                                            painter = painterResource(R.drawable.delete),
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        showDeletePlaylistDialog = true
                                    },
                                ),
                            )
                        }
                        playlist.playlist.shareLink?.let { shareLink ->
                            add(
                                Material3MenuItemData(
                                    title = { Text(text = stringResource(R.string.share)) },
                                    description = { Text(text = stringResource(R.string.share_desc)) },
                                    icon = {
                                        Icon(
                                            painter = painterResource(R.drawable.share),
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        val intent =
                                            Intent().apply {
                                                action = Intent.ACTION_SEND
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_TEXT, shareLink)
                                            }
                                        context.startActivity(Intent.createChooser(intent, null))
                                        onDismiss()
                                    },
                                ),
                            )
                        }
                    },
            )
        }
    }

    val exportPlaylistStr = stringResource(R.string.export_playlist)

    if (showExportDialog) {
        ExportDialog(
            onDismiss = { showExportDialog = false },
            onShare = { format ->
                val playlistSongs =
                    songs.map { s ->
                        com.metrolist.music.db.entities.PlaylistSong(
                            map =
                                com.metrolist.music.db.entities.PlaylistSongMap(
                                    songId = s.id,
                                    playlistId = playlist.id,
                                    position = 0,
                                ),
                            song = s,
                        )
                    }
                val result =
                    when (format) {
                        "csv" -> PlaylistExporter.exportPlaylistAsCSV(context, playlist.playlist.name, playlistSongs)
                        "m3u" -> PlaylistExporter.exportPlaylistAsM3U(context, playlist.playlist.name, playlistSongs)
                        else -> Result.failure(IllegalArgumentException("Unknown format"))
                    }
                result
                    .onSuccess { file ->
                        val uri = getExportFileUri(context, file)
                        val mimeType = if (format == "csv") "text/csv" else "audio/x-mpegurl"
                        val shareIntent =
                            Intent(Intent.ACTION_SEND).apply {
                                type = mimeType
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                        context.startActivity(Intent.createChooser(shareIntent, exportPlaylistStr))
                    }.onFailure {
                        Toast.makeText(context, R.string.export_failed, Toast.LENGTH_SHORT).show()
                    }
                showExportDialog = false
            },
            onSave = { format ->
                val playlistSongs =
                    songs.map { s ->
                        com.metrolist.music.db.entities.PlaylistSong(
                            map =
                                com.metrolist.music.db.entities.PlaylistSongMap(
                                    songId = s.id,
                                    playlistId = playlist.id,
                                    position = 0,
                                ),
                            song = s,
                        )
                    }
                val export =
                    when (format) {
                        "csv" -> PlaylistExporter.exportPlaylistAsCSV(context, playlist.playlist.name, playlistSongs)
                        "m3u" -> PlaylistExporter.exportPlaylistAsM3U(context, playlist.playlist.name, playlistSongs)
                        else -> Result.failure(IllegalArgumentException("Unknown format"))
                    }
                export
                    .onSuccess { file ->
                        val mimeType = if (format == "csv") "text/csv" else "audio/x-mpegurl"
                        val save = saveToPublicDocuments(context, file, mimeType)
                        save
                            .onSuccess { Toast.makeText(context, R.string.export_success, Toast.LENGTH_SHORT).show() }
                            .onFailure { Toast.makeText(context, R.string.export_failed, Toast.LENGTH_SHORT).show() }
                    }.onFailure {
                        Toast.makeText(context, R.string.export_failed, Toast.LENGTH_SHORT).show()
                    }
                showExportDialog = false
            },
        )
    }

    if (showAudioExportDialog) {
        ExportPlaylistAudioDialog(
            playlistName = playlist.playlist.name,
            songs = songs,
            visible = showAudioExportDialog,
            onDismiss = { showAudioExportDialog = false },
        )
    }
}

@Composable
fun ExportPlaylistAudioDialog(
    playlistName: String,
    songs: List<Song>,
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    if (!visible) return

    val context = LocalContext.current
    val exportUtil = LocalExportUtil.current
    val coroutineScope = rememberCoroutineScope()

    var threads by remember { mutableIntStateOf(4) }
    var qualityExpanded by remember { mutableStateOf(false) }
    var selectedQuality by remember { mutableStateOf("balanced") }
    var isExporting by remember { mutableStateOf(false) }
    var currentSongIndex by remember { mutableIntStateOf(0) }
    var totalSongs by remember { mutableIntStateOf(0) }
    var fileProgress by remember { mutableFloatStateOf(0f) }
    var cachedCount by remember { mutableIntStateOf(0) }
    var estimatedTotalBytes by remember { mutableLongStateOf(0L) }

    LaunchedEffect(songs) {
        val cached = songs.filter { exportUtil.isCached(it.id) }
        cachedCount = cached.size
        var total = 0L
        cached.forEach { song ->
            total += exportUtil.estimatedSize(song.id)
        }
        estimatedTotalBytes = total
    }

    val qualityLabel =
        when (selectedQuality) {
            "low" -> stringResource(R.string.song_export_quality_low)
            "high" -> stringResource(R.string.song_export_quality_high)
            else -> stringResource(R.string.song_export_quality_balanced)
        }

    val estimatedSizeStr = formatFileSize(estimatedTotalBytes)

    val folderLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
            if (treeUri != null && !isExporting) {
                isExporting = true
                currentSongIndex = 0
                totalSongs = cachedCount
                fileProgress = 0f
                coroutineScope.launch {
                    try {
                        // Take persistable permission
                        context.contentResolver.takePersistableUriPermission(
                            treeUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                        )
                    } catch (_: Exception) { }

                    // Export each cached song
                    var exportedCount = 0
                    val cachedSongs = songs.filter { exportUtil.isCached(it.id) }
                    cachedSongs.forEachIndexed { index, song ->
                        currentSongIndex = index + 1
                        fileProgress = 0f

                        val ext = exportUtil.suggestedExtension(song.id)
                        val safeName = song.title.replace(Regex("""[\\/:*?"<>|]"""), "_")

                        try {
                            val docUri = android.provider.DocumentsContract.createDocument(
                                context.contentResolver,
                                treeUri,
                                "audio/*",
                                "$safeName.$ext",
                            )
                            if (docUri != null) {
                                exportUtil.exportSong(
                                    songId = song.id,
                                    outputUri = docUri,
                                    threads = threads,
                                    onProgress = { fileProgress = it },
                                ).getOrThrow()
                                exportedCount++
                            }
                        } catch (_: Exception) { }
                    }

                    withContext(Dispatchers.Main) {
                        if (exportedCount > 0) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.playlist_exported, exportedCount),
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            Toast.makeText(
                                context,
                                R.string.playlist_export_no_downloads,
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                    isExporting = false
                    onDismiss()
                }
            }
        }

    AlertDialog(
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = { if (!isExporting) onDismiss() },
        title = { Text(stringResource(R.string.playlist_export_to_file)) },
        dismissButton = {
            TextButton(enabled = !isExporting, onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isExporting && cachedCount > 0,
                onClick = { folderLauncher.launch(null) },
            ) {
                Text(text = stringResource(R.string.song_export_button))
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Playlist info
                Text(
                    text = playlistName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.playlist_export_songs_ready, cachedCount, songs.size),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (estimatedTotalBytes > 0) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.playlist_export_estimated_size, estimatedSizeStr),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Threads row
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.song_export_threads),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    ValueAdjuster(
                        icon = R.drawable.speed,
                        currentValue = threads,
                        values = (1..32).toList(),
                        onValueUpdate = { threads = it },
                        valueText = { it.toString() },
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Quality row
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.song_export_quality_label),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Box {
                        TextButton(onClick = { qualityExpanded = true }) {
                            Text(
                                text = qualityLabel,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        DropdownMenu(
                            expanded = qualityExpanded,
                            onDismissRequest = { qualityExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.song_export_quality_low)) },
                                onClick = {
                                    selectedQuality = "low"
                                    qualityExpanded = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.song_export_quality_balanced)) },
                                onClick = {
                                    selectedQuality = "balanced"
                                    qualityExpanded = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.song_export_quality_high)) },
                                onClick = {
                                    selectedQuality = "high"
                                    qualityExpanded = false
                                },
                            )
                        }
                    }
                }

                if (isExporting) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.playlist_export_progress, currentSongIndex, totalSongs),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { fileProgress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${(fileProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
    )
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.size - 1) {
        value /= 1024
        unitIndex++
    }
    val df = DecimalFormat("#.##")
    return "${df.format(value)} ${units[unitIndex]}"
}
