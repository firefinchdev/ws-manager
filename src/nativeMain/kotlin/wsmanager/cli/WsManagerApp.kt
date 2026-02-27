package wsmanager.cli

import wsmanager.cli.commands.*
import wsmanager.cli.output.Printer
import wsmanager.cli.output.TerminalColors
import wsmanager.config.ConfigParseException
import wsmanager.config.ConfigParser
import wsmanager.config.ConfigValidationException
import wsmanager.config.WorkspaceConfig
import wsmanager.engine.ExecutionEngine
import wsmanager.git.GitCommandExecutor
import wsmanager.util.FileUtils

/**
 * Main CLI application.
 * Parses global options, resolves commands, and dispatches execution.
 */
class WsManagerApp {

    private val commands: Map<String, Command> = listOf(
        InitCommand(),
        CloneCommand(),
        SyncCommand(),
        StatusCommand(),
        CheckoutCommand(),
        PullCommand(),
        PushCommand(),
        FetchCommand(),
        MergeCommand(),
        RebaseCommand(),
        BranchCommand(),
        RemoteCommand(),
        StashCommand(),
        ForeachCommand(),
        LogCommand()
    ).associateBy { it.name }

    /**
     * Run the CLI with the given arguments.
     * @return Exit code
     */
    suspend fun run(args: Array<String>): Int {
        if (args.isEmpty()) {
            printHelp()
            return 0
        }

        // Parse global options
        val globalArgs = args.toMutableList()
        val configPath = extractOption(globalArgs, "--config", "-c")
            ?: WorkspaceConfig.DEFAULT_FILE_NAME
        val concurrencyStr = extractOption(globalArgs, "--concurrency", "-j")
        val concurrency = concurrencyStr?.toIntOrNull()

        // Check for help
        if (globalArgs.contains("--help") || globalArgs.contains("-h")) {
            val cmdName = globalArgs.firstOrNull { !it.startsWith("-") }
            if (cmdName != null && commands.containsKey(cmdName)) {
                printCommandHelp(commands[cmdName]!!)
            } else {
                printHelp()
            }
            return 0
        }

        // Check for version
        if (globalArgs.contains("--version") || globalArgs.contains("-v")) {
            println("ws-manager version 1.0.0")
            return 0
        }

        // Find the command
        val commandName = globalArgs.firstOrNull { !it.startsWith("-") }
        if (commandName == null) {
            printHelp()
            return 0
        }

        val command = commands[commandName]
        if (command == null) {
            Printer.error("Unknown command: '$commandName'")
            Printer.info("Run 'ws-manager --help' for available commands.")
            return 1
        }

        // Remove the command name from args, leaving only command-specific args
        val commandArgs = globalArgs.filter { it != commandName }

        // Load config (not required for 'init')
        val config = if (commandName != "init") {
            try {
                if (!FileUtils.exists(configPath)) {
                    if (commandName == "init") {
                        null
                    } else {
                        Printer.error("Configuration file not found: $configPath")
                        Printer.info("Run 'ws-manager init' to create one, or use '--config <path>' to specify a custom path.")
                        return 1
                    }
                } else {
                    ConfigParser.parseAndValidate(configPath)
                }
            } catch (e: ConfigParseException) {
                Printer.error("Configuration error: ${e.message}")
                return 1
            } catch (e: ConfigValidationException) {
                Printer.error("Configuration validation failed:")
                e.errors.forEach { error ->
                    Printer.error("  ${error.field}: ${error.message}")
                }
                return 1
            }
        } else {
            null
        }

        // Build context
        val effectiveConcurrency = concurrency ?: config?.maxConcurrency ?: 4
        val git = GitCommandExecutor()
        val engine = ExecutionEngine(maxConcurrency = effectiveConcurrency)

        val context = CommandContext(
            config = config,
            git = git,
            engine = engine,
            configPath = configPath,
            concurrencyOverride = concurrency
        )

        // Execute command
        return try {
            command.execute(commandArgs, context)
        } catch (e: IllegalStateException) {
            Printer.error(e.message ?: "An error occurred")
            1
        } catch (e: Exception) {
            Printer.error("Unexpected error: ${e.message}")
            1
        }
    }

    /**
     * Extract a global option and its value from the args list.
     * Removes the option and value from the list.
     */
    private fun extractOption(args: MutableList<String>, longFlag: String, shortFlag: String): String? {
        for (flag in listOf(longFlag, shortFlag)) {
            val index = args.indexOf(flag)
            if (index >= 0 && index + 1 < args.size) {
                val value = args[index + 1]
                args.removeAt(index + 1)
                args.removeAt(index)
                return value
            }
        }
        return null
    }

    private fun printHelp() {
        val c = TerminalColors
        println()
        println(c.boldCyan("  ws-manager") + " — Multi-Repository Workspace Manager")
        println(c.dim("  Manage multiple Git repositories as a single workspace"))
        println()
        println(c.bold("  USAGE:"))
        println("    ws-manager ${c.cyan("<command>")} [options]")
        println()
        println(c.bold("  WORKSPACE COMMANDS:"))
        printCommandEntry("init", "Initialize a new workspace configuration")
        printCommandEntry("clone", "Clone all repositories in the workspace")
        printCommandEntry("sync", "Clone missing repos and update existing ones")
        printCommandEntry("status", "Show status of all repositories")
        printCommandEntry("foreach", "Execute a command in each repository")
        printCommandEntry("log", "Show recent commits across all repositories")
        println()
        println(c.bold("  GIT COMMANDS:"))
        printCommandEntry("checkout", "Checkout a branch across all repositories")
        printCommandEntry("pull", "Pull from remote across all repositories")
        printCommandEntry("push", "Push to remote across all repositories")
        printCommandEntry("fetch", "Fetch from remote across all repositories")
        printCommandEntry("merge", "Merge a branch across all repositories")
        printCommandEntry("rebase", "Rebase onto a branch across all repositories")
        printCommandEntry("branch", "Branch management (list, create, delete)")
        printCommandEntry("remote", "Remote management (list, add, remove, set-url)")
        printCommandEntry("stash", "Stash operations (push, pop, list, drop)")
        println()
        println(c.bold("  GLOBAL OPTIONS:"))
        println("    ${c.cyan("--config, -c")} <path>    Path to workspace config file (default: workspace.json)")
        println("    ${c.cyan("--concurrency, -j")} <n>  Max parallel operations (overrides config)")
        println("    ${c.cyan("--help, -h")}             Show help")
        println("    ${c.cyan("--version, -v")}          Show version")
        println()
        println(c.dim("  Run 'ws-manager <command> --help' for command-specific help."))
        println()
    }

    private fun printCommandEntry(name: String, description: String) {
        val c = TerminalColors
        println("    ${c.green(name.padEnd(14))} $description")
    }

    private fun printCommandHelp(command: Command) {
        val c = TerminalColors
        println()
        println(c.boldCyan("  ${command.name}") + " — ${command.description}")
        println()
        println(c.bold("  USAGE:"))
        println("    ${command.usage}")
        println()
    }
}
