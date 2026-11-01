// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class, androidx.media3.common.util.ExperimentalApi::class)
package app.miru.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.extractor.*
import androidx.media3.extractor.metadata.icy.IcyHeaders
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.SubtitleParser

/** Pinned native defaults and complete public factory forwarding; no alternate extractor policy. */
internal class ObservedMp4ExtractorsFactory private constructor(
    private val catalog: Mp4CacheCatalog,
    private val requests: Mp4CacheRequestContext,
    private var actual: ExtractorsFactory,
    private val trustedDefaults: Boolean,
) : ExtractorsFactory {
    constructor(catalog: Mp4CacheCatalog, requests: Mp4CacheRequestContext) : this(catalog, requests, DefaultExtractorsFactory(), true)
    internal constructor(catalog: Mp4CacheCatalog, requests: Mp4CacheRequestContext, actual: ExtractorsFactory) : this(catalog, requests, actual, false)
    private var hagc = true
    private var transcode = true
    private var codecs = C.VIDEO_CODEC_FLAG_H264 or C.VIDEO_CODEC_FLAG_H265
    override fun setSubtitleParserFactory(factory: SubtitleParser.Factory): ExtractorsFactory = apply { actual = actual.setSubtitleParserFactory(factory) }
    @Suppress("DEPRECATION")
    override fun experimentalSetTextTrackTranscodingEnabled(enabled: Boolean): ExtractorsFactory = apply {
        actual = actual.experimentalSetTextTrackTranscodingEnabled(enabled); transcode = enabled
    }
    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(flags: Int): ExtractorsFactory = apply {
        actual = actual.experimentalSetCodecsToParseWithinGopSampleDependencies(flags); codecs = flags
    }
    override fun setParseHagcMetadata(enabled: Boolean): ExtractorsFactory = apply { actual = actual.setParseHagcMetadata(enabled); hagc = enabled }
    override fun createExtractors(): Array<Extractor> = observe(actual.createExtractors(), true)
    override fun createExtractors(uri: Uri, headers: Map<String, List<String>>): Array<Extractor> =
        observe(actual.createExtractors(uri, headers), IcyHeaders.parse(headers) == null)
    private fun observe(all: Array<Extractor>, addressingKnown: Boolean): Array<Extractor> {
        val profile = Mp4ParserProfile(trustedDefaults && addressingKnown, hagc, transcode, codecs)
        if(!addressingKnown) { requests.discardMetadata(); return all }
        return all.map { if(it is Mp4Extractor) ObservedMp4Extractor(it, catalog, requests, profile) else object : ForwardingExtractor(it) {
            override fun init(output: ExtractorOutput) { requests.discardMetadata(); super.init(output) }
            override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
                requests.discardMetadata(); return super.read(input, seekPosition)
            }
        } }.toTypedArray()
    }
}

internal class ObservedMp4Extractor(actual: Extractor, private val catalog: Mp4CacheCatalog,
    private val requests: Mp4CacheRequestContext, private val profile: Mp4ParserProfile) : ForwardingExtractor(actual) {
    private class Output(val id: Int, val type: Int, val ordinal: Int, val target: TrackOutput) {
        var format: Format? = null
        var durationUs = C.TIME_UNSET
        var forwarding: TrackOutput? = null
    }
    private val owner = Any()
    private val gate = Any()
    private val tracks = LinkedHashMap<Int, Output>()
    private var ended = false
    private var map: SeekMap? = null
    private var length = -1L
    private var original: Mp4CacheRequest? = null
    private var closed = false
    private var supported = true
    private var readStarted = false
    private var proof: Mp4MetadataProof? = null
    override fun init(output: ExtractorOutput) {
        super.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput {
                val target = output.track(id, type)
                return synchronized(gate) {
                    val current = tracks[id] ?: Output(id, type, tracks.size, target).also {
                        if(tracks.size < 256) tracks[id] = it else supported = false
                    }
                    if(current.type != type || current.target !== target) supported = false
                    if(!supported) catalog.remove(owner)
                    current.forwarding ?: object : TrackOutput by target {
                    override fun format(format: Format) {
                        target.format(format)
                        synchronized(gate) { if(!closed) { current.format = format; capture() } }
                    }
                    override fun durationUs(durationUs: Long) {
                        target.durationUs(durationUs)
                        synchronized(gate) { if(!closed) { current.durationUs = durationUs; capture() } }
                    }
                    }.also { current.forwarding = it }
                }
            }
            override fun endTracks() { output.endTracks(); synchronized(gate) { if(!closed) { ended = true; capture() } } }
            override fun seekMap(seekMap: SeekMap) { output.seekMap(seekMap); synchronized(gate) { if(!closed) { map = seekMap; capture() } } }
        })
    }
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        synchronized(gate) {
            if(!closed) {
                val next = requests.request()
                val nextLength = input.length
                if(next !== original || nextLength >= 0 && nextLength != length) {
                    original = next
                    if(nextLength >= 0) length = nextLength
                    capture()
                }
            }
        }
        readStarted = true
        return super.read(input, seekPosition)
    }
    override fun seek(position: Long, timeUs: Long) {
        super.seek(position, timeUs)
        if(position == 0L) synchronized(gate) { if(!closed) {
            if(readStarted) requests.restartMetadata()
            ended = false; map = null; proof = null; capture()
        } }
    }
    override fun release() {
        synchronized(gate) { closed = true; tracks.clear(); map = null; original = null }
        try { super.release() } finally { catalog.remove(owner) }
    }
    private fun capture() {
        if(closed) return
        // An excessive/ambiguous native output never establishes an index.
        if(!supported) { catalog.remove(owner); return }
        if(proof == null && ended && map != null && tracks.isNotEmpty() && tracks.values.all { it.format != null })
            proof = requests.freezeMetadata()
        catalog.update(owner, Mp4CacheCatalog.Entry(profile, tracks.values.map {
            Mp4CacheCatalog.Track(it.id, it.type, it.ordinal, it.format, it.durationUs)
        }, ended, map, length, original, proof))
    }
}
