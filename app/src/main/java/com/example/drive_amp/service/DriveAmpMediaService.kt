package com.example.drive_amp.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.example.drive_amp.MainActivity
import com.example.drive_amp.audio.AudioSpectrumAnalyzer
import com.example.drive_amp.audio.GraphicEqualizerManager
import com.example.drive_amp.data.PlaylistRepository
import com.example.drive_amp.widget.WidgetUpdater
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

@OptIn(UnstableApi::class)
class DriveAmpMediaService : MediaLibraryService() {

    private lateinit var player: ExoPlayer
    private lateinit var mediaLibrarySession: MediaLibrarySession
    private var consecutiveErrors = 0
    private var isRestoringState = false
    private val positionSaveHandler = Handler(Looper.getMainLooper())
    private val positionSaveRunnable = object : Runnable {
        override fun run() {
            if (::player.isInitialized && player.isPlaying) {
                saveCurrentPlayerState(commit = false)
                positionSaveHandler.postDelayed(this, 2000L)
            }
        }
    }

    companion object {
        const val ROOT_ID = "DRIVE_AMP_ROOT"
        const val LIST_ID = "DRIVE_AMP_LIST"
        const val FAVS_ID = "DRIVE_AMP_FAVS"
        const val SAVED_LISTS_ID = "DRIVE_AMP_SAVED_LISTS"
        const val SAVED_LIST_FOLDER_PREFIX = "listf:"
    }

    private fun saveCurrentPlayerState(commit: Boolean = false) {
        if (isRestoringState || !::player.isInitialized || player.mediaItemCount == 0) return
        val item = player.currentMediaItem ?: return
        val uri = item.localConfiguration?.uri?.toString()
        val idx = player.currentMediaItemIndex.coerceAtLeast(0)
        val pos = player.currentPosition.coerceAtLeast(0L)
        PlaylistRepository.savePlaybackState(
            context = this,
            mediaId = item.mediaId,
            uri = uri,
            index = idx,
            positionMs = pos,
            commit = commit
        )
    }

    /**
     * Si el reproductor está vacío (por ejemplo, al volver a encender y conectar el estéreo del auto),
     * recarga exactamente la última carpeta/lista, la pista y el segundo donde había quedado.
     */
    private fun ensureQueueLoaded(): Boolean {
        if (player.mediaItemCount > 0) return true
        val resumption = PlaylistRepository.getPlaybackResumptionData(this)
        if (resumption.mediaItems.isEmpty()) return false
        isRestoringState = true
        try {
            player.setMediaItems(resumption.mediaItems, resumption.startIndex, resumption.startPositionMs)
            player.prepare()
        } finally {
            isRestoringState = false
        }
        return true
    }

    private fun folder(id: String, title: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsPlayable(false)
                    .setIsBrowsable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build()
            )
            .build()

    private fun childrenOf(parentId: String): List<MediaItem> = when {
        parentId == ROOT_ID -> listOf(
            folder(LIST_ID, "Lista de reproducción"),
            folder(FAVS_ID, "Favoritos ★"),
            folder(SAVED_LISTS_ID, "Listas guardadas")
        )
        parentId == LIST_ID -> PlaylistRepository.getMediaItems(this)
        parentId == FAVS_ID -> PlaylistRepository.getFavoriteMediaItems(this)
        parentId == SAVED_LISTS_ID -> PlaylistRepository.getSavedLists(this).map {
            folder(SAVED_LIST_FOLDER_PREFIX + it.id, "${it.name} (${it.tracks.size})")
        }
        parentId.startsWith(SAVED_LIST_FOLDER_PREFIX) ->
            PlaylistRepository.getSavedListMediaItems(this, parentId.removePrefix(SAVED_LIST_FOLDER_PREFIX))
        else -> emptyList()
    }

    /** El auto pide pistas solo por mediaId (sin dirección): se completan con lo guardado en el teléfono. */
    private fun resolveOne(req: MediaItem): MediaItem? {
        if (req.localConfiguration != null) return req
        return PlaylistRepository.queueFor(this, req.mediaId).firstOrNull { it.mediaId == req.mediaId }
    }

