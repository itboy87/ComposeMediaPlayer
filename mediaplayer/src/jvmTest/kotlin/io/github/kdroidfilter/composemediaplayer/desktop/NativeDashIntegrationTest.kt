package io.github.kdroidfilter.composemediaplayer.desktop

import com.sun.net.httpserver.HttpServer
import androidx.compose.ui.graphics.toPixelMap
import io.github.kdroidfilter.composemediaplayer.*
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlin.test.*

class NativeDashIntegrationTest {
    @Test fun separateVideoAudioDecodeSeekPauseAndRelease() = runBlocking {
        assumeTrue(System.getenv("COMPOSE_MEDIA_PLAYER_RUN_DASH_TEST") == "1")
        NativeMediaBridge.runtimeDirectory
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        val rejected = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val representations = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        server.executor = executor
        server.createContext("/") { request ->
            val name = request.requestURI.path.removePrefix("/")
            Regex("chunk-stream([01])-").find(name)?.groupValues?.get(1)?.let { representations.add(it) }
            val resourceName = if (name == "proxy") "manifest.mpd" else name
            var data = javaClass.getResourceAsStream("/dash/$resourceName")?.use { it.readBytes() }
            if (resourceName.endsWith("mpd") && data != null) data = data.toString(Charsets.UTF_8).replace(".m4s\"", ".m4s?sig=a%2Bb%2F%3D\"").encodeToByteArray()
            if (request.requestHeaders.getFirst("Authorization") != "fixture" || (name.endsWith("m4s") && request.requestURI.rawQuery != "sig=a%2Bb%2F%3D")) {
                rejected.add("$name query=${request.requestURI.rawQuery} authenticated=${request.requestHeaders.getFirst("Authorization") == "fixture"}")
                request.sendResponseHeaders(403,-1); request.close(); return@createContext
            }
            if (data == null) request.sendResponseHeaders(404, -1)
            else {
                request.responseHeaders.set("Content-Type", if (name.endsWith("mpd")) "application/dash+xml" else "application/octet-stream")
                request.sendResponseHeaders(200, data.size.toLong()); request.responseBody.use { it.write(data) }
            }
            request.close()
        }
        server.start()
        val player = DashVideoPlayerState(null, testAudio = true)
        try {
            player.openSource(MediaSource("http://127.0.0.1:${server.address.port}/proxy?token=signed%2Bvalue", headers = mapOf("Authorization" to "fixture")))
            withTimeout(60_000) {
                while (player.currentTime < 1.0) { assertNull(player.error, rejected.toList().toString()); delay(30) }
            }
            assertNotNull(player.currentFrame)
            val first = player.diagnostics()!!
            assertTrue(first[7] > 1 && first[8] > 1000 && first[9] > 0.01, first.toList().toString())
            assertTrue(first[14] > 3)
            val firstPixels = player.frameChecksum()
            delay(300)
            assertNotEquals(firstPixels, player.frameChecksum())
            player.pause(); delay(250)
            val paused = player.currentTime; delay(300)
            assertTrue(kotlin.math.abs(player.currentTime - paused) < 0.1)
            player.seekTo(500f)
            withTimeout(5000) { while (player.currentTime < 5.0) delay(30) }
            player.play()
            withTimeout(5000) { while (player.currentTime < 7.0) { assertNull(player.error, rejected.toList().toString()); delay(30) } }
            val completed = java.util.concurrent.atomic.AtomicBoolean(false)
            player.onPlaybackEnded = { completed.set(true) }
            player.seekTo(950f)
            withTimeout(5000) { while (!completed.get()) { assertNull(player.error, rejected.toList().toString()); delay(30) } }
            assertEquals(setOf("0", "1"), representations, "The adaptive pipeline must request both video representations.")
        } finally {
            player.dispose(); withTimeout(10_000) { player.awaitDisposed() }
            server.stop(0); executor.shutdownNow()
        }
        assertEquals(0, NativeMediaBridge.nLivePlayers())
    }

    @Test fun assProducesPositionedStyledPixels() {
        assumeTrue(System.getenv("COMPOSE_MEDIA_PLAYER_RUN_DASH_TEST") == "1")
        NativeMediaBridge.runtimeDirectory
        val root = NativeMediaBridge.runtimeDirectory
        val ass = """
            [Script Info]
            ScriptType: v4.00+
            PlayResX: 320
            PlayResY: 180
            [V4+ Styles]
            Format: Name,Fontname,Fontsize,PrimaryColour,SecondaryColour,OutlineColour,BackColour,Bold,Italic,Underline,StrikeOut,ScaleX,ScaleY,Spacing,Angle,BorderStyle,Outline,Shadow,Alignment,MarginL,MarginR,MarginV,Encoding
            Style: Default,Noto Sans,24,&H0000FFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,1,0,2,10,10,10,1
            [Events]
            Format: Layer,Start,End,Style,Name,MarginL,MarginR,MarginV,Effect,Text
            Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,{\pos(160,90)}Hello
        """.trimIndent().encodeToByteArray()
        val handle = NativeMediaBridge.nAssCreate(ass, java.io.File(root,"fonts/NotoSans.ttf").path)
        assertNotEquals(0, handle)
        try {
            assertTrue(NativeMediaBridge.nAssRender(handle, 0, 320, 180)!!.all { it == 0.toByte() })
            assertTrue(NativeMediaBridge.nAssRender(handle, 1500, 320, 180)!!.any { it != 0.toByte() })
            assertTrue(NativeMediaBridge.nAssRender(handle, 4000, 320, 180)!!.all { it == 0.toByte() })
        } finally { NativeMediaBridge.nAssClose(handle) }
    }
}

