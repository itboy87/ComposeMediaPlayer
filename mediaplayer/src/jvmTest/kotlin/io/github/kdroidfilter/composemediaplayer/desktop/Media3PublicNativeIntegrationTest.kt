package io.github.kdroidfilter.composemediaplayer.desktop

import io.github.kdroidfilter.composemediaplayer.*
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Online coverage is separate from deterministic tests; upstream outages must fail this suite. */
class Media3PublicNativeIntegrationTest {
    private val samples = Properties().apply { checkNotNull(Media3PublicNativeIntegrationTest::class.java.getResourceAsStream("/dash/media3-public-samples.properties")).use { load(it) } }
    private fun sample(key: String) = checkNotNull(samples.getProperty(key))
    private fun enabled() {
        assumeTrue("Enable public network samples explicitly", System.getenv("COMPOSE_MEDIA_PLAYER_RUN_PUBLIC_TEST") == "1")
        NativeMediaBridge.runtimeDirectory
    }
    @Test fun clearH264DecodesVideoAndAudio() = playback("clear.h264")
    @Test fun multipleBaseUrlsDecodeVideoAndAudio() = playback("clear.multibase")
    @Test fun widevineCencDecodesVideoAndAudio() = playback("widevine.cenc", true)
    @Test fun widevineCbcsDecodesVideoAndAudio() = playback("widevine.cbcs", true)
    @Test fun shortLicenseRenewsDuringPlayback() = playback("widevine.cenc", true, true)

    private fun playback(key: String, encrypted: Boolean = false, renewal: Boolean = false) = runBlocking {
        enabled()
        val cdm = System.getenv("COMPOSE_MEDIA_PLAYER_WIDEVINE_CDM")
        if (encrypted) assumeTrue("Supply a compatible Widevine CDM for decryption", !cdm.isNullOrBlank())
        val renewals = AtomicInteger()
        val http = MediaHttp()
        val player = DashVideoPlayerState(if (encrypted) WidevineRuntime(cdm!!) else null, true)
        try {
            val drm = if (!encrypted) null else WidevineConfiguration(sample(if (renewal) "widevine.renewal.license" else "widevine.license"), transport = WidevineLicenseTransport { request ->
                if (request.isRenewal) renewals.incrementAndGet()
                http.open(request.url, mapOf("Content-Type" to "application/octet-stream") + request.headers, method = "POST", body = request.challenge).use { it.bytes() }
            })
            player.openSource(MediaSource(sample(key), mimeType = "application/dash+xml", widevine = drm, startupTimeoutMillis = 60_000))
            withTimeout(if (renewal) 100_000 else 75_000) {
                while (true) {
                    assertNull(player.error, "Public sample $key failed")
                    val state = player.diagnostics()
                    if (state != null && state[0] >= (if (renewal) 35.0 else 2.0) && state[8] > 1000 && state[9] > 0.0001) break
                    delay(50)
                }
            }
            assertNotNull(player.currentFrame)
            assertTrue(player.diagnostics()!![14] > 3)
            val first = player.frameChecksum()
            // Public films contain static title cards; wait for actual changing content.
            withTimeout(20_000) {
                while (player.frameChecksum() == first) { assertNull(player.error); delay(100) }
            }
            if (renewal) assertTrue(renewals.get() > 0, "Must send a real CDM renewal challenge")
            println("Media3 $key: decoded changing video and non-silent PCM; platform=${NativeRuntimeBundle.platform()}")
        } finally { http.close(); player.dispose(); withTimeout(15_000) { player.awaitDisposed() } }
        assertEquals(0, NativeMediaBridge.nLivePlayers())
    }
    @Test fun assPositioningRenders() = ass("ass.0")
    @Test fun assStylingRenders() = ass("ass.1")
    private fun ass(key: String) = runBlocking {
        enabled()
        val bytes = withContext(Dispatchers.IO) { MediaHttp().use { http -> http.open(sample(key)).use { it.bytes() } } }
        val handle = NativeMediaBridge.nAssCreate(bytes, File(NativeMediaBridge.runtimeDirectory, "fonts/NotoSans.ttf").path)
        assertNotEquals(0, handle)
        try {
            assertTrue(listOf(1000L,5000L,10_000L,20_000L,30_000L).any { time -> NativeMediaBridge.nAssRender(handle,time,640,360)!!.any { it != 0.toByte() } }, "Upstream ASS must render visible pixels")
        } finally { NativeMediaBridge.nAssClose(handle) }
    }
}
