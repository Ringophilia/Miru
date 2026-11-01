// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.player

import androidx.media3.common.*

internal object Mp4CachedSelection {
    class Selected(val entry: Mp4CacheCatalog.Entry, val outputs: List<Mp4CacheCatalog.Track>)
    fun select(snapshot: Mp4CacheCatalog.Snapshot, tracks: Tracks, parameters: TrackSelectionParameters,
        downstream: Map<Int, Format>): Selected? {
        val required = tracks.groups.filter { it.isSelected && it.type in setOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO) }
        if(required.isEmpty() || required.any { it.length != 1 || it.type in parameters.disabledTrackTypes }) return null
        val candidates = snapshot.entries.mapNotNull { entry ->
            if(!entry.profile.trustedDefaults || !entry.tracksEnded || entry.metadataProof == null || entry.seekMap?.isSeekable != true) return@mapNotNull null
            val selected = required.map { group ->
                val format = group.getTrackFormat(0)
                if(parameters.overrides[group.mediaTrackGroup]?.trackIndices?.contains(0) == false) return@mapNotNull null
                val output = entry.tracks.singleOrNull { output ->
                    val actual = output.format
                    output.type == group.type && output.ordinal.toString() == group.mediaTrackGroup.id && actual != null &&
                        actual.metadata != null && actual.metadata === format.metadata && normalize(actual) == normalize(format)
                } ?: return@mapNotNull null
                val emitted = requireNotNull(output.format)
                if(emitted.drmInitData != null || format.cryptoType != C.CRYPTO_TYPE_NONE ||
                    downstream[group.type]?.let { normalize(it) != normalize(emitted) } == true) return@mapNotNull null
                output
            }
            Selected(entry, selected)
        }
        return candidates.singleOrNull()
    }
    private fun normalize(format: Format) = format.buildUpon().setPrimaryTrackGroupId(null).setCryptoType(C.CRYPTO_TYPE_NONE).build()
}
