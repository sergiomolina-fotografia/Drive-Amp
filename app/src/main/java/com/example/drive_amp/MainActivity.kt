package com.example.drive_amp

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.drive_amp.audio.AudioSpectrumAnalyzer
import com.example.drive_amp.audio.GraphicEqualizerManager
import com.example.drive_amp.data.PlaylistRepository
import com.example.drive_amp.data.SavedPlaylist
import com.example.drive_amp.service.DriveAmpMediaService
import com.example.drive_amp.ui.theme.DriveAmpTheme
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

enum class TrackType(val label: String) {
    DRIVE_PUBLIC("Drive Público"),
    HTTP("Enlace HTTP"),
    LOCAL("Audio Local")
}

data class Track(
    val id: String,
    val title: String,
    val uri: String,
    val type: TrackType,
    val isFavorite: Boolean = false
)

data class DriveApiItem(
    val title: String,
    val streamUrl: String,
    // true cuando es una carpeta devuelta por la búsqueda (streamUrl guarda el enlace de la carpeta)
    val isFolder: Boolean = false
)

/** Nivel de navegación dentro de una carpeta de Google Drive durante la búsqueda. */
data class DriveBrowseLevel(
    val title: String,
    val items: List<DriveApiItem>
)

class MainActivity : ComponentActivity() {

    private var mediaController: MediaController? = null
    private lateinit var controllerFuture: ListenableFuture<MediaController>

    var playlist by mutableStateOf<List<Track>>(emptyList())
    // Favoritos: lista propia y permanente, independiente de la playlist actual
    var favorites by mutableStateOf<List<Track>>(emptyList())
    // Listas guardadas en Favoritos (locales, enlaces o mezcla)
    var savedLists by mutableStateOf<List<SavedPlaylist>>(emptyList())
    // Nombre sugerido al guardar la lista (por ejemplo, el nombre de la última carpeta abierta)
    var suggestedListName by mutableStateOf("")
    var currentTrackIndex by mutableStateOf(-1)
    var isPlaying by mutableStateOf(false)
    var isPaused by mutableStateOf(false)
    var isShuffle by mutableStateOf(false)
    var isRepeat by mutableStateOf(false)
    var currentTitle by mutableStateOf("DRIVE-AMP — agregá archivos o pegá un enlace de Drive")
    var currentSourceType by mutableStateOf("OFFLINE / G-DRIVE")
    var currentPositionMs by mutableStateOf(0L)
    var durationMs by mutableStateOf(0L)
    var volume by mutableStateOf(0.83f)
    var serverBaseUrl by mutableStateOf(PlaylistRepository.DEFAULT_SERVER_URL)

