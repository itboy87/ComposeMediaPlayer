package io.github.kdroidfilter.composemediaplayer.mac

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real AVFoundation regression with a slow, deterministic local HTTP source. */
class MacMetadataStartupNativeTest {
    @Test
    fun `cold MP4 metadata arriving after both old startup windows plays without recreating the player`() =
        verifySlowStartup("video.mp4")

    @Test
    fun `opaque source with unknown MIME waits for cold metadata without recreating the player`() =
        verifySlowStartup("getvid")

    private fun verifySlowStartup(path: String) = runBlocking {
        assumeTrue(System.getenv("COMPOSE_MEDIA_PLAYER_RUN_NATIVE_STARTUP_TEST") == "1")
        assumeTrue(System.getProperty("os.name").startsWith("Mac", ignoreCase = true))
        val media = checkNotNull(javaClass.getResourceAsStream("/startup/metadata-startup.mp4")).use { it.readBytes() }
        val firstRequestAt = AtomicLong(0L)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        server.executor = executor
        server.createContext("/$path") { exchange ->
            try {
                firstRequestAt.compareAndSet(0L, System.nanoTime())
                // Hold all requests for 16 seconds. Previously the first player and its single
                // replacement both exhausted their ~7-second windows before this valid MP4 arrived.
                while (System.nanoTime() - firstRequestAt.get() < METADATA_DELAY_NANOS) Thread.sleep(50)
                val range = exchange.requestHeaders.getFirst("Range")
                val bounds = range?.removePrefix("bytes=")?.split("-")
                val start = bounds?.getOrNull(0)?.toIntOrNull() ?: 0
                val end = (bounds?.getOrNull(1)?.toIntOrNull() ?: media.lastIndex).coerceAtMost(media.lastIndex)
                exchange.responseHeaders.set("Content-Type", "video/mp4")
                exchange.responseHeaders.set("Accept-Ranges", "bytes")
                if (range != null) exchange.responseHeaders.set("Content-Range", "bytes $start-$end/${media.size}")
                exchange.sendResponseHeaders(if (range == null) 200 else 206, (end - start + 1).toLong())
                exchange.responseBody.use { it.write(media, start, end - start + 1) }
            } finally {
                exchange.close()
            }
        }
        server.start()
        val player = MacVideoPlayerState()
        try {
            player.openUri("http://127.0.0.1:${server.address.port}/$path")
            withTimeout(40_000L) {
                while (!player.hasMedia || player.currentTime < 0.5) {
                    assertNull(player.error)
                    delay(50)
                }
            }
            assertTrue(player.hasMedia)
            assertTrue(player.isPlaying)
            assertTrue(player.duration > 0)
            assertTrue(System.nanoTime() - firstRequestAt.get() >= METADATA_DELAY_NANOS)
        } finally {
            player.dispose()
            server.stop(0)
            executor.shutdownNow()
        }
    }
}

private const val METADATA_DELAY_NANOS = 16_000_000_000L
