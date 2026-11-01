package com.example.drive_amp.data

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.example.drive_amp.Track
import com.example.drive_amp.TrackType
import com.example.drive_amp.art.ArtProvider
import org.json.JSONArray
import org.json.JSONObject

object PlaylistRepository {
    private const val PREFS_NAME = "DriveAmpPrefs"
    private const val KEY_PLAYLIST = "saved_playlist"
    private const val KEY_FAVORITES = "saved_favorites"

    /** Prefijo del mediaId de las pistas que se muestran dentro de la carpeta "Favoritos" del auto. */
    const val FAV_PREFIX = "fav:"

    fun savePlaylist(context: Context, tracks: List<Track>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonArray = JSONArray()
        tracks.forEach { track ->
            val obj = JSONObject().apply {
                put("id", track.id)
                put("title", track.title)
                put("uri", track.uri)
                put("type", track.type.name)
                put("fav", track.isFavorite)
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_PLAYLIST, jsonArray.toString()).apply()
    }

    fun getTracks(context: Context): List<Track> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_PLAYLIST, null) ?: return emptyList()
        val list = mutableListOf<Track>()
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val track = Track(
                    id = obj.getString("id"),
                    title = obj.getString("title"),
                    uri = obj.getString("uri"),
                    type = TrackType.valueOf(obj.getString("type")),
                    isFavorite = obj.optBoolean("fav", false)
                )
                list.add(track)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    /** Convierte pistas a items de Media3, con la carátula estilo Drive-Amp. */
    fun toMediaItems(tracks: List<Track>, idPrefix: String = ""): List<MediaItem> {
        val total = tracks.size
        return tracks.mapIndexed { i, track ->
            MediaItem.Builder()
                .setMediaId(idPrefix + track.id)
                .setUri(track.uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setArtist("Drive-Amp [${track.type.label}]")
                        .setIsPlayable(true)
                        .setIsBrowsable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                        .setArtworkUri(ArtProvider.uriFor(track.title, i + 1, total))
                        .build()
                )
                .build()
        }
    }

    fun getMediaItems(context: Context): List<MediaItem> = toMediaItems(getTracks(context))

    /** Favoritos: lista propia y permanente, independiente de la lista de reproducción. */
    fun saveFavorites(context: Context, tracks: List<Track>) {
        val arr = JSONArray()
        tracks.forEach { t ->
            arr.put(JSONObject().apply {
                put("id", t.id); put("title", t.title); put("uri", t.uri); put("type", t.type.name)
            })
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_FAVORITES, arr.toString()).apply()
    }

    fun getFavorites(context: Context): List<Track> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val str = prefs.getString(KEY_FAVORITES, null)
        if (str == null) {
            // Migración: usa los favoritos que ya estaban marcados dentro de la lista
            val migrated = getTracks(context).filter { it.isFavorite }.map { it.copy(isFavorite = true) }
            if (migrated.isNotEmpty()) saveFavorites(context, migrated)
            return migrated
        }
        val list = mutableListOf<Track>()
        try {
            val arr = JSONArray(str)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(Track(o.getString("id"), o.getString("title"), o.getString("uri"),
                    TrackType.valueOf(o.getString("type")), true))
            }
        } catch (e: Exception) { e.printStackTrace() }
        return list
    }

    fun getFavoriteMediaItems(context: Context): List<MediaItem> =
        toMediaItems(getFavorites(context), FAV_PREFIX)
}
