package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.*
import io.github.kdroidfilter.composemediaplayer.desktop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.net.URI
import java.util.concurrent.atomic.AtomicLong
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import io.github.kdroidfilter.composemediaplayer.linux.LinuxVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.mac.MacVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.util.CurrentPlatform
import io.github.kdroidfilter.composemediaplayer.windows.WindowsVideoPlayerState
import io.github.vinceglb.filekit.PlatformFile

actual fun createVideoPlayerState(
    audioMode: AudioMode,
    cacheConfig: CacheConfig,
): VideoPlayerState = DefaultVideoPlayerState()

/**
 * Represents the state and behavior of a video player. This class provides properties
 * and methods to control video playback, manage the playback state, and interact with
 * platform-specific implementations.
 *
 * Properties:
 * - `isPlaying`: Indicates whether the video is currently playing.
 * - `volume`: Controls the playback volume. Valid values are within the range of 0.0 (muted) to 1.0 (maximum volume).
 * - `sliderPos`: Represents the current playback position as a normalized value between 0.0 and 1.0.
 * - `userDragging`: Denotes whether the user is manually adjusting the playback position.
 * - `loop`: Specifies if the video should loop when it reaches the end.
 * - `positionText`: Returns the current playback position as a formatted string.
 * - `durationText`: Returns the total duration of the video as a formatted string.
 *
 * Methods:
 * - `openUri(uri: String)`: Opens a video file or URL for playback.
 * - `play()`: Starts or resumes video playback.
 * - `pause()`: Pauses video playback.
 * - `stop()`: Stops playback and resets the player state.
 * - `seekTo(value: Float)`: Seeks to a specific playback position based on the provided normalized value.
 * - `dispose()`: Releases resources used by the video player and disposes of the state.
 */
