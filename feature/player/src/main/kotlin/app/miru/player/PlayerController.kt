// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.provider.OpenableColumns
import app.miru.offline.OfflineCoordinator
import androidx.media3.common.*
import androidx.media3.datasource.*
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.*
import androidx.media3.exoplayer.source.*
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.DefaultDashChunkSource
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.session.MediaSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import okhttp3.OkHttpClient
import java.net.URI
import java.util.Locale
import java.io.ByteArrayOutputStream
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf

data class PlayerSnapshot(
    val ready: Boolean = false, val playing: Boolean = false, val buffering: Boolean = true,
    val position: Long = 0, val duration: Long = 0, val buffered: Long = 0,
    val speed: Float = 1f, val width: Int = 0, val height: Int = 0, val live: Boolean = false,
    val error: Boolean = false, val timedOut: Boolean = false, val resumed: Boolean = false,
    val decoder: String = "auto",
    val format: PlayerVideoInfo = PlayerVideoInfo(),
)

data class PlayerVideoInfo(val codec: String = "", val bitrate: Int = 0, val frameRate: Float = 0f,
    val pixelWidthHeightRatio: Float = 1f, val hdr: Boolean = false, val protectedContent: Boolean = false)

class PlayerController(
    private val context: Context,
    val request: PlaybackRequest,
    private val scope: CoroutineScope,
    private val returnToWeb: () -> Unit,
) {
    private val mutable = MutableStateFlow(PlayerSnapshot())
    val state = mutable.asStateFlow()
    var player: ExoPlayer? by mutableStateOf(null); private set
    private var mediaSession: MediaSession? = null
    private var ticker: Job? = null
    private var startup: Job? = null
    private var initialization: Job? = null
    private var authentication: Job? = null
    private var sourceRevision = 0L
    private var returnedToWeb = false
    private var released = false
    private var authenticationRetried = false
    private var softwareRetried = false
    private var forceProgressive = false
    private var waitingForNetwork = false
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { scope.launch {
            if(waitingForNetwork && !released) { waitingForNetwork = false; open(player?.currentPosition ?: state.value.position, state.value.decoder) }
        } }
    }
    private var headers = request.headers
    private var speedRevision = 0
    private var currentSpeed = 1f
    private val mutableTracks = MutableStateFlow(PlayerTrackOptions())
    val trackOptions = mutableTracks.asStateFlow()
    private val mutableSubtitleText = MutableStateFlow("")
    val subtitleText = mutableSubtitleText.asStateFlow()
    private val mutableCues = MutableStateFlow<List<androidx.media3.common.text.Cue>>(emptyList())
    val subtitleCues = mutableCues.asStateFlow()
    private var embeddedCues: List<androidx.media3.common.text.Cue> = emptyList()
    private var localSubtitle: LocalSubtitleDocument? = null
    private var subtitleMode = "auto"
    private var selectedAudioId: String? = null
    private var selectedSubtitleId: String? = null
    private var embeddedSubtitleText = ""
    private var subtitleImport: Job? = null
    private var subtitleImportRevision = 0L
    private var lastVideoSize = 0 to 0
    private var lastSave = 0L
    private val progressKey = PlaybackPolicy.progressKey(request.pageUrl, request.url)
    private val settingsRepository = PlayerPreferencesRepository(context)
    val preferences = settingsRepository.settings.stateIn(scope, SharingStarted.Eagerly, PlayerPreferences())

    init { connectivity.registerDefaultNetworkCallback(networkCallback); open(request.positionMs, "auto", restore = !request.fromWeb) }

    private fun open(position: Long, decoder: String, restore: Boolean = false) {
        if(released) return
        val revision = ++sourceRevision
        authentication?.cancel(); authentication = null
        initialization?.cancel()
        startup?.cancel(); ticker?.cancel()
        mediaSession?.release(); mediaSession = null
        player?.release(); player = null
        embeddedSubtitleText = ""
        embeddedCues = emptyList(); mutableCues.value = emptyList()
        mutableTracks.value = mutableTracks.value.copy(audio = emptyList(), subtitles = emptyList())
        mutable.value = mutable.value.copy(ready = false, buffering = true, error = false, timedOut = false, decoder = decoder)
        initialization = scope.launch {
            try {
            val options = settingsRepository.settings.first()
            val offline = request.offlineId?.let { id -> OfflineCoordinator.get(context).playback(id)
                ?: throw java.io.IOException("Offline media is unavailable") }
            val disk = withContext(Dispatchers.IO) { if(request.isPrivate || offline != null) null else runCatching { PlaybackCache.get(context) }.getOrNull() }
            val saved = if(restore && !request.isPrivate) withContext(Dispatchers.IO) {
                runCatching { PlaybackHistory.get(context).progress().get(progressKey)?.let { PlaybackPolicy.resume(it.position, it.duration) } ?: 0L }.getOrDefault(0L)
            } else 0L
            ensureActive()
            val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true).setMediaCodecSelector { mime, secure, tunneling ->
                MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling).filter { when(decoder) { "software" -> it.softwareOnly; "hardware" -> it.hardwareAccelerated; else -> true } }
            }
            val dataSaver = PlayerPreferences.dataSaverActive(connectivity.isActiveNetworkMetered, connectivity.restrictBackgroundStatus)
            val (minimum, maximum) = options.bufferMillis(dataSaver)
            val load = DefaultLoadControl.Builder().setBufferDurationsMs(minimum, maximum, 1_000, 2_500).setBackBuffer(30_000, true).build()
            val created = ExoPlayer.Builder(context, renderers).setLoadControl(load).build()
            player = created
            created.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            created.setHandleAudioBecomingNoisy(true)
            mediaSession = MediaSession.Builder(context, created).setId(java.util.UUID.randomUUID().toString()).build()
            // A completed offline item has an upstream-free factory for both manifests and
            // segments. Missing cache bytes fail locally instead of silently using mobile data.
            val upstream: DataSource.Factory = offline?.dataSourceFactory ?: DefaultDataSource.Factory(context, httpFactory())
            val partition = PlaybackPolicy.digest(headers.entries.sortedBy { it.key.lowercase() }.joinToString { "${it.key.lowercase()}=${it.value}" })
            val cached: DataSource.Factory = if(disk == null) upstream else CacheDataSource.Factory().setCache(disk).setUpstreamDataSourceFactory(upstream)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR).setCacheKeyFactory { spec -> "$partition:${PlaybackPolicy.normalizedCacheUrl(spec.uri.toString(), spec.position > 0 || spec.length != C.LENGTH_UNSET.toLong())}" }
            val item = (offline?.mediaItem?.buildUpon() ?: MediaItem.Builder().setUri(request.url))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(if(request.isPrivate) context.getString(R.string.player_title) else request.title).build())
                .apply { request.mimeType?.let(::setMimeType) }.build()
            val type = request.mimeType.orEmpty().lowercase()
            val path = android.net.Uri.parse(request.url).path.orEmpty().lowercase()
            val source = when {
                !forceProgressive && (path.endsWith(".m3u8") || type.contains("mpegurl")) -> HlsMediaSource.Factory { dataType -> (if(dataType == C.DATA_TYPE_MANIFEST) upstream else cached).createDataSource() }.createMediaSource(item)
                !forceProgressive && (path.endsWith(".mpd") || type.contains("dash+xml")) -> DashMediaSource.Factory(DefaultDashChunkSource.Factory(cached), upstream).createMediaSource(item)
                else -> ProgressiveMediaSource.Factory(if(path.endsWith(".m3u8") || path.endsWith(".mpd") || type.contains("mpegurl") || type.contains("dash+xml")) upstream else cached).createMediaSource(item)
            }
            created.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) { update() }
                override fun onTracksChanged(tracks: Tracks) {
                    if(released || revision != sourceRevision || player !== created) return
                    applyTrackPreferences(created, tracks)
                    updateTracks(created)
                }
                override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
                    if(released || revision != sourceRevision || player !== created) return
                    embeddedSubtitleText = cueGroup.cues.mapNotNull { it.text?.toString() }.joinToString("\n").take(8_192)
                    embeddedCues = cueGroup.cues.toList()
                    updateSubtitleText()
                }
                override fun onPlayerError(error: PlaybackException) {
                    if(released || returnedToWeb || revision != sourceRevision || player !== created) return
                    var cause: Throwable? = error
                    var status = 0
                    repeat(8) { if(cause is HttpDataSource.InvalidResponseCodeException) status = (cause as HttpDataSource.InvalidResponseCodeException).responseCode; cause = cause?.cause }
                    if(error.errorCode in setOf(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT) && connectivity.activeNetwork == null) {
                        waitingForNetwork = true
                        mutable.value = mutable.value.copy(error = false, buffering = true)
                    } else if(status in setOf(401, 403) && request.fromWeb) {
                        if(!authenticationRetried) {
                            authenticationRetried = true
                            val resumeAt = created.currentPosition
                            authentication = scope.launch {
                                val refreshed = try { withTimeoutOrNull(10_000) { request.refreshHeaders?.invoke() } }
                                    catch(cancelled: CancellationException) { throw cancelled }
                                    catch(_: Exception) { null }
                                if(released || revision != sourceRevision || player !== created) return@launch
                                if(refreshed == null) failBackToWeb() else { authentication = null; headers = refreshed; open(resumeAt, decoder) }
                            }
                        } else failBackToWeb()
                    } else if(error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED && !softwareRetried && decoder != "software") {
                        softwareRetried = true; open(created.currentPosition, "software")
                    } else if(error.errorCode in setOf(PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED)) {
                        if(!forceProgressive) { forceProgressive = true; open(created.currentPosition, decoder) }
                        else if(request.fromWeb) failBackToWeb() else mutable.value = mutable.value.copy(error = true, buffering = false)
                    } else mutable.value = mutable.value.copy(error = true, buffering = false)
                }
            })
            applyTrackPreferences(created, Tracks.EMPTY)
            created.setMediaSource(source, maxOf(position, saved))
            if(saved > 0) mutable.value = mutable.value.copy(resumed = true)
            created.prepare(); created.playWhenReady = true
            speed(currentSpeed)
            startup = scope.launch { delay(20_000); if(!mutable.value.ready && !mutable.value.error) { created.pause(); mutable.value = mutable.value.copy(timedOut = true, buffering = false) } }
            ticker = scope.launch { while(isActive) { update(); if(System.currentTimeMillis() - lastSave >= 10_000) save(); delay(250) } }
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) { mutable.value = mutable.value.copy(error = true, buffering = false) }
        }
    }

    private fun httpFactory(): HttpDataSource.Factory {
        val mediaOrigin = URI(request.url).let { Triple(it.scheme?.lowercase(), it.host?.lowercase(), if(it.port >= 0) it.port else if(it.scheme == "https") 443 else 80) }
        val client = OkHttpClient.Builder().addNetworkInterceptor { chain ->
            val current = chain.request()
            val allowed = Triple(current.url.scheme, current.url.host.lowercase(), current.url.port) == mediaOrigin
            val safe = if(allowed) current else current.newBuilder().removeHeader("Cookie").removeHeader("Authorization").build()
            chain.proceed(safe)
        }.build()
        return OkHttpDataSource.Factory(client).setDefaultRequestProperties(headers)
    }
    private fun failBackToWeb() {
        if(released || returnedToWeb) return
        returnedToWeb = true
        player?.pause()
        mutable.value = mutable.value.copy(error = true, buffering = false)
        returnToWeb()
    }

    private fun update() {
        val target = player ?: return
        if(released) return
        val size = target.videoSize
        val format = target.videoFormat
        val transfer = format?.colorInfo?.colorTransfer
        val videoInfo = PlayerVideoInfo(format?.codecs ?: format?.sampleMimeType.orEmpty(),
            (format?.bitrate ?: 0).coerceAtLeast(0), (format?.frameRate ?: 0f).coerceAtLeast(0f),
            size.pixelWidthHeightRatio,
            transfer == C.COLOR_TRANSFER_ST2084 || transfer == C.COLOR_TRANSFER_HLG, format?.drmInitData != null)
        if(size.width > 0 && size.height > 0 && lastVideoSize != (size.width to size.height)) {
            lastVideoSize = size.width to size.height
            runCatching { request.onVideoSize?.invoke(size.width, size.height) }
        }
        mutable.value = mutable.value.copy(ready = mutable.value.ready || target.playbackState == Player.STATE_READY || target.playbackState == Player.STATE_ENDED,
            playing = target.isPlaying, buffering = waitingForNetwork || target.playbackState == Player.STATE_BUFFERING,
            position = target.currentPosition.coerceAtLeast(0), duration = target.duration.coerceAtLeast(0), buffered = target.bufferedPosition.coerceAtLeast(0),
            speed = target.playbackParameters.speed, width = size.width, height = size.height, live = target.isCurrentMediaItemLive, format = videoInfo)
        updateTracks(target)
        updateSubtitleText()
    }

    // Format identity survives manifest group reordering when rebuilding for auth or decoder changes.
    private fun trackId(format: Format) = listOf(format.id.orEmpty(), format.language.orEmpty(), format.label.orEmpty(), format.sampleMimeType.orEmpty(), format.codecs.orEmpty(), format.channelCount, format.roleFlags).joinToString("|")

    private fun trackLabel(format: Format, index: Int): String {
        val language = format.language?.takeIf { it.isNotBlank() && it != "und" }?.let { Locale.forLanguageTag(it).getDisplayName(Locale.getDefault()) }
        return listOfNotNull(format.label?.takeIf { it.isNotBlank() }, language?.takeIf { it.isNotBlank() })
            .distinct().joinToString(" · ").ifBlank { context.getString(R.string.player_track_number, index + 1) }
    }

    private fun updateTracks(target: Player) {
        val audio = ArrayList<PlayerTrackOption>()
        val text = ArrayList<PlayerTrackOption>()
        target.currentTracks.groups.forEach { group ->
            if(group.type !in setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT)) return@forEach
            for(index in 0 until group.length) {
                if(!group.isTrackSupported(index)) continue
                val format = group.getTrackFormat(index)
                val selected = group.isTrackSelected(index) && (group.type != C.TRACK_TYPE_TEXT || subtitleMode !in setOf("off", "local"))
                val option = PlayerTrackOption(trackId(format), trackLabel(format, if(group.type == C.TRACK_TYPE_AUDIO) audio.size else text.size), format.language, selected)
                if(group.type == C.TRACK_TYPE_AUDIO) audio.add(option) else text.add(option)
            }
        }
        mutableTracks.value = mutableTracks.value.copy(audio = audio, subtitles = text,
            subtitlesOff = subtitleMode == "off" || (subtitleMode != "local" && text.none { it.selected }), localSubtitleName = localSubtitle?.name, localSubtitleSelected = subtitleMode == "local")
    }

    private fun applyTrackPreferences(target: Player, tracks: Tracks = target.currentTracks) {
        val builder = target.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, subtitleMode in setOf("off", "local"))
        fun apply(type: Int, id: String?) {
            if(id == null) return
            tracks.groups.forEach { group ->
                if(group.type == type) for(index in 0 until group.length) if(trackId(group.getTrackFormat(index)) == id && group.isTrackSupported(index)) {
                    builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
                }
            }
        }
        apply(C.TRACK_TYPE_AUDIO, selectedAudioId)
        if(subtitleMode == "embedded") apply(C.TRACK_TYPE_TEXT, selectedSubtitleId)
        val parameters = builder.build()
        if(parameters != target.trackSelectionParameters) target.trackSelectionParameters = parameters
    }

    fun selectAudio(id: String?) {
        if(released || (id != null && trackOptions.value.audio.none { it.id == id })) return
        selectedAudioId = id
        player?.let { target ->
            target.trackSelectionParameters = target.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO).build()
            applyTrackPreferences(target)
            updateTracks(target)
        }
    }

    /** null explicitly disables both embedded and local subtitles. */
    fun selectSubtitle(id: String?) {
        if(released || (id != null && trackOptions.value.subtitles.none { it.id == id })) return
        cancelSubtitleImport()
        selectedSubtitleId = id
        subtitleMode = if(id == null) "off" else "embedded"
        embeddedSubtitleText = ""
        embeddedCues = emptyList()
        player?.let { target ->
            target.trackSelectionParameters = target.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT).build()
            applyTrackPreferences(target)
            updateTracks(target)
        }
        updateSubtitleText()
    }

    suspend fun loadSubtitle(bytes: ByteArray, name: String) {
        val document = withContext(Dispatchers.Default) { LocalSubtitles.parse(name, bytes) }
        withContext(Dispatchers.Main.immediate) {
            if(released) return@withContext
            localSubtitle = document
            subtitleMode = "local"
            selectedSubtitleId = null
            embeddedSubtitleText = ""
            embeddedCues = emptyList()
            player?.let { applyTrackPreferences(it); updateTracks(it) }
            mutableTracks.value = mutableTracks.value.copy(importError = false)
            updateSubtitleText()
        }
    }

    fun selectLocalSubtitle() {
        if(released || localSubtitle == null) return
        cancelSubtitleImport()
        subtitleMode = "local"
        embeddedSubtitleText = ""
        embeddedCues = emptyList()
        player?.let { applyTrackPreferences(it); updateTracks(it) }
        updateSubtitleText()
    }

    private fun cancelSubtitleImport() {
        subtitleImportRevision++
        subtitleImport?.cancel(); subtitleImport = null
        mutableTracks.value = mutableTracks.value.copy(importing = false, importError = false)
    }

    fun loadLocalSubtitle(uri: Uri) {
        if(released) return
        val revision = ++subtitleImportRevision
        subtitleImport?.cancel()
        mutableTracks.value = mutableTracks.value.copy(importing = true, importError = false)
        subtitleImport = scope.launch {
            try {
                val (name, bytes) = withContext(Dispatchers.IO) {
                    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if(cursor.moveToFirst()) cursor.getString(0) else null
                    }.orEmpty().ifBlank { context.getString(R.string.player_local_subtitle) }
                    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while(true) {
                            ensureActive()
                            val count = input.read(buffer)
                            if(count < 0) break
                            require(output.size() + count <= LocalSubtitles.MAX_BYTES) { "Subtitle size is unsupported" }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    } ?: throw IllegalArgumentException("Subtitle is unavailable")
                    name to bytes
                }
                if(revision == subtitleImportRevision) loadSubtitle(bytes, name)
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) { if(!released && revision == subtitleImportRevision) mutableTracks.value = mutableTracks.value.copy(importError = true) }
            finally { if(!released && revision == subtitleImportRevision) mutableTracks.value = mutableTracks.value.copy(importing = false) }
        }
    }

    private fun updateSubtitleText() {
        mutableSubtitleText.value = when(subtitleMode) {
            "off" -> ""
            "local" -> localSubtitle?.textAt(player?.currentPosition ?: state.value.position).orEmpty()
            else -> embeddedSubtitleText
        }
        mutableCues.value = when(subtitleMode) {
            "off" -> emptyList()
            "local" -> mutableSubtitleText.value.takeIf { it.isNotBlank() }?.let {
                listOf(androidx.media3.common.text.Cue.Builder().setText(it).build())
            } ?: emptyList()
            else -> embeddedCues
        }
    }

    fun subtitleStyle(sizeSp: Float? = null, bottomMargin: Float? = null, backgroundOpacity: Float? = null) {
        scope.launch { settingsRepository.update { it.copy(subtitleSizeSp = sizeSp ?: it.subtitleSizeSp,
            subtitleBottomMargin = bottomMargin ?: it.subtitleBottomMargin, subtitleBackgroundOpacity = backgroundOpacity ?: it.subtitleBackgroundOpacity) } }
    }

    fun superResMode(value: app.miru.superres.SuperResMode) { scope.launch { settingsRepository.update { it.copy(superResMode = value) } } }
    fun superResTier(value: app.miru.superres.SuperResTier) { scope.launch { settingsRepository.update { it.copy(superResTier = value) } } }
    fun recordSuperResCeiling(value: app.miru.superres.SuperResTier) { scope.launch { settingsRepository.update {
        it.copy(superResDeviceCeiling = value.limitedBy(it.superResDeviceCeiling))
    } } }

    fun toggle() { player?.let { if(it.isPlaying) it.pause() else { if(it.playbackState == Player.STATE_ENDED) it.seekTo(0); it.play() } } }
    fun resume() { if(!released) player?.play() }
    fun seek(position: Long) { player?.let { target -> target.seekTo(if(target.duration > 0) position.coerceIn(0, target.duration) else position.coerceAtLeast(0)) }; update() }
    fun seekBy(delta: Long) { player?.let { seek(it.currentPosition + delta) } }
    fun live() { player?.seekToDefaultPosition() }
    fun restart() { seek(0); mutable.value = mutable.value.copy(resumed = false) }
    fun speed(requested: Float) {
        val revision = ++speedRevision
        scope.launch {
            if(revision != speedRevision || released) return@launch
            val target = player ?: return@launch
            val previous = target.playbackParameters.speed
            runCatching { target.playbackParameters = PlaybackParameters(requested.coerceIn(0.5f, 3f), 1f); currentSpeed = requested }
                .onFailure { runCatching { target.playbackParameters = PlaybackParameters(previous, 1f) } }
            update()
        }
    }
    fun decoder(value: String) { val position = player?.currentPosition ?: state.value.position; save(); open(position, value) }
    fun retry() { returnedToWeb = false; authenticationRetried = false; open(player?.currentPosition ?: 0, state.value.decoder) }
    fun pause() { player?.pause(); save() }
    fun save() {
        if(request.isPrivate) return
        val snapshot = state.value
        if(snapshot.duration <= 0 || !snapshot.ready) return
        lastSave = System.currentTimeMillis()
        scope.launch(Dispatchers.IO) { runCatching {
            val progress = PlaybackHistory.get(context).progress()
            progress.put(PlaybackProgress(progressKey, snapshot.position, snapshot.duration, System.currentTimeMillis()))
            progress.prune()
        } }
    }
    fun release() { if(released) return; save(); released = true; sourceRevision++; speedRevision++; subtitleImportRevision++; subtitleImport?.cancel(); localSubtitle = null; embeddedSubtitleText = ""; embeddedCues = emptyList(); mutableCues.value = emptyList(); mutableSubtitleText.value = ""; authentication?.cancel(); authentication = null; runCatching { connectivity.unregisterNetworkCallback(networkCallback) }; initialization?.cancel(); startup?.cancel(); ticker?.cancel(); mediaSession?.release(); mediaSession = null; player?.release(); player = null }
}