class NativeLifecycleIntegrationTest {
    @Test fun failedNetworkStartupReleasesNativePipeline() = runBlocking {
        assumeTrue(System.getenv("COMPOSE_MEDIA_PLAYER_RUN_DASH_TEST") == "1")
        NativeMediaBridge.runtimeDirectory
        val port=java.net.ServerSocket(0).use { it.localPort }
        val player=DashVideoPlayerState(null,true)
        try {
            player.openSource(MediaSource("http://127.0.0.1:$port/missing.mpd",startupTimeoutMillis=2000))
            withTimeout(5000) { while(player.error==null) delay(20) }
            assertTrue(player.error is VideoPlayerError.NetworkError, player.error.toString())
            withTimeout(5000) { while(NativeMediaBridge.nLivePlayers()!=0) delay(20) }
        } finally { player.dispose();withTimeout(5000) { player.awaitDisposed() } }
    }

    @Test fun cancelledStartupReleasesNativePipeline() = runBlocking {
        assumeTrue(System.getenv("COMPOSE_MEDIA_PLAYER_RUN_DASH_TEST") == "1")
        NativeMediaBridge.runtimeDirectory
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        val executor=Executors.newCachedThreadPool()
        server.executor=executor
        server.createContext("/") { request -> try { Thread.sleep(10_000); request.sendResponseHeaders(503,-1) } catch (_: Exception) {} finally { request.close() } }
        server.start()
        val player=DashVideoPlayerState(null,true)
        try {
            player.openSource(MediaSource("http://127.0.0.1:${server.address.port}/stall.mpd"))
            delay(150)
            player.dispose()
            withTimeout(5000) { player.awaitDisposed() }
            assertEquals(0,NativeMediaBridge.nLivePlayers())
        } finally { player.dispose();server.stop(0);executor.shutdownNow() }
    }

    @Test fun mp4AndHlsUseExistingBackendAcrossSourceReplacement() = runBlocking {
        assumeTrue(System.getenv("COMPOSE_MEDIA_PLAYER_RUN_DASH_TEST") == "1")
        NativeMediaBridge.runtimeDirectory
        assumeTrue(System.getProperty("os.name").let { it.startsWith("Mac") || it.startsWith("Windows") })
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        val executor=Executors.newCachedThreadPool();server.executor=executor
        server.createContext("/") { request ->
            try {
                val name=request.requestURI.path.removePrefix("/")
                val bytes=javaClass.getResourceAsStream("/dash/$name")?.use { it.readBytes() }
                if (bytes==null) request.sendResponseHeaders(404,-1) else {
                    val range=request.requestHeaders.getFirst("Range")?.removePrefix("bytes=")?.split('-')
                    val start=range?.getOrNull(0)?.toIntOrNull() ?: 0
                    val end=(range?.getOrNull(1)?.toIntOrNull() ?: bytes.lastIndex).coerceAtMost(bytes.lastIndex)
                    request.responseHeaders.set("Content-Type", when { name.endsWith("m3u8")->"application/vnd.apple.mpegurl"; name.endsWith("ts")->"video/mp2t";else->"video/mp4" })
                    request.responseHeaders.set("Accept-Ranges","bytes")
                    if(range!=null)request.responseHeaders.set("Content-Range","bytes $start-$end/${bytes.size}")
                    request.sendResponseHeaders(if(range==null)200 else 206,(end-start+1).toLong())
                    request.responseBody.use { it.write(bytes,start,end-start+1) }
                }
            } finally { request.close() }
        }
        server.start()
        val player=DefaultVideoPlayerState();player.volume=0.01f
        try {
            var previous: VideoPlayerState?=null
            var previousFrame: OwnedVideoFrame?=null
            for(name in listOf("regression.mp4","regression.m3u8")) {
                player.openUri("http://127.0.0.1:${server.address.port}/$name")
                withTimeout(20_000) { while(player.delegate===previous || !player.hasMedia || player.currentTime<0.3) { assertNull(player.error);delay(30) } }
                val backend=player.delegate
                when (backend) {
                    is io.github.kdroidfilter.composemediaplayer.mac.MacVideoPlayerState -> assertNotNull(backend.currentFrameState.value)
                    is io.github.kdroidfilter.composemediaplayer.windows.WindowsVideoPlayerState -> assertNotNull(backend.currentFrameState.value)
                    else -> fail("Expected an existing platform backend.")
                }
                previous?.let { assertFalse(it.hasMedia) }
                previousFrame?.let { assertFalse(it.draw { fail("A retired source frame must not be drawn.") }) }
                previousFrame=when(backend) {
                    is io.github.kdroidfilter.composemediaplayer.mac.MacVideoPlayerState -> backend.currentFrameState.value
                    is io.github.kdroidfilter.composemediaplayer.windows.WindowsVideoPlayerState -> backend.currentFrameState.value
                    else -> null
                }
                previous=backend
            }
        } finally { player.dispose();server.stop(0);executor.shutdownNow() }
    }
}
