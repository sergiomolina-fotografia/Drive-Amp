package com.sergiomolina.driveamp.data

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DriveHelper(private val context: Context) {

    suspend fun obtenerCancionesDeDrive(): List<MediaItem> = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("DriveAmpPrefs", Context.MODE_PRIVATE)
        val token = prefs.getString("oauth_token", null) ?: return@withContext emptyList()

        try {
            // Autenticar la librería de Google Drive con el token obtenido en el Paso 4
            val credential = GoogleCredential().setAccessToken(token)
            val driveService = Drive.Builder(
                NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                credential
            ).setApplicationName("Drive-Amp").build()

            // Consultar archivos: que sean de audio y no estén en la papelera
            val request = driveService.files().list()
                .setQ("mimeType contains 'audio/' and trashed = false")
                .setFields("files(id, name, size)")
                .setPageSize(50)

            val fileList = request.execute()
            val mediaItems = mutableListOf<MediaItem>()

            // Convertir cada archivo de Drive a un MediaItem reconocible por el estéreo
            fileList.files?.forEach { file ->
                val streamUri = "https://www.googleapis.com/drive/v3/files/${file.id}?alt=media"

                val item = MediaItem.Builder()
                    .setMediaId(file.id)
                    .setUri(streamUri)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(file.name.substringBeforeLast(".")) // Título sin extensión
                            .setArtist("Google Drive")
                            .setIsPlayable(true)     // Le dice al auto que se puede reproducir
                            .setIsBrowsable(false)   // No es una carpeta, es una canción
                            .build()
                    )
                    .build()
                mediaItems.add(item)
            }

            return@withContext mediaItems
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext emptyList()
        }
    }
}