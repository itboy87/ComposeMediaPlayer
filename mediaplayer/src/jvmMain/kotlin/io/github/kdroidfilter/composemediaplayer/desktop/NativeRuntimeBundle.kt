package io.github.kdroidfilter.composemediaplayer.desktop

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

/** Complete relocatable bundles, verified by content rather than file length. */
internal object NativeRuntimeBundle {
    private var loaded: File? = null
    internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    internal fun platform(): String {
        val arm = System.getProperty("os.arch").lowercase().let { it.contains("aarch64") || it.contains("arm64") }
        return when {
            System.getProperty("os.name").startsWith("Mac", true) -> if (arm) "darwin-aarch64" else "darwin-x86-64"
            System.getProperty("os.name").startsWith("Windows", true) -> if (arm) "win32-arm64" else "win32-x86-64"
            else -> error("The bundled DASH runtime supports macOS and Windows.")
        }
    }

    @Synchronized fun load(): File {
        loaded?.let { return it }
        val platform = platform()
        val root = System.getProperty("composemediaplayer.runtimeDir")?.let(::File) ?: extract(platform)
        verify(root, platform)
        if (platform.startsWith("win")) {
            System.load(File(root, "NativeMediaBootstrap.dll").absolutePath)
            RuntimeDllSearch.addDirectory(File(root, "lib").absolutePath)
        }
        System.load(File(root, if (platform.startsWith("win")) "NativeMediaRuntime.dll" else "libNativeMediaRuntime.dylib").absolutePath)
        loaded = root
        return root
    }

    internal fun verify(root: File, expectedPlatform: String) {
        val manifest = Properties().apply { File(root, "bundle.properties").inputStream().use(::load) }
        require(manifest.getProperty("abi") == "1" && manifest.getProperty("platform") == expectedPlatform) { "Native bundle ABI/architecture mismatch." }
        val files = manifest.stringPropertyNames().filter { it.startsWith("sha256.") }
        require(files.isNotEmpty()) { "Empty native bundle." }
        val indexed = files.map { it.removePrefix("sha256.") }.toSet()
        val actual = root.walkTopDown().filter { it.isFile && it.name != "bundle.properties" }.map { it.relativeTo(root).invariantSeparatorsPath }.toSet()
        require(actual == indexed) { "Native bundle contains unindexed or missing files." }
        files.forEach { key ->
            val file = safeFile(root, key.removePrefix("sha256."))
            require(file.isFile && sha256(file.readBytes()) == manifest.getProperty(key)) { "Native bundle checksum mismatch." }
        }
    }

    private fun safeFile(root: File, name: String): File {
        require(name.isNotBlank() && '\\' !in name && !name.startsWith('/') && name.split('/').none { it == ".." || it == "." }) { "Invalid bundle entry." }
        return File(root, name).also { require(it.canonicalPath.startsWith(root.canonicalPath + File.separator)) { "Invalid bundle path." } }
    }

    private fun extract(platform: String): File {
        val prefix = "composemediaplayer/runtime/$platform/"
        val loader = NativeRuntimeBundle::class.java.classLoader
        val manifestBytes = loader.getResourceAsStream(prefix + "bundle.properties")?.use { it.readBytes() }
            ?: error("The library artifact does not contain a DASH runtime for this platform.")
        val manifest = Properties().apply { manifestBytes.inputStream().use(::load) }
        val id = sha256(manifestBytes)
        val base = File(System.getProperty("composemediaplayer.cacheDir") ?: File(System.getProperty("user.home"), ".cache/composemediaplayer").path, "runtime/$platform")
        Files.createDirectories(base.toPath())
        FileChannel.open(File(base, ".extract.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                val directory = File(base, id)
                if (directory.exists()) { verify(directory, platform); return directory }
                val staging = File(base, ".$id-${UUID.randomUUID()}")
                try {
                    Files.createDirectory(staging.toPath())
                    manifest.stringPropertyNames().filter { it.startsWith("sha256.") }.forEach { key ->
                        val name = key.removePrefix("sha256.")
                        val destination = safeFile(staging, name)
                        destination.parentFile.mkdirs()
                        checkNotNull(loader.getResourceAsStream(prefix + name)) { "Native bundle entry is missing." }.use { input ->
                            Files.copy(input, destination.toPath())
                        }
                        if (name.endsWith(".dylib") || name.endsWith(".so")) destination.setExecutable(true, true)
                    }
                    File(staging, "bundle.properties").writeBytes(manifestBytes)
                    verify(staging, platform)
                    Files.move(staging.toPath(), directory.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    return directory
                } finally { if (staging.exists()) staging.deleteRecursively() }
            }
        }
    }
}
