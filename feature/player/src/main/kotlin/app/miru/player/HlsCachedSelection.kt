// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsRedundantGroup

/** Resolves EVERY required selected AV group, or returns no proof at all. */
internal object HlsCachedSelection {
    class Playlist(val uri: Uri, val playlist: HlsMediaPlaylist)

    fun select(catalog: HlsCacheCatalog.Snapshot, tracks: Tracks, parameters: TrackSelectionParameters,
        downstream: Map<Int, Format>): List<Playlist>? {
        val required = tracks.groups.filter { it.isSelected && it.type in setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO) }
        if(required.isEmpty() || required.any { it.type in parameters.disabledTrackTypes }) return null
        fun allowed(group: Tracks.Group, selected: Boolean): Set<Int> {
            val override = if(selected) parameters.overrides[group.mediaTrackGroup] else null
            return (0 until group.length).filter { index ->
                (!selected || group.isTrackSelected(index)) && (override == null || index in override.trackIndices)
            }.toSet()
        }
        return required.map { group ->
            val selected = (0 until group.length).filter(group::isTrackSelected).map(group::getTrackFormat)
            val parents = selected.mapNotNull { it.primaryTrackGroupId }.distinct()
            if(parents.size > 1) return null
            val parent = parents.singleOrNull()?.let { id -> tracks.groups.singleOrNull { it.mediaTrackGroup.id == id } ?: return null }
            val owner = parent ?: group
            val indices = allowed(owner, owner.isSelected)
            if(indices.isEmpty()) return null
            val kinds = when(owner.type) {
                C.TRACK_TYPE_VIDEO -> setOf(HlsRedundantGroup.VARIANT, HlsRedundantGroup.VIDEO_RENDITION)
                C.TRACK_TYPE_AUDIO -> setOf(HlsRedundantGroup.VARIANT, HlsRedundantGroup.AUDIO_RENDITION)
                else -> return null
            }
            val candidates = catalog.identities.entries.filter { (uri, identity) ->
                identity.kind in kinds && identity.currentUri == uri && identity.ownerGroupId == owner.mediaTrackGroup.id &&
                    identity.trackIndex in indices &&
                    downstream[identity.kind] == identity.format && catalog.playlists[uri] != null
            }
            // Never union adaptive alternatives or redundant transport paths.
            val chosen = candidates.singleOrNull() ?: candidates.singleOrNull {
                catalog.preferredGroups[owner.mediaTrackGroup.id] === it.value.group
            } ?: return null
            Playlist(chosen.key, requireNotNull(catalog.playlists[chosen.key]))
        }
    }
}
