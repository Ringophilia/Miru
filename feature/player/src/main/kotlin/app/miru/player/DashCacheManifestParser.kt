// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.player

import android.net.Uri
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.dash.manifest.Representation
import androidx.media3.exoplayer.upstream.ParsingLoadable
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.IdentityHashMap
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/** Observe SAP declarations while the official parser retains full playback ownership. */
internal class DashCacheManifestParser(private val catalog: DashCacheCatalog) : ParsingLoadable.Parser<DashManifest> {
    override fun parse(uri: Uri, inputStream: InputStream): DashManifest {
        val tee = BoundedTee(inputStream)
        val manifest = DashManifestParser().parse(uri, tee)
        val proof = if(tee.truncated) emptyMap() else runCatching { declarations(manifest, tee.output.toByteArray()) }.getOrDefault(emptyMap())
        catalog.manifestProofs(proof)
        return manifest
    }

    private fun declarations(manifest: DashManifest, bytes: ByteArray): Map<Representation, Int> {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(bytes.inputStream(), null)
        val periods = ArrayList<ArrayList<ArrayList<Int>>>()
        var periodDepth = -1
        var adaptationDepth = -1
        var adaptationSap = 0
        while(parser.eventType != XmlPullParser.END_DOCUMENT) {
            if(parser.eventType == XmlPullParser.START_TAG) {
                when {
                    parser.name == "Period" && parser.depth == 2 -> {
                        periods += arrayListOf<ArrayList<Int>>()
                        periodDepth = parser.depth
                    }
                    parser.name == "AdaptationSet" && periodDepth > 0 && parser.depth == periodDepth + 1 -> {
                        periods.last().add(arrayListOf())
                        adaptationDepth = parser.depth
                        adaptationSap = parser.getAttributeValue(null, "startWithSAP")?.toIntOrNull() ?: 0
                    }
                    parser.name == "Representation" && adaptationDepth > 0 && parser.depth == adaptationDepth + 1 -> {
                        val own = parser.getAttributeValue(null, "startWithSAP")
                        periods.last().last().add(if(own == null) adaptationSap else own.toIntOrNull() ?: 0)
                    }
                }
            } else if(parser.eventType == XmlPullParser.END_TAG) {
                if(parser.depth == adaptationDepth && parser.name == "AdaptationSet") adaptationDepth = -1
                if(parser.depth == periodDepth && parser.name == "Period") periodDepth = -1
            }
            parser.next()
        }
        // Never assign an ordinal declaration to a different parser output.
        require(periods.size == manifest.periodCount)
        val result = IdentityHashMap<Representation, Int>()
        periods.forEachIndexed { period, sets ->
            val actual = manifest.getPeriod(period).adaptationSets
            require(sets.size == actual.size)
            sets.forEachIndexed { adaptation, values ->
                require(values.size == actual[adaptation].representations.size)
                values.forEachIndexed { index, sap -> result[actual[adaptation].representations[index]] = sap }
            }
        }
        return result
    }

    private class BoundedTee(input: InputStream) : FilterInputStream(input) {
        val output = ByteArrayOutputStream()
        var truncated = false; private set
        private fun retain(bytes: ByteArray, offset: Int, count: Int) {
            if(count <= 0 || truncated) return
            if(count > MAX_BYTES - output.size()) { truncated = true; output.reset() }
            else output.write(bytes, offset, count)
        }
        override fun read(): Int = `in`.read().also { if(it >= 0) retain(byteArrayOf(it.toByte()), 0, 1) }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = `in`.read(bytes, offset, length).also { retain(bytes, offset, it) }
        override fun skip(length: Long): Long {
            val buffer = ByteArray(8192)
            var remaining = length
            while(remaining > 0) {
                val read = read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                if(read < 0) break
                remaining -= read
            }
            return length - remaining
        }
    }
    companion object { private const val MAX_BYTES = 8 * 1024 * 1024 }
}
