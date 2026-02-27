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
 * Cross-platform process execution using POSIX fork()/exec()/waitpid().
 *
 * Deliberately avoids system() because system() sets SIGINT to SIG_IGN in the
 * parent process while the child runs. This means pressing Ctrl+C kills the
 * child git process but leaves the parent CLI running. With fork()/exec(), the
 * parent's signal disposition is never modified, so SIGINT reaches the parent
 * normally and the signal handler in Main.kt can exit immediately.
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
        return forkExecShell(cmdString)
    }

    /**
     * Execute a raw shell command string.
     */
    fun executeShell(command: String): ProcessResult = forkExecShell(command)

    /**
     * Core implementation: fork a child process, exec `sh -c <command>` with
     * stdout/stderr redirected to temp files, then waitpid() for the result.
     *
     * Unlike system(), this does NOT mask SIGINT in the parent, so Ctrl+C
     * terminates the CLI immediately.
     */
    private fun forkExecShell(command: String): ProcessResult {
        val tag = "${getpid()}_${clock()}"
        val stdoutPath = "/tmp/wsm_out_$tag"
        val stderrPath = "/tmp/wsm_err_$tag"

        // Embed I/O redirection in the shell command; the child `sh` handles it.
        val shellCmd = "$command >$stdoutPath 2>$stderrPath"

        val pid = fork()

        // ---- CHILD PROCESS ----
        if (pid == 0) {
            memScoped {
                val argv = allocArray<CPointerVar<ByteVar>>(4)
                argv[0] = "sh".cstr.getPointer(this)
                argv[1] = "-c".cstr.getPointer(this)
                argv[2] = shellCmd.cstr.getPointer(this)
                argv[3] = null
                execv("/bin/sh", argv)
            }
            // execv only returns on error; terminate the child immediately.
            exit(127)
            // Unreachable — satisfies Kotlin's control-flow analysis.
            @Suppress("UNREACHABLE_CODE")
            return ProcessResult(127, "", "execv failed")
        }

        // ---- FORK FAILED ----
        if (pid < 0) {
            return ProcessResult(-1, "", "fork() failed")
        }

        // ---- PARENT PROCESS ----
        // Wait for the child.  waitpid() is interrupted by SIGINT (errno=EINTR),
        // at which point our signal handler in Main.kt calls exit(130).
        val exitCode = memScoped {
            val statusVar = alloc<IntVar>()
            waitpid(pid, statusVar.ptr, 0)
            // Equivalent to WEXITSTATUS(status): extract the 8-bit exit code.
            (statusVar.value shr 8) and 0xFF
        }

        val stdout = FileUtils.readFile(stdoutPath) ?: ""
        val stderr = FileUtils.readFile(stderrPath) ?: ""
        remove(stdoutPath)
        remove(stderrPath)

        return ProcessResult(exitCode = exitCode, stdout = stdout, stderr = stderr)
    }

    /**
     * Build a shell command string with optional cd and env-var prefixes.
     */
    private fun buildCommandString(
        command: List<String>,
        workingDir: String?,
        environment: Map<String, String>
    ): String {
        val parts = mutableListOf<String>()

        if (workingDir != null) {
            parts.add("cd ${shellEscape(workingDir)}")
        }

        for ((key, value) in environment) {
            parts.add("export ${shellEscape(key)}=${shellEscape(value)}")
        }

        val escapedCommand = command.joinToString(" ") { shellEscape(it) }
        parts.add(escapedCommand)

        return parts.joinToString(" && ")
    }

    /**
     * Escape a string for safe shell usage by wrapping in single quotes.
     * Prevents injection when user input is passed to the shell.
     */
    fun shellEscape(input: String): String {
        if (input.isEmpty()) return "''"
        if (input.all { it.isLetterOrDigit() || it in "/-_.=@:" }) return input
        return "'" + input.replace("'", "'\\''") + "'"
    }
}
