package com.example.drive_amp.art

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Entrega al auto (Android Auto) la carátula de cada canción, generada al momento.
 * Dirección: content://com.example.drive_amp.art/art?t=<título>&i=<número>&n=<total>
 */
class ArtProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "image/png"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx = context ?: return null
        val title = (uri.getQueryParameter("t") ?: "DRIVE-AMP").take(200)
        val idx = uri.getQueryParameter("i")?.toIntOrNull() ?: 0
        val total = uri.getQueryParameter("n")?.toIntOrNull() ?: 0
        val dir = File(ctx.cacheDir, "art").apply { mkdirs() }
        val file = File(dir, "a_" + (title + "|" + idx + "|" + total).hashCode() + ".png")
        if (!file.exists()) {
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeBytes(WinampArt.render(title, idx, total))
            tmp.renameTo(file)
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    // Solo lectura de imágenes: el resto de operaciones no se usan.
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "com.example.drive_amp.art"

        fun uriFor(title: String, index: Int, total: Int): Uri =
            Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .path("art")
                .appendQueryParameter("t", title)
                .appendQueryParameter("i", index.toString())
                .appendQueryParameter("n", total.toString())
                .build()
    }
}
