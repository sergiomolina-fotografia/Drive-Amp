package com.example.drive_amp.data

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.example.drive_amp.Track
import com.example.drive_amp.TrackType
import org.json.JSONArray
import org.json.JSONObject

/** Lista guardada en Favoritos (puede mezclar audios locales y enlaces Drive/HTTP). */
data class SavedPlaylist(
    val id: String,
    val name: String,
    val tracks: List<Track>
)

object PlaylistRepository {
    const val DEFAULT_SERVER_URL = "https://drive-amp-three.vercel.app"
    /** Prefijo de mediaId de las pistas de la carpeta "Favoritos" en Android Auto. */
    const val FAV_PREFIX = "fav:"
    private const val PREFS_NAME = "DriveAmpPrefs"
    private const val KEY_PLAYLIST = "saved_playlist"
    private const val KEY_FAVORITES = "saved_favorites"
    private const val KEY_SAVED_LISTS = "saved_lists"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun migrateFlootUri(rawUri: String): String {
        val trimmed = rawUri.trim()
        // Si es un endpoint proxy /_api/drive/stream?id=... o /api/stream?id=..., usamos descarga directa de Google Drive
        val proxyMatch = Regex("""(?:/_api/drive/stream|/api/stream)\?id=([A-Za-z0-9_-]{10,})""").find(trimmed)
        if (proxyMatch != null) {
            val fileId = proxyMatch.groupValues[1]
            return "https://drive.usercontent.google.com/download?id=$fileId&export=download&authuser=0&confirm=t"
        }
        return trimmed
            .replace("https://driveamp.floot.app", DEFAULT_SERVER_URL, ignoreCase = true)
            .replace("http://driveamp.floot.app", DEFAULT_SERVER_URL, ignoreCase = true)
    }

    private fun trackToJson(track: Track): JSONObject = JSONObject().apply {
        put("id", track.id)
        put("title", track.title)
        put("uri", migrateFlootUri(track.uri))
        put("type", track.type.name)
        put("isFavorite", track.isFavorite)
    }

    private fun trackFromJson(obj: JSONObject): Track? = try {
        Track(
            id = obj.getString("id"),
            title = obj.getString("title"),
            uri = migrateFlootUri(obj.getString("uri")),
            type = TrackType.valueOf(obj.getString("type")),
            isFavorite = obj.optBoolean("isFavorite", false)
        )
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }

    private fun tracksToJson(tracks: List<Track>): JSONArray =
        JSONArray().apply { tracks.forEach { put(trackToJson(it)) } }

