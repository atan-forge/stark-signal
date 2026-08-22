package com.atan.starkaudio.transcription

import android.content.Context
import com.atan.starkaudio.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Installs only a build-verified asset into the private vault. No model is downloaded at runtime. */
object BundledModelInstaller {
    fun manifestOrNull(): BundledModelManifest? {
        val id = BuildConfig.BUNDLED_MODEL_ID
        val hash = BuildConfig.BUNDLED_MODEL_SHA256
        return if (id.isBlank() || !hash.matches(Regex("[0-9a-fA-F]{64}"))) null
        else BundledModelManifest(
            id = id,
            fileName = BuildConfig.BUNDLED_MODEL_FILE,
            sha256 = hash.lowercase(),
            byteCount = BuildConfig.BUNDLED_MODEL_BYTE_COUNT,
            sourceUrl = BuildConfig.BUNDLED_MODEL_SOURCE_URL,
            license = BuildConfig.BUNDLED_MODEL_LICENSE,
            engineVersion = BuildConfig.BUNDLED_MODEL_ENGINE_REVISION
        )
    }

    suspend fun installIfNeeded(context: Context, manifest: BundledModelManifest): File = withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, "models").also { it.mkdirs() }
        File(directory, "${manifest.fileName}.partial").takeIf { it.exists() }?.delete()
        val destination = File(directory, manifest.fileName)
        if (destination.isFile && (manifest.byteCount <= 0L || destination.length() == manifest.byteCount) && sha256(destination) == manifest.sha256) return@withContext destination
        destination.delete()
        val partial = File(directory, "${manifest.fileName}.partial")
        context.assets.open("models/${manifest.fileName}").use { input ->
            partial.outputStream().use { output -> input.copyTo(output, 128 * 1024); output.fd.sync() }
        }
        if (sha256(partial) != manifest.sha256) {
            partial.delete()
            throw SecurityException("The bundled offline model failed its integrity check.")
        }
        if (manifest.byteCount > 0L && partial.length() != manifest.byteCount) {
            partial.delete()
            throw SecurityException("The bundled offline model has an unexpected size.")
        }
        check(partial.renameTo(destination)) { "The verified offline model could not be installed." }
        destination
    }

    /**
     * Deliberately does not install or hash a large model. This is safe to call during
     * application start-up and keeps model I/O out of normal navigation.
     */
    fun assetIsPresent(context: Context, manifest: BundledModelManifest): Boolean = runCatching {
        context.assets.open("models/${manifest.fileName}").use { }
        true
    }.getOrDefault(false)

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
