@file:OptIn(ExperimentalForeignApi::class)

package wsmanager.util

import kotlinx.cinterop.*
import platform.posix.*

/**
 * Cross-platform file utility functions using POSIX APIs.
 */
object FileUtils {

    /**
     * Read a file's contents as a string.
     * Returns null if the file doesn't exist or can't be read.
     */
    fun readFile(path: String): String? {
        val file = fopen(path, "r") ?: return null
        try {
            val builder = StringBuilder()
            memScoped {
                val bufferSize = 4096
                val buffer = allocArray<ByteVar>(bufferSize)
                while (true) {
                    val line = fgets(buffer, bufferSize, file) ?: break
                    builder.append(line.toKString())
                }
            }
            return builder.toString()
        } finally {
            fclose(file)
        }
    }

    /**
     * Write content to a file.
     * Creates parent directories if needed.
     */
    fun writeFile(path: String, content: String) {
        // Ensure parent directory exists
        val parentDir = path.substringBeforeLast('/')
        if (parentDir != path && parentDir.isNotEmpty()) {
            mkdirRecursive(parentDir)
        }

        val file = fopen(path, "w")
            ?: throw RuntimeException("Cannot write to file: $path")
        try {
            fputs(content, file)
        } finally {
            fclose(file)
        }
    }

    /**
     * Check if a file or directory exists.
     */
    fun exists(path: String): Boolean {
        return access(path, F_OK) == 0
    }

    /**
     * Check if a path is a directory.
     */
    fun isDirectory(path: String): Boolean {
        if (!exists(path)) return false
        val dir = opendir(path)
        if (dir != null) {
            closedir(dir)
            return true
        }
        return false
    }

    /**
     * Create a directory recursively.
     */
    fun mkdirRecursive(path: String) {
        val parts = path.split("/").filter { it.isNotEmpty() }
        var current = if (path.startsWith("/")) "" else ""
        for (part in parts) {
            current = if (current.isEmpty()) {
                if (path.startsWith("/")) "/$part" else part
            } else {
                "$current/$part"
            }
            if (!exists(current)) {
                mkdir(current, 0b111_101_101u) // 0755
            }
        }
    }

    /**
     * Get the absolute path from a potentially relative path.
     */
    fun absolutePath(path: String): String {
        if (path.startsWith("/")) return path
        val cwd = getCurrentDirectory()
        return "$cwd/$path"
    }

    /**
     * Get current working directory.
     */
    fun getCurrentDirectory(): String {
        return memScoped {
            val bufferSize = 4096
            val buffer = allocArray<ByteVar>(bufferSize)
            getcwd(buffer, bufferSize.toULong())
            buffer.toKString()
        }
    }

    /**
     * Delete a file.
     */
    fun deleteFile(path: String): Boolean {
        return remove(path) == 0
    }

    /**
     * Walk up the directory tree from [startDir] looking for a file named [fileName],
     * stopping at the filesystem root. Mirrors how `git` discovers `.git` directories.
     *
     * @return The absolute path of the first match found, or null if not found anywhere.
     */
    fun findFileUpwards(startDir: String, fileName: String): String? {
        // Resolve to absolute path and strip any trailing slash
        var current = (if (startDir.startsWith("/")) startDir else absolutePath(startDir))
            .trimEnd('/')

        while (true) {
            val candidate = "$current/$fileName"
            if (exists(candidate)) return candidate

            // Compute parent directory
            val lastSlash = current.lastIndexOf('/')

            if (lastSlash < 0) break           // No slash at all — give up
            if (lastSlash == 0) {
                // We are one level below root; check root itself then stop
                val rootCandidate = "/$fileName"
                if (exists(rootCandidate)) return rootCandidate
                break
            }

            current = current.substring(0, lastSlash)
        }

        return null
    }

    /**
     * Returns the parent directory of [path], or null if already at the root.
     */
    fun parentDirectory(path: String): String? {
        val normalized = path.trimEnd('/')
        val lastSlash = normalized.lastIndexOf('/')
        return when {
            lastSlash <= 0 -> null
            else -> normalized.substring(0, lastSlash)
        }
    }
}
