package com.superbot.sati.ai

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

internal data class ModelSpec(
    val fileName: String,
    val url: String,
    val bytes: Long,
    val sha256: String,
)

internal object SatiModel {
    val spec = ModelSpec(
        fileName = "qwen3-0.6b-e668717.litertlm",
        url = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/" +
            "e6687177e00897731e7716267eebd4eba0dde7c2/Qwen3-0.6B.litertlm",
        bytes = 613_722_264L,
        sha256 = "c5f39b4191926786c3e6668b62af4d6eb15248dfc7a5348252a9b406f33f2108",
    )
}

internal object ModelDownloadPolicy {
    fun allows(url: URL): Boolean {
        val host = url.host.lowercase()
        return url.protocol == "https" && url.userInfo == null &&
            (url.port == -1 || url.port == 443) &&
            (host == "huggingface.co" || host.endsWith(".huggingface.co") ||
                host == "hf.co" || host.endsWith(".hf.co"))
    }
}

internal class ModelResponse(
    val status: Int,
    val contentRange: String?,
    val body: InputStream,
    private val disconnect: () -> Unit = {},
) : Closeable {
    override fun close() {
        try { body.close() } finally { disconnect() }
    }
}

internal fun interface ModelTransport {
    fun open(url: URL, offset: Long): ModelResponse
}

/** The application's only network client: HTTPS GETs for the fixed model and its trusted CDN. */
internal class HttpsModelTransport : ModelTransport {
    override fun open(url: URL, offset: Long): ModelResponse {
        var target = url
        repeat(6) {
            if (!ModelDownloadPolicy.allows(target)) throw IOException("Unapproved model host")
            val connection = target.openConnection() as HttpsURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.requestMethod = "GET"
                connection.connectTimeout = 20_000
                connection.readTimeout = 20_000
                connection.useCaches = false
                connection.setRequestProperty("Accept-Encoding", "identity")
                if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
                val status = connection.responseCode
                if (status in setOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location") ?: throw IOException()
                    target = URL(target, location)
                    connection.disconnect()
                } else if (status == 200 || status == 206) {
                    return ModelResponse(
                        status, connection.getHeaderField("Content-Range"),
                        connection.inputStream, connection::disconnect,
                    )
                } else {
                    throw IOException("Model download HTTP $status")
                }
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
        }
        throw IOException("Too many model redirects")
    }
}

internal class ModelVerificationException : IOException("Model verification failed")

/** Uses a partial file for resuming; only verified weights are promoted to the final path. */
internal class ModelDownloader(
    private val spec: ModelSpec = SatiModel.spec,
    private val transport: ModelTransport = HttpsModelTransport(),
) {
    fun obtain(
        directory: File,
        checkCanceled: () -> Unit = {},
        onProgress: (Long) -> Unit = {},
    ): File {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("No model directory")
        val destination = File(directory, spec.fileName)
        if (valid(destination, checkCanceled)) return destination
        val partial = File(directory, spec.fileName + ".part")
        if (partial.length() > spec.bytes) partial.delete()
        if (partial.length() == spec.bytes) {
            if (valid(partial, checkCanceled)) return promote(partial, destination)
            partial.delete()
        }
        checkCanceled()
        var offset = partial.length()
        if (directory.usableSpace < spec.bytes - offset + 32L * 1024 * 1024) {
            throw IOException("Not enough storage")
        }
        onProgress(offset)
        transport.open(URL(spec.url), offset).use { response ->
            when (response.status) {
                200 -> offset = 0 // The server ignored Range: restart without appending.
                206 -> {
                    val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)")
                        .matchEntire(response.contentRange.orEmpty())
                        ?: throw IOException("Invalid Content-Range")
                    val start = range.groupValues[1].toLongOrNull()
                    val end = range.groupValues[2].toLongOrNull()
                    val total = range.groupValues[3].toLongOrNull()
                    if (start != offset || total != spec.bytes || end == null ||
                        end < offset || end >= spec.bytes
                    ) throw IOException("Unexpected model range")
                }
                else -> throw IOException("Unexpected model response")
            }
            FileOutputStream(partial, offset > 0).use { output ->
                var received = offset
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    checkCanceled()
                    val count = response.body.read(buffer)
                    if (count < 0) break
                    if (received + count > spec.bytes) {
                        throw ModelVerificationException()
                    }
                    output.write(buffer, 0, count)
                    received += count
                    onProgress(received)
                }
                output.fd.sync()
            }
        }
        checkCanceled()
        if (partial.length() != spec.bytes) throw IOException("Incomplete model download")
        if (!valid(partial, checkCanceled)) {
            partial.delete()
            throw ModelVerificationException()
        }
        return promote(partial, destination)
    }

    private fun valid(file: File, checkCanceled: () -> Unit): Boolean {
        if (!file.isFile || file.length() != spec.bytes) return false
        if (!file.inputStream().use(ModelFileFormat::hasValidHeader)) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkCanceled()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return actual == spec.sha256
    }

    private fun promote(partial: File, destination: File): File {
        if (!partial.renameTo(destination)) throw IOException("Cannot install model")
        return destination
    }
}
