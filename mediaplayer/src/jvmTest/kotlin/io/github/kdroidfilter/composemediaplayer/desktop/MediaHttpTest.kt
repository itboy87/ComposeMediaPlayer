package io.github.kdroidfilter.composemediaplayer.desktop

import com.sun.net.httpserver.HttpServer
import io.github.kdroidfilter.composemediaplayer.*
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class MediaHttpTest {
    @Test fun crossOriginRedirectDropsCredentialsAndLicenseBytesStayRaw() {
        val observed = AtomicReference<String?>()
        val range = AtomicReference<String?>()
        val target = HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        target.createContext("/") { request ->
            observed.set(request.requestHeaders.getFirst("Authorization"))
            range.set(request.requestHeaders.getFirst("Range"))
            val data=byteArrayOf(0,1,2,-1)
            request.sendResponseHeaders(200,data.size.toLong());request.responseBody.use { it.write(data) };request.close()
        }
        target.start()
        val origin=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        origin.createContext("/") { request ->
            request.responseHeaders.set("Location","http://127.0.0.1:${target.address.port}/license")
            request.sendResponseHeaders(302,-1);request.close()
        }
        origin.start()
        try {
            MediaHttp().use { http ->
                val data=http.open("http://127.0.0.1:${origin.address.port}/",mapOf("Authorization" to "fixture-secret", "Range" to "bytes=0-3")).use { it.bytes() }
                assertContentEquals(byteArrayOf(0,1,2,-1),data)
                assertNull(observed.get())
                assertEquals("bytes=0-3",range.get())
                http.open("http://127.0.0.1:${target.address.port}/",mapOf("Authorization" to "fixture-secret"),method="POST",body=byteArrayOf(1)).use { it.bytes() }
                assertEquals("fixture-secret",observed.get())
            }
            val source=MediaSource("https://test.invalid/token-secret",headers=mapOf("Authorization" to "fixture-secret"),widevine=WidevineConfiguration("https://license.invalid/token-secret"))
            assertFalse(source.toString().contains("token-secret"));assertFalse(source.toString().contains("fixture-secret"))
        } finally { origin.stop(0);target.stop(0) }
    }

    @Test fun wholePeriodSegmentListGetsDurationWithoutOverridingInheritedTiming() {
        fun normalized(xml: String): org.w3c.dom.Document = DashMediaGateway(MediaSource("https://fixture.invalid/manifest.mpd")).use { gateway ->
            val output = gateway.rewriteManifest(xml.encodeToByteArray(),java.net.URI("https://fixture.invalid/manifest.mpd"))
            javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware=true }.newDocumentBuilder().parse(output.inputStream())
        }
        val direct=normalized("<MPD xmlns='urn:mpeg:dash:schema:mpd:2011' mediaPresentationDuration='PT4.5S'><Period><AdaptationSet><Representation><SegmentList><SegmentURL media='video.mp4'/></SegmentList></Representation></AdaptationSet></Period></MPD>")
        val inferred=direct.getElementsByTagNameNS("*","SegmentList").item(0) as org.w3c.dom.Element
        assertEquals("1000000000",inferred.getAttribute("timescale"))
        assertEquals("4500000000",inferred.getAttribute("duration"))
        val inherited=normalized("<MPD xmlns='urn:mpeg:dash:schema:mpd:2011' mediaPresentationDuration='PT4.5S'><Period><AdaptationSet><SegmentList duration='2'/><Representation><SegmentList><SegmentURL media='video.mp4'/></SegmentList></Representation></AdaptationSet></Period></MPD>")
        val child=inherited.getElementsByTagNameNS("*","SegmentList").item(1) as org.w3c.dom.Element
        assertFalse(child.hasAttribute("duration"))
        assertFalse(child.hasAttribute("timescale"))
    }
}
