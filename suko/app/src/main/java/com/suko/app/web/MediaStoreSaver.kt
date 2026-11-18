package com.suko.app.web

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/** Step 17: saves a finished file to Download/Bezalel (Android 10+), or to the app's own Download folder (older Android). */
object MediaStoreSaver {
    fun open(ctx: Context, name: String, mime: String): SaveTarget =
        if (Build.VERSION.SDK_INT >= 29) viaMediaStore(ctx, name, mime) else viaAppFolder(ctx, name)

    private fun viaMediaStore(ctx: Context, name: String, mime: String): SaveTarget {
        val v = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/Bezalel")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri: Uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
            ?: throw IOException("could not create the file in Downloads")
        val stream = ctx.contentResolver.openOutputStream(uri, "w")
        if (stream == null) { ctx.contentResolver.delete(uri, null, null); throw IOException("could not open the file in Downloads") }
        return object : SaveTarget {
            override val out: OutputStream = stream
            override val where: String get() = "Download/Bezalel/$name"
            override fun commit() {
                out.close()
                ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            }
            override fun abort() {
                try { out.close() } catch (e: Exception) { }
                ctx.contentResolver.delete(uri, null, null)
            }
        }
    }

    private fun viaAppFolder(ctx: Context, name: String): SaveTarget {
        val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Bezalel").apply { mkdirs() }
        val file = File(dir, name)
        val stream = FileOutputStream(file)
        return object : SaveTarget {
            override val out: OutputStream = stream
            override val where: String get() = file.absolutePath
            override fun commit() { out.close() }
            override fun abort() { try { out.close() } catch (e: Exception) { }; file.delete() }
        }
    }
}
