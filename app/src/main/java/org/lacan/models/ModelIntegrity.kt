package org.lacan.models

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal object ModelIntegrity {
    fun verify(file: File, spec: ModelFile): Boolean {
        if (!file.isFile || file.length() != spec.sizeBytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == spec.sha256
    }
    fun publish(staging: File, installed: File, files: List<ModelFile>, beforePublish: () -> Unit = {}) {
        check(files.all { verify(File(staging, it.name), it) }) { "Model integrity check failed" }
        beforePublish()
        // Publication has no suspension point after cancellation is checked. Roll back an
        // existing installation if the final atomic rename fails (e.g. filesystem error).
        val backup = File(installed.parentFile, ".${installed.name}-backup-${java.util.UUID.randomUUID()}")
        val hadInstalled = installed.exists()
        if (hadInstalled) Files.move(installed.toPath(), backup.toPath(), StandardCopyOption.ATOMIC_MOVE)
        try {
            Files.move(staging.toPath(), installed.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (failure: Throwable) {
            if (hadInstalled) {
                try { Files.move(backup.toPath(), installed.toPath(), StandardCopyOption.ATOMIC_MOVE) }
                catch (restore: Throwable) { failure.addSuppressed(restore) }
            }
            throw failure
        }
        if (hadInstalled) backup.deleteRecursively()
    }
}
