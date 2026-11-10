package io.github.kdroidfilter.composemediaplayer.desktop

import java.nio.file.Files
import kotlin.test.*

class NativeRuntimeBundleTest {
    @Test fun checksumAndInventoryRejectCorruptionEvenAtTheSameSize() {
        val root=Files.createTempDirectory("cmp-bundle-test").toFile()
        try {
            val binary=java.io.File(root,"runtime.bin").apply { writeBytes(byteArrayOf(1,2,3)) }
            java.io.File(root,"bundle.properties").writeText("abi=1\nplatform=fixture\nsha256.runtime.bin=${NativeRuntimeBundle.sha256(binary.readBytes())}\n")
            NativeRuntimeBundle.verify(root,"fixture")
            binary.writeBytes(byteArrayOf(1,2,4))
            assertFailsWith<IllegalArgumentException> { NativeRuntimeBundle.verify(root,"fixture") }
            binary.writeBytes(byteArrayOf(1,2,3))
            java.io.File(root,"unindexed.dll").writeBytes(byteArrayOf(1))
            assertFailsWith<IllegalArgumentException> { NativeRuntimeBundle.verify(root,"fixture") }
        } finally { root.deleteRecursively() }
    }
}
