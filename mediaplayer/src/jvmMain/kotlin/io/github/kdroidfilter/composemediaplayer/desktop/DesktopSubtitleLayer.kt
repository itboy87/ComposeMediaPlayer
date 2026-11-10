package io.github.kdroidfilter.composemediaplayer.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntSize
import io.github.kdroidfilter.composemediaplayer.SubtitleTrack
import io.github.kdroidfilter.composemediaplayer.subtitle.ComposeSubtitleLayer
import kotlinx.coroutines.*
import org.jetbrains.skia.ColorAlphaType
import java.io.File
import java.net.URI

@Composable
internal fun DesktopSubtitleLayer(
    currentTimeMs: Long, durationMs: Long, isPlaying: Boolean, subtitleTrack: SubtitleTrack?, subtitlesEnabled: Boolean,
    modifier: Modifier = Modifier, textStyle: TextStyle = TextStyle.Default, backgroundColor: Color = Color.Transparent,
) {
    val format = subtitleTrack?.format?.lowercase() ?: subtitleTrack?.src?.substringBefore('?')?.substringAfterLast('.')?.lowercase()
    if (format !in setOf("ass", "ssa")) {
        ComposeSubtitleLayer(currentTimeMs, durationMs, isPlaying, subtitleTrack, subtitlesEnabled, modifier, textStyle, backgroundColor)
        return
    }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var frame by remember(subtitleTrack, size, subtitlesEnabled) { mutableStateOf<OwnedVideoFrame?>(null) }
    val time by rememberUpdatedState(currentTimeMs)
    val playing by rememberUpdatedState(isPlaying)
    LaunchedEffect(subtitleTrack, subtitlesEnabled, size) {
        frame = null
        if (!subtitlesEnabled || subtitleTrack == null || size.width <= 0 || size.height <= 0) return@LaunchedEffect
        val http = MediaHttp()
        val job = currentCoroutineContext().job
        val cancellation = job.invokeOnCompletion { http.close() }
        var handle = 0L
        try {
            withContext(Dispatchers.IO) {
                NativeMediaBridge.runtimeDirectory
                val uri = URI(subtitleTrack.src)
                val content = if (uri.scheme in listOf("http", "https")) http.open(subtitleTrack.src, subtitleTrack.headers).use { it.bytes() }
                else File(if (uri.scheme == "file") uri else File(subtitleTrack.src).toURI()).readBytes()
                handle = NativeMediaBridge.nAssCreate(content, File(NativeMediaBridge.runtimeDirectory, "fonts/NotoSans.ttf").path)
                check(handle != 0L) { "Invalid ASS subtitle." }
            }
            var baseTime = time
            var baseNanos = System.nanoTime()
            withContext(Dispatchers.Default) { while (isActive) {
                if (time != baseTime) { baseTime = time; baseNanos = System.nanoTime() }
                val position = baseTime + if (playing) ((System.nanoTime() - baseNanos) / 1_000_000).coerceAtMost(250) else 0
                val pixels = NativeMediaBridge.nAssRender(handle, position, size.width, size.height)
                val old = frame
                frame = pixels?.let { ownedBgraImage(it, size.width, size.height, ColorAlphaType.PREMUL) }
                old?.close()
                delay(33)
            } }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { frame?.close(); frame = null }
        finally { http.close(); cancellation.dispose(); withContext(NonCancellable + Dispatchers.Default) { frame?.close(); frame = null; if (handle != 0L) NativeMediaBridge.nAssClose(handle) } }
    }
    Canvas(modifier.fillMaxSize().onSizeChanged { size = it }) { frame?.draw { drawImage(it) } }
}
