package org.lacan.models

import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.zip.ZipInputStream

/** Call only after ModelRepository has verified the downloaded archive's size and SHA-256. */
internal object VoskModelArchive {
    @Synchronized fun prepare(
        archive: File,
        parent: File,
        knownRoot: String = "vosk-model-small-ru-0.22",
        maxExpandedBytes: Long = 512L * 1024 * 1024,
        maxEntries: Int = 1000
    ): File {
        require(knownRoot.matches(Regex("[A-Za-z0-9_-][A-Za-z0-9_.-]*")))
        require(maxExpandedBytes > 0 && maxEntries > 0)
        check(parent.isDirectory || parent.mkdirs()) { "Cannot create Vosk model directory" }
        val staging = Files.createTempDirectory(parent.toPath(), ".vosk-unpack-").toFile()
        try {
            extract(archive, staging, knownRoot, maxExpandedBytes, maxEntries)
            val root = File(staging, knownRoot)
            check(File(root, "am/final.mdl").isFile && File(root, "am/final.mdl").length() > 0 &&
                File(root, "conf/model.conf").isFile && File(root, "conf/model.conf").length() > 0 &&
                File(root, "graph").isDirectory) { "Incomplete Vosk model archive" }

            val installed = File(parent, "unpacked")
            val backup = File(parent, ".vosk-backup-${UUID.randomUUID()}")
            val hadInstalled = Files.exists(installed.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
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
            if (hadInstalled) deleteTree(backup.toPath())
            return File(installed, knownRoot)
        } catch (failure: Throwable) {
            try { deleteTree(staging.toPath()) }
            catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    private fun extract(archive: File, staging: File, knownRoot: String, maxBytes: Long, maxEntries: Int) {
        val seen = HashSet<String>()
        var count = 0
        var expanded = 0L
        val buffer = ByteArray(64 * 1024)
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                check(++count <= maxEntries) { "Too many Vosk archive entries" }
                val name = entry.name.removeSuffix("/")
                val components = name.split('/')
                check(name.isNotEmpty() && !name.startsWith('/') && '\\' !in name && ':' !in name &&
                    components.none { it.isEmpty() || it == "." || it == ".." } &&
                    components.first() == knownRoot) { "Unsafe Vosk archive path" }
                check(seen.add(name)) { "Duplicate Vosk archive entry" }
                val destination = File(staging, name)
                if (entry.isDirectory) {
                    check(zip.read() == -1) { "Vosk archive directory contains data" }
                    check(destination.isDirectory || destination.mkdirs()) { "Cannot create Vosk directory" }
                } else {
                    val directory = checkNotNull(destination.parentFile)
                    check(directory.isDirectory || directory.mkdirs())
                    destination.outputStream().buffered().use { output ->
                        while (true) {
                            val n = zip.read(buffer)
                            if (n < 0) break
                            check(n.toLong() <= maxBytes - expanded) { "Vosk archive exceeds expanded size limit" }
                            expanded += n
                            output.write(buffer, 0, n)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }

    // Do not follow symlinks in an old unpacked directory during replacement cleanup.
    private fun deleteTree(root: Path) {
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }
}
