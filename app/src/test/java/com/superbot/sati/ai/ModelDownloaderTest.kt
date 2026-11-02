package com.superbot.sati.ai

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelDownloaderTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bytes = "LITERTLM-small-test-weights".toByteArray()
    private val spec = ModelSpec(
        "test.litertlm", "https://huggingface.co/test/model.litertlm",
        bytes.size.toLong(), sha256(bytes),
    )

    @Test
    fun downloadsVerifiesAndReusesCachedModelWithoutAnotherRequest() {
        var requests = 0
        var responseClosed = false
        val downloader = ModelDownloader(spec, ModelTransport { url, offset ->
            requests++
            assertEquals(spec.url, url.toString())
            assertEquals(0L, offset)
            ModelResponse(200, null, ByteArrayInputStream(bytes)) { responseClosed = true }
        })
        val progress = mutableListOf<Long>()
        val file = downloader.obtain(temporary.root, onProgress = { progress += it })
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(bytes.size.toLong(), progress.last())
        assertTrue(responseClosed)
        downloader.obtain(temporary.root)
        assertEquals(1, requests)
    }

    @Test
    fun interruptedTransferResumesAtTheSavedOffset() {
        val interrupted = object : FilterInputStream(ByteArrayInputStream(bytes)) {
            var delivered = false
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (delivered) throw IOException("Disconnected")
                delivered = true
                return super.read(buffer, offset, minOf(length, 12))
            }
        }
        val first = ModelDownloader(spec, ModelTransport { _, _ -> ModelResponse(200, null, interrupted) })
        assertThrows(IOException::class.java) { first.obtain(temporary.root) }
        assertFalse(File(temporary.root, spec.fileName).exists())
        assertEquals(12L, partial().length())
        val resumed = ModelDownloader(spec, ModelTransport { _, offset ->
            assertEquals(12L, offset)
            ModelResponse(
                206, "bytes 12-${bytes.lastIndex}/${bytes.size}",
                ByteArrayInputStream(bytes.copyOfRange(12, bytes.size)),
            )
        })
        assertArrayEquals(bytes, resumed.obtain(temporary.root).readBytes())
    }

    @Test
    fun serverIgnoringRangeRestartsInsteadOfAppending() {
        partial().writeBytes(bytes.copyOfRange(0, 12))
        val downloader = ModelDownloader(spec, ModelTransport { _, offset ->
            assertEquals(12L, offset)
            ModelResponse(200, null, ByteArrayInputStream(bytes))
        })
        assertArrayEquals(bytes, downloader.obtain(temporary.root).readBytes())
    }

    @Test
    fun invalidContentRangeCannotPublishAModel() {
        partial().writeBytes(bytes.copyOfRange(0, 12))
        val downloader = ModelDownloader(spec, ModelTransport { _, _ ->
            ModelResponse(206, "bytes 0-2/3", ByteArrayInputStream(bytes))
        })
        assertThrows(IOException::class.java) { downloader.obtain(temporary.root) }
        assertFalse(File(temporary.root, spec.fileName).exists())
        assertEquals(12L, partial().length())
    }

    @Test
    fun wrongChecksumDeletesPartialAndNeverPublishesModel() {
        val wrong = bytes.copyOf().also { it[it.lastIndex] = 0 }
        val downloader = ModelDownloader(spec, ModelTransport { _, _ ->
            ModelResponse(200, null, ByteArrayInputStream(wrong))
        })
        assertThrows(ModelVerificationException::class.java) { downloader.obtain(temporary.root) }
        assertFalse(File(temporary.root, spec.fileName).exists())
        assertFalse(partial().exists())
    }

    @Test
    fun truncatedDownloadIsRetainedForRetryButNotLoaded() {
        val downloader = ModelDownloader(spec, ModelTransport { _, _ ->
            ModelResponse(200, null, ByteArrayInputStream(bytes.copyOfRange(0, 12)))
        })
        assertThrows(IOException::class.java) { downloader.obtain(temporary.root) }
        assertFalse(File(temporary.root, spec.fileName).exists())
        assertEquals(12L, partial().length())
    }

    @Test
    fun oversizedResponseCannotPublishAModel() {
        val downloader = ModelDownloader(spec, ModelTransport { _, _ ->
            ModelResponse(200, null, ByteArrayInputStream(bytes + byteArrayOf(1)))
        })
        assertThrows(ModelVerificationException::class.java) { downloader.obtain(temporary.root) }
        assertFalse(File(temporary.root, spec.fileName).exists())
    }

    @Test
    fun canceledDownloadKeepsPartialDataAndClosesTheResponse() {
        var canceled = false
        var closed = false
        val smallReads = object : FilterInputStream(ByteArrayInputStream(bytes)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, minOf(length, 4))
        }
        val downloader = ModelDownloader(spec, ModelTransport { _, _ ->
            ModelResponse(200, null, smallReads) { closed = true }
        })
        assertThrows(CancellationException::class.java) {
            downloader.obtain(
                temporary.root,
                checkCanceled = { if (canceled) throw CancellationException() },
                onProgress = { if (it >= 12) canceled = true },
            )
        }
        assertTrue(closed)
        assertEquals(12L, partial().length())
        assertFalse(File(temporary.root, spec.fileName).exists())
    }

    @Test
    fun completeVerifiedPartialIsRecoveredWithoutInternet() {
        partial().writeBytes(bytes)
        val downloader = ModelDownloader(spec, ModelTransport { _, _ -> error("Must stay offline") })
        assertArrayEquals(bytes, downloader.obtain(temporary.root).readBytes())
        assertFalse(partial().exists())
    }

    @Test
    fun corruptedCacheIsReplacedWithVerifiedWeights() {
        val destination = File(temporary.root, spec.fileName)
        destination.writeBytes(bytes.copyOf().also { it[it.lastIndex] = 0 })
        val downloader = ModelDownloader(spec, ModelTransport { _, _ ->
            ModelResponse(200, null, ByteArrayInputStream(bytes))
        })
        assertArrayEquals(bytes, downloader.obtain(temporary.root).readBytes())
    }

    @Test
    fun rejectsNonModelFilesEvenWithMatchingSizeAndDigest() {
        val html = "<html>this is not a model</html>".toByteArray()
        val invalidSpec = spec.copy(bytes = html.size.toLong(), sha256 = sha256(html))
        val downloader = ModelDownloader(invalidSpec, ModelTransport { _, _ ->
            ModelResponse(200, null, ByteArrayInputStream(html))
        })
        assertThrows(ModelVerificationException::class.java) { downloader.obtain(temporary.root) }
        assertFalse(File(temporary.root, spec.fileName).exists())
    }

    @Test
    fun redirectPolicyAllowsOnlyHttpsModelHosts() {
        assertTrue(ModelDownloadPolicy.allows(URL(SatiModel.spec.url)))
        assertTrue(ModelDownloadPolicy.allows(URL("https://cas-bridge.xethub.hf.co/model")))
        assertTrue(ModelDownloadPolicy.allows(URL("https://us.aws.cdn.hf.co/model")))
        assertFalse(ModelDownloadPolicy.allows(URL("http://huggingface.co/model")))
        assertFalse(ModelDownloadPolicy.allows(URL("https://huggingface.co.attacker.test/model")))
        assertFalse(ModelDownloadPolicy.allows(URL("https://example.com/model")))
        assertFalse(ModelDownloadPolicy.allows(URL("https://user:secret@huggingface.co/model")))
        assertFalse(ModelDownloadPolicy.allows(URL("https://huggingface.co:8443/model")))
    }

    @Test
    fun switchingModelsKeepsBothVerifiedDownloadsForOfflineReuse() {
        val secondBytes = "LITERTLM-other-model".toByteArray()
        val secondSpec = spec.copy(
            fileName = "second.litertlm",
            bytes = secondBytes.size.toLong(), sha256 = sha256(secondBytes),
        )
        ModelDownloader(spec, ModelTransport { _, _ ->
            ModelResponse(200, null, ByteArrayInputStream(bytes))
        }).obtain(temporary.root)
        ModelDownloader(secondSpec, ModelTransport { _, _ ->
            ModelResponse(200, null, ByteArrayInputStream(secondBytes))
        }).obtain(temporary.root)
        val offline = ModelTransport { _, _ -> error("Cached models must work offline") }
        assertArrayEquals(bytes, ModelDownloader(spec, offline).obtain(temporary.root).readBytes())
        assertArrayEquals(
            secondBytes, ModelDownloader(secondSpec, offline).obtain(temporary.root).readBytes(),
        )
    }

    private fun partial() = File(temporary.root, spec.fileName + ".part")
    private fun sha256(content: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(content).joinToString("") { "%02x".format(it) }
}
