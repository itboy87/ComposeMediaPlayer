package io.github.kdroidfilter.composemediaplayer.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.TextStyle
import io.github.kdroidfilter.composemediaplayer.*
import io.github.kdroidfilter.composemediaplayer.util.formatTime
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.*
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal fun ownedBgraImage(bytes: ByteArray, width: Int, height: Int, alpha: ColorAlphaType = ColorAlphaType.OPAQUE): OwnedVideoFrame =
    OwnedVideoFrame(Image.makeRaster(ImageInfo(width, height, ColorType.BGRA_8888, alpha), bytes, width * 4))

internal class DashVideoPlayerState(
    private val widevineRuntime: WidevineRuntime?,
    private val testAudio: Boolean = false,
) : VideoPlayerState {
    private val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "cmp-native-playback").apply { isDaemon = true } }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val handle = AtomicLong(0)
    private val disposed = AtomicBoolean(false)
    private val cleanup = CompletableDeferred<Unit>()
    private var playbackJob: Job? = null
    private var gateway: DashMediaGateway? = null
    private var licenseHttp = MediaHttp()
    private var ended = false
    @Volatile private var requestedPlaying = false
    internal var currentFrame by mutableStateOf<OwnedVideoFrame?>(null)
        private set
    private fun publishFrame(frame: OwnedVideoFrame?) { val old = currentFrame; currentFrame = frame; old?.close() }
    override var hasMedia by mutableStateOf(false)
        private set
    override var isPlaying by mutableStateOf(false)
        private set
    override var isLoading by mutableStateOf(false)
        private set
    override var error: VideoPlayerError? by mutableStateOf(null)
        private set
    override var currentTime by mutableStateOf(0.0)
        private set
    override var duration by mutableStateOf(0.0)
        private set
    override var aspectRatio by mutableStateOf(16f / 9)
        private set
    override var volume by mutableStateOf(1f)
    override var playbackSpeed by mutableStateOf(1f)
    override var loop by mutableStateOf(false)
    override var userDragging by mutableStateOf(false)
    override var sliderPos: Float
        get() = if (duration > 0) (currentTime / duration * 1000).toFloat() else 0f
        set(value) { currentTime = duration * value.coerceIn(0f, 1000f) / 1000 }
    override val positionText get() = formatTime(currentTime)
    override val durationText get() = formatTime(duration)
    override var isFullscreen by mutableStateOf(false)
    override val metadata = VideoMetadata()
    override var bufferedRanges by mutableStateOf<List<BufferedRange>>(emptyList())
        private set
    override var isBufferedRangeSupported by mutableStateOf(false)
        private set
    override val bufferedPercentage get() = bufferedRanges.bufferedPercentageAt(currentTime, duration)
    override var onPlaybackEnded: (() -> Unit)? = null
    override var onRestart: (() -> Unit)? = null
    override var subtitlesEnabled by mutableStateOf(false)
    override var currentSubtitleTrack: SubtitleTrack? by mutableStateOf(null)
    override val availableSubtitleTracks = mutableListOf<SubtitleTrack>()
    override var subtitleTextStyle by mutableStateOf(TextStyle.Default)
    override var subtitleBackgroundColor by mutableStateOf(Color.Transparent)

    override fun openUri(uri: String, initializeplayerState: InitialPlayerState) = openSource(MediaSource(uri), initializeplayerState)
    override fun openFile(file: PlatformFile, initializeplayerState: InitialPlayerState) = openUri(file.file.toURI().toString(), initializeplayerState)
    override fun openSource(source: MediaSource, initializeplayerState: InitialPlayerState) {
        check(!disposed.get()) { "Player is disposed." }
        check(playbackJob == null) { "A DASH backend owns exactly one source. Replace it through the player facade." }
        isLoading = true
        requestedPlaying = initializeplayerState == InitialPlayerState.PLAY
        availableSubtitleTracks.addAll(source.subtitles)
        source.subtitles.firstOrNull()?.let(::selectSubtitleTrack)
        playbackJob = scope.launch {
            try {
                NativeMediaBridge.runtimeDirectory
                ensureActive()
                if (disposed.get()) return@launch
                if (source.widevine != null && (widevineRuntime == null || !File(widevineRuntime.libraryPath).isFile)) {
                    error = VideoPlayerError.DrmError(VideoPlayerError.DrmError.Reason.CLIENT_MISSING, "Configure a compatible app-supplied Widevine CDM.")
                    return@launch
                }
                val uri = URI(source.uri)
                val mediaUri = if (uri.scheme in listOf("http", "https")) {
                    DashMediaGateway(source).also { gateway = it }.openManifest()
                } else if (uri.scheme == null) File(source.uri).toURI().toString() else source.uri
                ensureActive()
                val ptr = NativeMediaBridge.nCreate(mediaUri, gateway?.userAgent ?: "ComposeMediaPlayer", source.widevine?.let { widevineRuntime?.libraryPath }, source.widevine?.serviceCertificate, testAudio, source.preferredAudioLanguage)
                check(ptr != 0L) { "Native media pipeline could not be created." }
                handle.set(ptr)
                if (disposed.get()) { NativeMediaBridge.nCancel(ptr); return@launch }
                var sequence = -1L
                val frameInfo = LongArray(7)
                val deadline = System.nanoTime() + source.startupTimeoutMillis * 1_000_000
                var lastVolume = -1f
                var lastRate = 1f
                var begun = false
                val licenseJobs = mutableListOf<Job>()
                while (isActive && !disposed.get()) {
                    if (volume != lastVolume) { lastVolume = volume.coerceIn(0f, 1f); NativeMediaBridge.nSetVolume(ptr, lastVolume.toDouble()) }
                    if (playbackSpeed != lastRate && hasMedia) {
                        lastRate = playbackSpeed.coerceIn(VideoPlayerState.MIN_PLAYBACK_SPEED, VideoPlayerState.MAX_PLAYBACK_SPEED)
                        NativeMediaBridge.nSeek(ptr, currentTime, lastRate.toDouble())
                    }
                    while (true) {
                        val message = NativeMediaBridge.nPollLicense(ptr) ?: break
                        val config = source.widevine ?: error("Missing Widevine configuration.")
                        val buffer = ByteBuffer.wrap(message)
                        val token = buffer.int
                        val renewal = buffer.int == 1
                        val challenge = message.copyOfRange(8, message.size)
                        licenseJobs.removeAll { it.isCompleted }
                        licenseJobs += launch(Dispatchers.IO) {
                            try {
                                val request = WidevineLicenseRequest(config.licenseUrl, config.licenseHeaders, challenge, renewal)
                                val response = withTimeout(source.startupTimeoutMillis) {
                                    config.transport?.execute(request) ?: licenseHttp.open(
                                        config.licenseUrl, mapOf("Content-Type" to "application/octet-stream") + config.licenseHeaders,
                                        method = "POST", body = challenge,
                                    ).use { it.bytes() }
                                }
                                require(response.isNotEmpty() && response.size <= 4 * 1024 * 1024)
                                withContext(dispatcher) { if (!disposed.get() && handle.get() == ptr) NativeMediaBridge.nUpdateLicense(ptr, token, response) }
                            } catch (cancel: CancellationException) { throw cancel }
                            catch (_: Exception) { withContext(dispatcher) { if (handle.get() == ptr) NativeMediaBridge.nLicenseFailed(ptr) } }
                        }
                    }
                    val snapshot = NativeMediaBridge.nSnapshot(ptr) ?: error("Native player closed.")
                    val code = snapshot[3].toInt()
                    if (code != 0) { error = if (gateway?.networkFailure == true && code < 10) VideoPlayerError.NetworkError("Media request failed.") else nativeError(code); break }
                    currentTime = snapshot[0]
                    duration = snapshot[1]
                    if (duration > 0) metadata.duration = (duration * 1000).toLong()
                    metadata.audioChannels = snapshot[10].toInt().takeIf { it > 0 }
                    metadata.audioSampleRate = snapshot[11].toInt().takeIf { it > 0 }
                    NativeMediaBridge.nReadFrame(ptr, sequence, frameInfo)?.let { pixels ->
                        sequence = frameInfo[3]
                        val width = frameInfo[0].toInt(); val height = frameInfo[1].toInt()
                        publishFrame(ownedBgraImage(pixels, width, height))
                        metadata.width = width; metadata.height = height
                        metadata.mimeType = source.mimeType?.substringBefore(';') ?: gateway?.detectedMime ?: when {
                            uri.path.orEmpty().endsWith(".mp4", true) -> "video/mp4"
                            uri.path.orEmpty().endsWith(".m3u8", true) -> "application/vnd.apple.mpegurl"
                            uri.path.orEmpty().endsWith(".mpd", true) || source.widevine != null -> "application/dash+xml"
                            else -> null
                        }
                        aspectRatio = width.toFloat() * frameInfo[5].coerceAtLeast(1) / (height * frameInfo[6].coerceAtLeast(1)).toFloat()
                        hasMedia = true
                    }
                    if (!begun && hasMedia) {
                        begun = true; isLoading = false
                        NativeMediaBridge.nSetPlaying(ptr, requestedPlaying)
                    }
                    if (!begun && System.nanoTime() >= deadline) { error = VideoPlayerError.NetworkError("Media startup timed out."); break }
                    isPlaying = snapshot[2] == 1.0
                    val rawRanges = NativeMediaBridge.nBufferedRanges(ptr)
                    isBufferedRangeSupported = rawRanges != null
                    bufferedRanges = normalizeBufferedRanges(rawRanges?.toList()?.chunked(2)?.filter { it.size == 2 }?.map { BufferedRange(it[0], it[1]) }.orEmpty(), duration)
                    if (snapshot[4] == 1.0 && !ended) {
                        ended = true; isPlaying = false
                        if (loop) { NativeMediaBridge.nSeek(ptr, 0.0, playbackSpeed.toDouble()); NativeMediaBridge.nSetPlaying(ptr, true); ended = false; onRestart?.invoke() }
                        else onPlaybackEnded?.invoke()
                    }
                    delay(16)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Throwable) { error = VideoPlayerError.SourceError("Unable to initialize the bundled media runtime or source.") }
            finally { isLoading = false; if (error != null || disposed.get() || !isActive) {
                isPlaying = false; hasMedia = false
                coroutineContext.cancelChildren()
                gateway?.close(); licenseHttp.close()
                handle.getAndSet(0).takeIf { it != 0L }?.let { NativeMediaBridge.nCancel(it); NativeMediaBridge.nClose(it) }
            } }
        }
    }

    private fun nativeError(code: Int): VideoPlayerError = when (code) {
        2 -> VideoPlayerError.NetworkError("Media request failed.")
        10 -> VideoPlayerError.DrmError(VideoPlayerError.DrmError.Reason.CLIENT_MISSING, "Widevine client is missing.")
        11 -> VideoPlayerError.DrmError(VideoPlayerError.DrmError.Reason.CLIENT_INCOMPATIBLE, "Widevine client could not initialize.")
        12 -> VideoPlayerError.DrmError(VideoPlayerError.DrmError.Reason.LICENSE_REJECTED, "Widevine license request was rejected or timed out.")
        13 -> VideoPlayerError.DrmError(VideoPlayerError.DrmError.Reason.LICENSE_EXPIRED, "Widevine license expired.")
        14 -> VideoPlayerError.DrmError(VideoPlayerError.DrmError.Reason.UNSUPPORTED_PROTECTION, "This license requires unsupported protection.")
        15 -> VideoPlayerError.DrmError(VideoPlayerError.DrmError.Reason.DECRYPTION_FAILED, "Widevine sample decryption failed.")
        else -> VideoPlayerError.CodecError("Native media decoding failed.")
    }
    override fun play() { requestedPlaying = true; scope.launch { handle.get().takeIf { it != 0L }?.let { if (ended) { NativeMediaBridge.nSeek(it, 0.0, playbackSpeed.toDouble()); ended = false }; NativeMediaBridge.nSetPlaying(it, true) } } }
    override fun pause() { requestedPlaying = false; scope.launch { handle.get().takeIf { it != 0L }?.let { NativeMediaBridge.nSetPlaying(it, false) } } }
    override fun stop() { dispose() }
    override fun seekTo(value: Float) { scope.launch { handle.get().takeIf { it != 0L && duration > 0 }?.let { ended = false; NativeMediaBridge.nSeek(it, duration * value.coerceIn(0f, 1000f) / 1000, playbackSpeed.toDouble()) } } }
    override fun toggleFullscreen() { isFullscreen = !isFullscreen }
    override fun selectSubtitleTrack(track: SubtitleTrack?) { currentSubtitleTrack = track; subtitlesEnabled = track != null }
    override fun disableSubtitles() { subtitlesEnabled = false; currentSubtitleTrack = null }
    override fun clearError() { error = null }
    internal suspend fun frameChecksum(): Int? = withContext(dispatcher) { handle.get().takeIf { it != 0L }?.let { NativeMediaBridge.nReadFrame(it, -1, LongArray(7))?.contentHashCode() } }
    internal suspend fun diagnostics(): DoubleArray? = withContext(dispatcher) { handle.get().takeIf { it != 0L }?.let(NativeMediaBridge::nSnapshot) }
    internal suspend fun awaitDisposed() = cleanup.await()
    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        handle.get().takeIf { it != 0L }?.let(NativeMediaBridge::nCancel)
        playbackJob?.cancel()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                gateway?.close(); licenseHttp.close()
                withContext(dispatcher) {
                    playbackJob?.join()
                    handle.getAndSet(0).takeIf { it != 0L }?.let(NativeMediaBridge::nClose)
                    publishFrame(null); isPlaying = false; hasMedia = false; bufferedRanges = emptyList()
                }
            } finally { scope.cancel(); dispatcher.close(); cleanup.complete(Unit) }
        }
    }
}
