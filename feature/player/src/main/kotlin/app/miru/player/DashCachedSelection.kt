// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.dash.manifest.DashManifest

internal object DashCachedSelection {
    fun select(snapshot: DashCacheCatalog.Snapshot, manifest: DashManifest, periodIndex: Int, tracks: Tracks,
        parameters: TrackSelectionParameters, downstream: Map<Int, Format>): List<DashCacheRepresentation>? {
        val required = tracks.groups.filter { it.isSelected && it.type in setOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO) }
        if(required.isEmpty() || required.any { it.type in parameters.disabledTrackTypes }) return null
        return required.map { group ->
            val owner = snapshot.entries.singleOrNull { it.manifest === manifest && it.periodIndex == periodIndex &&
                it.trackGroup === group.mediaTrackGroup && it.trackType == group.type } ?: return null
            val override = parameters.overrides[group.mediaTrackGroup]
            val candidates = owner.representations.filter {
                it.trackIndex in 0 until group.length && group.isTrackSelected(it.trackIndex) &&
                    (override == null || it.trackIndex in override.trackIndices) &&
                    downstream[group.type] == group.getTrackFormat(it.trackIndex)
            }
            candidates.singleOrNull() ?: candidates.singleOrNull { it.trackIndex == owner.preferredTrackIndex } ?: return null
        }
    }
}
