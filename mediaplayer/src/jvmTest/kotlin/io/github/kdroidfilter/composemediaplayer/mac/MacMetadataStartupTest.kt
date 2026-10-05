package io.github.kdroidfilter.composemediaplayer.mac

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MacMetadataStartupTest {
    @Test
    fun noArgumentJvmConstructorRemainsAvailable() {
        assertNotNull(MacVideoPlayerState::class.java.getConstructor())
    }

    @Test
    fun readyMetadataReturnsImmediately() = runTest {
        assertTrue(awaitMacVideoDimensions(DEFAULT_MAC_METADATA_TIMEOUT_MILLIS, { true }) { 1920 to 1080 })
        assertEquals(0L, currentTime)
    }

    @Test
    fun firstMetadataMayArriveAfterBothPreviousStartupWindows() = runTest {
        assertTrue(awaitMacVideoDimensions(DEFAULT_MAC_METADATA_TIMEOUT_MILLIS, { true }) {
            if (currentTime >= 16_000L) 1920 to 1080 else 0 to 0
        })
        assertEquals(16_000L, currentTime)
    }

    @Test
    fun missingMetadataStopsAtConfiguredDeadline() = runTest {
        assertFalse(awaitMacVideoDimensions(DEFAULT_MAC_METADATA_TIMEOUT_MILLIS, { true }) { 0 to 0 })
        assertEquals(30_000L, currentTime)
    }

    @Test
    fun bothDimensionsMustBePositive() = runTest {
        assertFalse(awaitMacVideoDimensions(1_000L, { true }) { 1920 to 0 })
        assertEquals(1_000L, currentTime)
    }

    @Test
    fun supersededOpenStopsReadingNativeMetadata() = runTest {
        var current = true
        var reads = 0
        val wait = async {
            awaitMacVideoDimensions(DEFAULT_MAC_METADATA_TIMEOUT_MILLIS, { current }) {
                reads++
                0 to 0
            }
        }
        advanceTimeBy(1_000L)
        current = false
        assertFalse(wait.await())
        assertEquals(4, reads)
        assertEquals(1_000L, currentTime)
    }

    @Test
    fun cancellationStopsNativePollingImmediately() = runTest {
        var reads = 0
        val wait = async {
            awaitMacVideoDimensions(DEFAULT_MAC_METADATA_TIMEOUT_MILLIS, { true }) {
                reads++
                0 to 0
            }
        }
        advanceTimeBy(1_000L)
        wait.cancel()
        wait.join()
        advanceTimeBy(30_000L)
        assertTrue(wait.isCancelled)
        assertEquals(4, reads)
    }

    @Test
    fun nonPositiveTimeoutIsRejected() = runTest {
        assertFailsWith<IllegalArgumentException> { awaitMacVideoDimensions(0, { true }) { 0 to 0 } }
    }
}
