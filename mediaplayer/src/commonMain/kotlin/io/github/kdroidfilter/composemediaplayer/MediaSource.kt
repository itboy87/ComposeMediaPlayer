package io.github.kdroidfilter.composemediaplayer

/** A resolved media request. Provider/parser expressions must be resolved by the caller. */
class MediaSource(
    val uri: String,
    val mimeType: String? = null,
    val headers: Map<String, String> = emptyMap(),
    /** Additional HTTP origins allowed to receive [headers]; the source origin is always allowed. */
    val headerOrigins: Set<String> = emptySet(),
    val preferredAudioLanguage: String? = null,
    val subtitles: List<SubtitleTrack> = emptyList(),
    val widevine: WidevineConfiguration? = null,
    val startupTimeoutMillis: Long = 30_000,
) {
    init {
        require(uri.isNotBlank()) { "A media URI is required." }
        require(startupTimeoutMillis > 0) { "Startup timeout must be positive." }
    }

    override fun toString(): String = "MediaSource(mimeType=$mimeType, widevine=${widevine != null}, credentials=hidden)"
}

/** Temporary Widevine streaming licenses. The proprietary CDM is configured separately on JVM. */
class WidevineConfiguration(
    val licenseUrl: String,
    val licenseHeaders: Map<String, String> = emptyMap(),
    val serviceCertificate: ByteArray? = null,
    val transport: WidevineLicenseTransport? = null,
) {
    init { require(licenseUrl.isNotBlank()) { "A license URL is required." } }
    override fun toString(): String = "WidevineConfiguration(credentials=hidden)"
}

/** Return the raw license bytes accepted by the CDM, after any provider-specific unwrapping. */
fun interface WidevineLicenseTransport {
    suspend fun execute(request: WidevineLicenseRequest): ByteArray
}

class WidevineLicenseRequest(
    val url: String,
    val headers: Map<String, String>,
    val challenge: ByteArray,
    val isRenewal: Boolean,
) {
    override fun toString(): String = "WidevineLicenseRequest(isRenewal=$isRenewal, credentials=hidden)"
}

/** Ordered per-player events. No provider credentials or URLs are included. */
data class PlaybackEvent(val sourceId: Long, val type: Type, val error: VideoPlayerError? = null) {
    enum class Type { OPENING, READY, PLAYING, PAUSED, COMPLETED, REPLACED, STOPPED, FAILED, DISPOSED }
}
