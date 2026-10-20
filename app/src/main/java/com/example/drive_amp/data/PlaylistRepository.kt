package com.example.drive_amp.data

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.example.drive_amp.Track
import com.example.drive_amp.TrackType
import org.json.JSONArray
import org.json.JSONObject

object PlaylistRepository {
    private const val PREFS_NAME = "DriveAmpPrefs"
    private const val KEY_PLAYLIST = "saved_playlist"

    fun savePlaylist(context: Context, tracks: List<Track>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonArray = JSONArray()
        tracks.forEach { track ->
            val obj = JSONObject().apply {
                put("id", track.id)
                put("title", track.title)
                put("uri", track.uri)
                put("type", track.type.name)
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
                    type = TrackType.valueOf(obj.getString("type"))
                )
                list.add(track)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun getMediaItems(context: Context): List<MediaItem> {
        val defaultArtUri = Uri.parse("android.resource://com.example.drive_amp/drawable/default_album_art")
        return getTracks(context).map { track ->
            MediaItem.Builder()
                .setMediaId(track.id)
                .setUri(track.uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setArtist("Drive-Amp [${track.type.label}]")
                        .setIsPlayable(true)
                        .setIsBrowsable(false)
                        .setArtworkUri(defaultArtUri)
                        .build()
                )
                .build()
        }
    }
}
