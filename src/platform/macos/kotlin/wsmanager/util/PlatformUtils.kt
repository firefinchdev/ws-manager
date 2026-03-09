@file:OptIn(ExperimentalForeignApi::class)

package wsmanager.util

import kotlinx.cinterop.*
import platform.posix.*

// ─── Directory creation ───────────────────────────────────────────────────────

/**
 * Create a directory at [path] with mode 0755.
 * Returns true on success or if the directory already exists.
 */
internal fun platformMkdir(path: String): Boolean =
    mkdir(path, 0b111_101_101u) == 0 || errno == EEXIST

// ─── Working directory ────────────────────────────────────────────────────────

/** Return the current working directory as an absolute string. */
internal fun platformGetCurrentDir(): String = memScoped {
    val buffer = allocArray<ByteVar>(4096)
    getcwd(buffer, 4096.toULong())
    buffer.toKString()
}

// ─── Temporary directory ──────────────────────────────────────────────────────

/** Platform temp directory (e.g. /tmp on POSIX). */
internal fun platformTempDir(): String = "/tmp"

// ─── Process spawning ─────────────────────────────────────────────────────────

/**
 * Execute [shellCmd] via `/bin/sh -c`, wait for completion, and return the
 * actual exit code.
 *
 * Uses fork()+execv()+waitpid() instead of system() so that the parent
 * process's SIGINT disposition is never modified.  Ctrl+C therefore
 * terminates the CLI immediately via the signal handler in Main.kt.
 */
internal fun platformSpawnAndWait(shellCmd: String): Int {
    val pid = fork()

    // ── child ──────────────────────────────────────────────────────────────────
    if (pid == 0) {
        memScoped {
            val argv = allocArray<CPointerVar<ByteVar>>(4)
            argv[0] = "sh".cstr.getPointer(this)
            argv[1] = "-c".cstr.getPointer(this)
            argv[2] = shellCmd.cstr.getPointer(this)
            argv[3] = null
            execv("/bin/sh", argv)
        }
        // execv only returns on error; terminate the child.
        exit(127)
        @Suppress("UNREACHABLE_CODE")
        return 127
    }

    // ── fork failed ───────────────────────────────────────────────────────────
    if (pid < 0) return -1

    // ── parent ─────────────────────────────────────────────────────────────────
    // waitpid is interrupted by SIGINT (errno=EINTR); our handler in Main.kt
    // calls exit(130) at that point.
    return memScoped {
        val statusVar = alloc<IntVar>()
        waitpid(pid, statusVar.ptr, 0)
        // WEXITSTATUS equivalent: extract the 8-bit exit code
        (statusVar.value shr 8) and 0xFF
    }
}

// ─── Shell escaping ───────────────────────────────────────────────────────────

/** Escape [input] for safe use inside a POSIX sh command string. */
internal fun platformShellEscape(input: String): String {
    if (input.isEmpty()) return "''"
    if (input.all { it.isLetterOrDigit() || it in "/-_.=@:" }) return input
    return "'" + input.replace("'", "'\\''") + "'"
}

/** Build a `cd` command for a POSIX shell. */
internal fun platformCdCommand(path: String): String = "cd ${platformShellEscape(path)}"
