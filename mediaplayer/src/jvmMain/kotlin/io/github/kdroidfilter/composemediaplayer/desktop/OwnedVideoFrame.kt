package io.github.kdroidfilter.composemediaplayer.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

/** A bounded native image owner. UI borrowing and retirement cannot race a native draw. */
internal class OwnedVideoFrame(private val image: Image) : AutoCloseable {
    private val lock = Any()
    private val bitmap = image.toComposeImageBitmap()
    private var closed = false

    fun draw(block: (ImageBitmap) -> Unit): Boolean = synchronized(lock) {
        if (closed) false else { block(bitmap); true }
    }

    override fun close() = synchronized(lock) {
        if (!closed) { closed = true; image.close() }
    }
}
