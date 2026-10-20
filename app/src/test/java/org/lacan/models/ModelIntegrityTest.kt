package org.lacan.models

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ModelIntegrityTest {
    private val bytes = "abc".toByteArray()
    private val artifact = ModelFile("model.bin", "https://example.org/model.bin", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", 3)

    @Test fun verifiesBothSizeAndHash() {
        val f = File.createTempFile("model", ".bin")
        try {
            f.writeBytes(bytes)
            assertTrue(ModelIntegrity.verify(f, artifact))
            f.writeText("abd")
            assertFalse(ModelIntegrity.verify(f, artifact))
            f.writeText("abcx")
            assertFalse(ModelIntegrity.verify(f, artifact))
        } finally { f.delete() }
    }

    @Test fun invalidStagingNeverReplacesInstalledModel() {
        val root = Files.createTempDirectory("models").toFile()
        try {
            val installed = File(root, "installed").apply { mkdir() }
            File(installed, artifact.name).writeBytes(bytes)
            val staging = File(root, "staging").apply { mkdir() }
            File(staging, artifact.name).writeText("bad")
            assertThrows(IllegalStateException::class.java) { ModelIntegrity.publish(staging, installed, listOf(artifact)) }
            assertArrayEquals(bytes, File(installed, artifact.name).readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun cancellationAfterVerificationDoesNotPublish() {
        val root = Files.createTempDirectory("models").toFile()
        try {
            val staging = File(root, "staging").apply { mkdir() }
            File(staging, artifact.name).writeBytes(bytes)
            val installed = File(root, "installed")
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                ModelIntegrity.publish(staging, installed, listOf(artifact)) {
                    throw java.util.concurrent.CancellationException()
                }
            }
            assertFalse(installed.exists())
            assertTrue(staging.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun cancellationBeforeReplacementPreservesInstalledModel() {
        val root = Files.createTempDirectory("models").toFile()
        try {
            val installed = File(root, "installed").apply { mkdir() }
            File(installed, artifact.name).writeBytes(bytes)
            val staging = File(root, "staging").apply { mkdir() }
            File(staging, artifact.name).writeBytes(bytes)
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                ModelIntegrity.publish(staging, installed, listOf(artifact)) { throw java.util.concurrent.CancellationException() }
            }
            assertArrayEquals(bytes, File(installed, artifact.name).readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun failedFinalMoveRestoresExistingDirectory() {
        val root = Files.createTempDirectory("models").toFile()
        try {
            val installed = File(root, "installed").apply { mkdir() }
            File(installed, artifact.name).writeBytes(bytes)
            val staging = File(root, "staging").apply { mkdir() }
            File(staging, artifact.name).writeBytes(bytes)
            assertThrows(java.io.IOException::class.java) {
                ModelIntegrity.publish(staging, installed, listOf(artifact)) { staging.deleteRecursively() }
            }
            assertArrayEquals(bytes, File(installed, artifact.name).readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun publishesVerifiedDirectoryAndRemovesStaging() {
        val root = Files.createTempDirectory("models").toFile()
        try {
            val staging = File(root, "staging").apply { mkdir() }
            File(staging, artifact.name).writeBytes(bytes)
            val installed = File(root, "installed")
            ModelIntegrity.publish(staging, installed, listOf(artifact))
            assertFalse(staging.exists())
            assertTrue(ModelIntegrity.verify(File(installed, artifact.name), artifact))
        } finally { root.deleteRecursively() }
    }
}