    /**
     * Al elegir UNA pista (desde el auto, por ejemplo) se carga la cola completa a la que pertenece,
     * empezando por esa pista: así se ve toda la lista y siguiente/anterior del volante funcionan.
     * Si el estéreo reconecta pidiendo la misma pista que estaba sonando sin indicar posición,
     * se retoma desde el segundo guardado.
     */
    private fun resolveSelection(
        items: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): MediaSession.MediaItemsWithStartPosition {
        val lastSavedId = PlaylistRepository.getLastTrackId(this)
        val lastSavedPos = PlaylistRepository.getLastPositionMs(this)

        if (items.size == 1) {
            val id = items[0].mediaId
            val queue = PlaylistRepository.queueFor(this, id)
            val idx = queue.indexOfFirst { it.mediaId == id }
            if (idx >= 0) {
                val resumePos = when {
                    startPositionMs != C.TIME_UNSET && startPositionMs > 0L -> startPositionMs
                    id == lastSavedId -> lastSavedPos
                    else -> 0L
                }
                PlaylistRepository.savePlaybackState(
                    context = this,
                    mediaId = id,
                    uri = queue[idx].localConfiguration?.uri?.toString(),
                    index = idx,
                    positionMs = resumePos,
                    commit = true
                )
                return MediaSession.MediaItemsWithStartPosition(queue, idx, resumePos)
            }
        }
        val resolved = items.mapNotNull { resolveOne(it) }
        val start = if (startIndex == C.INDEX_UNSET) 0
            else startIndex.coerceIn(0, (resolved.size - 1).coerceAtLeast(0))
        val targetItem = resolved.getOrNull(start)
        val resumePos = when {
            startPositionMs != C.TIME_UNSET && startPositionMs > 0L -> startPositionMs
            targetItem != null && targetItem.mediaId == lastSavedId -> lastSavedPos
            else -> 0L
        }
        if (targetItem != null) {
            PlaylistRepository.savePlaybackState(
                context = this,
                mediaId = targetItem.mediaId,
                uri = targetItem.localConfiguration?.uri?.toString(),
                index = start,
                positionMs = resumePos,
                commit = true
            )
        }
        return MediaSession.MediaItemsWithStartPosition(resolved, start, resumePos)
    }

    /**
     * Al terminar la última pista de la lista actual, limpia esa lista y carga la siguiente
     * lista guardada en Favoritos (o vuelve a empezar si no hay listas guardadas).
     */
    private fun advanceToNextListOrLoop() {
        val nextList = PlaylistRepository.getNextSavedList(this)
        if (nextList != null) {
            val newTracks = PlaylistRepository.activateSavedListAsPlaylist(this, nextList)
            val newItems = PlaylistRepository.toMediaItems(newTracks)
            newItems.firstOrNull()?.let { first ->
                PlaylistRepository.savePlaybackState(
                    context = this,
                    mediaId = first.mediaId,
                    uri = first.localConfiguration?.uri?.toString(),
                    index = 0,
                    positionMs = 0L,
                    commit = true
                )
            }
            player.stop()
            player.setMediaItems(newItems, 0, 0L)
            player.prepare()
            player.play()
        } else if (player.mediaItemCount > 0) {
            // Si no hay listas guardadas en Favoritos, repite la playlist actual desde el principio
            player.seekTo(0, 0L)
            player.play()
        }
    }

    /** Envía al widget la pista actual y si está sonando. */
    private fun pushWidgetState() {
        val item = player.currentMediaItem
        val title = item?.mediaMetadata?.title?.toString() ?: "DRIVE-AMP READY"
        val info = if (item == null) {
            "Toca ▶ para reproducir"
        } else {
            val source = item.mediaMetadata.artist?.toString()
                ?.substringAfter("[", "")?.substringBefore("]")?.ifBlank { null } ?: "Drive-Amp"
            "${player.currentMediaItemIndex + 1}/${player.mediaItemCount} · $source"
        }
        // playWhenReady (y no isPlaying) para que el botón no parpadee mientras carga el stream
        val playing = player.playWhenReady &&
            player.playbackState != Player.STATE_IDLE && player.playbackState != Player.STATE_ENDED
        WidgetUpdater.push(this, title, info, playing)
    }

