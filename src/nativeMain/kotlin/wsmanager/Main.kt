@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package wsmanager

import kotlinx.cinterop.staticCFunction
import kotlinx.coroutines.runBlocking
import platform.posix.SIGINT
import platform.posix.exit
import platform.posix.signal
import wsmanager.cli.WsManagerApp
import kotlin.system.exitProcess

/**
 * Entry point for the ws-manager CLI tool.
 *
 * ws-manager is a multi-repository workspace management tool that enables
 * developers to manage multiple Git repositories as a single logical workspace.
 *
 * Features:
 * - Execute Git operations across all repos simultaneously
 * - Parallel execution with configurable concurrency
 * - ATOMIC strategy (all-or-nothing with rollback) for write operations
 * - BEST_EFFORT strategy (continue on failure) for read operations
 * - Structured output with progress indicators
 * - Human-readable JSON configuration
 * - Workspace config auto-discovery (walks up directory tree like `git`)
 */
fun main(args: Array<String>) {
    installSignalHandlers()

    val app = WsManagerApp()
    val exitCode = runBlocking {
        app.run(args)
    }
    exitProcess(exitCode)
}

/**
 * Install POSIX signal handlers so Ctrl+C (SIGINT) terminates the process
 * immediately with the conventional exit code 130 (128 + signal number 2).
 *
 * Why this is needed:
 *   The old ProcessRunner used system() which explicitly sets SIGINT to SIG_IGN
 *   in the parent process while a child command runs. This meant Ctrl+C killed
 *   the child git process but the parent CLI kept going.
 *
 *   ProcessRunner now uses fork()/exec()/waitpid() which never touches the
 *   parent's signal disposition. When Ctrl+C is pressed:
 *     1. SIGINT is sent to the entire process group (parent + child git processes).
 *     2. The child git processes exit.
 *     3. waitpid() in the parent is interrupted (EINTR) and returns.
 *     4. This handler runs on whichever thread received the signal.
 *     5. exit(130) terminates the whole process.
 */
private fun installSignalHandlers() {
    signal(SIGINT, staticCFunction { _: Int ->
        // exit() is called here to terminate the entire process, including all
        // coroutine worker threads.  Convention: Ctrl+C exit code = 128 + 2 = 130.
        exit(130)
    })
}
