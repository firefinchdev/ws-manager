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
 * Cross-platform process execution using POSIX APIs.
 * Executes commands and captures stdout/stderr.
 */
object ProcessRunner {

    /**
     * Execute a command with arguments in a given working directory.
     * @param command List of command parts (e.g., ["git", "status"])
     * @param workingDir The directory to execute the command in
     * @param environment Optional environment variables
     * @return ProcessResult with exit code and captured output
     */
    fun execute(
        command: List<String>,
        workingDir: String? = null,
        environment: Map<String, String> = emptyMap()
    ): ProcessResult {
        val cmdString = buildCommandString(command, workingDir, environment)
        return executeShell(cmdString)
    }

    /**
     * Execute a raw shell command string.
     */
    fun executeShell(command: String): ProcessResult {
        // Create temp files for stdout and stderr
        val stdoutFile = "/tmp/wsm_stdout_${getpid()}_${clock()}"
        val stderrFile = "/tmp/wsm_stderr_${getpid()}_${clock()}"

        try {
            val fullCommand = "$command >$stdoutFile 2>$stderrFile"
            val exitCode = system(fullCommand)
            val exitStatus = (exitCode shr 8) and 0xFF // Extract actual exit status

            val stdout = FileUtils.readFile(stdoutFile) ?: ""
            val stderr = FileUtils.readFile(stderrFile) ?: ""

            return ProcessResult(
                exitCode = exitStatus,
                stdout = stdout,
                stderr = stderr
            )
        } finally {
            remove(stdoutFile)
            remove(stderrFile)
        }
    }

    /**
     * Build a command string with cd and env vars.
     */
    private fun buildCommandString(
        command: List<String>,
        workingDir: String?,
        environment: Map<String, String>
    ): String {
        val parts = mutableListOf<String>()

        // Change to working directory if specified
        if (workingDir != null) {
            parts.add("cd ${shellEscape(workingDir)}")
        }

        // Set environment variables
        for ((key, value) in environment) {
            parts.add("export ${shellEscape(key)}=${shellEscape(value)}")
        }

        // Build the command with proper escaping
        val escapedCommand = command.joinToString(" ") { shellEscape(it) }
        parts.add(escapedCommand)

        return parts.joinToString(" && ")
    }

    /**
     * Escape a string for safe shell usage.
     * Prevents injection by wrapping in single quotes.
     */
    fun shellEscape(input: String): String {
        if (input.isEmpty()) return "''"
        // If the string is simple (alphanumeric + common safe chars), no escaping needed
        if (input.all { it.isLetterOrDigit() || it in "/-_.=@:" }) {
            return input
        }
        // Wrap in single quotes, escaping any existing single quotes
        return "'" + input.replace("'", "'\\''") + "'"
    }
}
