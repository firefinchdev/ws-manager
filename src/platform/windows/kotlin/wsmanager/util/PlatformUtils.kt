@file:OptIn(ExperimentalForeignApi::class)

package wsmanager.util

import kotlinx.cinterop.*
import platform.posix.*

// ─── Directory creation ───────────────────────────────────────────────────────

/**
 * Create a directory at [path].
 * On Windows MinGW, platform.posix.mkdir takes only ONE argument (no mode).
 * Returns true on success or if the directory already exists.
 */
internal fun platformMkdir(path: String): Boolean =
    mkdir(path) == 0 || errno == EEXIST

// ─── Working directory ────────────────────────────────────────────────────────

/**
 * Return the current working directory.
 * On Windows MinGW, getcwd's second parameter is Int (not ULong/size_t).
 */
internal fun platformGetCurrentDir(): String = memScoped {
    val buffer = allocArray<ByteVar>(4096)
    getcwd(buffer, 4096)          // Int on Windows, not ULong
    buffer.toKString()
}

// ─── Temporary directory ──────────────────────────────────────────────────────

/**
 * Windows temp directory from %TEMP% / %TMP% env vars.
 * Trailing backslash is stripped so callers can append "/filename" uniformly.
 */
internal fun platformTempDir(): String =
    getenv("TEMP")?.toKString()?.trimEnd('\\', '/')
        ?: getenv("TMP")?.toKString()?.trimEnd('\\', '/')
        ?: "C:/Temp"

// ─── Process spawning ─────────────────────────────────────────────────────────

/**
 * Execute [shellCmd] on Windows via system() which calls cmd.exe /C.
 *
 * cmd.exe supports:
 *   - && chaining
 *   - > and 2> file redirection
 *   - double-quoted paths with forward slashes
 *
 * Returns the process exit code directly (no WEXITSTATUS shift needed).
 *
 * Note: unlike the POSIX fork()/exec() implementation, system() on Windows
 * does NOT mask SIGINT in the parent.  Ctrl+C terminates the parent via
 * Main.kt's signal handler as normal.
 */
internal fun platformSpawnAndWait(shellCmd: String): Int =
    system(shellCmd)

// ─── Shell escaping ───────────────────────────────────────────────────────────

/**
 * Escape [input] for safe use inside a cmd.exe command string.
 * Uses double-quote wrapping (single quotes are not special in cmd.exe).
 */
internal fun platformShellEscape(input: String): String {
    if (input.isEmpty()) return "\"\""
    // Characters safe without quoting in cmd.exe
    if (input.all { it.isLetterOrDigit() || it in "/-_.=@:\\." }) return input
    // Wrap in double quotes; escape embedded double-quote characters
    return "\"" + input.replace("\"", "\\\"") + "\""
}

/**
 * Build a `cd` command for cmd.exe.
 * Uses /D so the drive letter is also changed (safe for cross-drive paths).
 */
internal fun platformCdCommand(path: String): String = "cd /D ${platformShellEscape(path)}"
