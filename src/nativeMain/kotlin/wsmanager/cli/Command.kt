package wsmanager.cli

import wsmanager.config.WorkspaceConfig
import wsmanager.engine.ExecutionEngine
import wsmanager.git.GitOperations

/**
 * Base interface for all CLI commands.
 * Provides a standard structure for command execution.
 */
interface Command {
    /** The command name used on the CLI. */
    val name: String

    /** Short description of the command. */
    val description: String

    /** Usage string showing arguments and options. */
    val usage: String

    /**
     * Execute the command with parsed arguments.
     * @return Exit code (0 for success)
     */
    suspend fun execute(args: List<String>, context: CommandContext): Int
}

/**
 * Context provided to every command containing shared dependencies.
 */
data class CommandContext(
    val config: WorkspaceConfig?,
    val git: GitOperations,
    val engine: ExecutionEngine,
    val configPath: String,
    val concurrencyOverride: Int? = null
) {
    /**
     * Get the effective config, throwing if not loaded.
     */
    fun requireConfig(): WorkspaceConfig {
        return config ?: throw IllegalStateException(
            "No workspace configuration found. Run 'ws-manager init' first or specify --config path."
        )
    }

    /**
     * Get effective concurrency.
     */
    val effectiveConcurrency: Int
        get() = concurrencyOverride ?: config?.maxConcurrency ?: 4
}
