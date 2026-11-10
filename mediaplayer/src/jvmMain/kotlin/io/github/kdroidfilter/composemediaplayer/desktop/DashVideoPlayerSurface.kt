package io.github.kdroidfilter.composemediaplayer.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import io.github.kdroidfilter.composemediaplayer.common.openFullscreenWindow
import io.github.kdroidfilter.composemediaplayer.util.drawScaledImage
import io.github.kdroidfilter.composemediaplayer.util.toCanvasModifier

@Composable
internal fun DashVideoPlayerSurface(state: DashVideoPlayerState, modifier: Modifier, contentScale: ContentScale, overlay: @Composable () -> Unit, fullscreen: Boolean = false) {
    Box(modifier, contentAlignment = Alignment.Center) {
        if (!state.isFullscreen || fullscreen) {
            Box(contentScale.toCanvasModifier(state.aspectRatio, state.metadata.width, state.metadata.height)) {
                state.currentFrame?.let { frame -> Canvas(Modifier.fillMaxSize()) { frame.draw { drawScaledImage(it, IntSize(size.width.toInt(), size.height.toInt()), contentScale) } } }
                DesktopSubtitleLayer((state.currentTime * 1000).toLong(), (state.duration * 1000).toLong(), state.isPlaying, state.currentSubtitleTrack, state.subtitlesEnabled)
            }
        }
        Box(Modifier.fillMaxSize()) { overlay() }
    }
    if (state.isFullscreen && !fullscreen) openFullscreenWindow(state) { _, windowModifier, _ -> DashVideoPlayerSurface(state, windowModifier, contentScale, overlay, true) }
}
