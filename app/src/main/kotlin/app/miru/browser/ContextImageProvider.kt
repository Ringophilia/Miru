// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.OpenableColumns
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.util.UUID
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.ext.DefaultHandler2

/** Image bytes only. No source URL, title, Cookie, Authorization, or private-session identifier. */
class ContextImagePayload(bytes: ByteArray) {
    init { require(bytes.size in 1..ContextImageStore.MAX_BYTES) { "Unsupported image size" } }
    internal val bytes = bytes.copyOf()
    override fun toString() = "ContextImagePayload(bytes=${bytes.size})"
}

/** Both ordinary and private image actions use ephemeral memory, never shared/private disk files. */
object ContextImageStore {
    const val MAX_BYTES = 16 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 32 * 1024 * 1024
    private const val TTL_MS = 10 * 60 * 1000L
    private const val MAX_ENTRIES = 4
    internal class Entry(val bytes: ByteArray, val mime: String, val name: String, val created: Long)
    private val entries = linkedMapOf<String, Entry>()

    fun put(context: Context, payload: ContextImagePayload): Uri {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(payload.bytes, 0, payload.bytes.size, bounds)
        val rasterMime = bounds.outMimeType.orEmpty().lowercase()
        val mime = if(rasterMime in setOf("image/png", "image/jpeg", "image/webp", "image/gif", "image/avif", "image/bmp", "image/x-ms-bmp", "image/vnd.wap.wbmp")) {
            require(bounds.outWidth in 1..16_384 && bounds.outHeight in 1..16_384 && bounds.outWidth.toLong() * bounds.outHeight <= 36_000_000L) {
                "The response is not a supported image"
            }
            rasterMime
        } else {
            require(isSvgImage(payload.bytes)) { "The response is not a supported image" }
            "image/svg+xml"
        }
        val extension = when(mime) { "image/jpeg" -> "jpg"; "image/webp" -> "webp"; "image/gif" -> "gif"; "image/avif" -> "avif"; "image/png" -> "png"; "image/svg+xml" -> "svg"; else -> "bmp" }
        val token = UUID.randomUUID().toString()
        synchronized(entries) {
            purge()
            while(entries.size >= MAX_ENTRIES || entries.values.sumOf { it.bytes.size } + payload.bytes.size > MAX_TOTAL_BYTES) {
                entries.remove(entries.keys.first())
            }
            entries[token] = Entry(payload.bytes, mime, "Miru-image.$extension", SystemClock.elapsedRealtime())
        }
        return Uri.Builder().scheme("content").authority("${context.packageName}.context-images").appendPath("image").appendPath(token).build()
    }

    internal fun find(uri: Uri): Entry? = synchronized(entries) {
        purge()
        if(uri.pathSegments.size != 2 || uri.pathSegments[0] != "image") return@synchronized null
        entries[uri.pathSegments[1]]
    }

    fun remove(uri: Uri) { synchronized(entries) { if(uri.pathSegments.size == 2) entries.remove(uri.pathSegments[1]) } }
    private fun purge() { val now = SystemClock.elapsedRealtime(); entries.entries.removeAll { now - it.value.created >= TTL_MS } }
}

/**
 * Identifies the original SVG document without rendering, executing, rewriting,
 * or fetching anything it references. The full document must be well formed;
 * legitimate descendants, dimensions, CSS and image content are left intact.
 */
private fun isSvgImage(bytes: ByteArray): Boolean = try {
    val reader = SAXParserFactory.newInstance().apply { isNamespaceAware = true; isValidating = false }
        .newSAXParser().xmlReader
    // These settings and the lexical handler are mandatory: an unsupported
    // parser fails closed rather than retrying with external resolution enabled.
    reader.setFeature("http://xml.org/sax/features/external-general-entities", false)
    reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    val handler = object : DefaultHandler2() {
        var hasRoot = false
        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            if(!hasRoot) {
                if(uri != "http://www.w3.org/2000/svg" || localName != "svg") throw SAXException("Not an SVG image")
                hasRoot = true
            }
        }
        override fun startDTD(name: String?, publicId: String?, systemId: String?): Unit = throw SAXException("SVG DTD is not allowed")
        override fun resolveEntity(publicId: String?, systemId: String?): InputSource = throw SAXException("External entities are not allowed")
        override fun resolveEntity(name: String?, publicId: String?, baseURI: String?, systemId: String?): InputSource = throw SAXException("External entities are not allowed")
        override fun skippedEntity(name: String?): Unit = throw SAXException("Unresolved SVG entity")
        override fun error(exception: SAXParseException): Unit = throw exception
        override fun fatalError(exception: SAXParseException): Unit = throw exception
    }
    reader.contentHandler = handler
    reader.entityResolver = handler
    reader.errorHandler = handler
    reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
    ByteArrayInputStream(bytes).use { reader.parse(InputSource(it)) }
    handler.hasRoot
} catch(_: Exception) { false }

/** Register exported=false + grantUriPermissions=true; grants are read-only, opaque, and expire. */
class ContextImageProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri): String? = ContextImageStore.find(uri)?.mime
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val entry = ContextImageStore.find(uri) ?: throw FileNotFoundException("Image expired")
        val columns = projection?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }?.toTypedArray()
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply { addRow(columns.map { if(it == OpenableColumns.DISPLAY_NAME) entry.name else entry.bytes.size }) }
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if(mode != "r") throw FileNotFoundException("Read-only image")
        val entry = ContextImageStore.find(uri) ?: throw FileNotFoundException("Image expired")
        return openPipeHelper(uri, entry.mime, null, entry.bytes) { output, _, _, _, bytes ->
            runCatching { ParcelFileDescriptor.AutoCloseOutputStream(output).use { it.write(bytes) } }
        }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException("Read-only image")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read-only image")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read-only image")
}
