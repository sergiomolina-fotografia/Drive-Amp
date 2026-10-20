package com.example.drive_amp

import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.drive_amp.data.PlaylistRepository
import com.example.drive_amp.data.resolveFolderTracks
import com.example.drive_amp.service.DriveAmpMediaService
import com.example.drive_amp.ui.theme.DriveAmpTheme
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
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

class MainActivity : ComponentActivity() {

    private var mediaController: MediaController? = null
    private lateinit var controllerFuture: ListenableFuture<MediaController>

    var playlist by mutableStateOf<List<Track>>(emptyList())
    var favorites by mutableStateOf<List<Track>>(emptyList())
    var currentTrackIndex by mutableStateOf(-1)
    var isPlaying by mutableStateOf(false)
    var currentTitle by mutableStateOf("01. [DRIVE-AMP READY]")
    var currentSourceType by mutableStateOf("STREAMING DRIVE")
    var currentPositionMs by mutableStateOf(0L)
    var durationMs by mutableStateOf(0L)
    var volume by mutableStateOf(0.81f)
    var serverBaseUrl by mutableStateOf("https://driveamp.floot.app")

    // Selector de archivos locales
    private val localFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val newTracks = uris.map { uri ->
                val name = getFileName(uri) ?: "Audio Local"
                Track(System.currentTimeMillis().toString() + name, name, uri.toString(), TrackType.LOCAL)
            }
            updatePlaylist(playlist + newTracks)
            if (currentTrackIndex == -1 && playlist.isNotEmpty()) playTrack(0)
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
                    updatePlaylist(playlist + discoveredTracks)
                    if (currentTrackIndex == -1) playTrack(0)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        playlist = PlaylistRepository.getTracks(this)
        favorites = playlist.filter { it.isFavorite }

        val prefs = getSharedPreferences("DriveAmpPrefs", MODE_PRIVATE)
        serverBaseUrl = prefs.getString("server_base_url", "https://driveamp.floot.app") ?: "https://driveamp.floot.app"

        // Conectar con DriveAmpMediaService (Foreground Service) para reproducción en segundo plano ininterrumpida al bloquear la pantalla
        val sessionToken = SessionToken(this, ComponentName(this, DriveAmpMediaService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({
            mediaController = controllerFuture.get()
            mediaController?.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    mediaItem?.let {
                        currentTitle = it.mediaMetadata.title?.toString() ?: "Reproduciendo..."
                        // Mantiene el número de pista al avanzar solo o al elegir desde el auto
                        val id = it.mediaId.removePrefix(PlaylistRepository.FAV_PREFIX)
                        val idx = playlist.indexOfFirst { track -> track.id == id }
                        if (idx >= 0) currentTrackIndex = idx
                    }
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        playNext()
                    }
                }
            })
        }, ContextCompat.getMainExecutor(this))

        setContent {
            DriveAmpTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF1B2230)
                ) {
                    WinampPlayerScreen(
                        playlist = playlist,
                        favorites = favorites,
                        currentIndex = currentTrackIndex,
                        isPlaying = isPlaying,
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
                        onPlayClick = { togglePlayPause() },
                        onStopClick = { stopPlayer() },
                        onNextClick = { playNext() },
                        onPrevClick = { playPrev() },
                        onTrackSelected = { index -> playTrack(index) },
                        onRemoveTrack = { index -> removeTrack(index) },
                        onClearPlaylist = { clearPlaylist() },
                        onAddDriveTracks = { tracks -> addTracks(tracks) },
                        onAddHttpUrl = { url -> addHttpUrl(url) },
                        onOpenFiles = { localFileLauncher.launch(arrayOf("audio/*")) },
                        onOpenFolder = { localFolderLauncher.launch(null) },
                        onToggleFavorite = { track -> toggleFavorite(track) },
                        onVolumeChange = { v ->
                            volume = v
                            mediaController?.volume = v
                        }
                    )
                }
            }
        }
    }

    private fun updatePlaylist(newList: List<Track>) {
        playlist = newList
        favorites = playlist.filter { it.isFavorite }
        PlaylistRepository.savePlaylist(this, playlist)
    }

    private fun clearPlaylist() {
        mediaController?.stop()
        currentTrackIndex = -1
        currentTitle = "01. [PLAYLIST VACÍA]"
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
        val updated = playlist.map {
            if (it.id == track.id) it.copy(isFavorite = !it.isFavorite) else it
        }
        updatePlaylist(updated)
    }

    private fun playTrack(index: Int) {
        if (index < 0 || index >= playlist.size) return
        currentTrackIndex = index
        val track = playlist[index]
        currentSourceType = when(track.type) {
            TrackType.DRIVE_PUBLIC -> "STREAMING DRIVE"
            TrackType.HTTP -> "STREAM RED"
            TrackType.LOCAL -> "AUDIO OFFLINE"
        }

        val mediaItem = MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(Uri.parse(track.uri))
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist("Drive-Amp")
                    .build()
            )
            .build()

        mediaController?.let { controller ->
            controller.setMediaItem(mediaItem)
            controller.prepare()
            controller.play()
        }
    }

    private fun togglePlayPause() {
        if (playlist.isEmpty()) return
        mediaController?.let { controller ->
            if (controller.isPlaying) {
                controller.pause()
            } else {
                if (currentTrackIndex == -1) playTrack(0) else controller.play()
            }
        }
    }

    private fun stopPlayer() {
        mediaController?.let { controller ->
            controller.stop()
            controller.seekTo(0)
        }
        isPlaying = false
        currentTitle = "01. [DETENIDO]"
    }

    private fun playNext() {
        if (playlist.isEmpty()) return
        playTrack((currentTrackIndex + 1) % playlist.size)
    }

    private fun playPrev() {
        if (playlist.isEmpty()) return
        playTrack(if (currentTrackIndex - 1 < 0) playlist.size - 1 else currentTrackIndex - 1)
    }

    private fun removeTrack(index: Int) {
        val list = playlist.toMutableList()
        list.removeAt(index)
        updatePlaylist(list)
        if (currentTrackIndex == index) {
            mediaController?.stop()
            currentTrackIndex = -1
            currentTitle = "01. [PISTA ELIMINADA]"
        } else if (currentTrackIndex > index) {
            currentTrackIndex -= 1
        }
    }

    override fun onDestroy() {
        MediaController.releaseFuture(controllerFuture)
        super.onDestroy()
    }
}

