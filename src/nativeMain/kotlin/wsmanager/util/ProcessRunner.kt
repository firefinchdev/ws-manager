@file:OptIn(ExperimentalForeignApi::class)

package wsmanager.util

import kotlinx.cinterop.*
import platform.posix.*

/**
 * Result of executing a process.
 */
data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
) {
    val isSuccess: Boolean get() = exitCode == 0
    val output: String get() = stdout.trim()
    val errorOutput: String get() = stderr.trim()

    /**
     * Combined output (stdout + stderr).
     */
    val combinedOutput: String
        get() = buildString {
            if (stdout.isNotBlank()) append(stdout.trim())
            if (stderr.isNotBlank()) {
                if (isNotEmpty()) appendLine()
                append(stderr.trim())
            }
        }
}

/**
 * Cross-platform process execution.
 *
 * POSIX (macOS / Linux): fork() + execv("/bin/sh -c") + waitpid()
 *   — parent's SIGINT disposition is never modified, so Ctrl+C in the
 *     terminal terminates the CLI immediately via Main.kt's signal handler.
 *
 * Windows (MinGW): system() via cmd.exe
 *   — cmd.exe handles the &&-chained command with stdout/stderr redirected
 *     to temp files.  SIGINT on Windows is delivered to all console
 *     processes; Main.kt's signal handler calls exit(130).
 *
 * The per-platform implementation lives in each platform source directory
 * via platformSpawnAndWait(), platformShellEscape(), platformCdCommand(),
 * and platformTempDir().
 */
object ProcessRunner {

    /**
     * Execute a command with arguments in a given working directory.
     * @param command List of command parts (e.g., ["git", "status"])
     * @param workingDir The directory to execute the command in
     * @param environment Optional environment variables
     */
    fun execute(
        command: List<String>,
        workingDir: String? = null,
        environment: Map<String, String> = emptyMap()
    ): ProcessResult {
        val cmdString = buildCommandString(command, workingDir, environment)
        return spawnAndCapture(cmdString)
    }

    /**
     * Execute a raw shell command string.
     */
    fun executeShell(command: String): ProcessResult = spawnAndCapture(command)

    /**
     * Core implementation: build a shell command that redirects stdout/stderr
     * to temp files, then delegate to the platform-specific spawn function.
     */
    private fun spawnAndCapture(command: String): ProcessResult {
        val tag    = "${getpid()}_${clock()}"
        val tmpDir = platformTempDir()
        val stdoutPath = "$tmpDir/wsm_out_$tag"
        val stderrPath = "$tmpDir/wsm_err_$tag"

        // Redirect stdout and stderr to temp files inside the shell command.
        // Both POSIX sh and Windows cmd.exe support > and 2> redirection.
        val shellCmd = "$command >$stdoutPath 2>$stderrPath"

        val exitCode = platformSpawnAndWait(shellCmd)

        val stdout = FileUtils.readFile(stdoutPath) ?: ""
        val stderr = FileUtils.readFile(stderrPath) ?: ""
        remove(stdoutPath)
        remove(stderrPath)

        return ProcessResult(exitCode = exitCode, stdout = stdout, stderr = stderr)
    }

    /**
     * Build a shell command string with optional cd and env-var prefixes.
     * Uses platform-appropriate cd command and quoting via platformCdCommand()
     * and platformShellEscape().
     */
    private fun buildCommandString(
        command: List<String>,
        workingDir: String?,
        environment: Map<String, String>
    ): String {
        val parts = mutableListOf<String>()

        if (workingDir != null) {
            // platformCdCommand handles `cd` syntax differences:
            //   POSIX  → cd '/path/to/repo'
            //   Windows → cd /D "D:\path\to\repo"
            parts.add(platformCdCommand(workingDir))
        }

        for ((key, value) in environment) {
            parts.add("export ${platformShellEscape(key)}=${platformShellEscape(value)}")
        }

        val escapedCommand = command.joinToString(" ") { platformShellEscape(it) }
        parts.add(escapedCommand)

        return parts.joinToString(" && ")
    }

    /**
     * Escape a string for safe shell usage.
     * Delegates to the platform-specific implementation.
     */
    fun shellEscape(input: String): String = platformShellEscape(input)
}
