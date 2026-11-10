package io.github.kdroidfilter.composemediaplayer.desktop

import com.sun.net.httpserver.HttpServer
import io.github.kdroidfilter.composemediaplayer.MediaSource
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Element

internal fun httpOrigin(uri: URI): String {
    require(uri.scheme in listOf("http", "https") && uri.host != null && uri.userInfo == null) { "Invalid HTTP origin." }
    val port = if (uri.port == -1) (if (uri.scheme == "https") 443 else 80) else uri.port
    return "${uri.scheme.lowercase()}://${uri.host.lowercase()}:$port"
}

/** Blocking transport, called only by IO workers. Closing it aborts every outstanding request. */
internal class MediaHttp : AutoCloseable {
    private val connections = ConcurrentHashMap.newKeySet<HttpURLConnection>()
    @Volatile private var closed = false

    class Response(val uri: URI, val connection: HttpURLConnection, private val owner: MediaHttp) : AutoCloseable {
        val status: Int get() = connection.responseCode
        fun bytes(limit: Int = 4 * 1024 * 1024): ByteArray {
            require(status in 200..299) { "HTTP request failed ($status)." }
            return connection.inputStream.use { input ->
                val bytes = input.readNBytes(limit + 1)
                require(bytes.size <= limit) { "HTTP response exceeds the configured limit." }
                bytes
            }
        }
        override fun close() { connection.disconnect(); owner.connections.remove(connection) }
    }

    fun open(
        url: String,
        headers: Map<String, String> = emptyMap(),
        allowedOrigins: Set<String> = emptySet(),
        method: String = "GET",
        body: ByteArray? = null,
    ): Response {
        var uri = URI(url)
        val origins = allowedOrigins.ifEmpty { setOf(httpOrigin(uri)) }
        repeat(6) {
            check(!closed) { "Request cancelled." }
            httpOrigin(uri)
            val connection = uri.toURL().openConnection() as HttpURLConnection
            connections += connection
            try {
                check(!closed) { "Request cancelled." }
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.requestMethod = method
                connection.setRequestProperty("Accept-Encoding", "identity")
                headers.forEach { (name, value) ->
                    require(name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) && '\r' !in value && '\n' !in value) {
                        "Invalid request header."
                    }
                    require(name.lowercase() !in setOf("host", "connection", "content-length", "transfer-encoding")) {
                        "Transport-owned request header."
                    }
                    if (httpOrigin(uri) in origins || name.equals("Range", true) || name.equals("If-Range", true)) connection.setRequestProperty(name, value)
                }
                if (body != null) {
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(body.size)
                    connection.outputStream.use { it.write(body) }
                }
                val status = connection.responseCode
                if (status in setOf(301, 302, 303, 307, 308)) {
                    val next = uri.resolve(connection.getHeaderField("Location") ?: error("Redirect lacks a location."))
                    require(uri.scheme != "https" || next.scheme == "https") { "Insecure HTTP redirect." }
                    // Binary license POSTs cannot be silently replayed at another origin or changed to GET.
                    require(method == "GET" || method == "HEAD" || (status in setOf(307, 308) && httpOrigin(next) == httpOrigin(uri))) {
                        "Unsupported license redirect."
                    }
                    uri = next
                    connection.disconnect()
                    connections.remove(connection)
                } else return Response(uri, connection, this)
            } catch (failure: Exception) {
                connection.disconnect()
                connections.remove(connection)
                throw failure
            }
        }
        error("Too many HTTP redirects.")
    }

    override fun close() { closed = true; connections.forEach { it.disconnect() }; connections.clear() }
}

/**
 * Origin-preserving loopback transport for adaptive downloads. Each upstream origin gets its own
 * port, so relative paths, root-relative paths, byte ranges and signed queries keep their meaning.
 * GStreamer's adaptive downloader does not propagate arbitrary source headers. Keeping provider
 * credentials in this transport also prevents cross-origin header inheritance in native plugins.
 */
internal class DashMediaGateway(private val source: MediaSource) : AutoCloseable {
    private val manifestAlias = "/.cmp-${UUID.randomUUID()}.mpd"
    fun openManifest(): String = URI(route(URI(source.uri))).let { "${it.scheme}://${it.rawAuthority}$manifestAlias" }
    @Volatile var networkFailure: Boolean = false
        private set
    @Volatile var detectedMime: String? = null
        private set
    val userAgent = "ComposeMediaPlayer/${UUID.randomUUID()}"
    private val http = MediaHttp()
    private val servers = LinkedHashMap<String, HttpServer>()
    private val executor = Executors.newCachedThreadPool { runnable -> Thread(runnable, "cmp-media-http").apply { isDaemon = true } }
    private val origins = source.headerOrigins.map { httpOrigin(URI(it)) }.toSet() + httpOrigin(URI(source.uri))
    @Volatile private var closed = false

