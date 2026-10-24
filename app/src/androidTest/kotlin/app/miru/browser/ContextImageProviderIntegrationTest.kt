// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the registered, opaque read-only provider using actual image bytes. */
@RunWith(AndroidJUnit4::class)
class ContextImageProviderIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver

    @Test fun originalSvgBytesRemainReadableWithoutRewritingLegalContent() {
        val bytes = ("<?xml version='1.0' encoding='UTF-8'?>" +
            "<s:svg xmlns:s='http://www.w3.org/2000/svg' xmlns:xlink='http://www.w3.org/1999/xlink' width='100%' viewBox='0 0 120 90'>" +
            "<s:title>Miru 图像 &amp; &#x2764;</s:title><s:style><![CDATA[text { fill: #123456; }]]></s:style>" +
            "<s:defs><s:linearGradient id='paint'><s:stop offset='0' stop-color='red'/></s:linearGradient></s:defs>" +
            "<s:rect width='120' height='90' fill='url(#paint)'/><s:text y='30'>原始 SVG</s:text>" +
            "<s:foreignObject width='30' height='20'><p xmlns='http://www.w3.org/1999/xhtml'>Content</p></s:foreignObject>" +
            "</s:svg>").toByteArray()
        withImage(bytes) { uri ->
            assertEquals("image/svg+xml", resolver.getType(uri))
            assertArrayEquals(bytes, resolver.openInputStream(uri)!!.use { it.readBytes() })
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)!!.use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Miru-image.svg", cursor.getString(0))
                assertEquals(bytes.size.toLong(), cursor.getLong(1))
            }
        }
    }

    @Test fun xmlEncodingIsDetectedFromTheOriginalBytes() {
        val text = "<?xml version='1.0' encoding='UTF-16'?><svg xmlns='http://www.w3.org/2000/svg'><title>图像</title></svg>"
        val bytes = text.toByteArray(Charsets.UTF_16)
        withImage(bytes) { uri ->
            assertEquals("image/svg+xml", resolver.getType(uri))
            assertArrayEquals(bytes, resolver.openInputStream(uri)!!.use { it.readBytes() })
        }
    }

    @Test fun allDoctypesIncludingExternalEntitiesAreRejected() {
        val documents = listOf(
            "<!DOCTYPE svg SYSTEM 'http://127.0.0.1:9/private.dtd'><svg xmlns='http://www.w3.org/2000/svg'/>",
            "<!DOCTYPE svg [<!ENTITY secret SYSTEM 'file:///data/data/app.miru.browser/files/private'>]><svg xmlns='http://www.w3.org/2000/svg'><text>&secret;</text></svg>",
            "<!DOCTYPE svg [<!ENTITY legal 'text'>]><svg xmlns='http://www.w3.org/2000/svg'><text>&legal;</text></svg>",
        )
        documents.forEach { assertRejected(it.toByteArray()) }
    }

    @Test fun validPngStillProvidesTheOriginalRasterBytes() {
        val bytes = ByteArrayOutputStream().use { output ->
            val bitmap = Bitmap.createBitmap(7, 5, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
            try { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) } finally { bitmap.recycle() }
            output.toByteArray()
        }
        withImage(bytes) { uri ->
            assertEquals("image/png", resolver.getType(uri))
            assertArrayEquals(bytes, resolver.openInputStream(uri)!!.use { it.readBytes() })
        }
    }

    @Test fun wrongTypesNamespacesAndMalformedDocumentsAreRejected() {
        listOf(
            "https://example.test/image.svg",
            "<html xmlns='http://www.w3.org/1999/xhtml'><svg xmlns='http://www.w3.org/2000/svg'/></html>",
            "<svg xmlns='https://attacker.test/not-svg'/>",
            "<svg/>",
            "<html xmlns='http://www.w3.org/2000/svg'/>",
            "<svg xmlns='http://www.w3.org/2000/svg'><path></svg>",
            "<svg xmlns='http://www.w3.org/2000/svg'/><other/>",
        ).forEach { assertRejected(it.toByteArray()) }
    }

    @Test fun svgUriRemainsOpaqueAndRefusesReadWriteAccess() {
        withImage("<svg xmlns='http://www.w3.org/2000/svg'/>".toByteArray()) { uri ->
            assertEquals("content", uri.scheme)
            assertEquals("${context.packageName}.context-images", uri.authority)
            assertEquals("image", uri.pathSegments[0])
            assertTrue(uri.pathSegments[1].matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
            assertNull(uri.query)
            assertNull(uri.fragment)
            try {
                resolver.openFileDescriptor(uri, "rw")?.close()
                fail("The image provider must reject read/write access")
            } catch(_: FileNotFoundException) { }
        }
    }

    private inline fun withImage(bytes: ByteArray, action: (Uri) -> Unit) {
        val uri = ContextImageStore.put(context, ContextImagePayload(bytes))
        try { action(uri) } finally { ContextImageStore.remove(uri) }
    }

    private fun assertRejected(bytes: ByteArray) {
        var uri: Uri? = null
        try {
            uri = ContextImageStore.put(context, ContextImagePayload(bytes))
            fail("Non-image or unsafe XML must not enter the image provider")
        } catch(_: IllegalArgumentException) { }
        finally { uri?.let(ContextImageStore::remove) }
    }
}
