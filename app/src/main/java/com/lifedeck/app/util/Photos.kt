package com.lifedeck.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * Photo attachments for memories: copied into app-private storage (never
 * leaving the device unless the user exports), decoded with a downsample so
 * large camera images don't blow up memory.
 */
object Photos {

    fun import(context: Context, uri: Uri, ownerId: String): String? = try {
        val fileName = queryName(context, uri) ?: "photo.jpg"
        val dir = File(context.filesDir, "photos").apply { mkdirs() }
        val target = File(dir, "${ownerId}-${System.currentTimeMillis()}-${fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        target.absolutePath
    } catch (e: Exception) {
        null
    }

    fun listFor(context: Context, paths: List<String>): List<File> =
        paths.map { File(it) }.filter { it.exists() }

    /** Decode with downsampling; safe for UI-sized images. */
    fun decode(path: String, maxDim: Int = 1280): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDim && bounds.outHeight / (sample * 2) >= maxDim) {
            sample *= 2
        }
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (e: Exception) {
        null
    }

    private fun queryName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}
