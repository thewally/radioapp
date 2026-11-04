package com.arjenvdw.radioapp

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.FileNotFoundException

/**
 * Serves the country-flag PNGs (res/drawable/ic_cflag_<cc>.png) as content://
 * uris. Android Auto runs in a separate process and does not reliably load
 * android.resource:// uris or embedded artwork bytes for the browse *tabs*
 * shown at the root (the country circles) — it just renders a grey placeholder.
 * A content:// uri backed by this exported provider is loaded reliably, so the
 * flags actually appear in those tabs.
 */
class FlagProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context ?: throw FileNotFoundException(uri.toString())
        val cc = uri.lastPathSegment?.removeSuffix(".png")
            ?: throw FileNotFoundException(uri.toString())
        val resId = ctx.resources.getIdentifier("ic_cflag_$cc", "drawable", ctx.packageName)
        if (resId == 0) throw FileNotFoundException(uri.toString())
        val bytes = ctx.resources.openRawResource(resId).use { it.readBytes() }

        // Stream the bytes through a pipe so this works regardless of how the
        // PNG is stored in the APK (openRawResourceFd fails for compressed ones).
        val pipe = ParcelFileDescriptor.createPipe()
        Thread {
            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(bytes) }
        }.start()
        return pipe[0]
    }

    override fun getType(uri: Uri): String = "image/png"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val name = "${uri.lastPathSegment?.removeSuffix(".png").orEmpty()}.png"
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
            addRow(arrayOf(name, null))
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?
    ): Int = 0

    companion object {
        const val AUTHORITY = "com.arjenvdw.radioapp.flags"
        fun uriFor(cc: String): Uri = Uri.parse("content://$AUTHORITY/$cc.png")
    }
}
