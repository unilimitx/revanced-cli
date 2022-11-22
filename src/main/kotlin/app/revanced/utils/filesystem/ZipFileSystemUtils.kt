@file:Suppress("MemberVisibilityCanBePrivate")

package app.revanced.utils.filesystem

import java.io.Closeable
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry

internal class ZipFileUtils(zipFile: File) : Closeable {
    private var fileSystem = FileSystems.newFileSystem(zipFile.toPath(), mapOf("noCompression" to true))

    /**
     * Get a path to a file in the zip file system.
     *
     * @param path The path to the file.
     * @return The path to the file.
     */
    fun getFsPath(path: String): Path = fileSystem.getPath(path)

    /**
     * Delete files in the zip file system.
     *
     * @param paths The paths to the files.
     */
    fun decompress(vararg paths: String) =
        paths.forEach { Files.setAttribute(getFsPath(it), "zip:method", ZipEntry.STORED) }

    /**
     * Delete a file or path recursively from the zip file.
     *
     * @param path The path to delete.
     * @throws IllegalArgumentException If the path does not exist.
     */
    fun delete(path: String) {
        fun deleteRecursively(path: Path) {
            path.also {
                if (Files.isDirectory(it)) Files.list(it).forEach(::deleteRecursively)
            }.let(Files::delete)
        }

        getFsPath(path).also {
            if (Files.exists(it)) return@also
            throw IllegalStateException("File does not exist in the current file system")
        }.let(::deleteRecursively)
    }

    /**
     * Write a file to the zip file.
     *
     * @param fromPath The path to the file to write.
     */
    fun write(fromPath: Path) {
        Files.list(fromPath).forEach { path ->
            // delete files that already exist in the zip file root directory
            path.toString().let(::delete)
        }

        Files.walk(fromPath).skip(1).forEach { path ->
            getFsPath(path.toString()).also { toPath ->
                if (Files.isDirectory(path)) Files.createDirectories(toPath)
                else Files.copy(path, toPath, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    /**
     * Write a file to the zip file.
     *
     * @param path The path to write the [content] to.
     * @param content The content to write.
     */
    internal fun write(path: String, content: ByteArray) = Files.write(getFsPath(path), content)

    override fun close() = fileSystem.close()
}