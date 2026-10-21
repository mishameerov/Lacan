package org.lacan.models

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException

class ModelFileCopyTest {
    @Test fun copiesExactlyExpectedBytes() {
        val output = ByteArrayOutputStream()
        copyModelFile(ByteArrayInputStream(byteArrayOf(1, 2, 3)), output, 3)
        assertArrayEquals(byteArrayOf(1, 2, 3), output.toByteArray())
    }
    @Test fun oversizedSourceCannotWritePastPinnedSize() {
        val output = ByteArrayOutputStream()
        assertThrows(IllegalStateException::class.java) {
            copyModelFile(ByteArrayInputStream(ByteArray(200_000)), output, 150_000)
        }
        assertTrue(output.size() <= 150_000)
    }
    @Test fun rejectsTruncatedSource() {
        assertThrows(IllegalStateException::class.java) {
            copyModelFile(ByteArrayInputStream(byteArrayOf(1)), ByteArrayOutputStream(), 3)
        }
    }
    @Test fun cancellationStopsCopyBeforePublication() {
        val output = ByteArrayOutputStream()
        assertThrows(CancellationException::class.java) {
            copyModelFile(ByteArrayInputStream(ByteArray(200_000)), output, 200_000, checkActive = { throw CancellationException() })
        }
        assertEquals(0, output.size())
    }
}
