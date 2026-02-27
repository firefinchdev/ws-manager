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
        val explicitConfig = extractOption(globalArgs, "--config", "-c")
        val configPath = explicitConfig ?: resolveWorkspaceConfig()
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
                    Printer.error("Configuration file not found: $configPath")
                    Printer.info("Run 'ws-manager init' to create one, or use '--config <path>' to specify a custom path.")
                    return 1
                }

                // Show discovery hint when config was found in a parent directory
                val cwd = FileUtils.getCurrentDirectory()
                val absConfig = FileUtils.absolutePath(configPath)
                val cwdConfig = "$cwd/${WorkspaceConfig.DEFAULT_FILE_NAME}"
                if (explicitConfig == null && absConfig != cwdConfig) {
                    val c = TerminalColors
                    println(c.dim("  ↑ workspace: $absConfig"))
                }

                val parsed = ConfigParser.parseAndValidate(configPath)
                // Normalize basePath to absolute so repo paths resolve correctly
                // regardless of the directory the user runs the command from.
                val workspaceRoot = workspaceRootOf(configPath)
                val absoluteBasePath = when {
                    parsed.basePath == "." -> workspaceRoot
                    parsed.basePath.startsWith("/") -> parsed.basePath
                    else -> "$workspaceRoot/${parsed.basePath}"
                }
                parsed.copy(basePath = absoluteBasePath)
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
     * Return the absolute directory that contains the given config file.
     * This becomes the "workspace root" — all relative repo paths are resolved against it.
     */
    private fun workspaceRootOf(configPath: String): String {
        val abs = FileUtils.absolutePath(configPath)
        val lastSlash = abs.lastIndexOf('/')
        return if (lastSlash > 0) abs.substring(0, lastSlash) else "/"
    }

    /**
     * Resolve the workspace config path by walking up from the current working directory,
     * mirroring how `git` discovers the `.git` directory.
     *
     * Search order:
     *   1. Current directory
     *   2. Each parent directory up to the filesystem root
     *
     * Falls back to the default filename in the current directory if nothing is found,
     * so that normal "not found" error messages still fire correctly.
     */
    private fun resolveWorkspaceConfig(): String {
        val found = FileUtils.findFileUpwards(
            startDir = FileUtils.getCurrentDirectory(),
            fileName = WorkspaceConfig.DEFAULT_FILE_NAME
        )
        return found ?: WorkspaceConfig.DEFAULT_FILE_NAME
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
        println("    ${c.cyan("--config, -c")} <path>    Config file path (auto-discovered upwards if omitted)")
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