    // Native URI canonicalization must not alter upstream signatures. Static query pieces are
    // carried as base64url while DASH substitutions remain visible to the adaptive demuxer.
    private fun wireQuery(query: String): String {
        val template = Regex("\\$(?:RepresentationID|Number(?:%0[0-9]+d)?|Bandwidth|Time(?:%0[0-9]+d)?)\\$")
        val parts = mutableListOf<String>()
        val variables = mutableListOf<String>()
        var position = 0
        template.findAll(query).forEach { match ->
            parts += query.substring(position, match.range.first)
            variables += match.value
            position = match.range.last + 1
        }
        parts += query.substring(position)
        val encoded = parts.joinToString(".") { java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(Charsets.UTF_8)) }
        return "__cmp_q=$encoded" + variables.mapIndexed { index, value -> "&__cmp_v$index=$value" }.joinToString("")
    }
    private fun upstreamQuery(query: String?): String? {
        if (query == null || !query.startsWith("__cmp_q=")) return query
        val fields = query.split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val parts = fields.getValue("__cmp_q").split('.')
        return buildString {
            parts.forEachIndexed { index, value ->
                if (index > 0) append(fields.getValue("__cmp_v${index - 1}"))
                append(java.util.Base64.getUrlDecoder().decode(value).toString(Charsets.UTF_8))
            }
        }
    }

    @Synchronized
    fun route(uri: URI): String {
        check(!closed) { "Playback source closed." }
        val origin = httpOrigin(uri)
        val server = servers.getOrPut(origin) {
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                executor = this@DashMediaGateway.executor
                createContext("/") { exchange ->
                    try {
                        if (closed || exchange.requestHeaders.getFirst("User-Agent") != userAgent) {
                            exchange.sendResponseHeaders(403, -1)
                        } else {
                            val requested = if (exchange.requestURI.rawPath == manifestAlias) source.uri else origin + exchange.requestURI.rawPath + (upstreamQuery(exchange.requestURI.rawQuery)?.let { "?$it" } ?: "")
                            val headers = if (origin in origins) source.headers.toMutableMap() else mutableMapOf()
                            exchange.requestHeaders.getFirst("Range")?.let { headers["Range"] = it }
                            http.open(requested, headers, origins + origin.takeIf { headers.keys.all { it.equals("Range", true) } }.orEmpty()).use { response ->
                                if (response.status !in 200..299) networkFailure = true
                                val contentType = response.connection.contentType.orEmpty()
                                val isManifest = exchange.requestURI.rawPath == manifestAlias || contentType.substringBefore(';').equals("application/dash+xml", true) ||
                                    response.uri.path.endsWith(".mpd", true) || (origin == httpOrigin(URI(source.uri)) && exchange.requestURI.rawPath == URI(source.uri).rawPath)
                                if (response.status in 200..299 && isManifest) {
                                    val input = response.connection.inputStream
                                    val prefix = input.readNBytes(2048)
                                    val mpd = prefix.toString(Charsets.UTF_8).contains(Regex("<(?:[A-Za-z0-9_]+:)?MPD(?:\\s|>)"))
                                    detectedMime = if (mpd) "application/dash+xml" else contentType.substringBefore(';').takeIf { it.isNotBlank() && it != "application/octet-stream" }
                                    if (mpd) {
                                        val remainder = input.readNBytes(4 * 1024 * 1024)
                                        require(remainder.size < 4 * 1024 * 1024) { "Manifest exceeds size limit." }
                                        val rewritten = rewriteManifest(prefix + remainder, response.uri)
                                        exchange.responseHeaders.set("Content-Type", "application/dash+xml")
                                        exchange.sendResponseHeaders(response.status, rewritten.size.toLong())
                                        exchange.responseBody.use { it.write(rewritten) }
                                    } else {
                                        for (header in listOf("Content-Type", "Content-Range", "Accept-Ranges")) response.connection.getHeaderField(header)?.let { exchange.responseHeaders.set(header, it) }
                                        val length = response.connection.contentLengthLong
                                        exchange.sendResponseHeaders(response.status, if (length > 0) length else 0)
                                        exchange.responseBody.use { output -> output.write(prefix); input.copyTo(output) }
                                    }
                                    input.close()
                                } else {
                                    for (header in listOf("Content-Type", "Content-Range", "Accept-Ranges")) {
                                        response.connection.getHeaderField(header)?.let { exchange.responseHeaders.set(header, it) }
                                    }
                                    val length = response.connection.contentLengthLong
                                    exchange.sendResponseHeaders(response.status, if (length > 0) length else 0)
                                    if (response.status in 200..299) response.connection.inputStream.use { input ->
                                        exchange.responseBody.use { output -> input.copyTo(output) }
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {
                        networkFailure = true
                        // Native errors expose status/category only, never the upstream URL or credentials.
                        runCatching { exchange.sendResponseHeaders(502, -1) }
                    } finally { exchange.close() }
                }
                start()
            }
        }
        return "http://127.0.0.1:${server.address.port}${uri.rawPath.ifEmpty { "/" }}" +
            (uri.rawQuery?.let { "?${wireQuery(it)}" } ?: "")
    }

    internal fun rewriteManifest(data: ByteArray, effectiveUri: URI): ByteArray {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(data))
        val root = document.documentElement
        require(root.localName == "MPD") { "Invalid DASH manifest." }
        val rootBases = (0 until root.childNodes.length).map { root.childNodes.item(it) }.filterIsInstance<Element>().filter { it.localName == "BaseURL" }
        if (rootBases.isEmpty()) {
            val base = document.createElementNS(root.namespaceURI, "BaseURL")
            base.textContent = route(URI(effectiveUri.toASCIIString().substringBefore('?')))
            root.insertBefore(base, root.firstChild)
        } else rootBases.forEach { it.textContent = route(effectiveUri.resolve(it.textContent.trim())) }
        fun mapped(value: String): String = when {
            value.startsWith("http://") || value.startsWith("https://") -> route(URI(value))
            value.startsWith("//") -> route(URI(effectiveUri.scheme + ":" + value))
            else -> if ('?' in value) value.substringBefore('?') + "?" + wireQuery(value.substringAfter('?')) else value
        }
        val elements = root.getElementsByTagName("*")
        for (i in 0 until elements.length) {
            val element = elements.item(i) as Element
            if (element.localName in setOf("BaseURL", "Location", "PatchLocation") && element !in rootBases && element.parentNode != root) {
                element.textContent = mapped(element.textContent.trim())
            }
            // Whole-period SegmentList entries may omit duration. dashdemux2 requires it when
            // finding synchronization samples; infer it only for an unambiguous single segment.
            var owner = element.parentNode
            var inheritedList = false
            while (owner is Element) {
                for (childIndex in 0 until owner.childNodes.length) {
                    val sibling = owner.childNodes.item(childIndex)
                    if (sibling is Element && sibling !== element && sibling.localName == "SegmentList") inheritedList = true
                }
                owner = owner.parentNode
            }
            if (!inheritedList && element.localName == "SegmentList" && !element.hasAttribute("duration") &&
                !element.hasAttribute("timescale") && !element.hasAttribute("presentationTimeOffset") &&
                element.getElementsByTagNameNS("*", "SegmentURL").length == 1 &&
                element.getElementsByTagNameNS("*", "SegmentTimeline").length == 0) {
                var ancestor = element.parentNode
                while (ancestor is Element && ancestor.localName != "Period") ancestor = ancestor.parentNode
                val duration = (ancestor as? Element)?.getAttribute("duration")?.takeIf { it.isNotBlank() }
                    ?: root.getAttribute("mediaPresentationDuration").takeIf { root.getElementsByTagNameNS("*", "Period").length == 1 && it.isNotBlank() }
                val nanos = duration?.let { runCatching { java.time.Duration.parse(it).toNanos() }.getOrNull() }
                if (nanos != null && nanos > 0) {
                    element.setAttribute("timescale", "1000000000")
                    element.setAttribute("duration", nanos.toString())
                }
            }
            for (attr in listOf("media", "initialization", "sourceURL", "index", "href")) {
                if (element.hasAttribute(attr)) element.setAttribute(attr, mapped(element.getAttribute(attr)))
            }
        }
        val output = java.io.ByteArrayOutputStream()
        TransformerFactory.newInstance().apply { setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "") }
            .newTransformer().transform(DOMSource(document), StreamResult(output))
        return output.toByteArray()
    }

    @Synchronized override fun close() {
        closed = true
        http.close()
        servers.values.forEach { it.stop(0) }
        servers.clear()
        executor.shutdownNow()
    }
}
