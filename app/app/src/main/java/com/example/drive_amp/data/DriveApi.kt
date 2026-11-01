package com.example.drive_amp.data

import com.example.drive_amp.DriveApiItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Pide al servidor de Floot las pistas de un enlace o carpeta de Drive (el mismo servidor que usa la página web). */
suspend fun resolveFolderTracks(serverBaseUrl: String, link: String): List<DriveApiItem> = withContext(Dispatchers.IO) {
    val server = serverBaseUrl.trim().trimEnd('/')
    val conn = URL("$server/_api/drive/resolve").openConnection() as HttpURLConnection
    try {
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "text/plain")
        conn.doOutput = true
        conn.connectTimeout = 10_000
        conn.readTimeout = 90_000
        val payload = JSONObject().put("json", JSONObject().put("links", JSONArray().put(link))).toString()
        conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
        if (conn.responseCode != 200) return@withContext emptyList()
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val root = JSONObject(body)
        val json = root.optJSONObject("json") ?: root
        val arr = json.optJSONArray("tracks") ?: JSONArray()
        val out = mutableListOf<DriveApiItem>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.optString("id", "")
            if (id.isNotBlank()) {
                out.add(DriveApiItem(o.optString("title", "Audio"), "$server/_api/drive/stream?id=$id"))
            }
        }
        out
    } catch (e: Exception) {
        e.printStackTrace()
        emptyList()
    } finally {
        conn.disconnect()
    }
}

/** Igual que resolveFolderTracks pero devuelve también un mensaje de error legible. */
suspend fun resolveFolderTracksOrError(serverBaseUrl: String, link: String): Pair<List<DriveApiItem>, String?> = withContext(Dispatchers.IO) {
    val server = serverBaseUrl.trim().trimEnd('/')
    try {
        val conn = URL("$server/_api/drive/resolve").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "text/plain")
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 90_000
            val payload = JSONObject().put("json", JSONObject().put("links", JSONArray().put(link))).toString()
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val body = (if (code == 200) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            val root = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
            val json = root.optJSONObject("json") ?: root
            if (code != 200) {
                val msg = json.optString("error", "").ifBlank { "El servidor respondió con error $code" }
                return@withContext Pair(emptyList<DriveApiItem>(), msg)
            }
            val arr = json.optJSONArray("tracks") ?: JSONArray()
            val out = mutableListOf<DriveApiItem>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("id", "")
                if (id.isNotBlank()) out.add(DriveApiItem(o.optString("title", "Audio"), "$server/_api/drive/stream?id=$id"))
            }
            Pair(out.toList(), if (out.isEmpty()) "La carpeta no tiene audios accesibles." else null)
        } finally {
            conn.disconnect()
        }
    } catch (e: java.net.SocketTimeoutException) {
        Pair(emptyList<DriveApiItem>(), "Se agotó el tiempo de espera. Probá de nuevo.")
    } catch (e: Exception) {
        Pair(emptyList<DriveApiItem>(), "No se pudo conectar: ${e.javaClass.simpleName} ${e.message ?: ""}")
    }
}