    override fun onCreate() {
        super.onCreate()
        GraphicEqualizerManager.loadFromPrefs(this)

        // Configurar AudioAttributes con manejo automático de Foco de Audio (evita silenciamientos)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(
                        arrayOf(
                            GraphicEqualizerManager.createAudioProcessor(),
                            TeeAudioProcessor(AudioSpectrumAnalyzer.audioBufferSink)
                        )
                    )
                    .build()
            }
        }

        player = ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        // REPEAT_MODE_OFF para detectar el fin de la lista actual, limpiarla y saltar a la siguiente lista guardada
        player.repeatMode = Player.REPEAT_MODE_OFF

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (isRestoringState || mediaItem == null) return
                val prevId = PlaylistRepository.getLastTrackId(this@DriveAmpMediaService)
                val isSameTrack = mediaItem.mediaId == prevId
                val posToSave = if (isSameTrack) {
                    player.currentPosition.coerceAtLeast(PlaylistRepository.getLastPositionMs(this@DriveAmpMediaService))
                } else {
                    0L
                }
                PlaylistRepository.savePlaybackState(
                    context = this@DriveAmpMediaService,
                    mediaId = mediaItem.mediaId,
                    uri = mediaItem.localConfiguration?.uri?.toString(),
                    index = player.currentMediaItemIndex.coerceAtLeast(0),
                    positionMs = posToSave,
                    commit = !isSameTrack
                )
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // Solo avanza a la siguiente carpeta si la última pista de la lista actual
                // realmente terminó de reproducirse (evita saltos falsos al reconectar el estéreo)
                if (playbackState == Player.STATE_ENDED &&
                    !isRestoringState &&
                    player.mediaItemCount > 0 &&
                    player.playWhenReady &&
                    player.currentMediaItemIndex >= player.mediaItemCount - 1 &&
                    player.currentPosition > 0L
                ) {
                    advanceToNextListOrLoop()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                positionSaveHandler.removeCallbacks(positionSaveRunnable)
                if (isPlaying) {
                    consecutiveErrors = 0
                    positionSaveHandler.postDelayed(positionSaveRunnable, 2000L)
                } else {
                    // Al pausarse (ej. al apagar el estéreo del auto), guarda de inmediato carpeta, pista y segundo
                    saveCurrentPlayerState(commit = true)
                }
            }

            // Si ocurre un error de red al apagar/encender el auto, guarda la posición actual
            // y NUNCA salta a la siguiente carpeta por error
            override fun onPlayerError(error: PlaybackException) {
                saveCurrentPlayerState(commit = true)
                consecutiveErrors++
                if (player.currentPosition > 0L) {
                    // Estaba reproduciendo esta pista: conservamos la pista y posición para reintentar al dar Play
                    return
                }
                if (consecutiveErrors < 3 && player.hasNextMediaItem() && player.playWhenReady) {
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                }
            }

            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(
                        Player.EVENT_MEDIA_ITEM_TRANSITION,
                        Player.EVENT_PLAY_WHEN_READY_CHANGED,
                        Player.EVENT_PLAYBACK_STATE_CHANGED,
                        Player.EVENT_TIMELINE_CHANGED,
                        Player.EVENT_MEDIA_METADATA_CHANGED
                    )
                ) {
                    pushWidgetState()
                }
            }
        })

        // Precarga al iniciar el servicio la última carpeta/lista, canción y posición guardadas
        ensureQueueLoaded()
        pushWidgetState()

        val callback = object : MediaLibrarySession.Callback {
            override fun onGetLibraryRoot(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                params: LibraryParams?
            ): ListenableFuture<LibraryResult<MediaItem>> {
                val rootItem = MediaItem.Builder()
                    .setMediaId(ROOT_ID)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setIsPlayable(false)
                            .setIsBrowsable(true)
                            .setTitle("Drive-Amp")
                            .build()
                    )
                    .build()
                return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
            }

            override fun onGetChildren(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                parentId: String,
                page: Int,
                pageSize: Int,
                params: LibraryParams?
            ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                val items = childrenOf(parentId)
                val from = (page.toLong() * pageSize).coerceIn(0L, items.size.toLong()).toInt()
                val to = (from.toLong() + pageSize).coerceIn(0L, items.size.toLong()).toInt()
                return Futures.immediateFuture(
                    LibraryResult.ofItemList(ImmutableList.copyOf(items.subList(from, to)), params)
                )
            }

            override fun onGetItem(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                mediaId: String
            ): ListenableFuture<LibraryResult<MediaItem>> {
                val item = when (mediaId) {
                    LIST_ID -> folder(LIST_ID, "Lista de reproducción")
                    FAVS_ID -> folder(FAVS_ID, "Favoritos ★")
                    SAVED_LISTS_ID -> folder(SAVED_LISTS_ID, "Listas guardadas")
                    else -> PlaylistRepository.queueFor(this@DriveAmpMediaService, mediaId)
                        .firstOrNull { it.mediaId == mediaId }
                }
                return if (item != null) {
                    Futures.immediateFuture(LibraryResult.ofItem(item, null))
                } else {
                    Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
                }
            }

            override fun onAddMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>
            ): ListenableFuture<MutableList<MediaItem>> {
                return Futures.immediateFuture(mediaItems.mapNotNull { resolveOne(it) }.toMutableList())
            }

            override fun onSetMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>,
                startIndex: Int,
                startPositionMs: Long
            ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                return Futures.immediateFuture(resolveSelection(mediaItems, startIndex, startPositionMs))
            }

            override fun onPlaybackResumption(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo
            ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                val resumption = PlaylistRepository.getPlaybackResumptionData(this@DriveAmpMediaService)
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(
                        resumption.mediaItems,
                        resumption.startIndex,
                        resumption.startPositionMs
                    )
                )
            }
        }

        // Tocar la notificación / el reproductor del auto abre la app
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Envuelve ExoPlayer para que el botón "Siguiente" del volante de Android Auto
        // siempre esté habilitado (incluso en la última pista) y salte a la siguiente lista guardada
        val sessionPlayer = object : ForwardingPlayer(player) {
            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands().buildUpon()
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .build()
            }

            override fun isCommandAvailable(command: Int): Boolean {
                if (command == Player.COMMAND_SEEK_TO_NEXT || command == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) {
                    return true
                }
                return super.isCommandAvailable(command)
            }

            override fun prepare() {
                ensureQueueLoaded()
                super.prepare()
            }

            override fun play() {
                ensureQueueLoaded()
                if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
                    player.prepare()
                }
                super.play()
            }

            override fun seekToNext() {
                if (player.mediaItemCount == 0) {
                    ensureQueueLoaded()
                    return
                }
                if (player.hasNextMediaItem()) super.seekToNext() else advanceToNextListOrLoop()
            }

            override fun seekToNextMediaItem() {
                if (player.mediaItemCount == 0) {
                    ensureQueueLoaded()
                    return
                }
                if (player.hasNextMediaItem()) super.seekToNextMediaItem() else advanceToNextListOrLoop()
            }
        }

        mediaLibrarySession = MediaLibrarySession.Builder(this, sessionPlayer, callback)
            .setSessionActivity(openApp)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession {
        return mediaLibrarySession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveCurrentPlayerState(commit = true)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        positionSaveHandler.removeCallbacks(positionSaveRunnable)
        saveCurrentPlayerState(commit = true)
        // El widget queda mostrando la última pista, en pausa
        WidgetUpdater.push(
            this,
            player.currentMediaItem?.mediaMetadata?.title?.toString() ?: "DRIVE-AMP READY",
            "En pausa · toca ▶ para seguir",
            false
        )
        mediaLibrarySession.release()
        player.release()
        super.onDestroy()
    }
}
