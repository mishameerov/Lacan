package org.lacan.models

import java.io.InputStream
import java.io.OutputStream

/** A content provider cannot write beyond the catalog's pinned size, even if its metadata lies. */
internal fun copyModelFile(
    input: InputStream,
    output: OutputStream,
    expectedBytes: Long,
    onProgress: (Long) -> Unit = {},
    checkActive: () -> Unit = {},
) {
    require(expectedBytes > 0)
    val buffer = ByteArray(128 * 1024)
    var copied = 0L
    while (true) {
        checkActive()
        val count = input.read(buffer)
        checkActive()
        if (count < 0) break
        check(count.toLong() <= expectedBytes - copied) { "Model exceeds expected size" }
        output.write(buffer, 0, count)
        copied += count
        onProgress(copied)
    }
    check(copied == expectedBytes) { "Model is shorter than expected size" }
}
