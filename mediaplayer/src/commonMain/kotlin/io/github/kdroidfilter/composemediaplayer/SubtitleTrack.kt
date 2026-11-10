package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Stable

@Stable
data class SubtitleTrack(
    val label: String,
    val language: String,
    val src: String,
    /** Explicit format (ass, ssa, srt, vtt), useful for opaque/signed URLs. */
    val format: String? = null,
    /** Independent request headers. Empty never inherits the media request's credentials. */
    val headers: Map<String, String> = emptyMap(),
) {
    constructor(label: String, language: String, src: String) : this(label, language, src, null, emptyMap())
    override fun toString(): String = "SubtitleTrack(label=$label, language=$language, format=$format, credentials=hidden)"
}