@Stable
open class DefaultVideoPlayerState : VideoPlayerState {
    var delegate: VideoPlayerState by mutableStateOf(PreviewableVideoPlayerState(hasMedia = false, isPlaying = false, loop = false, sliderPos = 0f, currentTime = 0.0, duration = 0.0))
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val generation = AtomicLong()
    private var opening: Job? = null
    private val lifecycle = MutableSharedFlow<PlaybackEvent>(replay = 1, extraBufferCapacity = 64)
    private val lifecycleQueue = kotlinx.coroutines.channels.Channel<PlaybackEvent>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private val lifecycleWriter = scope.launch { for (event in lifecycleQueue) lifecycle.emit(event) }
    override val events: Flow<PlaybackEvent> = lifecycle.asSharedFlow()
    private var routingLoading by mutableStateOf(false)
    private var routingError: VideoPlayerError? by mutableStateOf(null)
    @Volatile private var disposed = false
    private var lastSource: MediaSource? = null
    private var widevineRuntime: WidevineRuntime? = null
    fun configureWidevine(runtime: WidevineRuntime) { widevineRuntime = runtime }
    private fun event(id: Long, type: PlaybackEvent.Type, error: VideoPlayerError? = null) {
        lifecycleQueue.trySend(PlaybackEvent(id, type, error))
    }
    override fun openSource(source: MediaSource, initializeplayerState: InitialPlayerState) {
        if (disposed) return
        lastSource = source
        val previous = generation.getAndIncrement()
        if (previous > 0) event(previous, PlaybackEvent.Type.REPLACED)
        val id = generation.get()
        opening?.cancel()
        routingLoading = true
        routingError = null
        val old = delegate
        opening = scope.launch {
            try {
                old.dispose()
                if (old is DashVideoPlayerState) old.awaitDisposed()
                ensureActive()
                event(id, PlaybackEvent.Type.OPENING)
                val uri = URI(source.uri)
                var dash = source.widevine != null || source.mimeType?.substringBefore(';')?.equals("application/dash+xml", true) == true || uri.path.orEmpty().endsWith(".mpd", true)
                if (!dash && uri.scheme in listOf("http", "https") && '.' !in uri.path.orEmpty().substringAfterLast('/')) {
                    val probe = MediaHttp()
                    try {
                        probe.open(source.uri, source.headers).use { response ->
                            val prefix = response.connection.inputStream.use { it.readNBytes(2048) }.toString(Charsets.UTF_8)
                            dash = response.connection.contentType.orEmpty().substringBefore(';').equals("application/dash+xml", true) || prefix.contains(Regex("<(?:[A-Za-z0-9_]+:)?MPD(?:\\s|>)"))
                        }
                    } finally { probe.close() }
                }
                val next: VideoPlayerState = if (dash || source.headers.isNotEmpty()) {
                    require(CurrentPlatform.os != CurrentPlatform.OS.LINUX) { "Configured DASH playback currently supports macOS and Windows." }
                    DashVideoPlayerState(widevineRuntime)
                } else when (CurrentPlatform.os) {
                    CurrentPlatform.OS.MAC -> MacVideoPlayerState()
                    CurrentPlatform.OS.WINDOWS -> WindowsVideoPlayerState()
                    CurrentPlatform.OS.LINUX -> LinuxVideoPlayerState()
                }
                if (id != generation.get() || disposed) { next.dispose(); return@launch }
                next.volume = old.volume
                next.loop = old.loop
                next.playbackSpeed = old.playbackSpeed
                next.isFullscreen = old.isFullscreen
                next.subtitleTextStyle = old.subtitleTextStyle
                next.subtitleBackgroundColor = old.subtitleBackgroundColor
                next.onPlaybackEnded = { event(id, PlaybackEvent.Type.COMPLETED); onPlaybackEndedCallback?.invoke() }
                next.onRestart = { onRestartCallback?.invoke() }
                delegate = next
                if (next is DashVideoPlayerState) next.openSource(source, initializeplayerState)
                else {
                    next.availableSubtitleTracks.addAll(source.subtitles)
                    source.subtitles.firstOrNull()?.let(next::selectSubtitleTrack)
                    next.openUri(source.uri, initializeplayerState)
                }
                routingLoading = false
                var ready = false
                var playing: Boolean? = null
                while (isActive && id == generation.get()) {
                    next.error?.let { event(id, PlaybackEvent.Type.FAILED, it); return@launch }
                    if (!ready && next.hasMedia) { ready = true; event(id, PlaybackEvent.Type.READY) }
                    if (ready && playing != next.isPlaying) {
                        playing = next.isPlaying
                        event(id, if (playing) PlaybackEvent.Type.PLAYING else PlaybackEvent.Type.PAUSED)
                    }
                    delay(20)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Throwable) {
                if (id == generation.get()) {
                    routingError = VideoPlayerError.SourceError("Unable to open the configured media source.")
                    routingLoading = false
                    event(id, PlaybackEvent.Type.FAILED, routingError)
                }
            }
        }
    }

    override val hasMedia: Boolean get() = delegate.hasMedia
    override val isPlaying: Boolean get() = delegate.isPlaying
    override val isLoading: Boolean get() = routingLoading || delegate.isLoading
    override val error: VideoPlayerError? get() = routingError ?: delegate.error
    override var volume: Float
        get() = delegate.volume
        set(value) {
            delegate.volume = value
        }
    override var sliderPos: Float
        get() = delegate.sliderPos
        set(value) {
            delegate.sliderPos = value
        }
    override var userDragging: Boolean
        get() = delegate.userDragging
        set(value) {
            delegate.userDragging = value
        }
    override var loop: Boolean
        get() = delegate.loop
        set(value) {
            delegate.loop = value
        }

    override var playbackSpeed: Float
        get() = delegate.playbackSpeed
        set(value) {
            delegate.playbackSpeed = value
        }

    override var isFullscreen: Boolean
        get() = delegate.isFullscreen
        set(value) {
            delegate.isFullscreen = value
        }

    override val metadata: VideoMetadata get() = delegate.metadata
    override val aspectRatio: Float get() = delegate.aspectRatio

    override var subtitlesEnabled: Boolean
        get() = delegate.subtitlesEnabled
        set(value) { delegate.subtitlesEnabled = value }
    override var currentSubtitleTrack: SubtitleTrack?
        get() = delegate.currentSubtitleTrack
        set(value) { delegate.currentSubtitleTrack = value }
    override val availableSubtitleTracks get() = delegate.availableSubtitleTracks
    override var subtitleTextStyle: TextStyle
        get() = delegate.subtitleTextStyle
        set(value) {
            delegate.subtitleTextStyle = value
        }
    override var subtitleBackgroundColor: Color
        get() = delegate.subtitleBackgroundColor
        set(value) {
            delegate.subtitleBackgroundColor = value
        }

    override fun selectSubtitleTrack(track: SubtitleTrack?) = delegate.selectSubtitleTrack(track)

    override fun disableSubtitles() = delegate.disableSubtitles()

    override val positionText: String get() = delegate.positionText
    override val durationText: String get() = delegate.durationText
    override val currentTime: Double get() = delegate.currentTime
    override val duration: Double get() = delegate.duration

    // Must be forwarded explicitly: these members have interface defaults, so omitting them here
    // would report "no buffering support" instead of asking the platform delegate.
    override val isBufferedRangeSupported: Boolean get() = delegate.isBufferedRangeSupported
    override val bufferedRanges: List<BufferedRange> get() = delegate.bufferedRanges
    override val bufferedPercentage: Float get() = delegate.bufferedPercentage

    override fun openUri(
        uri: String,
        initializeplayerState: InitialPlayerState,
    ) = openSource(MediaSource(uri), initializeplayerState)

    override fun openFile(
        file: PlatformFile,
        initializeplayerState: InitialPlayerState,
    ) = openSource(MediaSource(file.file.toURI().toString()), initializeplayerState)

    override fun play() {
        if (!delegate.hasMedia && lastSource != null) openSource(lastSource!!, InitialPlayerState.PLAY) else delegate.play()
    }

    override fun pause() = delegate.pause()

    override fun stop() { val id = generation.getAndIncrement(); opening?.cancel(); routingLoading = false; delegate.stop(); event(id, PlaybackEvent.Type.STOPPED) }

    override fun seekTo(value: Float) = delegate.seekTo(value)

    override fun toggleFullscreen() = delegate.toggleFullscreen()

    override fun dispose() {
        if (disposed) return
        disposed = true
        opening?.cancel()
        scope.launch { delegate.dispose(); event(generation.get(), PlaybackEvent.Type.DISPOSED); lifecycleQueue.close(); lifecycleWriter.join(); scope.cancel() }
    }

    private var onPlaybackEndedCallback: (() -> Unit)? = null
    private var onRestartCallback: (() -> Unit)? = null
    override var onPlaybackEnded: (() -> Unit)?
        get() = onPlaybackEndedCallback
        set(value) { onPlaybackEndedCallback = value }
    override var onRestart: (() -> Unit)?
        get() = onRestartCallback
        set(value) { onRestartCallback = value }

    override fun restart() = delegate.restart()

    override fun clearError() { routingError = null; delegate.clearError() }
}
