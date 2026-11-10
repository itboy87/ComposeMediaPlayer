package io.github.kdroidfilter.composemediaplayer.subtitle

import io.github.kdroidfilter.composemediaplayer.desktop.MediaHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI

actual suspend fun loadSubtitleContent(src: String): String = loadSubtitleContent(src, emptyMap())

/** Subtitle credentials are independent of media credentials and scoped to their own origin. */
actual suspend fun loadSubtitleContent(src: String, headers: Map<String, String>): String = withContext(Dispatchers.IO) {
    when {
        src.startsWith("http://") || src.startsWith("https://") -> MediaHttp().use { http ->
            http.open(src, headers).use { it.bytes().toString(Charsets.UTF_8) }
        }
        src.startsWith("file://") -> File(URI(src)).readText()
        else -> File(src).readText()
    }
}