@Composable
fun WinampPlayerScreen(
    playlist: List<Track>,
    favorites: List<Track>,
    currentIndex: Int,
    isPlaying: Boolean,
    currentTitle: String,
    currentSourceType: String,
    currentPositionMs: Long,
    durationMs: Long,
    volume: Float,
    serverBaseUrl: String,
    onSaveServerUrl: (String) -> Unit,
    onPlayClick: () -> Unit,
    onStopClick: () -> Unit,
    onNextClick: () -> Unit,
    onPrevClick: () -> Unit,
    onTrackSelected: (Int) -> Unit,
    onRemoveTrack: (Int) -> Unit,
    onClearPlaylist: () -> Unit,
    onAddDriveTracks: (List<Track>) -> Unit,
    onAddHttpUrl: (String) -> Unit,
    onOpenFiles: () -> Unit,
    onOpenFolder: () -> Unit,
    onToggleFavorite: (Track) -> Unit,
    onVolumeChange: (Float) -> Unit
) {
    var visualizerStyle by remember { mutableStateOf(0) }
    var activeModal by remember { mutableStateOf<String?>(null) } // "DRIVE", "SEARCH", "RED", "FAVORITOS", "CFG"
    var urlInput by remember { mutableStateOf("") }
    var searchInput by remember { mutableStateOf("") }
    var serverInput by remember { mutableStateOf(serverBaseUrl) }

    // Estados para resolver enlaces Drive
    var resolvedTracks by remember { mutableStateOf<List<DriveApiItem>?>(null) }
    var isResolving by remember { mutableStateOf(false) }

    // Estados para búsqueda Drive
    var searchResults by remember { mutableStateOf<List<DriveApiItem>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1B2230))
            .padding(8.dp)
    ) {
        // --- 1. CABECERA DRIVE-AMP CON CFG ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(Color(0xFF2C3E50), Color(0xFF1A252F))),
                    RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)
                )
                .border(1.dp, Color(0xFF4A607A), RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "DRIVE-AMP",
                color = Color.White,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        serverInput = serverBaseUrl
                        activeModal = "CFG"
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF293241)),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(3.dp)
                ) {
                    Text("⚙ CFG", color = Color.White, fontSize = 10.sp)
                }
                Text("—  □  ✕", color = Color(0xFFAAAAAA), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        }

        // --- 2. PANTALLA LED RETRO ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF060A06))
                .border(2.dp, Color(0xFF2E3B4E))
                .clickable { visualizerStyle = (visualizerStyle + 1) % 3 }
                .padding(10.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (isPlaying) "▶" else "❚❚",
                            color = Color(0xFF00FF66),
                            fontSize = 16.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "0:14",
                            color = Color(0xFF00FF66),
                            fontSize = 24.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                    }

                    AudioVisualizerBars(isPlaying = isPlaying, style = visualizerStyle)
                }

                Spacer(modifier = Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF030503))
                        .border(1.dp, Color(0xFF003311))
                        .padding(8.dp)
                ) {
                    Text(
                        text = currentTitle,
                        color = Color(0xFF00FF66),
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = currentSourceType,
                        color = Color(0xFF00FF66),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "3:31",
                        color = Color(0xFF00FF66),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "${playlist.size} pistas",
                        color = Color(0xFF00FF66),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        Slider(
            value = 0.2f,
            onValueChange = {},
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF8899AA),
                activeTrackColor = Color(0xFF556677),
                inactiveTrackColor = Color(0xFF222831)
            ),
            modifier = Modifier.height(16.dp)
        )

        // --- 3. BOTONES DE TRANSPORTE ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            WinampIconButton("⏮", onPrevClick)
            WinampIconButton(if (isPlaying) "❚❚" else "▶", onPlayClick, highlight = isPlaying)
            WinampIconButton("⏹", onStopClick)
            WinampIconButton("⏭", onNextClick)
            WinampIconButton("⏏", { if (currentIndex >= 0) onRemoveTrack(currentIndex) })
            WinampIconButton("📁", onOpenFiles)
            WinampIconButton("📂", onOpenFolder)
        }

        Spacer(modifier = Modifier.height(8.dp))

        // --- 4. BOTONES DE ACCIÓN (ABRIR DRIVE, BUSCAR DRIVE) ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            WinampActionButton("ALEAT", false, Modifier.weight(1f)) {}
            WinampActionButton("REP", false, Modifier.weight(1f)) {}
            WinampActionButton("ABRIR DRIVE ✔", true, Modifier.weight(1.8f)) {
                resolvedTracks = null
                urlInput = ""
                activeModal = "DRIVE"
            }
            WinampActionButton("BUSCAR DRIVE 🔍", false, Modifier.weight(1.8f)) {
                searchResults = emptyList()
                searchInput = ""
                activeModal = "SEARCH"
            }
            WinampActionButton("ABRIR RED 🔗", false, Modifier.weight(1.5f)) { activeModal = "RED" }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            WinampActionButton("FAVORITOS ★", favorites.isNotEmpty(), Modifier.weight(1f)) { activeModal = "FAVORITOS" }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // --- 5. CONTROL DE VOLUMEN ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF141A23), RoundedCornerShape(3.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("VOL", color = Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Slider(
                value = volume,
                onValueChange = onVolumeChange,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
                    .height(16.dp),
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFF8899AA),
                    activeTrackColor = Color(0xFF4A607A),
                    inactiveTrackColor = Color(0xFF222831)
                )
            )
            Text("${(volume * 100).toInt()}%", color = Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }

        Spacer(modifier = Modifier.height(8.dp))

        // --- 6. PLAYLIST ACTUAL & BOTÓN BORRAR LISTA ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "PLAYLIST (${playlist.size} pistas)",
                color = Color(0xFFFFCC00),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
            Button(
                onClick = onClearPlaylist,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF552222)),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                shape = RoundedCornerShape(3.dp)
            ) {
                Text("🗑 Borrar lista", color = Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }
        Spacer(modifier = Modifier.height(2.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF0E131A), RoundedCornerShape(4.dp))
                .border(1.dp, Color(0xFF2E3B4E))
                .padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            itemsIndexed(playlist) { index, track ->
                val isSelected = index == currentIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (isSelected) Color(0xFF1A365D) else Color(0xFF161F2C))
                        .clickable { onTrackSelected(index) }
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${index + 1}. ",
                            color = if (isSelected) Color(0xFF00FF66) else Color.Gray,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = track.title,
                            color = if (isSelected) Color.White else Color(0xFFCCCCCC),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onToggleFavorite(track) }, modifier = Modifier.size(20.dp)) {
                            Text(if (track.isFavorite) "★" else "☆", color = Color(0xFFFFCC00), fontSize = 12.sp)
                        }
                        IconButton(onClick = { onRemoveTrack(index) }, modifier = Modifier.size(20.dp)) {
                            Text("❌", fontSize = 9.sp)
                        }
                    }
                }
            }
        }
    }

    // --- MODALES (DRIVE RESOLVE, SEARCH, RED, FAVORITOS, CFG) ---
    if (activeModal != null) {
        AlertDialog(
            onDismissRequest = { activeModal = null },
            containerColor = Color(0xFF1B2230),
            title = {
                Text(
                    text = when(activeModal) {
                        "DRIVE" -> "☁️ ABRIR ENLACE G-DRIVE"
                        "SEARCH" -> "🔍 BUSCAR CARPETAS / AUDIOS EN DRIVE"
                        "RED" -> "🔗 AGREGAR ENLACE RED / HTTP"
                        "FAVORITOS" -> "★ PISTAS FAVORITAS"
                        "CFG" -> "⚙ CONFIGURACIÓN DE SERVIDOR"
                        else -> ""
                    },
                    color = Color.White,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
            },
            text = {
                Column(modifier = Modifier.heightIn(max = 350.dp)) {
                    if (activeModal == "FAVORITOS") {
                        if (favorites.isEmpty()) {
                            Text("No tienes favoritos guardados aún. Toca la estrella ★ en cualquier pista.", color = Color.Gray, fontSize = 11.sp)
                        } else {
                            LazyColumn(modifier = Modifier.height(200.dp)) {
                                itemsIndexed(favorites) { _, fav ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(text = "★ ${fav.title}", color = Color(0xFFFFCC00), fontSize = 12.sp, modifier = Modifier.weight(1f))
                                        Button(
                                            onClick = {
                                                onAddDriveTracks(listOf(fav))
                                                activeModal = null
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0055A5)),
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text("Reproducir", fontSize = 10.sp)
                                        }
                                    }
                                }
                            }
                        }
                    } else if (activeModal == "CFG") {
                        Text("URL del Servidor Backend (Floot / Proxy):", color = Color.LightGray, fontSize = 11.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = serverInput,
                            onValueChange = { serverInput = it },
                            label = { Text("https://driveamp.floot.app", fontSize = 11.sp, color = Color.Gray) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF00FF66),
                                unfocusedBorderColor = Color.Gray,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            singleLine = true
                        )
                    } else if (activeModal == "DRIVE") {
                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            label = { Text("Pegar enlace público de Google Drive...", fontSize = 11.sp, color = Color.LightGray) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF00FF66),
                                unfocusedBorderColor = Color.Gray,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                if (urlInput.isNotBlank()) {
                                    isResolving = true
                                    coroutineScope.launch(Dispatchers.IO) {
                                        try {
                                            val cleanServer = serverBaseUrl.trimEnd('/')
                                            val apiUri = "$cleanServer/_api/drive/resolve"
                                            val conn = URL(apiUri).openConnection() as HttpURLConnection
                                            conn.requestMethod = "POST"
                                            conn.setRequestProperty("Content-Type", "text/plain")
                                            conn.doOutput = true
                                            conn.connectTimeout = 10000
                                            conn.readTimeout = 90000

                                            val payload = JSONObject().apply {
                                                put("json", JSONObject().apply {
                                                    put("links", JSONArray().put(urlInput.trim()))
                                                })
                                            }.toString()

                                            conn.outputStream.write(payload.toByteArray(Charsets.UTF_8))

                                            val code = conn.responseCode
                                            val body = if (code == 200) {
                                                conn.inputStream.bufferedReader().use { it.readText() }
                                            } else {
                                                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                                            }

                                            val list = mutableListOf<DriveApiItem>()
                                            try {
                                                val rootJson = JSONObject(body)
                                                val jsonObj = rootJson.optJSONObject("json") 
                                                    ?: rootJson.optJSONObject("result")?.optJSONObject("data")?.optJSONObject("json") 
                                                    ?: rootJson
                                                
                                                val tracksArr = jsonObj.optJSONArray("tracks") ?: jsonObj.optJSONArray("results") ?: JSONArray()
                                                for (i in 0 until tracksArr.length()) {
                                                    val obj = tracksArr.getJSONObject(i)
                                                    val name = obj.optString("title", obj.optString("name", "Audio"))
                                                    val id = obj.optString("id", "")
                                                    val folderUrl = if (jsonObj.has("results")) obj.optString("url", "") else ""
                                                    val streamUrl = if (folderUrl.isNotBlank()) {
                                                        folderUrl
                                                    } else if (id.isNotBlank()) {
                                                        "$cleanServer/_api/drive/stream?id=$id"
                                                    } else {
                                                        obj.optString("streamUrl", obj.optString("url", ""))
                                                    }
                                                    if (streamUrl.isNotBlank()) {
                                                        list.add(DriveApiItem(name, streamUrl, folderUrl.isNotBlank()))
                                                    }
                                                }
                                            } catch (ex: Exception) {
                                                ex.printStackTrace()
                                            }

                                            withContext(Dispatchers.Main) {
                                                resolvedTracks = list
                                                isResolving = false
                                            }
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                            withContext(Dispatchers.Main) {
                                                resolvedTracks = emptyList()
                                                isResolving = false
                                            }
                                        }
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0055A5)),
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Verificar Enlace", fontSize = 11.sp)
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        if (isResolving) {
                            Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        } else if (resolvedTracks != null) {
                            if (resolvedTracks!!.isEmpty()) {
                                Text("⚠️ Enlace vacío o sin archivos de audio.", color = Color(0xFFFF5555), fontSize = 12.sp)
                            } else {
                                Text("¡${resolvedTracks!!.size} pistas encontradas!", color = Color(0xFF00FF66), fontSize = 12.sp)
                                Spacer(modifier = Modifier.height(4.dp))
                                LazyColumn(modifier = Modifier.height(180.dp)) {
                                    itemsIndexed(resolvedTracks!!) { _, item ->
                                        Text("• ${item.title}", color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        val tracksToAdd = resolvedTracks!!.map {
                                            Track(System.currentTimeMillis().toString() + it.title, it.title, it.streamUrl, TrackType.DRIVE_PUBLIC)
                                        }
                                        onAddDriveTracks(tracksToAdd)
                                        activeModal = null
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007733)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("➕ Agregar lista (${resolvedTracks!!.size} pistas)", fontSize = 12.sp)
                                }
                            }
                        }
                    } else if (activeModal == "SEARCH") {
                        OutlinedTextField(
                            value = searchInput,
                            onValueChange = { searchInput = it },
                            label = { Text("Buscar carpetas o audios...", fontSize = 11.sp, color = Color.LightGray) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF00FF66),
                                unfocusedBorderColor = Color.Gray,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                if (searchInput.isNotBlank()) {
                                    isSearching = true
                                    coroutineScope.launch(Dispatchers.IO) {
                                        try {
                                            val cleanServer = serverBaseUrl.trimEnd('/')
                                            val apiUri = "$cleanServer/_api/drive/search"
                                            val conn = URL(apiUri).openConnection() as HttpURLConnection
                                            conn.requestMethod = "POST"
                                            conn.setRequestProperty("Content-Type", "text/plain")
                                            conn.doOutput = true
                                            conn.connectTimeout = 10000
                                            conn.readTimeout = 90000

                                            val payload = JSONObject().apply {
                                                put("json", JSONObject().apply {
                                                    put("query", searchInput.trim())
                                                })
                                            }.toString()

                                            conn.outputStream.write(payload.toByteArray(Charsets.UTF_8))

                                            val code = conn.responseCode
                                            val body = if (code == 200) {
                                                conn.inputStream.bufferedReader().use { it.readText() }
                                            } else {
                                                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                                            }

                                            val list = mutableListOf<DriveApiItem>()
                                            try {
                                                val rootJson = JSONObject(body)
                                                val jsonObj = rootJson.optJSONObject("json") 
                                                    ?: rootJson.optJSONObject("result")?.optJSONObject("data")?.optJSONObject("json") 
                                                    ?: rootJson
                                                
                                                val tracksArr = jsonObj.optJSONArray("tracks") ?: jsonObj.optJSONArray("results") ?: JSONArray()
                                                for (i in 0 until tracksArr.length()) {
                                                    val obj = tracksArr.getJSONObject(i)
                                                    val name = obj.optString("title", obj.optString("name", "Audio"))
                                                    val id = obj.optString("id", "")
                                                    val folderUrl = if (jsonObj.has("results")) obj.optString("url", "") else ""
                                                    val streamUrl = if (folderUrl.isNotBlank()) {
                                                        folderUrl
                                                    } else if (id.isNotBlank()) {
                                                        "$cleanServer/_api/drive/stream?id=$id"
                                                    } else {
                                                        obj.optString("streamUrl", obj.optString("url", ""))
                                                    }
                                                    if (streamUrl.isNotBlank()) {
                                                        list.add(DriveApiItem(name, streamUrl, folderUrl.isNotBlank()))
                                                    }
                                                }
                                            } catch (ex: Exception) {
                                                ex.printStackTrace()
                                            }

                                            withContext(Dispatchers.Main) {
                                                searchResults = list
                                                isSearching = false
                                            }
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                            withContext(Dispatchers.Main) {
                                                searchResults = emptyList()
                                                isSearching = false
                                            }
                                        }
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0055A5)),
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Buscar en Drive", fontSize = 11.sp)
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        if (isSearching) {
                            Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        } else {
                            if (searchResults.isNotEmpty() && searchResults.none { it.isFolder }) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Button(
                                    onClick = {
                                        val tracksToAdd = searchResults.map {
                                            Track(System.currentTimeMillis().toString() + it.title, it.title, it.streamUrl, TrackType.DRIVE_PUBLIC)
                                        }
                                        onAddDriveTracks(tracksToAdd)
                                        activeModal = null
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007733)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("➕ Agregar lista (${searchResults.size} pistas)", fontSize = 12.sp)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                            }

                            LazyColumn(modifier = Modifier.height(160.dp)) {
                                itemsIndexed(searchResults) { _, item ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(text = item.title, color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Button(
                                            onClick = {
                                                coroutineScope.launch {
                                                    val found = if (item.isFolder) resolveFolderTracks(serverBaseUrl, item.streamUrl) else listOf(item)
                                                    onAddDriveTracks(found.map { Track(System.currentTimeMillis().toString() + it.title, it.title, it.streamUrl, TrackType.DRIVE_PUBLIC) })
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007733)),
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(if (item.isFolder) "Añadir carpeta" else "Añadir", fontSize = 10.sp)
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            label = { Text("Pegar enlace aquí...", fontSize = 11.sp, color = Color.LightGray) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF00FF66),
                                unfocusedBorderColor = Color.Gray,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            singleLine = true
                        )
                    }
                }
            },
            confirmButton = {
                if (activeModal == "RED") {
                    Button(
                        onClick = {
                            onAddHttpUrl(urlInput)
                            urlInput = ""
                            activeModal = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0055A5))
                    ) {
                        Text("Guardar y Añadir", fontSize = 11.sp)
                    }
                } else if (activeModal == "CFG") {
                    Button(
                        onClick = {
                            onSaveServerUrl(serverInput)
                            activeModal = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0055A5))
                    ) {
                        Text("Guardar Servidor", fontSize = 11.sp)
                    }
                }
            },
            dismissButton = {
                Button(
                    onClick = { activeModal = null },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333))
                ) {
                    Text("Cerrar", fontSize = 11.sp)
                }
            }
        )
    }
}

