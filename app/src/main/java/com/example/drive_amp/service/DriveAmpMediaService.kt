package com.example.drive_amp.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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

    companion object {
        const val ROOT_ID = "DRIVE_AMP_ROOT"
        const val LIST_ID = "DRIVE_AMP_LIST"
        const val FAVS_ID = "DRIVE_AMP_FAVS"
        const val SAVED_LISTS_ID = "DRIVE_AMP_SAVED_LISTS"
        const val SAVED_LIST_FOLDER_PREFIX = "listf:"
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
     * Si llegan varias pistas (la app ya manda la playlist completa) se respetan tal cual.
     */
    private fun resolveSelection(
        items: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): MediaSession.MediaItemsWithStartPosition {
        if (items.size == 1) {
            val id = items[0].mediaId
            val queue = PlaylistRepository.queueFor(this, id)
            val idx = queue.indexOfFirst { it.mediaId == id }
            if (idx >= 0) return MediaSession.MediaItemsWithStartPosition(queue, idx, startPositionMs)
        }
        val resolved = items.mapNotNull { resolveOne(it) }
        val start = if (startIndex == C.INDEX_UNSET) C.INDEX_UNSET
            else startIndex.coerceIn(0, (resolved.size - 1).coerceAtLeast(0))
        return MediaSession.MediaItemsWithStartPosition(resolved, start, startPositionMs)
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
                mediaItem?.let { PlaylistRepository.saveLastTrackId(this@DriveAmpMediaService, it.mediaId) }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    advanceToNextListOrLoop()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) consecutiveErrors = 0
            }

            // Si un enlace falla (Drive caído, archivo borrado) salta a la siguiente en vez de quedar mudo
            override fun onPlayerError(error: PlaybackException) {
                consecutiveErrors++
                if (consecutiveErrors < 5) {
                    if (player.hasNextMediaItem()) {
                        player.seekToNextMediaItem()
                        player.prepare()
                        player.play()
                    } else {
                        advanceToNextListOrLoop()
                    }
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

            override fun seekToNext() {
                if (player.hasNextMediaItem()) super.seekToNext() else advanceToNextListOrLoop()
            }

            override fun seekToNextMediaItem() {
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

    override fun onDestroy() {
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
