package org.lacan.models

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class VoskModelArchiveTest {
    private val model = "vosk-model-small-ru-0.22"
    private fun archive(parent: File, entries: List<Pair<String, String>>): File =
        File(parent, "model.zip").also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                entries.forEach { (name, text) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
            }
        }
    private fun validEntries() = listOf(
        "$model/am/final.mdl" to "model", "$model/conf/model.conf" to "config", "$model/graph/" to ""
    )
    private fun inTemp(block: (File) -> Unit) {
        val parent = Files.createTempDirectory("vosk-archive").toFile()
        try { block(parent) } finally { parent.deleteRecursively() }
    }

    @Test fun extractsValidModelAndReplacesModifiedUnpackedBytes() = inTemp { parent ->
        val zip = archive(parent, validEntries())
        val root = VoskModelArchive.prepare(zip, parent)
        assertEquals(File(parent, "unpacked/$model"), root)
        File(root, "am/final.mdl").writeText("tampered")
        File(root, "unexpected").writeText("stale")
        val refreshed = VoskModelArchive.prepare(zip, parent)
        assertEquals("model", File(refreshed, "am/final.mdl").readText())
        assertFalse(File(refreshed, "unexpected").exists())
        assertEquals(listOf("model.zip", "unpacked"), parent.list()!!.sorted())
    }

    @Test fun rejectsUnsafePathsAndCleansStaging() = inTemp { parent ->
        for (path in listOf("../escape", "/absolute", "$model/../escape", "$model/./bad", "$model\\bad", "C:/bad")) {
            val zip = archive(parent, validEntries() + (path to "bad"))
            assertThrows(IllegalStateException::class.java) { VoskModelArchive.prepare(zip, parent) }
            assertEquals(listOf("model.zip"), parent.list()!!.toList())
        }
    }

    @Test fun rejectsMissingRequiredFilesWithoutReplacingPreviousModel() = inTemp { parent ->
        val good = VoskModelArchive.prepare(archive(parent, validEntries()), parent)
        val bad = archive(parent, listOf("$model/graph/" to ""))
        assertThrows(IllegalStateException::class.java) { VoskModelArchive.prepare(bad, parent) }
        assertEquals("model", File(good, "am/final.mdl").readText())
        assertFalse(parent.list()!!.any { it.startsWith(".vosk-") })
    }

    @Test fun boundsActualExpandedBytesAndEntryCount() = inTemp { parent ->
        val zip = archive(parent, validEntries())
        assertThrows(IllegalStateException::class.java) {
            VoskModelArchive.prepare(zip, parent, maxExpandedBytes = 4)
        }
        assertThrows(IllegalStateException::class.java) {
            VoskModelArchive.prepare(zip, parent, maxEntries = 2)
        }
        assertFalse(File(parent, "unpacked").exists())
        assertFalse(parent.list()!!.any { it.startsWith(".vosk-") })
    }

    @Test fun rejectsDuplicateNormalizedEntries() = inTemp { parent ->
        val zip = archive(parent, validEntries() + ("$model/graph" to "conflict"))
        assertThrows(IllegalStateException::class.java) { VoskModelArchive.prepare(zip, parent) }
        assertFalse(File(parent, "unpacked").exists())
    }
}
