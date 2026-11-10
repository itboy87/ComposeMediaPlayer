package io.github.kdroidfilter.composemediaplayer.desktop

import io.github.kdroidfilter.composemediaplayer.*
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.Properties
import kotlin.test.*

/** Actual CDM/license validation is opt-in; it is never replaced by clear media or a mock CDM. */
class WidevineNativeIntegrationTest {
    @Test fun suppliedCdmLicenseDecryptsBothVideoAndAudio() = runBlocking {
        val path=System.getenv("COMPOSE_MEDIA_PLAYER_WIDEVINE_CONFIG")
        assumeTrue("A compatible app-supplied CDM and authorized fixture are required",!path.isNullOrBlank())
        val config=Properties().apply { File(path!!).inputStream().use { load(it) } }
        fun required(name: String)=requireNotNull(config.getProperty(name)) { "Missing Widevine test configuration field." }
        fun headers(prefix: String)=config.stringPropertyNames().filter { it.startsWith(prefix) }.associate { it.removePrefix(prefix) to config.getProperty(it) }
        val player=DashVideoPlayerState(WidevineRuntime(required("cdm")),testAudio=true)
        try {
            player.openSource(MediaSource(required("manifest"),mimeType="application/dash+xml",headers=headers("media.header."),widevine=WidevineConfiguration(required("license"),headers("license.header.")),startupTimeoutMillis=60_000))
            withTimeout(60_000) {
                while(player.currentTime<1.0) { assertNull(player.error);delay(30) }
            }
            assertNotNull(player.currentFrame)
            val snapshot=player.diagnostics()!!
            assertTrue(snapshot[8]>1000 && snapshot[9]>0.001,"Encrypted audio must produce non-silent decoded PCM.")
            assertTrue(snapshot[14]>2,"Encrypted video must produce decoded frames.")
        } finally { player.dispose();withTimeout(10_000) { player.awaitDisposed() } }
        assertEquals(0,NativeMediaBridge.nLivePlayers())
    }
}
