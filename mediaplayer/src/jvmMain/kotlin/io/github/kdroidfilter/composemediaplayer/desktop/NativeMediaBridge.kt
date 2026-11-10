package io.github.kdroidfilter.composemediaplayer.desktop

internal object NativeMediaBridge {
    val runtimeDirectory by lazy {
        NativeRuntimeBundle.load().also {
            check(nAbiVersion() == 1) { "Native media ABI mismatch." }
            check(nInitialize(it.absolutePath) == 1) { "The bundled media runtime is incomplete." }
        }
    }
    @JvmStatic external fun nAbiVersion(): Int
    @JvmStatic external fun nInitialize(root: String): Int
    @JvmStatic external fun nCreate(uri: String, userAgent: String, cdm: String?, certificate: ByteArray?, testAudio: Boolean, audioLanguage: String?): Long
    @JvmStatic external fun nCancel(handle: Long)
    @JvmStatic external fun nClose(handle: Long)
    @JvmStatic external fun nSetPlaying(handle: Long, playing: Boolean)
    @JvmStatic external fun nSetVolume(handle: Long, volume: Double)
    @JvmStatic external fun nSeek(handle: Long, seconds: Double, rate: Double): Boolean
    @JvmStatic external fun nSnapshot(handle: Long): DoubleArray?
    @JvmStatic external fun nReadFrame(handle: Long, previous: Long, info: LongArray): ByteArray?
    @JvmStatic external fun nBufferedRanges(handle: Long): DoubleArray?
    @JvmStatic external fun nPollLicense(handle: Long): ByteArray?
    @JvmStatic external fun nUpdateLicense(handle: Long, token: Int, response: ByteArray)
    @JvmStatic external fun nLicenseFailed(handle: Long)
    @JvmStatic external fun nLivePlayers(): Int
    @JvmStatic external fun nAssCreate(content: ByteArray, font: String): Long
    @JvmStatic external fun nAssClose(handle: Long)
    @JvmStatic external fun nAssRender(handle: Long, timeMillis: Long, width: Int, height: Int): ByteArray?
}

/** The caller supplies an existing, compatible CDM binary. It is never downloaded or bundled. */
class WidevineRuntime(val libraryPath: String) {
    override fun toString(): String = "WidevineRuntime(path=hidden)"
}

internal object RuntimeDllSearch {
    @JvmStatic external fun addDirectory(directory: String)
}