    // Selector de archivos locales
    private val localFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val newTracks = uris.map { uri ->
                // Permiso persistente: sin esto los audios locales guardados en Favoritos
                // dejan de poder abrirse al reiniciar la app o el teléfono
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: SecurityException) {
                    e.printStackTrace()
                }
                val name = getFileName(uri) ?: "Audio Local"
                Track(System.currentTimeMillis().toString() + name, name, uri.toString(), TrackType.LOCAL)
            }
            addTracks(newTracks)
        }
    }

    // Selector de carpetas locales
    private val localFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { treeUri ->
            contentResolver.takePersistableUriPermission(
                treeUri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            val rootDoc = DocumentFile.fromTreeUri(this, treeUri)
            if (rootDoc != null) {
                val discoveredTracks = mutableListOf<Track>()

                fun scanDirectory(doc: DocumentFile) {
                    if (doc.isDirectory) {
                        doc.listFiles().forEach { scanDirectory(it) }
                    } else if (doc.isFile) {
                        val mime = doc.type ?: ""
                        val name = doc.name?.lowercase() ?: ""
                        if (mime.startsWith("audio/") || name.endsWith(".mp3") || name.endsWith(".m4a") || name.endsWith(".wav") || name.endsWith(".flac")) {
                            discoveredTracks.add(
                                Track(
                                    id = System.currentTimeMillis().toString() + doc.name,
                                    title = doc.name ?: "Audio",
                                    uri = doc.uri.toString(),
                                    type = TrackType.LOCAL
                                )
                            )
                        }
                    }
                }

                scanDirectory(rootDoc)
                if (discoveredTracks.isNotEmpty()) {
                    suggestedListName = rootDoc.name ?: ""
                    addTracks(discoveredTracks)
                }
            }
        }
    }

    // Escanear: elige una carpeta del dispositivo y guarda sus subcarpetas como listas en Favoritos
    private val scanFavoritesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { treeUri ->
            try {
                contentResolver.takePersistableUriPermission(
                    treeUri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                e.printStackTrace()
            }
            Toast.makeText(this, "Escaneando carpetas…", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch(Dispatchers.IO) {
                val rootDoc = DocumentFile.fromTreeUri(this@MainActivity, treeUri)
                val scannedLists = mutableListOf<Pair<String, List<Track>>>()

                fun isAudioFile(doc: DocumentFile): Boolean {
                    val mime = doc.type ?: ""
                    val name = doc.name?.lowercase() ?: ""
                    return mime.startsWith("audio/") ||
                        name.endsWith(".mp3") || name.endsWith(".m4a") ||
                        name.endsWith(".wav") || name.endsWith(".flac") ||
                        name.endsWith(".ogg") || name.endsWith(".aac") || name.endsWith(".opus")
                }

                // Recorre recursivamente: cada carpeta que tiene audios directos se guarda como una lista con su nombre
                fun collectFolderLists(folder: DocumentFile) {
                    val directTracks = mutableListOf<Track>()
                    val subDirs = mutableListOf<DocumentFile>()
                    folder.listFiles().forEach { child ->
                        if (child.isDirectory) {
                            subDirs.add(child)
                        } else if (child.isFile && isAudioFile(child)) {
                            directTracks.add(
                                Track(
                                    id = "${System.nanoTime()}_${child.name}",
                                    title = child.name ?: "Audio",
                                    uri = child.uri.toString(),
                                    type = TrackType.LOCAL
                                )
                            )
                        }
                    }
                    if (directTracks.isNotEmpty()) {
                        val folderName = folder.name?.takeIf { it.isNotBlank() } ?: "Carpeta Local"
                        scannedLists.add(folderName to directTracks.sortedBy { it.title.lowercase() })
                    }
                    subDirs.sortedBy { it.name?.lowercase() ?: "" }.forEach { collectFolderLists(it) }
                }

                if (rootDoc != null) {
                    collectFolderLists(rootDoc)
                }

                withContext(Dispatchers.Main) {
                    if (scannedLists.isEmpty()) {
                        Toast.makeText(this@MainActivity, "No se encontraron subcarpetas con audios.", Toast.LENGTH_LONG).show()
                    } else {
                        saveMultipleLists(scannedLists)
                        val totalTracks = scannedLists.sumOf { it.second.size }
                        Toast.makeText(
                            this@MainActivity,
                            "★ Se guardaron ${scannedLists.size} listas ($totalTracks pistas) en Favoritos.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        playlist = PlaylistRepository.getTracks(this)
        favorites = PlaylistRepository.getFavorites(this)
        savedLists = PlaylistRepository.getSavedLists(this)
        GraphicEqualizerManager.loadFromPrefs(this)

        val prefs = getSharedPreferences("DriveAmpPrefs", MODE_PRIVATE)
        val storedServer = prefs.getString("server_base_url", PlaylistRepository.DEFAULT_SERVER_URL)
            ?.trim()
            .orEmpty()
        serverBaseUrl = if (storedServer.isBlank() || storedServer.contains("floot.app", ignoreCase = true)) {
            prefs.edit().putString("server_base_url", PlaylistRepository.DEFAULT_SERVER_URL).apply()
            PlaylistRepository.DEFAULT_SERVER_URL
        } else {
            storedServer
        }
        isShuffle = prefs.getBoolean("shuffle", false)
        isRepeat = prefs.getBoolean("repeat", false)
        volume = prefs.getFloat("volume", 0.83f)

        // Conectar con DriveAmpMediaService (Foreground Service) para reproducción en segundo plano ininterrumpida al bloquear la pantalla
        val sessionToken = SessionToken(this, ComponentName(this, DriveAmpMediaService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({
            val controller = controllerFuture.get()
            mediaController = controller
            controller.shuffleModeEnabled = isShuffle
            controller.volume = volume
            controller.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                    if (playing) isPaused = false
                }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    // Mantiene el número de pista al avanzar solo, desde el volante, el auto o el widget,
                    // y recarga la playlist si el reproductor pasó automáticamente a la siguiente lista guardada
                    syncNowPlaying(mediaItem)
                }
            })
            // Si ya estaba sonando (desde el auto o el widget) la app muestra esa pista y lista al abrirse
            playlist = PlaylistRepository.getTracks(this)
            isPlaying = controller.isPlaying
            syncNowPlaying(controller.currentMediaItem)
        }, ContextCompat.getMainExecutor(this))

        // Actualización continua del reloj digital y barra de progreso (como en la web)
        lifecycleScope.launch {
            while (true) {
                mediaController?.let { c ->
                    currentPositionMs = c.currentPosition.coerceAtLeast(0L)
                    durationMs = if (c.duration > 0) c.duration else 0L
                }
                delay(250L)
            }
        }

        setContent {
            DriveAmpTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0B0D12)
                ) {
                    WinampPlayerScreen(
                        playlist = playlist,
                        favorites = favorites,
                        currentIndex = currentTrackIndex,
                        isPlaying = isPlaying,
                        isPaused = isPaused,
                        isShuffle = isShuffle,
                        isRepeat = isRepeat,
                        currentTitle = currentTitle,
                        currentSourceType = currentSourceType,
                        currentPositionMs = currentPositionMs,
                        durationMs = durationMs,
                        volume = volume,
                        serverBaseUrl = serverBaseUrl,
                        onSaveServerUrl = { newUrl ->
                            serverBaseUrl = newUrl
                            prefs.edit().putString("server_base_url", newUrl).apply()
                        },
                        onPlayClick = { startPlay() },
                        onPauseClick = { pausePlayer() },
                        onStopClick = { stopPlayer() },
                        onMinimizeClick = { minimizeApp() },
                        onCloseClick = { closeApp() },
                        onNextClick = { playNext() },
                        onPrevClick = { playPrev() },
                        onSeekTo = { posMs ->
                            currentPositionMs = posMs
                            mediaController?.seekTo(posMs)
                        },
                        onToggleShuffle = {
                            isShuffle = !isShuffle
                            mediaController?.shuffleModeEnabled = isShuffle
                            prefs.edit().putBoolean("shuffle", isShuffle).apply()
                        },
                        onToggleRepeat = {
                            isRepeat = !isRepeat
                            prefs.edit().putBoolean("repeat", isRepeat).apply()
                        },
                        onTrackSelected = { index -> playTrack(index) },
                        onRemoveTrack = { index -> removeTrack(index) },
                        onClearPlaylist = { clearPlaylist() },
                        onAddDriveTracks = { tracks -> addTracks(tracks) },
                        onAddHttpUrl = { url -> addHttpUrl(url) },
                        onOpenFiles = { localFileLauncher.launch(arrayOf("audio/*")) },
                        onOpenFolder = { localFolderLauncher.launch(null) },
                        onScanFoldersToFavorites = { scanFavoritesLauncher.launch(null) },
                        onToggleFavorite = { track -> toggleFavorite(track) },
                        savedLists = savedLists,
                        suggestedListName = suggestedListName,
                        onPlayFavorite = { track -> playFavorite(track) },
                        onRemoveFavorite = { track -> removeFavorite(track) },
                        onAddAllFavorites = { addTracks(favorites) },
                        onSaveList = { name, tracks -> saveList(name, tracks) },
                        onLoadList = { list, replace -> loadList(list, replace) },
                        onDeleteList = { list -> deleteList(list) },
                        onVolumeChange = { v ->
                            volume = v
                            mediaController?.volume = v
                            prefs.edit().putFloat("volume", v).apply()
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Sincroniza la playlist en pantalla por si el servicio avanzó a la siguiente lista en segundo plano
        val savedTracks = PlaylistRepository.getTracks(this)
        if (savedTracks.map { it.id } != playlist.map { it.id }) {
            playlist = savedTracks
        }
        mediaController?.let { controller ->
            isPlaying = controller.isPlaying
            syncNowPlaying(controller.currentMediaItem)
        }
    }

    private fun updatePlaylist(newList: List<Track>) {
        // Solo toca la playlist actual: favoritos y listas guardadas se manejan aparte
        playlist = newList
        PlaylistRepository.savePlaylist(this, playlist)
        syncControllerQueue()
    }

    /** Limpia ÚNICAMENTE la lista de reproducción actual. Favoritos y listas guardadas quedan intactos. */
    private fun clearPlaylist() {
        mediaController?.let { controller ->
            controller.stop()
            controller.clearMediaItems()
        }
        isPlaying = false
        isPaused = false
        currentPositionMs = 0L
        durationMs = 0L
        currentTrackIndex = -1
        currentTitle = "DRIVE-AMP — agregá archivos o pegá un enlace de Drive"
        currentSourceType = "OFFLINE / G-DRIVE"
        PlaylistRepository.saveCurrentSavedListId(this, null)
        updatePlaylist(emptyList())
    }

    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex != -1) {
                name = cursor.getString(nameIndex)
            }
        }
        return name ?: uri.lastPathSegment
    }

    private fun addTracks(newTracks: List<Track>) {
        if (newTracks.isEmpty()) return
        val known = playlist.map { it.uri }.toSet()
        val fresh = newTracks.filter { it.uri !in known }
        if (fresh.isEmpty()) {
            // Ya estaba en la lista (por ejemplo, un favorito): se reproduce en vez de duplicarlo
            val existing = playlist.indexOfFirst { it.uri == newTracks[0].uri }
            if (existing >= 0) playTrack(existing)
            return
        }
        // Ids únicos (varias pistas se crean en el mismo milisegundo)
        val unique = fresh.mapIndexed { i, tr -> tr.copy(id = "${System.nanoTime()}_${i}_${tr.id.hashCode()}") }
        val startAt = playlist.size
        updatePlaylist(playlist + unique)
        if (currentTrackIndex == -1) playTrack(startAt)
    }

    private fun addHttpUrl(rawUrl: String) {
        if (rawUrl.isBlank()) return
        val title = rawUrl.substringAfterLast("/").ifBlank { "Stream Red" }
        val track = Track(System.currentTimeMillis().toString(), title, rawUrl.trim(), TrackType.HTTP)
        updatePlaylist(playlist + track)
        if (currentTrackIndex == -1) playTrack(playlist.size - 1)
    }

    private fun toggleFavorite(track: Track) {
        // Los favoritos se identifican por URI (el id cambia cada vez que la pista se agrega a la playlist)
        favorites = if (favorites.any { it.uri == track.uri }) {
            favorites.filter { it.uri != track.uri }
        } else {
            favorites + track.copy(id = "fav_${System.nanoTime()}", isFavorite = true)
        }
        PlaylistRepository.saveFavorites(this, favorites)
    }

    private fun removeFavorite(track: Track) {
        favorites = favorites.filter { it.uri != track.uri }
        PlaylistRepository.saveFavorites(this, favorites)
    }

    /** Reproduce un favorito: si ya está en la playlist salta a él, si no lo agrega al final y lo reproduce. */
    private fun playFavorite(track: Track) {
        val existing = playlist.indexOfFirst { it.uri == track.uri }
        if (existing >= 0) {
            playTrack(existing)
            return
        }
        updatePlaylist(playlist + track.copy(id = "${System.nanoTime()}_${track.uri.hashCode()}", isFavorite = false))
        playTrack(playlist.size - 1)
    }

    /** Guarda una lista (playlist actual, carpeta local o resultado de Drive) en Favoritos de forma permanente. */
    private fun saveList(name: String, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val cleanName = name.trim().ifBlank { "Lista ${savedLists.size + 1}" }
        val newList = SavedPlaylist(
            id = "list_${System.currentTimeMillis()}",
            name = cleanName,
            tracks = tracks.map { it.copy(isFavorite = false) }
        )
        // Si ya existe una lista con el mismo nombre se reemplaza (permite "actualizar" una lista)
        val idx = savedLists.indexOfFirst { it.name.equals(cleanName, ignoreCase = true) }
        savedLists = if (idx >= 0) {
            savedLists.toMutableList().also { it[idx] = newList.copy(id = savedLists[idx].id) }
        } else {
            savedLists + newList
        }
        PlaylistRepository.saveSavedLists(this, savedLists)
    }

    /** Guarda varias listas escaneadas de una sola vez en Favoritos (reemplaza las que tengan el mismo nombre). */
    private fun saveMultipleLists(entries: List<Pair<String, List<Track>>>) {
        if (entries.isEmpty()) return
        val updated = savedLists.toMutableList()
        val baseTime = System.currentTimeMillis()
        entries.forEachIndexed { index, (rawName, tracks) ->
            if (tracks.isEmpty()) return@forEachIndexed
            val cleanName = rawName.trim().ifBlank { "Lista ${updated.size + 1}" }
            val existingIdx = updated.indexOfFirst { it.name.equals(cleanName, ignoreCase = true) }
            val listId = if (existingIdx >= 0) updated[existingIdx].id else "list_${baseTime}_$index"
            val saved = SavedPlaylist(
                id = listId,
                name = cleanName,
                tracks = tracks.map { it.copy(isFavorite = false) }
            )
            if (existingIdx >= 0) {
                updated[existingIdx] = saved
            } else {
                updated.add(saved)
            }
        }
        savedLists = updated
        PlaylistRepository.saveSavedLists(this, savedLists)
    }

    /** Carga una lista guardada: reemplazando la playlist actual o agregándola al final. */
    private fun loadList(list: SavedPlaylist, replace: Boolean) {
        if (list.tracks.isEmpty()) return
        if (!replace) {
            addTracks(list.tracks)
            return
        }
        mediaController?.let { controller ->
            controller.stop()
            controller.clearMediaItems()
        }
        currentTrackIndex = -1
        PlaylistRepository.saveCurrentSavedListId(this, list.id)
        updatePlaylist(list.tracks.mapIndexed { i, t ->
            t.copy(id = "${System.nanoTime()}_${i}_${t.uri.hashCode()}", isFavorite = false)
        })
        playTrack(0)
    }

    private fun deleteList(list: SavedPlaylist) {
        savedLists = savedLists.filter { it.id != list.id }
        PlaylistRepository.saveSavedLists(this, savedLists)
        if (PlaylistRepository.getCurrentSavedListId(this) == list.id) {
            PlaylistRepository.saveCurrentSavedListId(this, null)
        }
    }

    private fun sourceLabel(type: TrackType) = when (type) {
        TrackType.DRIVE_PUBLIC -> "STREAMING DRIVE"
        TrackType.HTTP -> "ENLACE DE RED"
        TrackType.LOCAL -> "OFFLINE NATIVO"
    }

    private fun formatMarqueeTitle(idx: Int, track: Track?): String {
        if (track == null) return "DRIVE-AMP — agregá archivos o pegá un enlace de Drive"
        val prefix = when (track.type) {
            TrackType.HTTP -> "[RED] "
            TrackType.DRIVE_PUBLIC -> "[G-DRIVE] "
            TrackType.LOCAL -> ""
        }
        return "${idx + 1}. $prefix${track.title}"
    }

    /** Actualiza título / número de pista según lo que está sonando en el reproductor. */
    private fun syncNowPlaying(mediaItem: MediaItem?) {
        if (mediaItem == null) {
            if (playlist.isEmpty()) {
                currentTitle = "DRIVE-AMP — agregá archivos o pegá un enlace de Drive"
                currentSourceType = "OFFLINE / G-DRIVE"
            }
            return
        }
        var idx = playlist.indexOfFirst { it.id == mediaItem.mediaId }
        if (idx < 0 && !mediaItem.mediaId.startsWith(PlaylistRepository.FAV_PREFIX) && !mediaItem.mediaId.startsWith(PlaylistRepository.LIST_ITEM_PREFIX)) {
            // Si el servicio limpió la lista anterior y cargó la siguiente lista guardada automáticamente,
            // actualizamos la playlist en pantalla para que muestre la lista nueva
            val latest = PlaylistRepository.getTracks(this)
            val newIdx = latest.indexOfFirst { it.id == mediaItem.mediaId }
            if (newIdx >= 0) {
                playlist = latest
                idx = newIdx
            }
        }
        currentTrackIndex = idx
        val track = playlist.getOrNull(idx)
        if (track != null) {
            currentTitle = formatMarqueeTitle(idx, track)
            currentSourceType = sourceLabel(track.type)
        } else {
            currentTitle = mediaItem.mediaMetadata.title?.toString() ?: "Reproduciendo..."
        }
    }

    /** true si la cola del reproductor es exactamente la playlist actual. */
    private fun controllerHasPlaylist(c: MediaController): Boolean =
        c.mediaItemCount == playlist.size &&
            (0 until c.mediaItemCount).all { c.getMediaItemAt(it).mediaId == playlist[it].id }

    /**
     * Mantiene la cola del reproductor (la que ve Android Auto y usa el volante/widget)
     * igual a la playlist de la app, sin cortar la pista que está sonando.
     */
    private fun syncControllerQueue() {
        val c = mediaController ?: return
        val n = c.mediaItemCount
        if (n == 0) return // nada cargado todavía: playTrack carga la lista completa
        val oldIds = (0 until n).map { c.getMediaItemAt(it).mediaId }
        // Si desde el auto se está escuchando Favoritos o una lista guardada, no se toca esa cola
        if (oldIds.any { it.startsWith(PlaylistRepository.FAV_PREFIX) || it.startsWith(PlaylistRepository.LIST_ITEM_PREFIX) }) return
        if (playlist.isEmpty()) {
            c.stop()
            c.clearMediaItems()
            return
        }
        val newIds = playlist.map { it.id }
        if (oldIds == newIds) return
        val items = PlaylistRepository.toMediaItems(playlist)
        // Caso común: se agregaron pistas al final
        if (newIds.size > n && newIds.subList(0, n) == oldIds) {
            c.addMediaItems(items.subList(n, items.size))
            return
        }
        val curIdx = c.currentMediaItemIndex
        val newCur = newIds.indexOf(oldIds.getOrNull(curIdx))
        if (newCur >= 0) {
            // Reemplaza todo alrededor de la pista actual para que siga sonando sin cortes
            if (curIdx + 1 < n) c.removeMediaItems(curIdx + 1, n)
            if (curIdx > 0) c.removeMediaItems(0, curIdx)
            if (newCur > 0) c.addMediaItems(0, items.subList(0, newCur))
            if (newCur + 1 < items.size) c.addMediaItems(items.subList(newCur + 1, items.size))
        } else {
            // La pista que sonaba ya no está en la lista
            c.pause()
            c.setMediaItems(items, curIdx.coerceIn(0, items.size - 1), 0L)
        }
    }

    private fun playTrack(index: Int) {
        if (index < 0 || index >= playlist.size) return
        currentTrackIndex = index
        isPaused = false
        val tr = playlist[index]
        currentTitle = formatMarqueeTitle(index, tr)
        currentSourceType = sourceLabel(tr.type)

        mediaController?.let { controller ->
            if (controllerHasPlaylist(controller)) {
                controller.seekTo(index, 0L)
            } else {
                // Se carga la playlist COMPLETA: así Android Auto muestra toda la lista y
                // siguiente/anterior funcionan desde el volante, la notificación y el widget
                controller.setMediaItems(PlaylistRepository.toMediaItems(playlist), index, 0L)
            }
            controller.prepare()
            controller.play()
        }
    }

    private fun startPlay() {
        if (playlist.isEmpty()) {
            localFileLauncher.launch(arrayOf("audio/*"))
            return
        }
        mediaController?.let { controller ->
            isPaused = false
            if (currentTrackIndex == -1 || controller.mediaItemCount == 0) {
                playTrack(0)
            } else {
                if (controller.playbackState == Player.STATE_IDLE || controller.playbackState == Player.STATE_ENDED) {
                    controller.prepare()
                }
                controller.play()
            }
        }
    }

    private fun pausePlayer() {
        mediaController?.let { controller ->
            if (controller.isPlaying) {
                controller.pause()
                isPaused = true
            } else if (isPaused) {
                isPaused = false
                controller.play()
            }
        }
    }

    private fun stopPlayer() {
        mediaController?.let { controller ->
            controller.pause()
            controller.seekTo(0)
        }
        isPlaying = false
        isPaused = false
        currentPositionMs = 0L
    }

    private fun minimizeApp() {
        moveTaskToBack(true)
    }

    private fun closeApp() {
        stopPlayer()
        mediaController?.stop()
        stopService(Intent(this, DriveAmpMediaService::class.java))
        finishAndRemoveTask()
    }

    private fun playNext() {
        if (playlist.isEmpty()) {
            val nextList = PlaylistRepository.getNextSavedList(this)
            if (nextList != null) loadList(nextList, replace = true)
            return
        }
        if (isShuffle && playlist.size > 1) {
            var next = currentTrackIndex
            while (next == currentTrackIndex) next = Random.nextInt(playlist.size)
            playTrack(next)
            return
        }
        if (currentTrackIndex + 1 < playlist.size) {
            playTrack(currentTrackIndex + 1)
        } else {
            // Al llegar al final de la lista actual, limpia y pasa a la siguiente lista guardada en Favoritos
            val nextList = PlaylistRepository.getNextSavedList(this)
            if (!isRepeat && nextList != null) {
                loadList(nextList, replace = true)
            } else {
                playTrack(0)
            }
        }
    }

    private fun playPrev() {
        val c = mediaController
        if (c != null && c.currentPosition > 3000L) {
            c.seekTo(0L)
            return
        }
        if (playlist.isEmpty()) return
        playTrack(if (currentTrackIndex - 1 < 0) playlist.size - 1 else currentTrackIndex - 1)
    }

    private fun removeTrack(index: Int) {
        if (index !in playlist.indices) return
        val removingCurrent = currentTrackIndex == index
        val list = playlist.toMutableList()
        list.removeAt(index)
        if (removingCurrent) mediaController?.stop()
        updatePlaylist(list)
        if (removingCurrent) {
            currentTrackIndex = -1
            isPlaying = false
            isPaused = false
            currentTitle = "DRIVE-AMP — agregá archivos o pegá un enlace de Drive"
            currentSourceType = "OFFLINE / G-DRIVE"
        } else if (currentTrackIndex > index) {
            currentTrackIndex -= 1
        }
    }

    override fun onDestroy() {
        MediaController.releaseFuture(controllerFuture)
        super.onDestroy()
    }
}

// ============================================================================
// PALETA Y BORDES BISELADOS 3D DE DRIVE-AMP WEB (WINAMP CLASSIC SKIN)
// ============================================================================
private val WinBg = Color(0xFF0B0D12)
private val WinBgGlow = Color(0xFF1A2238)
private val WinChrome = Color(0xFF2B3140)
private val WinBevelLight = Color(0xFF6D7690)
private val WinBevelDark = Color(0xFF0D1017)
private val WinGreen = Color(0xFF00E000)
private val WinDimGreen = Color(0xFF14A014)
private val WinOrange = Color(0xFFFF9D00)
private val WinText = Color(0xFFD7DFEC)
private val WinTextMuted = Color(0xFF7F8AA0)

/** Marco de ventana estilo Winamp: fondo #2b3140, borde exterior negro y bisel 3D elevado (#6d7690 / #0d1017). */
private fun Modifier.winampWindow(): Modifier = this
    .background(WinChrome)
    .drawWithContent {
        drawContent()
        val s = 1.dp.toPx()
        val b = 2.dp.toPx()
        // Sombra exterior negra
        drawRect(Color.Black, style = Stroke(width = s))
        // Bisel superior e izquierdo claro (#6d7690)
        drawLine(WinBevelLight, Offset(s, s + b / 2), Offset(size.width - s, s + b / 2), strokeWidth = b)
        drawLine(WinBevelLight, Offset(s + b / 2, s), Offset(s + b / 2, size.height - s), strokeWidth = b)
        // Bisel inferior y derecho oscuro (#0d1017)
        drawLine(WinBevelDark, Offset(s, size.height - s - b / 2), Offset(size.width - s, size.height - s - b / 2), strokeWidth = b)
        drawLine(WinBevelDark, Offset(size.width - s - b / 2, s), Offset(size.width - s - b / 2, size.height - s), strokeWidth = b)
    }
    .padding(3.dp)

/** Caja hundida estilo Winamp (LCD, lista de reproducción, inputs): fondo negro y bisel invertido (#0d1017 / #6d7690). */
private fun Modifier.winampSunken(bgColor: Color = Color.Black): Modifier = this
    .background(bgColor)
    .drawWithContent {
        drawContent()
        val b = 2.dp.toPx()
        // Arriba e izquierda oscuros (#0d1017)
        drawLine(WinBevelDark, Offset(0f, b / 2), Offset(size.width, b / 2), strokeWidth = b)
        drawLine(WinBevelDark, Offset(b / 2, 0f), Offset(b / 2, size.height), strokeWidth = b)
        // Abajo y derecha claros (#6d7690)
        drawLine(WinBevelLight, Offset(0f, size.height - b / 2), Offset(size.width, size.height - b / 2), strokeWidth = b)
        drawLine(WinBevelLight, Offset(size.width - b / 2, 0f), Offset(size.width - b / 2, size.height), strokeWidth = b)
    }

/** Bisel elevado de 1dp para botones metálicos de transporte y controles. */
private fun Modifier.winampRaisedBevel(
    topLeft: Color = WinBevelLight,
    bottomRight: Color = WinBevelDark
): Modifier = this.drawWithContent {
    drawContent()
    val b = 1.5.dp.toPx()
    drawLine(topLeft, Offset(0f, b / 2), Offset(size.width, b / 2), strokeWidth = b)
    drawLine(topLeft, Offset(b / 2, 0f), Offset(b / 2, size.height), strokeWidth = b)
    drawLine(bottomRight, Offset(0f, size.height - b / 2), Offset(size.width, size.height - b / 2), strokeWidth = b)
    drawLine(bottomRight, Offset(size.width - b / 2, 0f), Offset(size.width - b / 2, size.height), strokeWidth = b)
}

private fun formatWinampTime(ms: Long, placeholderIfZero: Boolean = false): String {
    if (ms <= 0L) return if (placeholderIfZero) "--:--" else "0:00"
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val min = totalSec / 60L
    val sec = totalSec % 60L
    return "$min:${sec.toString().padStart(2, '0')}"
}

@Composable
fun WinampPlayerScreen(
    playlist: List<Track>,
    favorites: List<Track>,
    currentIndex: Int,
    isPlaying: Boolean,
    isPaused: Boolean,
    isShuffle: Boolean,
    isRepeat: Boolean,
    currentTitle: String,
    currentSourceType: String,
    currentPositionMs: Long,
    durationMs: Long,
    volume: Float,
    serverBaseUrl: String,
    onSaveServerUrl: (String) -> Unit,
    onPlayClick: () -> Unit,
    onPauseClick: () -> Unit,
    onStopClick: () -> Unit,
    onMinimizeClick: () -> Unit,
    onCloseClick: () -> Unit,
    onNextClick: () -> Unit,
    onPrevClick: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onTrackSelected: (Int) -> Unit,
    onRemoveTrack: (Int) -> Unit,
    onClearPlaylist: () -> Unit,
    onAddDriveTracks: (List<Track>) -> Unit,
    onAddHttpUrl: (String) -> Unit,
    onOpenFiles: () -> Unit,
    onOpenFolder: () -> Unit,
    onScanFoldersToFavorites: () -> Unit,
    onToggleFavorite: (Track) -> Unit,
    savedLists: List<SavedPlaylist>,
    suggestedListName: String,
    onPlayFavorite: (Track) -> Unit,
    onRemoveFavorite: (Track) -> Unit,
    onAddAllFavorites: () -> Unit,
    onSaveList: (String, List<Track>) -> Unit,
    onLoadList: (SavedPlaylist, Boolean) -> Unit,
    onDeleteList: (SavedPlaylist) -> Unit,
    onVolumeChange: (Float) -> Unit
) {
    // Modos de visualizador idénticos a Drive-Amp-Web:
    // 0 = PUNTOS (matriz LED 24x5 de la captura), 1 = BARRAS (19 barras + picos naranjas),
    // 2 = ONDA, 3 = ESPEJO, 4 = RADIAL, 5 = APAGADO
    val vizModes = remember { listOf("PUNTOS", "BARRAS", "ONDA", "ESPEJO", "RADIAL", "APAGADO") }
    var visualizerStyle by remember { mutableStateOf(0) }
    var vizToastText by remember { mutableStateOf<String?>(null) }

    // Ventanas acopladas desplegables (igual que en Drive-Amp-Web)
    var showEqWindow by remember { mutableStateOf(false) }
    var showDriveWindow by remember { mutableStateOf(false) }
    var showSearchWindow by remember { mutableStateOf(true) } // Visible como en la captura
    var showRedWindow by remember { mutableStateOf(false) }
    var showFavWindow by remember { mutableStateOf(false) }
    var playlistCollapsed by remember { mutableStateOf(false) }

    val context = LocalContext.current
    var eqEnabled by remember { mutableStateOf(GraphicEqualizerManager.isEnabled) }
    var eqPresetName by remember { mutableStateOf(GraphicEqualizerManager.currentPresetName) }
    var eqGains by remember { mutableStateOf(GraphicEqualizerManager.getGainsSnapshot()) }

    // Entradas de texto de cada panel
    var driveUrlInput by remember { mutableStateOf("") }
    var driveStatus by remember { mutableStateOf("Pegá el enlace compartido en modo «Cualquier persona con el enlace» y tocá CARGAR.") }
    var driveStatusColor by remember { mutableStateOf(Color(0xFF8CE99A)) }
    var resolvedTracks by remember { mutableStateOf<List<DriveApiItem>?>(null) }
    var isResolving by remember { mutableStateOf(false) }

    var searchInput by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<DriveApiItem>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var searchStatus by remember { mutableStateOf("Escribí un tema y buscá. Cada búsqueda tarda unos segundos.") }
    var searchStatusColor by remember { mutableStateOf(Color(0xFF72A9FF)) }
    var folderStack by remember { mutableStateOf<List<DriveBrowseLevel>>(emptyList()) }

    var redUrlInput by remember { mutableStateOf("") }
    var redStatus by remember { mutableStateOf("Para archivos MP3/FLAC/OGG en tu servidor, NAS o radios online con enlace directo.") }
    var redStatusColor by remember { mutableStateOf(Color(0xFFD0BFFF)) }

    var favNameInput by remember { mutableStateOf("") }
    var favStatus by remember { mutableStateOf("Armá una lista abajo o cargá una carpeta de Drive y guardala con nombre para tenerla siempre a mano.") }
    var favStatusColor by remember { mutableStateOf(Color(0xFFFFE066)) }

    // Modal auxiliar cuando se guarda directamente una carpeta desde Búsqueda o desde Verificar Drive
    var pendingSaveTracks by remember { mutableStateOf<List<Track>?>(null) }
    var modalListNameInput by remember { mutableStateOf("") }

    val favoriteUris = remember(favorites) { favorites.map { it.uri }.toSet() }
    val coroutineScope = rememberCoroutineScope()

    // Sincroniza sugerencia de nombre cuando cambia la carpeta local cargada
    LaunchedEffect(suggestedListName) {
        if (suggestedListName.isNotBlank() && favNameInput.isBlank()) {
            favNameInput = suggestedListName
        }
    }

    // Oculta el cartelito de modo del visualizador luego de 900ms
    LaunchedEffect(vizToastText) {
        if (vizToastText != null) {
            delay(900L)
            vizToastText = null
        }
    }

    fun cycleVisualizer() {
        visualizerStyle = (visualizerStyle + 1) % vizModes.size
        vizToastText = "MODO: ${vizModes[visualizerStyle]}"
    }

    fun driveItemsToTracks(items: List<DriveApiItem>): List<Track> =
        items.mapIndexed { i, r -> Track("${System.nanoTime()}_$i", r.title, r.streamUrl, TrackType.DRIVE_PUBLIC) }

    fun resolveDriveInput(addAfter: Boolean, saveToFavsAfter: Boolean = false) {
        val link = driveUrlInput.trim()
        if (link.isBlank()) {
            driveStatus = "Pegá un enlace de archivo o carpeta pública de Google Drive."
            driveStatusColor = Color(0xFFFF6B6B)
            return
        }
        if (isResolving) return
        isResolving = true
        driveStatus = "Consultando Google Drive…"
        driveStatusColor = WinOrange
        coroutineScope.launch {
            val list = resolveDriveLink(serverBaseUrl, link)
            resolvedTracks = list
            isResolving = false
            if (list.isEmpty()) {
                driveStatus = "⚠️ Enlace vacío, privado o sin archivos de audio."
                driveStatusColor = Color(0xFFFF6B6B)
            } else {
                val tracks = driveItemsToTracks(list)
                if (saveToFavsAfter) {
                    val autoName = favNameInput.trim().ifBlank {
                        suggestedListName.ifBlank { "Drive ${savedLists.size + 1}" }
                    }
                    onSaveList(autoName, tracks)
                    driveStatus = "★ Guardada en Favoritos como «$autoName» (${tracks.size} pistas)."
                    driveStatusColor = WinGreen
                    showFavWindow = true
                } else if (addAfter) {
                    onAddDriveTracks(tracks)
                    driveStatus = "✓ ${tracks.size} pista(s) agregada(s) a la lista de reproducción."
                    driveStatusColor = WinGreen
                } else {
                    driveStatus = "✓ ¡${list.size} pista(s) verificada(s)! Tocá + CARGAR A LISTA o ★ GUARDAR."
                    driveStatusColor = WinGreen
                }
            }
        }
    }

    fun runDriveSearch() {
        val q = searchInput.trim()
        if (q.length < 2) {
            searchStatus = "Escribí al menos 2 letras para buscar."
            searchStatusColor = Color(0xFFFF6B6B)
            return
        }
        if (isSearching) return
        isSearching = true
        folderStack = emptyList()
        searchStatus = "Buscando carpetas públicas de «$q» en Google Drive…"
        searchStatusColor = WinOrange
        coroutineScope.launch {
            val list = searchPublicDriveFolders(serverBaseUrl, q)
            searchResults = list
            if (list.isEmpty()) {
                searchStatus = "No aparecieron carpetas públicas para «$q». Probá con otro artista o género."
                searchStatusColor = WinOrange
            } else {
                searchStatus = "✓ ${list.size} carpeta(s) pública(s) encontrada(s). Tocá 📂 ABRIR para explorar o + CARGAR."
                searchStatusColor = WinGreen
            }
            isSearching = false
        }
    }

    fun openDriveSearchFolder(folder: DriveApiItem) {
        if (isSearching) return
        isSearching = true
        searchStatus = "Abriendo carpeta «${folder.title}»…"
        searchStatusColor = WinOrange
        coroutineScope.launch {
            val contents = resolveFolderTracks(serverBaseUrl, folder.streamUrl)
            isSearching = false
            if (contents.isEmpty()) {
                searchStatus = "⚠️ La carpeta «${folder.title}» está vacía o sin audios."
                searchStatusColor = Color(0xFFFF6B6B)
            } else {
                searchStatus = "📂 Dentro de «${folder.title}» (${contents.size} elementos)."
                searchStatusColor = WinGreen
                folderStack = folderStack + DriveBrowseLevel(folder.title, contents)
            }
        }
    }

    val seekFraction = if (durationMs > 0L) {
        (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(WinBgGlow, WinBg, WinBg)))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // =========================================================================
            // VENTANA 1: REPRODUCTOR PRINCIPAL (DRIVE-AMP)
            // =========================================================================
            Column(modifier = Modifier.fillMaxWidth().winampWindow()) {
                // --- Barra de título con rayas metálicas y botones funcionales de minimizar y cerrar ---
                WinampTitleBar(
                    title = "DRIVE-AMP",
                    gradientColors = listOf(Color(0xFF3C5A96), Color(0xFF1D2C52)),
                    showStripes = true,
                    rightContent = {
                        WinampTitleSquareButton("_", bgColor = Color(0xFF353C4E)) { onMinimizeClick() }
                        Spacer(modifier = Modifier.width(4.dp))
                        WinampTitleSquareButton("✕", bgColor = Color(0xFF8A2020)) { onCloseClick() }
                    }
                )

                // --- Cuerpo del reproductor ---
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 1. PANTALLA LCD RETRO
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .winampSunken(Color.Black)
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Fila superior LCD: Indicador + Reloj digital grande | Visualizador con botón ◐
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Caja izquierda: estado (■ / ▶ / ❚❚) + tiempo actual en verde brillante
                            Row(
                                modifier = Modifier.widthIn(min = 112.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = when {
                                        isPlaying -> "▶"
                                        isPaused -> "❚❚"
                                        else -> "■"
                                    },
                                    color = WinGreen,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(end = 6.dp)
                                )
                                Text(
                                    text = formatWinampTime(currentPositionMs, placeholderIfZero = false),
                                    color = WinGreen,
                                    fontSize = 30.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                            }

                            // Caja derecha: Visualizador interactivo (PUNTOS 24x5 como en la captura, BARRAS, ONDA, etc.)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(42.dp)
                                    .background(Color.Black)
                                    .border(1.dp, Color(0xFF0A2A0A))
                                    .clickable { cycleVisualizer() }
                            ) {
                                WinampVisualizerCanvas(
                                    isPlaying = isPlaying,
                                    modeIndex = visualizerStyle,
                                    modifier = Modifier.fillMaxSize()
                                )
                                // Cartelito de cambio de modo
                                if (vizToastText != null) {
                                    Text(
                                        text = vizToastText!!,
                                        color = WinGreen,
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .align(Alignment.TopStart)
                                            .padding(start = 4.dp, top = 2.dp)
                                    )
                                }
                                // Botón ◐ en la esquina inferior derecha igual a Drive-Amp-Web
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(2.dp)
                                        .size(width = 16.dp, height = 14.dp)
                                        .background(Color(0xBF001E00))
                                        .border(1.dp, Color(0xFF0F5A0F))
                                        .clickable { cycleVisualizer() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "◐",
                                        color = WinGreen,
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }

                        // Fila central LCD: Marquesina (#021002 con borde #052b05)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF021002))
                                .border(1.dp, Color(0xFF052B05))
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = currentTitle,
                                color = WinGreen,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Fila inferior LCD: Origen | Duración | Cantidad de pistas
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = currentSourceType,
                                color = WinDimGreen,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = formatWinampTime(durationMs, placeholderIfZero = true),
                                color = WinDimGreen,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "${playlist.size} pistas",
                                color = WinDimGreen,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    // 2. BARRA DE PROGRESO (SEEK) HUNDIDA CON CURSOR METÁLICO BISELADO
                    WinampBevelSlider(
                        value = seekFraction,
                        onValueChange = { frac ->
                            if (durationMs > 0L) {
                                onSeekTo((frac * durationMs).toLong())
                            }
                        },
                        trackHeight = 12.dp,
                        thumbWidth = 22.dp,
                        thumbHeight = 12.dp,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // 3. FILA DE BOTONES DE TRANSPORTE (7 botones metálicos 3D + píldoras de acción)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        WinampTransportButton("⏮", modifier = Modifier.weight(1f), onClick = onPrevClick)
                        WinampTransportButton("▶", active = isPlaying && !isPaused, modifier = Modifier.weight(1f), onClick = onPlayClick)
                        WinampTransportButton("⏸", active = isPaused, iconColor = WinOrange, modifier = Modifier.weight(1f), onClick = onPauseClick)
                        WinampTransportButton("⏹", iconColor = WinOrange, modifier = Modifier.weight(1f), onClick = onStopClick)
                        WinampTransportButton("⏭", modifier = Modifier.weight(1f), onClick = onNextClick)
                        WinampTransportButton("⏏", modifier = Modifier.weight(1f), onClick = onOpenFiles)
                        WinampTransportButton("📁", modifier = Modifier.weight(1f), onClick = onOpenFolder)
                    }

                    // 4. FILA 1 DE PÍLDORAS: ALEAT | REP | ABRIR DRIVE ✔ | BUSCAR DRIVE 🔍 | ABRIR RED 🔗
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        WinampPillButton(
                            label = "ALEAT",
                            active = isShuffle,
                            activeTextColor = WinGreen,
                            activeBorderColor = WinGreen,
                            activeBgColor = Color(0xFF142A14),
                            modifier = Modifier.weight(0.85f),
                            onClick = onToggleShuffle
                        )
                        WinampPillButton(
                            label = "REP",
                            active = isRepeat,
                            activeTextColor = WinGreen,
                            activeBorderColor = WinGreen,
                            activeBgColor = Color(0xFF142A14),
                            modifier = Modifier.weight(0.7f),
                            onClick = onToggleRepeat
                        )
                        WinampPillButton(
                            label = "ABRIR DRIVE ✔",
                            active = showDriveWindow,
                            textColor = Color(0xFF8CE99A),
                            activeTextColor = WinGreen,
                            activeBorderColor = WinGreen,
                            activeBgColor = Color(0xFF142A14),
                            modifier = Modifier.weight(1.45f),
                            onClick = { showDriveWindow = !showDriveWindow }
                        )
                        WinampPillButton(
                            label = "BUSCAR DRIVE 🔍",
                            active = showSearchWindow,
                            textColor = Color(0xFF72A9FF),
                            activeTextColor = Color(0xFF72A9FF),
                            activeBorderColor = Color(0xFF72A9FF),
                            activeBgColor = Color(0xFF19273C),
                            modifier = Modifier.weight(1.55f),
                            onClick = { showSearchWindow = !showSearchWindow }
                        )
                        WinampPillButton(
                            label = "ABRIR RED 🔗",
                            active = showRedWindow,
                            textColor = Color(0xFFD0BFFF),
                            activeTextColor = Color(0xFFC08BFF),
                            activeBorderColor = Color(0xFFC08BFF),
                            activeBgColor = Color(0xFF28193C),
                            modifier = Modifier.weight(1.25f),
                            onClick = { showRedWindow = !showRedWindow }
                        )
                    }

                    // 5. FILA 2 DE PÍLDORAS: ECUALIZADOR 🎚 (a la izquierda) + FAVORITOS ★ + ESCANEAR 📂★
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        WinampPillButton(
                            label = if (eqEnabled) "ECUALIZADOR 🎚 ($eqPresetName)" else "ECUALIZADOR 🎚 (OFF)",
                            active = showEqWindow,
                            textColor = if (eqEnabled) WinGreen else WinTextMuted,
                            activeTextColor = WinGreen,
                            activeBorderColor = WinGreen,
                            activeBgColor = Color(0xFF142A14),
                            modifier = Modifier.weight(1.15f),
                            onClick = { showEqWindow = !showEqWindow }
                        )
                        val favCount = savedLists.size + favorites.size
                        WinampPillButton(
                            label = if (favCount > 0) "FAVORITOS ★ ($favCount)" else "FAVORITOS ★",
                            active = showFavWindow,
                            textColor = Color(0xFFFFD24A),
                            activeTextColor = Color(0xFFFFD24A),
                            activeBorderColor = Color(0xFFFFD24A),
                            activeBgColor = Color(0xFF3A2E10),
                            modifier = Modifier.weight(1f),
                            onClick = { showFavWindow = !showFavWindow }
                        )
                        WinampPillButton(
                            label = "ESCANEAR 📂★",
                            active = false,
                            textColor = Color(0xFFFFD24A),
                            activeTextColor = Color(0xFFFFD24A),
                            activeBorderColor = Color(0xFFFFD24A),
                            activeBgColor = Color(0xFF3A2E10),
                            modifier = Modifier.weight(0.95f),
                            onClick = onScanFoldersToFavorites
                        )
                    }

                    // 6. FILA DE VOLUMEN (VOL + barra negra hundida con cursor metálico + %)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "VOL",
                            color = WinTextMuted,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        WinampBevelSlider(
                            value = volume.coerceIn(0f, 1f),
                            onValueChange = onVolumeChange,
                            trackHeight = 8.dp,
                            thumbWidth = 14.dp,
                            thumbHeight = 12.dp,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${(volume * 100).roundToInt()}%",
                            color = WinTextMuted,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(34.dp)
                        )
                    }
                }
            }

            // =========================================================================
            // VENTANA ACOPLADA: 🎚 ECUALIZADOR GRÁFICO (8 BANDAS + PRESETS)
            // =========================================================================
            if (showEqWindow) {
                Column(modifier = Modifier.fillMaxWidth().winampWindow()) {
                    WinampTitleBar(
                        title = "🎚 ECUALIZADOR GRÁFICO",
                        gradientColors = listOf(Color(0xFF3C5A96), Color(0xFF1D2C52)),
                        showStripes = true,
                        rightContent = {
                            WinampTitleSquareButton("-") { showEqWindow = false }
                        }
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Fila superior del EQ: Botón ON/OFF + Curva de respuesta LCD + Botón PLANO (0 dB)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            WinampSmallBevelButton(
                                label = if (eqEnabled) "● ON" else "○ OFF",
                                accentGreen = eqEnabled,
                                onClick = {
                                    eqEnabled = !eqEnabled
                                    GraphicEqualizerManager.setEnabled(context, eqEnabled)
                                }
                            )
                            // Mini pantalla LCD con la curva del ecualizador estilo Winamp
                            WinampEqCurveDisplay(
                                gainsDb = eqGains,
                                enabled = eqEnabled,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(26.dp)
                            )
                            Text(
                                text = eqPresetName,
                                color = if (eqEnabled) WinGreen else WinTextMuted,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                            WinampSmallBevelButton(
                                label = "0 dB",
                                onClick = {
                                    val flat = GraphicEqualizerManager.PRESETS.first()
                                    GraphicEqualizerManager.applyPreset(context, flat)
                                    eqEnabled = GraphicEqualizerManager.isEnabled
                                    eqPresetName = GraphicEqualizerManager.currentPresetName
                                    eqGains = GraphicEqualizerManager.getGainsSnapshot()
                                }
                            )
                        }

                        // Las 8 bandas gráficas verticales (60, 170, 310, 600, 1K, 3K, 6K, 14K Hz)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .winampSunken(Color(0xFF0A0D12))
                                .padding(horizontal = 6.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Escala lateral dB (+12 / 0 / -12)
                            Column(
                                modifier = Modifier.height(126.dp).padding(end = 2.dp),
                                verticalArrangement = Arrangement.SpaceBetween,
                                horizontalAlignment = Alignment.End
                            ) {
                                Text("+12", color = WinOrange, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                                Text(" 0dB", color = WinDimGreen, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                                Text("-12", color = WinTextMuted, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                            }

                            for (bandIdx in 0 until GraphicEqualizerManager.BAND_COUNT) {
                                val gain = eqGains.getOrElse(bandIdx) { 0f }
                                val freqLabel = GraphicEqualizerManager.BAND_LABELS[bandIdx]
                                WinampVerticalEqSlider(
                                    gainDb = gain,
                                    freqLabel = freqLabel,
                                    enabled = eqEnabled,
                                    modifier = Modifier.weight(1f),
                                    onGainChange = { newDb ->
                                        GraphicEqualizerManager.setBandGain(context, bandIdx, newDb)
                                        eqPresetName = GraphicEqualizerManager.currentPresetName
                                        eqGains = GraphicEqualizerManager.getGainsSnapshot()
                                    }
                                )
                            }
                        }

                        // Listado inferior de preestablecidos (Rock, Pop, Bass+, Jazz, Clásica, Electro, Plano)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "PREESTABLECIDOS:",
                                color = WinTextMuted,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                GraphicEqualizerManager.PRESETS.forEach { preset ->
                                    val isSelected = eqEnabled && eqPresetName == preset.name
                                    WinampPillButton(
                                        label = preset.name,
                                        active = isSelected,
                                        textColor = WinText,
                                        activeTextColor = WinGreen,
                                        activeBorderColor = WinGreen,
                                        activeBgColor = Color(0xFF142A14),
                                        modifier = Modifier.weight(1f),
                                        onClick = {
                                            GraphicEqualizerManager.applyPreset(context, preset)
                                            eqEnabled = GraphicEqualizerManager.isEnabled
                                            eqPresetName = GraphicEqualizerManager.currentPresetName
                                            eqGains = GraphicEqualizerManager.getGainsSnapshot()
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // =========================================================================
            // VENTANA ACOPLADA: ✔ AGREGAR ENLACE DE GOOGLE DRIVE (ARCHIVO O CARPETA)
            // =========================================================================
            if (showDriveWindow) {
                Column(modifier = Modifier.fillMaxWidth().winampWindow()) {
                    WinampTitleBar(
                        title = "✔ AGREGAR ENLACE DE GOOGLE DRIVE (ARCHIVO O CARPETA)",
                        gradientColors = listOf(Color(0xFF1B5E20), Color(0xFF0D3813)),
                        showStripes = true,
                        rightContent = {
                            WinampTitleSquareButton("-") { showDriveWindow = false }
                        }
                    )
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        WinampSunkenTextField(
                            value = driveUrlInput,
                            onValueChange = {
                                driveUrlInput = it
                                resolvedTracks = null
                            },
                            placeholder = "Pegá enlace de ARCHIVO o CARPETA pública de Google Drive...",
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = driveStatus,
                                color = driveStatusColor,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val resolvedCount = resolvedTracks?.size ?: 0
                            WinampSmallBevelButton(
                                label = if (resolvedCount > 0) "+ CARGAR A LISTA ($resolvedCount)" else "+ CARGAR A LISTA",
                                accentGreen = true,
                                enabled = !isResolving,
                                onClick = {
                                    val already = resolvedTracks
                                    if (!already.isNullOrEmpty()) {
                                        onAddDriveTracks(driveItemsToTracks(already))
                                        driveStatus = "✓ ${already.size} pista(s) agregada(s) a la lista."
                                        driveStatusColor = WinGreen
                                    } else {
                                        resolveDriveInput(addAfter = true)
                                    }
                                }
                            )
                            WinampSmallBevelButton(
                                label = "VERIFICAR",
                                enabled = !isResolving,
                                onClick = { resolveDriveInput(addAfter = false) }
                            )
                            WinampSmallBevelButton(
                                label = "★ GUARDAR",
                                accentGold = true,
                                enabled = !isResolving,
                                onClick = {
                                    val already = resolvedTracks
                                    if (!already.isNullOrEmpty()) {
                                        pendingSaveTracks = driveItemsToTracks(already)
                                        modalListNameInput = favNameInput.ifBlank { "Drive ${savedLists.size + 1}" }
                                    } else {
                                        resolveDriveInput(addAfter = false, saveToFavsAfter = true)
                                    }
                                }
                            )
                            WinampSmallBevelButton(
                                label = "LIMPIAR",
                                onClick = {
                                    driveUrlInput = ""
                                    resolvedTracks = null
                                    driveStatus = "Pegá el enlace compartido en modo «Cualquier persona con el enlace» y tocá CARGAR."
                                    driveStatusColor = Color(0xFF8CE99A)
                                }
                            )
                        }
                        if (!resolvedTracks.isNullOrEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 120.dp)
                                    .winampSunken(Color.Black)
                                    .padding(4.dp)
                            ) {
                                LazyColumn {
                                    itemsIndexed(resolvedTracks!!) { idx, item ->
                                        Text(
                                            text = "${idx + 1}. ${item.title}",
                                            color = WinGreen,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // =========================================================================
            // VENTANA ACOPLADA: 🔍 BUSCAR CARPETAS PÚBLICAS DE DRIVE (COMO EN LA CAPTURA)
            // =========================================================================
            if (showSearchWindow) {
                Column(modifier = Modifier.fillMaxWidth().winampWindow()) {
                    WinampTitleBar(
                        title = "🔍 BUSCAR CARPETAS PÚBLICAS DE DRIVE",
                        gradientColors = listOf(Color(0xFF2B4570), Color(0xFF14223B)),
                        showStripes = true,
                        rightContent = {
                            WinampTitleSquareButton("-") { showSearchWindow = false }
                        }
                    )
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Fila de buscador: campo negro hundido con texto verde + botón azul [ BUSCAR ]
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            WinampSunkenTextField(
                                value = searchInput,
                                onValueChange = { searchInput = it },
                                placeholder = "Buscar carpetas públicas de Drive (artista, género, tema)...",
                                modifier = Modifier.weight(1f)
                            )
                            WinampSmallBevelButton(
                                label = if (isSearching) "BUSCANDO…" else "BUSCAR",
                                accentBlue = true,
                                enabled = !isSearching,
                                onClick = { runDriveSearch() }
                            )
                        }

                        // Estado de búsqueda en azul claro (#72a9ff) igual a la captura
                        Text(
                            text = searchStatus,
                            color = searchStatusColor,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )

                        // Si estamos navegando dentro de una carpeta, mostramos barra de navegación (⬅ Volver / ★ Guardar / + Cargar carpeta)
                        if (folderStack.isNotEmpty()) {
                            val currentLevel = folderStack.last()
                            val currentAudioItems = currentLevel.items.filter { !it.isFolder }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF141B29))
                                    .border(1.dp, Color(0xFF395580))
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                WinampSmallBevelButton(
                                    label = "⬅ VOLVER",
                                    onClick = {
                                        folderStack = folderStack.dropLast(1)
                                        searchStatus = if (folderStack.isEmpty()) {
                                            "✓ ${searchResults.size} resultado(s) de búsqueda."
                                        } else {
                                            "📂 Dentro de «${folderStack.last().title}»."
                                        }
                                        searchStatusColor = WinGreen
                                    }
                                )
                                Text(
                                    text = "📂 ${currentLevel.title} (${currentLevel.items.size})",
                                    color = Color(0xFFFFD24A),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (currentAudioItems.isNotEmpty()) {
                                    WinampSmallBevelButton(
                                        label = "★ GUARDAR",
                                        accentGold = true,
                                        onClick = {
                                            pendingSaveTracks = driveItemsToTracks(currentAudioItems)
                                            modalListNameInput = currentLevel.title
                                        }
                                    )
                                    WinampSmallBevelButton(
                                        label = "+ CARGAR (${currentAudioItems.size})",
                                        accentGreen = true,
                                        onClick = {
                                            onAddDriveTracks(driveItemsToTracks(currentAudioItems))
                                            searchStatus = "✓ Se cargaron ${currentAudioItems.size} pistas de «${currentLevel.title}»."
                                            searchStatusColor = WinGreen
                                        }
                                    )
                                }
                            }
                        }

                        val displayedItems = if (folderStack.isNotEmpty()) folderStack.last().items else searchResults
                        if (displayedItems.isNotEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 190.dp)
                                    .winampSunken(Color.Black)
                                    .padding(2.dp)
                            ) {
                                LazyColumn {
                                    itemsIndexed(displayedItems) { idx, item ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(if (idx % 2 == 0) Color.Black else Color(0xFF050A05))
                                                .clickable {
                                                    if (item.isFolder) {
                                                        openDriveSearchFolder(item)
                                                    } else {
                                                        onPlayFavorite(
                                                            Track(
                                                                "${System.nanoTime()}",
                                                                item.title,
                                                                item.streamUrl,
                                                                TrackType.DRIVE_PUBLIC
                                                            )
                                                        )
                                                        searchStatus = "▶ Reproduciendo «${item.title}»"
                                                        searchStatusColor = WinGreen
                                                    }
                                                }
                                                .padding(horizontal = 6.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(
                                                text = if (item.isFolder) "📁 ${item.title}" else "${idx + 1}. 🎵 ${item.title}",
                                                color = if (item.isFolder) WinGreen else Color(0xFFD7DFEC),
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            if (item.isFolder) {
                                                WinampSmallBevelButton(
                                                    label = "📂 ABRIR",
                                                    accentBlue = true,
                                                    onClick = { openDriveSearchFolder(item) }
                                                )
                                                WinampSmallBevelButton(
                                                    label = "+ CARGAR",
                                                    accentGreen = true,
                                                    onClick = {
                                                        if (!isSearching) {
                                                            isSearching = true
                                                            searchStatus = "Cargando «${item.title}»…"
                                                            searchStatusColor = WinOrange
                                                            coroutineScope.launch {
                                                                val tracks = resolveDriveLink(serverBaseUrl, item.streamUrl)
                                                                    .filter { !it.isFolder }
                                                                isSearching = false
                                                                if (tracks.isEmpty()) {
                                                                    searchStatus = "⚠️ «${item.title}» no tiene audios directos; tocá 📂 ABRIR para explorar subcarpetas."
                                                                    searchStatusColor = WinOrange
                                                                } else {
                                                                    onAddDriveTracks(driveItemsToTracks(tracks))
                                                                    searchStatus = "✓ ${tracks.size} pistas de «${item.title}» cargadas a la lista."
                                                                    searchStatusColor = WinGreen
                                                                }
                                                            }
                                                        }
                                                    }
                                                )
                                                WinampSmallBevelButton(
                                                    label = "★",
                                                    accentGold = true,
                                                    onClick = {
                                                        if (!isSearching) {
                                                            isSearching = true
                                                            searchStatus = "Guardando «${item.title}» en Favoritos…"
                                                            searchStatusColor = WinOrange
                                                            coroutineScope.launch {
                                                                val tracks = resolveDriveLink(serverBaseUrl, item.streamUrl)
                                                                    .filter { !it.isFolder }
                                                                isSearching = false
                                                                if (tracks.isEmpty()) {
                                                                    searchStatus = "⚠️ «${item.title}» no tiene audios directos; abrila primero con 📂 ABRIR."
                                                                    searchStatusColor = WinOrange
                                                                } else {
                                                                    onSaveList(item.title, driveItemsToTracks(tracks))
                                                                    searchStatus = "★ Carpeta «${item.title}» (${tracks.size} pistas) guardada en Favoritos."
                                                                    searchStatusColor = WinGreen
                                                                }
                                                            }
                                                        }
                                                    }
                                                )
                                            } else {
                                                val singleTrack = Track(
                                                    id = "drv_${item.streamUrl.hashCode()}",
                                                    title = item.title,
                                                    uri = item.streamUrl,
                                                    type = TrackType.DRIVE_PUBLIC
                                                )
                                                val isFav = item.streamUrl in favoriteUris
                                                WinampSmallBevelButton(
                                                    label = "▶",
                                                    accentBlue = true,
                                                    onClick = {
                                                        onPlayFavorite(singleTrack)
                                                        searchStatus = "▶ Reproduciendo «${item.title}»"
                                                        searchStatusColor = WinGreen
                                                    }
                                                )
                                                WinampSmallBevelButton(
                                                    label = if (isFav) "★" else "☆",
                                                    accentGold = isFav,
                                                    onClick = { onToggleFavorite(singleTrack) }
                                                )
                                                WinampSmallBevelButton(
                                                    label = "+ LISTA",
                                                    accentGreen = true,
                                                    onClick = {
                                                        onAddDriveTracks(listOf(singleTrack))
                                                        searchStatus = "✓ «${item.title}» agregada a la lista."
                                                        searchStatusColor = WinGreen
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // =========================================================================
            // VENTANA ACOPLADA: 🔗 ENLACES DIRECTOS (NAS, SERVIDORES, RADIOS)
            // =========================================================================
            if (showRedWindow) {
                Column(modifier = Modifier.fillMaxWidth().winampWindow()) {
                    WinampTitleBar(
                        title = "🔗 ENLACES DIRECTOS (NAS, SERVIDORES, RADIOS)",
                        gradientColors = listOf(Color(0xFF4A2B6B), Color(0xFF24113B)),
                        showStripes = true,
                        rightContent = {
                            WinampTitleSquareButton("-") { showRedWindow = false }
                        }
                    )
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        WinampSunkenTextField(
                            value = redUrlInput,
                            onValueChange = { redUrlInput = it },
                            placeholder = "https://servidor.com/cancion.mp3",
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = redStatus,
                                color = redStatusColor,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f)
                            )
                            WinampSmallBevelButton(
                                label = "+ CARGAR A LISTA",
                                accentGreen = true,
                                onClick = {
                                    val clean = redUrlInput.trim()
                                    if (clean.isBlank()) {
                                        redStatus = "Pegá un enlace directo HTTP/HTTPS de audio."
                                        redStatusColor = Color(0xFFFF6B6B)
                                    } else {
                                        onAddHttpUrl(clean)
                                        redUrlInput = ""
                                        redStatus = "✓ Enlace agregado a la lista de reproducción."
                                        redStatusColor = WinGreen
                                    }
                                }
                            )
                            WinampSmallBevelButton(
                                label = "LIMPIAR",
                                onClick = {
                                    redUrlInput = ""
                                    redStatus = "Para archivos MP3/FLAC/OGG en tu servidor, NAS o radios online con enlace directo."
                                    redStatusColor = Color(0xFFD0BFFF)
                                }
                            )
                        }
                    }
                }
            }

            // =========================================================================
            // VENTANA ACOPLADA: ★ LISTAS FAVORITAS (PERMANENTES EN EL DISPOSITIVO)
            // =========================================================================
            if (showFavWindow) {
                Column(modifier = Modifier.fillMaxWidth().winampWindow()) {
                    WinampTitleBar(
                        title = "★ LISTAS FAVORITAS",
                        gradientColors = listOf(Color(0xFF7A5A10), Color(0xFF3D2C05)),
                        showStripes = true,
                        rightContent = {
                            WinampTitleSquareButton("-") { showFavWindow = false }
                        }
                    )
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Fila para guardar la playlist actual con nombre (locales + enlaces Drive/Red)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            WinampSunkenTextField(
                                value = favNameInput,
                                onValueChange = { favNameInput = it },
                                placeholder = "Nombre para la lista actual (ej. Rock 80s)",
                                modifier = Modifier.weight(1f)
                            )
                            WinampSmallBevelButton(
                                label = "★ GUARDAR",
                                accentGold = true,
                                onClick = {
                                    if (playlist.isEmpty()) {
                                        favStatus = "⚠️ La lista de reproducción actual está vacía. Cargá temas primero."
                                        favStatusColor = Color(0xFFFF6B6B)
                                    } else {
                                        val name = favNameInput.trim().ifBlank {
                                            suggestedListName.ifBlank { "Lista ${savedLists.size + 1}" }
                                        }
                                        onSaveList(name, playlist)
                                        favNameInput = ""
                                        favStatus = "✓ Lista «$name» (${playlist.size} pistas) guardada permanentemente en el dispositivo."
                                        favStatusColor = WinGreen
                                    }
                                }
                            )
                        }

                        Text(
                            text = favStatus,
                            color = favStatusColor,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )

                        // Caja negra hundida con las listas guardadas y las pistas favoritas individuales
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .winampSunken(Color.Black)
                                .padding(4.dp)
                        ) {
                            if (savedLists.isEmpty() && favorites.isEmpty()) {
                                Text(
                                    text = "— No tenés listas favoritas guardadas —",
                                    color = WinDimGreen,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp)
                                )
                            } else {
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    if (savedLists.isNotEmpty()) {
                                        item {
                                            Text(
                                                text = "LISTAS GUARDADAS (${savedLists.size}) — al terminar una continúa la siguiente:",
                                                color = Color(0xFFFFD24A),
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                            )
                                        }
                                        itemsIndexed(savedLists) { _, saved ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(Color(0xFF060C06))
                                                    .clickable { onLoadList(saved, true) }
                                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Text(
                                                    text = "★ ${saved.name}",
                                                    color = Color(0xFFFFD24A),
                                                    fontSize = 11.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Text(
                                                    text = "${saved.tracks.size} pistas · ${trackTypesSummary(saved.tracks)}",
                                                    color = WinDimGreen,
                                                    fontSize = 9.sp,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                                WinampSmallBevelButton(
                                                    label = "▶ CARGAR",
                                                    accentGreen = true,
                                                    onClick = { onLoadList(saved, true) }
                                                )
                                                WinampSmallBevelButton(
                                                    label = "+ AGREGAR",
                                                    onClick = { onLoadList(saved, false) }
                                                )
                                                WinampSmallBevelButton(
                                                    label = "✕",
                                                    onClick = { onDeleteList(saved) }
                                                )
                                            }
                                        }
                                    }

                                    if (favorites.isNotEmpty()) {
                                        item {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 4.dp, vertical = 4.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "PISTAS FAVORITAS SUELTAS (${favorites.size}):",
                                                    color = WinGreen,
                                                    fontSize = 10.sp,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                                WinampSmallBevelButton(
                                                    label = "+ CARGAR TODAS",
                                                    accentGreen = true,
                                                    onClick = onAddAllFavorites
                                                )
                                            }
                                        }
                                        itemsIndexed(favorites) { _, fav ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(Color(0xFF040804))
                                                    .clickable { onPlayFavorite(fav) }
                                                    .padding(horizontal = 6.dp, vertical = 3.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Text(
                                                    text = "★ ${fav.title}",
                                                    color = WinGreen,
                                                    fontSize = 11.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                WinampSmallBevelButton(
                                                    label = "▶",
                                                    accentGreen = true,
                                                    onClick = { onPlayFavorite(fav) }
                                                )
                                                WinampSmallBevelButton(
                                                    label = "✕",
                                                    onClick = { onRemoveFavorite(fav) }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // =========================================================================
            // VENTANA 2: LISTA DE REPRODUCCIÓN (PLAYLIST WINDOW DE DRIVE-AMP WEB)
            // =========================================================================
            Column(modifier = Modifier.fillMaxWidth().winampWindow()) {
                WinampTitleBar(
                    title = "LISTA DE REPRODUCCIÓN",
                    gradientColors = listOf(Color(0xFF3C5A96), Color(0xFF1D2C52)),
                    showStripes = true,
                    rightContent = {
                        WinampTitleSquareButton("-") { playlistCollapsed = !playlistCollapsed }
                    }
                )

                if (!playlistCollapsed) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Caja negra hundida de pistas
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 190.dp, max = 280.dp)
                                .winampSunken(Color.Black)
                                .padding(3.dp)
                        ) {
                            if (playlist.isEmpty()) {
                                Text(
                                    text = "— La lista está vacía —",
                                    color = WinDimGreen,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 22.dp)
                                )
                            } else {
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    itemsIndexed(playlist) { index, track ->
                                        val isCurrent = index == currentIndex
                                        val isFav = track.uri in favoriteUris
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(if (isCurrent) Color(0xFF0B3A7A) else Color.Transparent)
                                                .clickable { onTrackSelected(index) }
                                                .padding(horizontal = 6.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(
                                                text = "${index + 1}.",
                                                color = if (isCurrent) Color.White else WinDimGreen,
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                textAlign = TextAlign.End,
                                                modifier = Modifier.width(24.dp)
                                            )
                                            // Etiqueta [DRIVE] o [RED] igual que en Drive-Amp-Web
                                            if (track.type == TrackType.DRIVE_PUBLIC) {
                                                Text(
                                                    text = "[DRIVE]",
                                                    color = WinGreen,
                                                    fontSize = 9.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    modifier = Modifier
                                                        .background(Color(0xFF0D2E16))
                                                        .border(1.dp, Color(0xFF1F6F36))
                                                        .padding(horizontal = 3.dp, vertical = 1.dp)
                                                )
                                            } else if (track.type == TrackType.HTTP) {
                                                Text(
                                                    text = "[RED]",
                                                    color = Color(0xFFC08BFF),
                                                    fontSize = 9.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    modifier = Modifier
                                                        .background(Color(0xFF231338))
                                                        .border(1.dp, Color(0xFF5A348A))
                                                        .padding(horizontal = 3.dp, vertical = 1.dp)
                                                )
                                            }
                                            Text(
                                                text = track.title,
                                                color = if (isCurrent) Color.White else WinGreen,
                                                fontSize = 12.sp,
                                                fontFamily = FontFamily.Monospace,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            // Estrella de favorito individual
                                            Text(
                                                text = if (isFav) "★" else "☆",
                                                color = if (isFav) Color(0xFFFFD24A) else WinDimGreen,
                                                fontSize = 13.sp,
                                                fontFamily = FontFamily.Monospace,
                                                modifier = Modifier
                                                    .clickable { onToggleFavorite(track) }
                                                    .padding(horizontal = 4.dp)
                                            )
                                            // Quitar pista de la lista actual
                                            Text(
                                                text = "✕",
                                                color = if (isCurrent) Color.White else Color(0xFFAA4444),
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                modifier = Modifier
                                                    .clickable { onRemoveTrack(index) }
                                                    .padding(horizontal = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Pie de Lista de Reproducción (.foot):
                        // [+ ARCHIVOS] [+ CARPETA] [★ GUARDAR] [VACIAR]   |  [0:00/--:--]
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                WinampSmallBevelButton(
                                    label = "+ ARCHIVOS",
                                    onClick = onOpenFiles
                                )
                                WinampSmallBevelButton(
                                    label = "+ CARPETA",
                                    onClick = onOpenFolder
                                )
                                WinampSmallBevelButton(
                                    label = "★ GUARDAR",
                                    accentGold = true,
                                    onClick = {
                                        if (playlist.isNotEmpty()) {
                                            pendingSaveTracks = playlist
                                            modalListNameInput = suggestedListName.ifBlank { "Lista ${savedLists.size + 1}" }
                                        } else {
                                            showFavWindow = true
                                        }
                                    }
                                )
                                // VACIAR: actúa ÚNICAMENTE sobre la lista de reproducción actual
                                WinampSmallBevelButton(
                                    label = "VACIAR",
                                    enabled = playlist.isNotEmpty(),
                                    onClick = onClearPlaylist
                                )
                            }

                            // Caja negra hundida de tiempo 0:00/--:-- igual a la captura
                            Box(
                                modifier = Modifier
                                    .winampSunken(Color.Black)
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                            ) {
                                val curStr = formatWinampTime(currentPositionMs, placeholderIfZero = false)
                                val durStr = formatWinampTime(durationMs, placeholderIfZero = true)
                                Text(
                                    text = "$curStr/$durStr",
                                    color = WinGreen,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }

            // =========================================================================
            // LEYENDA INFERIOR (.hint) IDÉNTICA A LA CAPTURA Y DRIVE-AMP-WEB
            // =========================================================================
            Text(
                text = "Reproduce archivos locales y música de Google Drive (archivos y carpetas públicas). Arrastrá archivos a la ventana, pegá enlaces de Drive o buscá carpetas arriba.",
                color = WinTextMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.SansSerif,
                textAlign = TextAlign.Center,
                lineHeight = 15.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }

    // Diálogo rápido para confirmar nombre al tocar "★ GUARDAR" desde la playlist o una carpeta explorada
    if (pendingSaveTracks != null) {
        val toSave = pendingSaveTracks!!
        AlertDialog(
            onDismissRequest = { pendingSaveTracks = null },
            containerColor = WinChrome,
            title = {
                Text(
                    text = "★ GUARDAR EN LISTAS FAVORITAS",
                    color = Color(0xFFFFD24A),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Se guardarán ${toSave.size} pistas (${trackTypesSummary(toSave)}) de forma permanente en el dispositivo.",
                        color = WinText,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    WinampSunkenTextField(
                        value = modalListNameInput,
                        onValueChange = { modalListNameInput = it },
                        placeholder = "Nombre de la lista (ej. Rock 80s)",
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                WinampSmallBevelButton(
                    label = "★ GUARDAR LISTA",
                    accentGold = true,
                    onClick = {
                        val name = modalListNameInput.trim().ifBlank { "Lista ${savedLists.size + 1}" }
                        onSaveList(name, toSave)
                        favStatus = "✓ Lista «$name» (${toSave.size} pistas) guardada en Favoritos."
                        favStatusColor = WinGreen
                        pendingSaveTracks = null
                        showFavWindow = true
                    }
                )
            },
            dismissButton = {
                WinampSmallBevelButton(
                    label = "CANCELAR",
                    onClick = { pendingSaveTracks = null }
                )
            }
        )
    }
}

// ============================================================================
// COMPONENTES VISUALES WINAMP (BARRA DE TÍTULO, VISUALIZADOR 6 MODOS, CONTROLES)
// ============================================================================

@Composable
fun WinampTitleBar(
    title: String,
    gradientColors: List<Color>,
    showStripes: Boolean = true,
    rightContent: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.horizontalGradient(gradientColors))
            .drawBehind {
                drawLine(
                    color = Color(0xFF0D1017),
                    start = Offset(0f, size.height - 1.dp.toPx()),
                    end = Offset(size.width, size.height - 1.dp.toPx()),
                    strokeWidth = 1.dp.toPx()
                )
            }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showStripes) {
            WinampGripStripes(modifier = Modifier.weight(1f).height(8.dp))
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = title,
            color = Color.White,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (showStripes) {
            Spacer(modifier = Modifier.width(6.dp))
            WinampGripStripes(modifier = Modifier.weight(1f).height(8.dp))
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.width(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, content = rightContent)
    }
}

@Composable
fun WinampGripStripes(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val step = 2.dp.toPx()
        var y = 0f
        var light = true
        while (y < size.height) {
            drawLine(
                color = if (light) WinBevelLight.copy(alpha = 0.75f) else Color.Black.copy(alpha = 0.75f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.dp.toPx()
            )
            y += step
            light = !light
        }
    }
}

@Composable
fun WinampTitleSquareButton(
    symbol: String,
    bgColor: Color = Color(0xFF353C4E),
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(16.dp)
            .background(bgColor)
            .winampRaisedBevel()
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = symbol,
            color = Color(0xFFD7DFEC),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun WinampVisualizerCanvas(
    isPlaying: Boolean,
    modeIndex: Int,
    modifier: Modifier = Modifier
) {
    val numBands = 24
    val bands = remember { FloatArray(numBands) }
    val peaks = remember { FloatArray(numBands) }
    val waveSamples = remember { FloatArray(64) }
    var bassPulse by remember { mutableStateOf(0f) }
    var frameTrigger by remember { mutableStateOf(0L) }

    LaunchedEffect(isPlaying, modeIndex) {
        while (true) {
            val hasLivePcm = isPlaying &&
                (SystemClock.elapsedRealtime() - AudioSpectrumAnalyzer.lastFrameTimestampMs) < 350L

            if (hasLivePcm) {
                // Lee el espectro FFT real y la onda PCM real decodificada por ExoPlayer
                val rawBass = AudioSpectrumAnalyzer.liveBassPulse
                bassPulse = if (rawBass > bassPulse) {
                    bassPulse * 0.25f + rawBass * 0.75f
                } else {
                    (bassPulse - 0.08f).coerceAtLeast(0.02f)
                }

                for (i in 0 until numBands) {
                    val target = AudioSpectrumAnalyzer.liveBands[i].coerceIn(0f, 1f)
                    val current = bands[i]
                    // Subida instantánea con el golpe real de la canción y caída con gravedad Winamp
                    val nextVal = if (target > current) {
                        current * 0.22f + target * 0.78f
                    } else {
                        (current - 0.095f).coerceAtLeast(target)
                    }
                    bands[i] = nextVal.coerceIn(0f, 1f)

                    peaks[i] = if (bands[i] >= peaks[i]) {
                        bands[i]
                    } else {
                        (peaks[i] - 0.022f).coerceAtLeast(0f)
                    }
                }

                val nSamples = waveSamples.size
                for (s in 0 until nSamples) {
                    val targetW = AudioSpectrumAnalyzer.liveWave[s]
                    waveSamples[s] = (waveSamples[s] * 0.25f + targetW * 0.75f).coerceIn(-0.98f, 0.98f)
                }
            } else {
                // En pausa/stop o silencio cae suavemente por gravedad hasta el reposo
                var anyActive = false
                for (i in 0 until numBands) {
                    if (bands[i] > 0.01f || peaks[i] > 0.01f) {
                        bands[i] = (bands[i] - 0.08f).coerceAtLeast(0f)
                        peaks[i] = (peaks[i] - 0.04f).coerceAtLeast(0f)
                        anyActive = true
                    }
                }
                for (s in waveSamples.indices) {
                    waveSamples[s] *= 0.6f
                }
                bassPulse = (bassPulse - 0.08f).coerceAtLeast(0f)
                if (!anyActive) {
                    frameTrigger++
                    delay(80L)
                    continue
                }
            }
            frameTrigger++
            delay(28L)
        }
    }

    Canvas(modifier = modifier) {
        // Lee frameTrigger para redibujar en cada pulso rítmico
        val unusedTick = frameTrigger
        if (unusedTick < 0L) return@Canvas

        val w = size.width
        val h = size.height

        // Cuadrícula de fondo sutil estilo LCD
        val stepX = 4.dp.toPx()
        val stepY = 4.dp.toPx()
        var gx = 0f
        while (gx < w) {
            drawLine(Color(0xFF001400), Offset(gx, 0f), Offset(gx, h), strokeWidth = 1f)
            gx += stepX
        }
        var gy = 0f
        while (gy < h) {
            drawLine(Color(0xFF001400), Offset(0f, gy), Offset(w, gy), strokeWidth = 1f)
            gy += stepY
        }

        when (modeIndex) {
            0 -> {
                // MODO 0: PUNTOS (Matriz LED 24x5 rítmica por banda + punto de pico naranja que cae)
                val cols = 24
                val rows = 5
                val cellW = w / cols
                val cellH = h / rows
                val dotR = min(cellW, cellH) * 0.30f
                for (c in 0 until cols) {
                    val activeRows = if (isPlaying || bands[c] > 0.02f) {
                        (bands[c] * rows).roundToInt().coerceIn(if (isPlaying) 1 else 0, rows)
                    } else 0
                    val peakRowFromBottom = if (peaks[c] > 0.15f) {
                        (peaks[c] * (rows - 1)).roundToInt().coerceIn(0, rows - 1)
                    } else -1

                    for (r in 0 until rows) {
                        val rowFromBottom = rows - 1 - r
                        val isLit = rowFromBottom < activeRows
                        val isPeakDot = rowFromBottom == peakRowFromBottom && peakRowFromBottom >= activeRows
                        val dotColor = when {
                            !isPlaying && activeRows == 0 && r == rows - 1 -> WinOrange.copy(alpha = 0.85f)
                            isPeakDot -> WinOrange
                            !isLit -> Color(0xFF003800)
                            rowFromBottom >= rows - 1 -> Color(0xFFFF3B30)
                            rowFromBottom == rows - 2 -> WinOrange
                            else -> WinGreen
                        }
                        drawCircle(
                            color = dotColor,
                            radius = dotR,
                            center = Offset((c + 0.5f) * cellW, (r + 0.5f) * cellH)
                        )
                    }
                }
            }
            1 -> {
                // MODO 1: BARRAS (19 barras rítmicas de espectro + picos naranjas con caída por gravedad)
                val n = 19
                val gap = 2.dp.toPx()
                val bw = (w - gap * (n - 1)) / n
                for (i in 0 until n) {
                    val idx = (i * numBands / n).coerceIn(0, numBands - 1)
                    val v = if (isPlaying || bands[idx] > 0.02f) bands[idx].coerceIn(0.05f, 0.98f) else 0.05f
                    val bh = v * (h - 4.dp.toPx())
                    val x = i * (bw + gap)
                    val y = h - bh
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color(0xFFFF3B30), WinOrange, WinGreen),
                            startY = 0f,
                            endY = h
                        ),
                        topLeft = Offset(x, y),
                        size = Size(bw, bh)
                    )
                    // Pico naranja retenido que cae con gravedad
                    val pk = peaks[idx].coerceAtLeast(v)
                    val py = (h - pk * (h - 4.dp.toPx()) - 2.dp.toPx()).coerceIn(0f, h - 2.dp.toPx())
                    drawRect(
                        color = WinOrange,
                        topLeft = Offset(x, py),
                        size = Size(bw, 2.dp.toPx())
                    )
                }
            }
            2 -> {
                // MODO 2: ONDA (Osciloscopio estacionario que vibra al ritmo del golpe sin desplazarse)
                val path = Path()
                val samples = waveSamples.size
                for (i in 0 until samples) {
                    val x = (i.toFloat() / (samples - 1).toFloat()) * w
                    val y = h / 2f + waveSamples[i] * (h * 0.42f)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color = WinGreen, style = Stroke(width = 2.dp.toPx()))
            }
            3 -> {
                // MODO 3: ESPEJO (Espectro rítmico simétrico desde el centro con picos naranjas)
                val n = 19
                val gap = 2.dp.toPx()
                val bw = (w - gap * (n - 1)) / n
                val mid = h / 2f
                for (i in 0 until n) {
                    val idx = (i * numBands / n).coerceIn(0, numBands - 1)
                    val v = if (isPlaying || bands[idx] > 0.02f) bands[idx].coerceIn(0.04f, 0.95f) else 0.04f
                    val bh = v * (h * 0.46f)
                    val x = i * (bw + gap)
                    drawRect(
                        color = WinGreen,
                        topLeft = Offset(x, mid - bh),
                        size = Size(bw, bh)
                    )
                    drawRect(
                        color = Color(0xFF009A00),
                        topLeft = Offset(x, mid),
                        size = Size(bw, bh)
                    )
                    if (peaks[idx] > 0.08f) {
                        val pkY = (mid - peaks[idx] * (h * 0.46f) - 2.dp.toPx()).coerceAtLeast(0f)
                        drawRect(
                            color = WinOrange,
                            topLeft = Offset(x, pkY),
                            size = Size(bw, 2.dp.toPx())
                        )
                    }
                }
            }
            4 -> {
                // MODO 4: RADIAL (Parlante central que late con el grave + rayos fijos que saltan al ritmo)
                val cx = w / 2f
                val cy = h / 2f
                val baseInner = min(w, h) * (0.14f + bassPulse * 0.08f)
                val maxR = min(w, h) * 0.48f
                val rays = 24
                for (i in 0 until rays) {
                    // Ángulo fijo por banda (no gira como carrusel: late hacia afuera al ritmo)
                    val a = (i.toFloat() / rays) * 6.283185f - 1.570796f
                    val v = if (isPlaying || bands[i] > 0.02f) bands[i].coerceIn(0.08f, 1.0f) else 0.08f
                    val r1 = baseInner + v * (maxR - baseInner)
                    drawLine(
                        color = if (v > 0.72f) WinOrange else WinGreen,
                        start = Offset(cx + cos(a) * baseInner, cy + sin(a) * baseInner),
                        end = Offset(cx + cos(a) * r1, cy + sin(a) * r1),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
                drawCircle(
                    color = WinGreen.copy(alpha = (0.25f + bassPulse * 0.65f).coerceIn(0.2f, 0.9f)),
                    radius = baseInner * 0.72f,
                    center = Offset(cx, cy)
                )
            }
            else -> {
                // MODO 5: APAGADO
            }
        }
    }
}

@Composable
fun WinampBevelSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    trackHeight: Dp,
    thumbWidth: Dp,
    thumbHeight: Dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(maxOf(trackHeight, thumbHeight) + 4.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val frac = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                    onValueChange(frac)
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    val frac = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                    onValueChange(frac)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(maxOf(trackHeight, thumbHeight))) {
            val th = trackHeight.toPx()
            val trackTop = (size.height - th) / 2f
            // Carril negro hundido con bisel invertido
            drawRect(
                color = Color.Black,
                topLeft = Offset(0f, trackTop),
                size = Size(size.width, th)
            )
            val b = 1.5.dp.toPx()
            drawLine(WinBevelDark, Offset(0f, trackTop), Offset(size.width, trackTop), strokeWidth = b)
            drawLine(WinBevelDark, Offset(0f, trackTop), Offset(0f, trackTop + th), strokeWidth = b)
            drawLine(WinBevelLight, Offset(0f, trackTop + th), Offset(size.width, trackTop + th), strokeWidth = b)
            drawLine(WinBevelLight, Offset(size.width, trackTop), Offset(size.width, trackTop + th), strokeWidth = b)

            // Cursor rectangular metálico biselado (#8d97b3 -> #4a5268)
            val tw = thumbWidth.toPx()
            val tHeight = thumbHeight.toPx()
            val thumbX = (value.coerceIn(0f, 1f) * (size.width - tw)).coerceIn(0f, (size.width - tw).coerceAtLeast(0f))
            val thumbTop = (size.height - tHeight) / 2f
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF8D97B3), Color(0xFF4A5268)),
                    startY = thumbTop,
                    endY = thumbTop + tHeight
                ),
                topLeft = Offset(thumbX, thumbTop),
                size = Size(tw, tHeight)
            )
            // Bisel del cursor
            drawLine(Color(0xFFC5CEE0), Offset(thumbX, thumbTop), Offset(thumbX + tw, thumbTop), strokeWidth = b)
            drawLine(Color(0xFFC5CEE0), Offset(thumbX, thumbTop), Offset(thumbX, thumbTop + tHeight), strokeWidth = b)
            drawLine(WinBevelDark, Offset(thumbX, thumbTop + tHeight), Offset(thumbX + tw, thumbTop + tHeight), strokeWidth = b)
            drawLine(WinBevelDark, Offset(thumbX + tw, thumbTop), Offset(thumbX + tw, thumbTop + tHeight), strokeWidth = b)
        }
    }
}

@Composable
fun WinampEqCurveDisplay(
    gainsDb: List<Float>,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .winampSunken(Color.Black)
            .padding(2.dp)
    ) {
        val w = size.width
        val h = size.height
        val midY = h / 2f

        // Línea central 0 dB
        drawLine(
            color = Color(0xFF1A3A1A),
            start = Offset(0f, midY),
            end = Offset(w, midY),
            strokeWidth = 1.dp.toPx()
        )

        if (gainsDb.isEmpty()) return@Canvas
        val path = Path()
        val n = gainsDb.size
        for (i in 0 until n) {
            val x = if (n > 1) (i.toFloat() / (n - 1).toFloat()) * w else w / 2f
            val db = if (enabled) gainsDb[i].coerceIn(-12f, 12f) else 0f
            val y = (midY - (db / 12f) * (h * 0.42f)).coerceIn(2f, h - 2f)
            if (i == 0) {
                path.moveTo(x, y)
            } else {
                val prevX = ((i - 1).toFloat() / (n - 1).toFloat()) * w
                val prevDb = if (enabled) gainsDb[i - 1].coerceIn(-12f, 12f) else 0f
                val prevY = (midY - (prevDb / 12f) * (h * 0.42f)).coerceIn(2f, h - 2f)
                val cx = (prevX + x) / 2f
                path.cubicTo(cx, prevY, cx, y, x, y)
            }
        }
        drawPath(
            path = path,
            color = if (enabled) WinGreen else WinTextMuted,
            style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

@Composable
fun WinampVerticalEqSlider(
    gainDb: Float,
    freqLabel: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onGainChange: (Float) -> Unit
) {
    val dbInt = gainDb.roundToInt()
    val dbText = when {
        dbInt > 0 -> "+$dbInt"
        else -> "$dbInt"
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        // Valor actual en dB arriba de la banda
        Text(
            text = dbText,
            color = when {
                !enabled -> WinTextMuted
                gainDb > 3f -> WinOrange
                gainDb > 0.2f -> WinGreen
                else -> WinDimGreen
            },
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )

        // Carril vertical hundido con barra LED y cursor metálico biselado
        Box(
            modifier = Modifier
                .width(28.dp)
                .height(96.dp)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        // Arriba = +12 dB, Abajo = -12 dB
                        val frac = 1f - (offset.y / size.height.toFloat()).coerceIn(0f, 1f)
                        val newDb = (frac * 24f - 12f).coerceIn(-12f, 12f)
                        onGainChange(newDb)
                    }
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures { change, _ ->
                        change.consume()
                        val frac = 1f - (change.position.y / size.height.toFloat()).coerceIn(0f, 1f)
                        val newDb = (frac * 24f - 12f).coerceIn(-12f, 12f)
                        onGainChange(newDb)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val trackW = 8.dp.toPx()
                val trackLeft = (size.width - trackW) / 2f
                val midY = size.height / 2f

                // Marcas laterales de graduación estilo Winamp
                for (tickIdx in 0..6) {
                    val ty = (tickIdx / 6f) * (size.height - 8.dp.toPx()) + 4.dp.toPx()
                    val tickColor = if (tickIdx == 3) WinGreen.copy(alpha = 0.6f) else Color(0xFF2B3548)
                    drawLine(tickColor, Offset(1.dp.toPx(), ty), Offset(trackLeft - 2.dp.toPx(), ty), strokeWidth = 1.dp.toPx())
                    drawLine(tickColor, Offset(trackLeft + trackW + 2.dp.toPx(), ty), Offset(size.width - 1.dp.toPx(), ty), strokeWidth = 1.dp.toPx())
                }

                // Ranura negra hundida
                drawRect(
                    color = Color.Black,
                    topLeft = Offset(trackLeft, 0f),
                    size = Size(trackW, size.height)
                )
                val b = 1.2.dp.toPx()
                drawLine(WinBevelDark, Offset(trackLeft, 0f), Offset(trackLeft + trackW, 0f), strokeWidth = b)
                drawLine(WinBevelDark, Offset(trackLeft, 0f), Offset(trackLeft, size.height), strokeWidth = b)
                drawLine(WinBevelLight, Offset(trackLeft, size.height), Offset(trackLeft + trackW, size.height), strokeWidth = b)
                drawLine(WinBevelLight, Offset(trackLeft + trackW, 0f), Offset(trackLeft + trackW, size.height), strokeWidth = b)

                // Relleno LED desde el centro (0 dB) hasta la posición del cursor
                val norm = ((gainDb.coerceIn(-12f, 12f) + 12f) / 24f).coerceIn(0f, 1f)
                val thumbH = 11.dp.toPx()
                val thumbW = 20.dp.toPx()
                val thumbTop = ((1f - norm) * (size.height - thumbH)).coerceIn(0f, size.height - thumbH)
                val thumbCenterY = thumbTop + thumbH / 2f

                if (enabled) {
                    val barTop = minOf(midY, thumbCenterY)
                    val barBottom = maxOf(midY, thumbCenterY)
                    val fillColor = when {
                        gainDb > 4f -> WinOrange
                        gainDb >= 0f -> WinGreen
                        else -> WinDimGreen
                    }
                    drawRect(
                        color = fillColor.copy(alpha = 0.75f),
                        topLeft = Offset(trackLeft + 1.5.dp.toPx(), barTop),
                        size = Size((trackW - 3.dp.toPx()).coerceAtLeast(1f), (barBottom - barTop).coerceAtLeast(1f))
                    )
                }

                // Cursor metálico biselado horizontal
                val thumbLeft = (size.width - thumbW) / 2f
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0xFF9BA6C4), Color(0xFF4A5268)),
                        startY = thumbTop,
                        endY = thumbTop + thumbH
                    ),
                    topLeft = Offset(thumbLeft, thumbTop),
                    size = Size(thumbW, thumbH)
                )
                drawLine(Color(0xFFD0D8EC), Offset(thumbLeft, thumbTop), Offset(thumbLeft + thumbW, thumbTop), strokeWidth = b)
                drawLine(Color(0xFFD0D8EC), Offset(thumbLeft, thumbTop), Offset(thumbLeft, thumbTop + thumbH), strokeWidth = b)
                drawLine(WinBevelDark, Offset(thumbLeft, thumbTop + thumbH), Offset(thumbLeft + thumbW, thumbTop + thumbH), strokeWidth = b)
                drawLine(WinBevelDark, Offset(thumbLeft + thumbW, thumbTop), Offset(thumbLeft + thumbW, thumbTop + thumbH), strokeWidth = b)
                // Ranura central del cursor con LED verde/naranja
                drawLine(
                    color = if (!enabled) WinBevelDark else if (gainDb > 4f) WinOrange else WinGreen,
                    start = Offset(thumbLeft + 3.dp.toPx(), thumbCenterY),
                    end = Offset(thumbLeft + thumbW - 3.dp.toPx(), thumbCenterY),
                    strokeWidth = 1.5.dp.toPx()
                )
            }
        }

        // Etiqueta de frecuencia abajo (60, 170, 310, 600, 1K, 3K, 6K, 14K)
        Text(
            text = freqLabel,
            color = WinText,
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun WinampTransportButton(
    icon: String,
    active: Boolean = false,
    iconColor: Color = WinText,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(26.dp)
            .background(
                Brush.verticalGradient(
                    if (active) listOf(Color(0xFF282E3E), Color(0xFF1F2432))
                    else listOf(Color(0xFF4B546B), Color(0xFF2A3040))
                )
            )
            .winampRaisedBevel(
                topLeft = if (active) WinBevelDark else WinBevelLight,
                bottomRight = if (active) WinBevelLight else WinBevelDark
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = icon,
            color = if (active) WinGreen else iconColor,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun WinampPillButton(
    label: String,
    active: Boolean,
    textColor: Color = WinTextMuted,
    activeTextColor: Color = WinGreen,
    activeBorderColor: Color = WinGreen,
    activeBgColor: Color = Color(0xFF142A14),
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(22.dp)
            .background(if (active) activeBgColor else Color(0xFF1E2330))
            .then(
                if (active) Modifier.border(1.dp, activeBorderColor)
                else Modifier.winampRaisedBevel()
            )
            .clickable { onClick() }
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (active) activeTextColor else textColor,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun WinampSmallBevelButton(
    label: String,
    accentGreen: Boolean = false,
    accentBlue: Boolean = false,
    accentGold: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val grad = when {
        accentGreen -> listOf(Color(0xFF2A5A32), Color(0xFF17351C))
        accentBlue -> listOf(Color(0xFF274574), Color(0xFF152745))
        accentGold -> listOf(Color(0xFF634A12), Color(0xFF382907))
        else -> listOf(Color(0xFF4B546B), Color(0xFF2A3040))
    }
    val txtColor = when {
        !enabled -> WinTextMuted.copy(alpha = 0.45f)
        accentGreen -> Color(0xFF9FFFA8)
        accentBlue -> Color(0xFFA8CFFF)
        accentGold -> Color(0xFFFFE066)
        else -> WinText
    }
    Box(
        modifier = Modifier
            .height(22.dp)
            .background(Brush.verticalGradient(grad))
            .winampRaisedBevel()
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = txtColor,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
fun WinampSunkenTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(
            color = WinGreen,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        ),
        cursorBrush = SolidColor(WinGreen),
        modifier = modifier
            .winampSunken(Color.Black)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        decorationBox = { innerTextField ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        color = Color(0xFF266326),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                innerTextField()
            }
        }
    )
}

/** Resumen del origen de las pistas de una lista, por ejemplo "Local/Drive". */
fun trackTypesSummary(tracks: List<Track>): String =
    tracks.map { it.type }.distinct().joinToString("/") {
        when (it) {
            TrackType.LOCAL -> "Local"
            TrackType.DRIVE_PUBLIC -> "Drive"
            TrackType.HTTP -> "Red"
        }
    }.ifBlank { "vacía" }

private const val DRIVE_BROWSER_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

private val AUDIO_EXT_REGEX = Regex("""\.(mp3|m4a|wav|flac|ogg|oga|opus|aac|wma|weba|mp4a)$""", RegexOption.IGNORE_CASE)
private val FOLDER_URL_ID_REGEX = Regex("""drive\.google\.com/drive/(?:u/\d+/)?folders/([A-Za-z0-9_-]{15,})""")
private val FILE_URL_ID_REGEX = Regex("""drive\.google\.com/file/d/([A-Za-z0-9_-]{15,})""")
private val RESOURCE_KEY_REGEX = Regex("""[?&]resourcekey=([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)

private fun decodeHtmlEntities(raw: String): String {
    return raw
        .replace(Regex("""&#(\d+);""")) { m ->
            val code = m.groupValues[1].toIntOrNull()
            if (code != null) String(Character.toChars(code)) else m.value
        }
        .replace(Regex("""&#x([0-9a-fA-F]+);""")) { m ->
            val code = m.groupValues[1].toIntOrNull(16)
            if (code != null) String(Character.toChars(code)) else m.value
        }
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
        .replace(Regex("""\s+"""), " ")
        .trim()
}

private fun directDriveStreamUrl(fileId: String, resourceKey: String? = null): String {
    val rkPart = if (!resourceKey.isNullOrBlank()) "&resourcekey=$resourceKey" else ""
    return "https://drive.usercontent.google.com/download?id=$fileId&export=download&authuser=0&confirm=t$rkPart"
}

private fun httpGetText(urlStr: String, timeoutMs: Int = 8000): String? {
    return try {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", DRIVE_BROWSER_UA)
        conn.setRequestProperty("Accept-Language", "es-AR,es;q=0.9,en;q=0.8")
        conn.instanceFollowRedirects = true
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        val code = conn.responseCode
        if (code in 200..299) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else null
    } catch (_: Exception) {
        null
    }
}

private data class FolderInspection(
    val id: String,
    val title: String,
    val items: List<DriveApiItem>
)

/**
 * Lee directamente de Google Drive (`embeddedfolderview`) el título real y los elementos
 * (subcarpetas y archivos de audio) de una carpeta pública, sin depender de ningún servidor intermedio.
 */
private fun inspectPublicDriveFolder(folderId: String, resourceKey: String? = null): FolderInspection? {
    val rkParam = if (!resourceKey.isNullOrBlank()) "&resourcekey=$resourceKey" else ""
    val html = httpGetText(
        "https://drive.google.com/embeddedfolderview?id=${URLEncoder.encode(folderId, "UTF-8")}$rkParam#list",
        timeoutMs = 7000
    ) ?: return null

    val titleMatch = Regex("""<title>([\s\S]*?)</title>""", RegexOption.IGNORE_CASE).find(html)
    val rawTitle = titleMatch?.groupValues?.get(1)?.let { decodeHtmlEntities(it) }.orEmpty()
        .replace(Regex("""\s*[-–|]\s*Google\s+Drive\s*$""", RegexOption.IGNORE_CASE), "")
        .trim()
    if (rawTitle.isBlank() || Regex("""^(google drive|sign in|iniciar sesi)""", RegexOption.IGNORE_CASE).containsMatchIn(rawTitle)) {
        return null
    }

    val items = mutableListOf<DriveApiItem>()
    val seenIds = mutableSetOf<String>()
    val parts = html.split("""<div class="flip-entry"""")
    for (idx in 1 until parts.size) {
        val chunk = parts[idx]
        val entryId = Regex("""id="entry-([A-Za-z0-9_-]+)"""").find(chunk)?.groupValues?.get(1) ?: continue
        if (!seenIds.add(entryId)) continue

        val href = Regex("""<a\s+href="([^"]+)"""").find(chunk)?.groupValues?.get(1)
            ?.let { decodeHtmlEntities(it) }.orEmpty()
        val entryTitle = Regex("""<div class="flip-entry-title">([\s\S]*?)</div>""").find(chunk)
            ?.groupValues?.get(1)
            ?.let { decodeHtmlEntities(it) }
            .orEmpty()
            .ifBlank { "Elemento Drive" }

        val entryRk = RESOURCE_KEY_REGEX.find(href)?.groupValues?.get(1)
        val isFolder = href.contains("/drive/folders/") ||
            chunk.contains("drive-sprite-folder") ||
            chunk.contains("""aria-label="Folder"""")

        if (isFolder) {
            val rkSuffix = if (!entryRk.isNullOrBlank()) "?resourcekey=$entryRk" else ""
            items.add(
                DriveApiItem(
                    title = entryTitle,
                    streamUrl = "https://drive.google.com/drive/folders/$entryId$rkSuffix",
                    isFolder = true
                )
            )
        } else {
            val isAudioMime = chunk.contains("type/audio/")
            val isAudioExt = AUDIO_EXT_REGEX.containsMatchIn(entryTitle)
            if (isAudioMime || isAudioExt) {
                val cleanSongTitle = entryTitle.replace(AUDIO_EXT_REGEX, "").trim().ifBlank { entryTitle }
                items.add(
                    DriveApiItem(
                        title = cleanSongTitle,
                        streamUrl = directDriveStreamUrl(entryId, entryRk),
                        isFolder = false
                    )
                )
            }
        }
    }

    return FolderInspection(
        id = folderId,
        title = rawTitle,
        items = items
    )
}

/**
 * Busca carpetas públicas de Google Drive directamente desde el teléfono (Brave Search, Google, Bing)
 * y valida el título y contenido real de cada carpeta en paralelo.
 */
suspend fun searchPublicDriveFolders(serverBaseUrl: String, query: String): List<DriveApiItem> = withContext(Dispatchers.IO) {
    val cleanQuery = query.trim()
    // Si el usuario pegó directamente un enlace de carpeta en el buscador, lo abrimos directo
    val pastedFolder = FOLDER_URL_ID_REGEX.find(cleanQuery)?.groupValues?.get(1)
    if (pastedFolder != null) {
        val rk = RESOURCE_KEY_REGEX.find(cleanQuery)?.groupValues?.get(1)
        val info = inspectPublicDriveFolder(pastedFolder, rk)
        val url = "https://drive.google.com/drive/folders/$pastedFolder" + (if (rk != null) "?resourcekey=$rk" else "")
        return@withContext listOf(DriveApiItem(info?.title ?: "Carpeta de Drive", url, isFolder = true))
    }

    val discoveredIds = LinkedHashSet<String>()
    val discoveredTitles = mutableMapOf<String, String>()

    fun extractFolderIdsFromHtml(rawHtml: String) {
        val decoded = try {
            URLDecoder.decode(rawHtml, "UTF-8")
        } catch (_: Exception) {
            rawHtml
        }
        for (m in FOLDER_URL_ID_REGEX.findAll(decoded)) {
            val id = m.groupValues[1]
            if (id.length >= 15) {
                discoveredIds.add(id)
            }
        }
    }

    coroutineScope {
        val qAudio = URLEncoder.encode("site:drive.google.com/drive/folders $cleanQuery mp3 OR audio OR music", "UTF-8")
        val qPlain = URLEncoder.encode("site:drive.google.com/drive/folders $cleanQuery", "UTF-8")

        val searchJobs = listOf(
            // 1. Brave Search (con filtro de música/audio para priorizar carpetas musicales)
            async {
                httpGetText("https://search.brave.com/search?q=$qAudio", timeoutMs = 7000)
                    ?.let { extractFolderIdsFromHtml(it) }
            },
            // 2. Brave Search general por nombre de carpeta
            async {
                httpGetText("https://search.brave.com/search?q=$qPlain", timeoutMs = 7000)
                    ?.let { extractFolderIdsFromHtml(it) }
            },
            // 3. Google Search directo desde el teléfono
            async {
                httpGetText("https://www.google.com/search?num=25&hl=es&q=$qPlain", timeoutMs = 6000)
                    ?.let { extractFolderIdsFromHtml(it) }
            },
            // 4. Bing Search
            async {
                httpGetText("https://www.bing.com/search?q=$qPlain&count=25", timeoutMs = 6000)
                    ?.let { extractFolderIdsFromHtml(it) }
            },
            // 5. Servidor configurado (si devuelve resultados adicionales)
            async {
                try {
                    val cleanServer = serverBaseUrl.trimEnd('/')
                    val conn = URL("$cleanServer/_api/drive/search").openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "text/plain;charset=UTF-8")
                    conn.doOutput = true
                    conn.connectTimeout = 5000
                    conn.readTimeout = 8000
                    val payload = JSONObject().apply {
                        put("json", JSONObject().apply { put("query", cleanQuery) })
                    }.toString()
                    conn.outputStream.write(payload.toByteArray(Charsets.UTF_8))
                    if (conn.responseCode == 200) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        val rootJson = JSONObject(body)
                        val jsonObj = rootJson.optJSONObject("json") ?: rootJson
                        val arr = jsonObj.optJSONArray("results") ?: jsonObj.optJSONArray("tracks") ?: JSONArray()
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            val id = o.optString("id", "").ifBlank {
                                FOLDER_URL_ID_REGEX.find(o.optString("url", ""))?.groupValues?.get(1).orEmpty()
                            }
                            val title = o.optString("title", o.optString("name", "")).trim()
                            if (id.length >= 15) {
                                synchronized(discoveredIds) {
                                    discoveredIds.add(id)
                                    if (title.isNotBlank() && title != "Carpeta de Drive") {
                                        discoveredTitles[id] = title
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        )
        searchJobs.awaitAll()
    }

    if (discoveredIds.isEmpty()) return@withContext emptyList()

    // Inspeccionamos en paralelo hasta 14 carpetas encontradas para obtener su nombre real y priorizar las que tienen audio/subcarpetas
    val candidates = discoveredIds.take(14)
    val inspected = coroutineScope {
        candidates.map { id ->
            async {
                val info = inspectPublicDriveFolder(id)
                if (info != null) {
                    val audioCount = info.items.count { !it.isFolder }
                    val subFolderCount = info.items.count { it.isFolder }
                    val suffix = when {
                        audioCount > 0 && subFolderCount > 0 -> " ($audioCount pistas, $subFolderCount carpetas)"
                        audioCount > 0 -> " ($audioCount pistas)"
                        subFolderCount > 0 -> " ($subFolderCount carpetas)"
                        else -> ""
                    }
                    val hasMusicContent = audioCount > 0 || subFolderCount > 0
                    Triple(
                        DriveApiItem(
                            title = "${info.title}$suffix",
                            streamUrl = "https://drive.google.com/drive/folders/$id",
                            isFolder = true
                        ),
                        hasMusicContent,
                        audioCount * 10 + subFolderCount
                    )
                } else {
                    val fallbackTitle = discoveredTitles[id]
                    if (!fallbackTitle.isNullOrBlank()) {
                        Triple(
                            DriveApiItem(
                                title = fallbackTitle,
                                streamUrl = "https://drive.google.com/drive/folders/$id",
                                isFolder = true
                            ),
                            false,
                            0
                        )
                    } else null
                }
            }
        }.awaitAll().filterNotNull()
    }

    inspected
        .sortedWith(compareByDescending<Triple<DriveApiItem, Boolean, Int>> { it.second }.thenByDescending { it.third })
        .map { it.first }
}

/**
 * Resuelve un enlace público de Drive (archivo o carpeta) directamente desde el dispositivo
 * (con respaldo en el servidor configurado si fuera necesario).
 */
suspend fun resolveDriveLink(serverBaseUrl: String, link: String): List<DriveApiItem> = withContext(Dispatchers.IO) {
    val clean = link.trim()
    if (clean.isBlank()) return@withContext emptyList()
    val rk = RESOURCE_KEY_REGEX.find(clean)?.groupValues?.get(1)

    // 1. ¿Es una carpeta de Drive?
    val folderId = FOLDER_URL_ID_REGEX.find(clean)?.groupValues?.get(1)
        ?: if (clean.contains("embeddedfolderview")) Regex("""[?&]id=([A-Za-z0-9_-]{15,})""").find(clean)?.groupValues?.get(1) else null

    if (folderId != null) {
        val direct = inspectPublicDriveFolder(folderId, rk)
        if (direct != null) {
            val audioTracks = direct.items.filter { !it.isFolder }.toMutableList()
            // Si la carpeta raíz tiene subcarpetas (ej. CD1, CD2), incluimos también los audios del primer nivel de subcarpetas
            val subFolders = direct.items.filter { it.isFolder }.take(6)
            for (sf in subFolders) {
                val sfId = FOLDER_URL_ID_REGEX.find(sf.streamUrl)?.groupValues?.get(1) ?: continue
                val sfRk = RESOURCE_KEY_REGEX.find(sf.streamUrl)?.groupValues?.get(1)
                val subInfo = inspectPublicDriveFolder(sfId, sfRk)
                if (subInfo != null) {
                    audioTracks.addAll(subInfo.items.filter { !it.isFolder })
                }
            }
            if (audioTracks.isNotEmpty()) return@withContext audioTracks.distinctBy { it.streamUrl }
        }
    }

    // 2. ¿Es un archivo individual de Drive?
    val fileId = FILE_URL_ID_REGEX.find(clean)?.groupValues?.get(1)
        ?: Regex("""[?&]id=([A-Za-z0-9_-]{15,})""").find(clean)?.groupValues?.get(1)
        ?: Regex("""^([A-Za-z0-9_-]{25,})$""").find(clean)?.groupValues?.get(1)

    if (fileId != null && folderId == null) {
        val viewHtml = httpGetText("https://drive.google.com/file/d/$fileId/view", timeoutMs = 6000)
        val rawTitle = viewHtml?.let {
            Regex("""<title>([\s\S]*?)</title>""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)
        }?.let { decodeHtmlEntities(it) }
            ?.replace(Regex("""\s*[-–|]\s*Google\s+Drive\s*$""", RegexOption.IGNORE_CASE), "")
            ?.replace(AUDIO_EXT_REGEX, "")
            ?.trim()
            .orEmpty()
            .ifBlank { "Pista de Drive" }
        return@withContext listOf(
            DriveApiItem(
                title = rawTitle,
                streamUrl = directDriveStreamUrl(fileId, rk),
                isFolder = false
            )
        )
    }

    emptyList()
}

/**
 * Devuelve el contenido de una carpeta de Drive (tanto subcarpetas navegables como pistas de audio)
 * para explorar con 📂 ABRIR o cargar con + CARGAR.
 */
suspend fun resolveFolderTracks(serverBaseUrl: String, folderUrl: String): List<DriveApiItem> = withContext(Dispatchers.IO) {
    val clean = folderUrl.trim()
    val folderId = FOLDER_URL_ID_REGEX.find(clean)?.groupValues?.get(1)
        ?: Regex("""[?&]id=([A-Za-z0-9_-]{15,})""").find(clean)?.groupValues?.get(1)
        ?: return@withContext emptyList()
    val rk = RESOURCE_KEY_REGEX.find(clean)?.groupValues?.get(1)

    val info = inspectPublicDriveFolder(folderId, rk) ?: return@withContext emptyList()
    // Ordena primero las subcarpetas y luego las pistas de audio
    info.items.sortedWith(compareByDescending<DriveApiItem> { it.isFolder }.thenBy { it.title.lowercase() })
}
