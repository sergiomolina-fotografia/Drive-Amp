package com.example.drive_amp.service

import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.example.drive_amp.data.PlaylistRepository
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

@OptIn(UnstableApi::class)
class DriveAmpMediaService : MediaLibraryService() {

    private lateinit var player: ExoPlayer
    private lateinit var mediaLibrarySession: MediaLibrarySession

    companion object {
        const val ROOT_ID = "DRIVE_AMP_ROOT"
        const val LIST_ID = "DRIVE_AMP_LIST"
        const val FAVS_ID = "DRIVE_AMP_FAVS"
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

    /**
     * El auto (y otros controles) piden reproducir una pista solo por su mediaId, sin dirección.
     * Acá se completa con la dirección guardada y se carga toda la lista (o la de favoritos)
     * empezando por la pista elegida, así "siguiente" y "anterior" funcionan.
     */
    private fun resolveSelection(
        items: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): MediaSession.MediaItemsWithStartPosition {
        val first = items.firstOrNull()
        if (items.size == 1 && first != null) {
            val id = first.mediaId
            val list = if (id.startsWith(PlaylistRepository.FAV_PREFIX))
                PlaylistRepository.getFavoriteMediaItems(this)
            else
                PlaylistRepository.getMediaItems(this)
            val idx = list.indexOfFirst { it.mediaId == id }
            if (idx >= 0) {
                return MediaSession.MediaItemsWithStartPosition(list, idx, startPositionMs)
            }
        }
        val resolved = items.mapNotNull { resolveOne(it) }
        return MediaSession.MediaItemsWithStartPosition(resolved, if (startIndex in resolved.indices) startIndex else 0, startPositionMs)
    }

    /** Completa un pedido que llega solo con mediaId (sin dirección) usando la lista guardada. */
    private fun resolveOne(req: MediaItem): MediaItem? {
        if (req.localConfiguration != null) return req
        return (PlaylistRepository.getMediaItems(this) + PlaylistRepository.getFavoriteMediaItems(this))
            .firstOrNull { it.mediaId == req.mediaId }
    }

    override fun onCreate() {
        super.onCreate()

        // AudioAttributes con manejo automático de foco de audio (evita silencios al cambiar de app o bloquear)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .build()

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
                val items: List<MediaItem> = when (parentId) {
                    ROOT_ID -> listOf(folder(LIST_ID, "Lista de reproducción"), folder(FAVS_ID, "Favoritos ★"))
                    LIST_ID -> PlaylistRepository.getMediaItems(this@DriveAmpMediaService)
                    FAVS_ID -> PlaylistRepository.getFavoriteMediaItems(this@DriveAmpMediaService)
                    else -> emptyList()
                }
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
                val item: MediaItem? = when (mediaId) {
                    LIST_ID -> folder(LIST_ID, "Lista de reproducción")
                    FAVS_ID -> folder(FAVS_ID, "Favoritos ★")
                    else -> (PlaylistRepository.getMediaItems(this@DriveAmpMediaService) +
                        PlaylistRepository.getFavoriteMediaItems(this@DriveAmpMediaService))
                        .firstOrNull { it.mediaId == mediaId }
                }
                return if (item != null) {
                    Futures.immediateFuture(LibraryResult.ofItem(item, null))
                } else {
                    Futures.immediateFuture(LibraryResult.ofError<MediaItem>(LibraryResult.RESULT_ERROR_BAD_VALUE))
                }
            }

            override fun onAddMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>
            ): ListenableFuture<MutableList<MediaItem>> {
                val resolved = mediaItems.mapNotNull { resolveOne(it) }.toMutableList()
                return Futures.immediateFuture(resolved)
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

        mediaLibrarySession = MediaLibrarySession.Builder(this, player, callback).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession {
        return mediaLibrarySession
    }

    override fun onDestroy() {
        mediaLibrarySession.release()
        player.release()
        super.onDestroy()
    }
}
