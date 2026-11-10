package io.github.kdroidfilter.composemediaplayer.util

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Loads native libraries following a two-stage strategy:
 * 1. Try [System.loadLibrary] (works for packaged apps / GraalVM native-image
 *    where the lib sits on `java.library.path`).
 * 2. Fallback: extract from the classpath (`composemediaplayer/native/<platform>/`)
 *    into a persistent cache and load from there.
 */
internal object NativeLibraryLoader {
    private const val RESOURCE_PREFIX = "composemediaplayer/native"
    private val loadedLibraries = mutableSetOf<String>()

    @Synchronized
    fun load(
        libraryName: String,
        callerClass: Class<*>,
    ): Boolean {
        if (libraryName in loadedLibraries) return true

        // 1. Try system library path (packaged app / GraalVM native-image)
        try {
            System.loadLibrary(libraryName)
            loadedLibraries += libraryName
            return true
        } catch (_: UnsatisfiedLinkError) {
            // Not on java.library.path, try classpath extraction
        }

        // 2. Extract from classpath to persistent cache
        val file = extractToCache(libraryName, callerClass) ?: return false
        System.load(file.absolutePath)
        loadedLibraries += libraryName
        return true
    }

    private fun extractToCache(
        libraryName: String,
        callerClass: Class<*>,
    ): File? {
        val platform = detectPlatform()
        val fileName = mapLibraryName(libraryName)
        val resourcePath = "$RESOURCE_PREFIX/$platform/$fileName"

        val resourceUrl = callerClass.classLoader?.getResource(resourcePath) ?: return null

        val bytes = resourceUrl.openStream().use { it.readBytes() }
        fun hash(data: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        val digest = hash(bytes)
        val cacheDir = File(resolveCacheDir(platform), digest)
        Files.createDirectories(cacheDir.toPath())
        val cachedFile = File(cacheDir, fileName)
        java.nio.channels.FileChannel.open(File(cacheDir, ".extract.lock").toPath(), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                if (!cachedFile.exists() || hash(cachedFile.readBytes()) != digest) {
                    val temporary = Files.createTempFile(cacheDir.toPath(), fileName, ".tmp")
                    try {
                        Files.write(temporary, bytes)
                        Files.move(temporary, cachedFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                        cachedFile.setExecutable(true)
                    } finally { Files.deleteIfExists(temporary) }
                }
            }
        }

        return cachedFile
    }

    private fun resolveCacheDir(platform: String): File {
        val os = System.getProperty("os.name")?.lowercase() ?: ""
        val base =
            when {
                os.contains("win") ->
                    File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"))
                else ->
                    File(System.getProperty("user.home"), ".cache")
            }
        return File(base, "composemediaplayer/native/$platform")
    }

    private fun detectPlatform(): String {
        val os = System.getProperty("os.name")?.lowercase() ?: ""
        val arch = System.getProperty("os.arch") ?: ""
        return when {
            os.contains("win") ->
                if (arch.contains("aarch64") || arch.contains("arm")) "win32-arm64" else "win32-x86-64"
            os.contains("linux") ->
                if (arch.contains("aarch64") || arch.contains("arm")) "linux-aarch64" else "linux-x86-64"
            os.contains("mac") ->
                if (arch.contains("aarch64") || arch.contains("arm")) "darwin-aarch64" else "darwin-x86-64"
            else -> "unknown"
        }
    }

    private fun mapLibraryName(name: String): String {
        val os = System.getProperty("os.name")?.lowercase() ?: ""
        return when {
            os.contains("win") -> "$name.dll"
            os.contains("mac") -> "lib$name.dylib"
            else -> "lib$name.so"
        }
    }
}
