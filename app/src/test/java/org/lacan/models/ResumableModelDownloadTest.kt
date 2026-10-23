package org.lacan.models

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files

class ResumableModelDownloadTest {
    private val spec = ModelFile("model.bin", "https://example.org/model", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", 3)
    private fun withFile(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("resume").toFile()
        try { block(File(dir, spec.name)) } finally { dir.deleteRecursively() }
    }
    private fun response(code: Int, body: String, range: String? = null, location: String? = null) = object : ModelDownloadResponse {
        override val status = code
        override fun header(name: String) = when (name) { "Content-Range" -> range; "Location" -> location; else -> null }
        override fun body() = ByteArrayInputStream(body.toByteArray())
        override fun close() = Unit
    }
    @Test fun resumesAtExistingByteAndValidatesRange() = withFile { file ->
        file.writeText("a")
        ResumableModelDownload { _, offset -> assertEquals(1L, offset); response(206, "bc", "bytes 1-2/3") }.download(spec, file)
        assertEquals("abc", file.readText())
    }
    @Test fun ignoredRangeRestartsInsteadOfAppending() = withFile { file ->
        file.writeText("a")
        ResumableModelDownload { _, _ -> response(200, "abc") }.download(spec, file)
        assertEquals("abc", file.readText())
    }
    @Test fun wrongContentRangeNeverAppends() = withFile { file ->
        file.writeText("a")
        assertThrows(IOException::class.java) { ResumableModelDownload { _, _ -> response(206, "bc", "bytes 0-1/3") }.download(spec, file) }
        assertEquals("a", file.readText())
    }
    @Test fun completeVerifiedFileNeedsNoNetwork() = withFile { file ->
        file.writeText("abc")
        ResumableModelDownload { _, _ -> error("Unexpected network") }.download(spec, file)
    }
    @Test fun completeCorruptFileIsReplaced() = withFile { file ->
        file.writeText("abd")
        ResumableModelDownload { _, offset -> assertEquals(0L, offset); response(200, "abc") }.download(spec, file)
        assertEquals("abc", file.readText())
    }
    @Test fun unsatisfiedRangeRestartsPartial() = withFile { file ->
        file.writeText("a")
        var requests = 0
        ResumableModelDownload { _, offset ->
            requests++
            if (requests == 1) response(416, "") else { assertEquals(0L, offset); response(200, "abc") }
        }.download(spec, file)
        assertEquals(2, requests)
        assertEquals("abc", file.readText())
    }
    @Test fun interruptionPreservesPartialForNextAttempt() = withFile { file ->
        assertThrows(IOException::class.java) { ResumableModelDownload { _, _ -> response(200, "a") }.download(spec, file) }
        assertEquals("a", file.readText())
        ResumableModelDownload { _, offset -> assertEquals(1L, offset); response(206, "bc", "bytes 1-2/3") }.download(spec, file)
        assertEquals("abc", file.readText())
    }
    @Test fun redirectCannotDowngradeToHttp() = withFile { file ->
        assertThrows(IllegalArgumentException::class.java) { ResumableModelDownload { _, _ -> response(302, "", location = "http://example.org/model") }.download(spec, file) }
        assertFalse(file.exists())
    }
    @Test fun hashMismatchIsDiscarded() = withFile { file ->
        assertThrows(IOException::class.java) { ResumableModelDownload { _, _ -> response(200, "abd") }.download(spec, file) }
        assertFalse(file.exists())
    }
    @Test fun httpsRedirectPreservesResumeOffset() = withFile { file ->
        file.writeText("a")
        var calls = 0
        ResumableModelDownload { url, offset ->
            assertEquals(1L, offset)
            if (++calls == 1) response(307, "", location = "https://cdn.example.org/file")
            else { assertEquals("cdn.example.org", url.host); response(206, "bc", "bytes 1-2/3") }
        }.download(spec, file)
        assertEquals("abc", file.readText())
    }
    @Test fun wrongTotalAndMissingRangeNeverAppend() = withFile { file ->
        file.writeText("a")
        for (range in listOf("bytes 1-2/4", null)) {
            assertThrows(IOException::class.java) { ResumableModelDownload { _, _ -> response(206, "bc", range) }.download(spec, file) }
            assertEquals("a", file.readText())
        }
    }
    @Test fun cancellationPreservesBytesAlreadyWritten() = withFile { file ->
        file.writeText("a")
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            ResumableModelDownload { _, _ -> response(206, "bc", "bytes 1-2/3") }.download(spec, file,
                checkActive = { if (file.length() == 3L) throw java.util.concurrent.CancellationException() })
        }
        assertEquals("abc", file.readText())
        ResumableModelDownload { _, _ -> error("Verified complete file should not download") }.download(spec, file)
    }
    @Test fun repeatedUnsatisfiedRangeDoesNotLoopForever() = withFile { file ->
        file.writeText("a")
        var calls = 0
        assertThrows(IOException::class.java) {
            ResumableModelDownload { _, _ -> calls++; response(416, "") }.download(spec, file)
        }
        assertEquals(2, calls)
    }

}