    private fun tracksFromJson(arr: JSONArray?): List<Track> {
        if (arr == null) return emptyList()
        val list = mutableListOf<Track>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { trackFromJson(it) }?.let { list.add(it) }
        }
        return list
    }

    fun savePlaylist(context: Context, tracks: List<Track>) {
        prefs(context).edit().putString(KEY_PLAYLIST, tracksToJson(tracks).toString()).commit()
    }

    fun getTracks(context: Context): List<Track> {
        val jsonStr = prefs(context).getString(KEY_PLAYLIST, null) ?: return emptyList()
        return try {
            tracksFromJson(JSONArray(jsonStr))
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun saveFavorites(context: Context, tracks: List<Track>) {
        prefs(context).edit()
            .putString(KEY_FAVORITES, tracksToJson(tracks.map { it.copy(isFavorite = true) }).toString())
            .commit()
    }

    fun getFavorites(context: Context): List<Track> {
        val str = prefs(context).getString(KEY_FAVORITES, null)
        if (str == null) {
            val migrated = getTracks(context).filter { it.isFavorite }.distinctBy { it.uri }
            saveFavorites(context, migrated)
            return migrated
        }
        return try {
            tracksFromJson(JSONArray(str)).map { it.copy(isFavorite = true) }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun saveSavedLists(context: Context, lists: List<SavedPlaylist>) {
        val arr = JSONArray()
        lists.forEach { l ->
            arr.put(JSONObject().apply {
                put("id", l.id)
                put("name", l.name)
                put("tracks", tracksToJson(l.tracks))
            })
        }
        prefs(context).edit().putString(KEY_SAVED_LISTS, arr.toString()).commit()
    }

    fun getSavedLists(context: Context): List<SavedPlaylist> {
        val str = prefs(context).getString(KEY_SAVED_LISTS, null) ?: return emptyList()
        val result = mutableListOf<SavedPlaylist>()
        try {
            val arr = JSONArray(str)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                result.add(
                    SavedPlaylist(
                        id = o.optString("id", "list_$i"),
                        name = o.optString("name", "Lista ${i + 1}"),
                        tracks = tracksFromJson(o.optJSONArray("tracks"))
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }

    // ---------- Android Auto / Widget / Media3 ----------

    /** Prefijo de mediaId para las pistas de la lista de saved lists: "lst:<idLista>:<índice>". */
    const val LIST_ITEM_PREFIX = "lst:"

    // Último estado de reproducción (lo usa el widget y para retomar al reconectar el estéreo/auto)
    private const val KEY_LAST_TRACK_ID = "last_track_id"
    private const val KEY_LAST_TRACK_URI = "last_track_uri"
    private const val KEY_LAST_TRACK_INDEX = "last_track_index"
    private const val KEY_LAST_POSITION_MS = "last_position_ms"
    // ID de la lista guardada activa (para recordar la carpeta actual y pasar a la siguiente solo cuando termina)
    private const val KEY_CURRENT_SAVED_LIST_ID = "current_saved_list_id"

    data class PlaybackResumptionData(
        val mediaItems: List<MediaItem>,
        val startIndex: Int,
        val startPositionMs: Long
    )

    fun saveLastTrackId(context: Context, mediaId: String) {
        prefs(context).edit().putString(KEY_LAST_TRACK_ID, mediaId).apply()
    }

    fun getLastTrackId(context: Context): String? = prefs(context).getString(KEY_LAST_TRACK_ID, null)

    fun getLastTrackUri(context: Context): String? = prefs(context).getString(KEY_LAST_TRACK_URI, null)

    fun getLastTrackIndex(context: Context): Int = prefs(context).getInt(KEY_LAST_TRACK_INDEX, 0)

    fun getLastPositionMs(context: Context): Long =
        prefs(context).getLong(KEY_LAST_POSITION_MS, 0L).coerceAtLeast(0L)

    fun saveLastPositionMs(context: Context, positionMs: Long, commit: Boolean = false) {
        val editor = prefs(context).edit().putLong(KEY_LAST_POSITION_MS, positionMs.coerceAtLeast(0L))
        if (commit) editor.commit() else editor.apply()
    }

    fun savePlaybackState(
        context: Context,
        mediaId: String?,
        uri: String?,
        index: Int,
        positionMs: Long,
        commit: Boolean = false
    ) {
        val editor = prefs(context).edit()
        if (!mediaId.isNullOrBlank()) {
            editor.putString(KEY_LAST_TRACK_ID, mediaId)
            if (mediaId.startsWith(LIST_ITEM_PREFIX)) {
                val listId = mediaId.removePrefix(LIST_ITEM_PREFIX).substringBeforeLast(":")
                if (listId.isNotBlank()) {
                    editor.putString(KEY_CURRENT_SAVED_LIST_ID, listId)
                }
            }
        }
        if (!uri.isNullOrBlank()) {
            editor.putString(KEY_LAST_TRACK_URI, uri)
        }
        if (index >= 0) {
            editor.putInt(KEY_LAST_TRACK_INDEX, index)
        }
        editor.putLong(KEY_LAST_POSITION_MS, positionMs.coerceAtLeast(0L))
        if (commit) editor.commit() else editor.apply()
    }

    fun saveCurrentSavedListId(context: Context, listId: String?) {
        prefs(context).edit().putString(KEY_CURRENT_SAVED_LIST_ID, listId).commit()
    }

    fun getCurrentSavedListId(context: Context): String? =
        prefs(context).getString(KEY_CURRENT_SAVED_LIST_ID, null)

    /**
     * Activa una lista guardada como la playlist actual: usa IDs determinísticos ("lst:<idLista>:<índice>")
     * para que la app, el widget y Android Auto compartan exactamente los mismos mediaId y posición.
     */
    fun activateSavedListAsPlaylist(context: Context, list: SavedPlaylist): List<Track> {
        val tracks = list.tracks.mapIndexed { i, t ->
            t.copy(id = "$LIST_ITEM_PREFIX${list.id}:$i", isFavorite = false)
        }
        savePlaylist(context, tracks)
        saveCurrentSavedListId(context, list.id)
        return tracks
    }

    /**
     * Devuelve la siguiente lista guardada en Favoritos con pistas (en orden circular).
     * Si no había ninguna lista activa o ya no existe, toma la primera disponible.
     */
    fun getNextSavedList(context: Context): SavedPlaylist? {
        val lists = getSavedLists(context).filter { it.tracks.isNotEmpty() }
        if (lists.isEmpty()) return null
        val curId = getCurrentSavedListId(context)
        val curIdx = lists.indexOfFirst { it.id == curId }
        val nextIdx = if (curIdx >= 0) (curIdx + 1) % lists.size else 0
        return lists[nextIdx]
    }

    /**
     * Reconstruye la cola, el índice de pista y el segundo exacto donde quedó la reproducción
     * antes de apagar el estéreo o cerrar la conexión con el auto.
     */
    fun getPlaybackResumptionData(context: Context): PlaybackResumptionData {
        val lastId = getLastTrackId(context)
        val lastUri = getLastTrackUri(context)
        val savedIndex = getLastTrackIndex(context)
        val savedPosMs = getLastPositionMs(context)
        val curListId = getCurrentSavedListId(context)

        val items: List<MediaItem> = when {
            lastId != null && lastId.startsWith(FAV_PREFIX) -> {
                getFavoriteMediaItems(context).ifEmpty { getMediaItems(context) }
            }
            lastId != null && lastId.startsWith(LIST_ITEM_PREFIX) -> {
                val listId = lastId.removePrefix(LIST_ITEM_PREFIX).substringBeforeLast(":")
                val savedList = getSavedLists(context).firstOrNull { it.id == listId }
                if (savedList != null && savedList.tracks.isNotEmpty()) {
                    val tracks = activateSavedListAsPlaylist(context, savedList)
                    toMediaItems(tracks)
                } else {
                    getMediaItems(context)
                }
            }
            else -> {
                val currentItems = getMediaItems(context)
                if (currentItems.isNotEmpty()) {
                    currentItems
                } else if (!curListId.isNullOrBlank()) {
                    val savedList = getSavedLists(context).firstOrNull { it.id == curListId }
                    if (savedList != null && savedList.tracks.isNotEmpty()) {
                        toMediaItems(activateSavedListAsPlaylist(context, savedList))
                    } else emptyList()
                } else {
                    emptyList()
                }
            }
        }

        if (items.isEmpty()) {
            return PlaybackResumptionData(emptyList(), 0, 0L)
        }

        val idxById = if (!lastId.isNullOrBlank()) items.indexOfFirst { it.mediaId == lastId } else -1
        val idxByUri = if (idxById < 0 && !lastUri.isNullOrBlank()) {
            items.indexOfFirst { it.localConfiguration?.uri?.toString() == lastUri }
        } else -1

        val resolvedIndex = when {
            idxById >= 0 -> idxById
            idxByUri >= 0 -> idxByUri
            else -> savedIndex.coerceIn(0, items.size - 1)
        }

        return PlaybackResumptionData(
            mediaItems = items,
            startIndex = resolvedIndex,
            startPositionMs = savedPosMs
        )
    }

    /**
     * Convierte pistas en MediaItems para el reproductor. El mediaId de la playlist es exactamente
     * track.id (así la app, el auto y el widget identifican la misma pista).
     */
    fun toMediaItems(tracks: List<Track>, idOf: (Int, Track) -> String = { _, t -> t.id }): List<MediaItem> {
        val defaultArtUri = Uri.parse("android.resource://com.example.drive_amp/drawable/default_album_art")
        return tracks.mapIndexed { i, track ->
            MediaItem.Builder()
                .setMediaId(idOf(i, track))
                .setUri(track.uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setArtist("Drive-Amp [${track.type.label}]")
                        .setIsPlayable(true)
                        .setIsBrowsable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                        .setArtworkUri(defaultArtUri)
                        .build()
                )
                .build()
        }
    }

    /** Playlist actual completa (la cola que se ve en Android Auto). */
    fun getMediaItems(context: Context): List<MediaItem> = toMediaItems(getTracks(context))

    fun getFavoriteMediaItems(context: Context): List<MediaItem> =
        toMediaItems(getFavorites(context)) { _, t -> FAV_PREFIX + t.id }

    fun getSavedListMediaItems(context: Context, listId: String): List<MediaItem> {
        val list = getSavedLists(context).firstOrNull { it.id == listId } ?: return emptyList()
        return toMediaItems(list.tracks) { i, _ -> "$LIST_ITEM_PREFIX$listId:$i" }
    }

    /**
     * Devuelve la cola completa a la que pertenece un mediaId (playlist, favoritos o una lista guardada),
     * para que al elegir una pista desde el auto se carguen todas y funcione siguiente/anterior.
     */
    fun queueFor(context: Context, mediaId: String): List<MediaItem> = when {
        mediaId.startsWith(FAV_PREFIX) -> getFavoriteMediaItems(context)
        mediaId.startsWith(LIST_ITEM_PREFIX) -> {
            val listId = mediaId.removePrefix(LIST_ITEM_PREFIX).substringBeforeLast(":")
            val list = getSavedLists(context).firstOrNull { it.id == listId }
            if (list != null) {
                toMediaItems(activateSavedListAsPlaylist(context, list))
            } else {
                saveCurrentSavedListId(context, listId)
                getSavedListMediaItems(context, listId)
            }
        }
        else -> getMediaItems(context)
    }
}
