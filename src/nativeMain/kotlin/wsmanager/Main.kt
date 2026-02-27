package wsmanager

import kotlinx.coroutines.runBlocking
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
 */
fun main(args: Array<String>) {
    val app = WsManagerApp()
    val exitCode = runBlocking {
        app.run(args)
    }
    exitProcess(exitCode)
}
