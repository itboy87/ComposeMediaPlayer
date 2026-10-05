package io.github.kdroidfilter.composemediaplayer.mac

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

internal const val DEFAULT_MAC_METADATA_TIMEOUT_MILLIS = 30_000L
private const val DIMENSION_POLL_INTERVAL_MILLIS = 250L

/** Wait for asynchronous AVFoundation metadata without restarting a valid, slow first open. */
internal suspend fun awaitMacVideoDimensions(
    timeoutMillis: Long,
    isCurrentOpen: () -> Boolean,
    readDimensions: () -> Pair<Int, Int>,
): Boolean {
    require(timeoutMillis > 0) { "Metadata timeout must be positive." }
    return withTimeoutOrNull(timeoutMillis) {
        while (isCurrentOpen()) {
            val (width, height) = readDimensions()
            if (width > 0 && height > 0) return@withTimeoutOrNull true
            delay(DIMENSION_POLL_INTERVAL_MILLIS)
        }
        false
    } ?: false
}