@Composable
fun AudioVisualizerBars(isPlaying: Boolean, style: Int) {
    val infiniteTransition = rememberInfiniteTransition(label = "visualizer")
    val animPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "phase"
    )

    Canvas(modifier = Modifier.width(120.dp).height(35.dp)) {
        val barCount = 12
        val barWidth = size.width / (barCount * 1.5f)
        val spacing = barWidth * 0.5f

        for (i: Int in 0 until barCount) {
            val randomFactor = if (isPlaying) (Random(i + (animPhase * 10).toInt()).nextFloat() * 0.8f + 0.2f) else 0.1f
            val barHeight = size.height * randomFactor

            val color = when (style) {
                0 -> if (i > barCount - 3) Color(0xFFFFCC00) else Color(0xFF00FF66)
                1 -> if (i % 2 == 0) Color(0xFF00E5FF) else Color(0xFFFF00FF)
                else -> Color(0xFFFF3300)
            }

            drawRect(
                color = color,
                topLeft = Offset(i * (barWidth + spacing), size.height - barHeight),
                size = Size(barWidth, barHeight)
            )
        }
    }
}

@Composable
fun WinampIconButton(icon: String, onClick: () -> Unit, highlight: Boolean = false) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (highlight) Color(0xFF1B4D3E) else Color(0xFF283447)
        ),
        shape = RoundedCornerShape(3.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        border = BorderStroke(1.dp, Color(0xFF4A607A))
    ) {
        Text(text = icon, color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun WinampActionButton(label: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) Color(0xFF1E3A2F) else Color(0xFF232C3A)
        ),
        shape = RoundedCornerShape(3.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
        border = BorderStroke(1.dp, Color(0xFF4A607A))
    ) {
        Text(
            text = label,
            color = if (active) Color(0xFF00FF66) else Color.White,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
