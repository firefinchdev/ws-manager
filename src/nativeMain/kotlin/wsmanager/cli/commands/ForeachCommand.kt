package wsmanager.cli.commands

import wsmanager.cli.Command
import wsmanager.cli.CommandContext
import wsmanager.cli.output.Printer
import wsmanager.util.FileUtils

/**
 * Execute arbitrary shell commands across all repositories.
 * Uses BEST_EFFORT strategy - continues on failure.
 */
class ForeachCommand : Command {
    override val name = "foreach"
    override val description = "Execute a command in each repository"
    override val usage = "ws-manager foreach -- <command>"

    override suspend fun execute(args: List<String>, context: CommandContext): Int {
        val config = context.requireConfig()
        val repos = config.repositories

        // Everything after "--" or the whole args is the command
        val separatorIndex = args.indexOf("--")
        val commandParts = if (separatorIndex >= 0) {
            args.subList(separatorIndex + 1, args.size)
        } else {
            args
        }

        if (commandParts.isEmpty()) {
            Printer.error("No command specified. Usage: $usage")
            return 1
        }

        val command = commandParts.joinToString(" ")

        // Basic injection prevention - warn about dangerous patterns
        val dangerousPatterns = listOf("rm -rf /", "mkfs", "> /dev/", "dd if=")
        for (pattern in dangerousPatterns) {
            if (command.contains(pattern)) {
                Printer.error("Potentially dangerous command detected: '$pattern'. Aborting.")
                return 1
            }
        }

        Printer.operationStart("foreach: $command", repos.size)

        // Validate all repo paths before execution
        val validRepos = repos.filter { repo ->
            val repoPath = resolvePath(config.basePath, repo.path)
            if (!FileUtils.isDirectory(repoPath)) {
                Printer.warning("Skipping ${repo.displayName}: directory not found at $repoPath")
                false
            } else true
        }

        if (validRepos.isEmpty()) {
            Printer.error("No valid repositories found.")
            return 1
        }

        val result = context.engine.executeForEach(
            repositories = validRepos.map { repo ->
                repo.copy(path = resolvePath(config.basePath, repo.path))
            },
            command = command,
            onProgress = { _, result ->
                Printer.repoResult(result)
            }
        )

        Printer.operationSummary(result)
        return if (result.isFullSuccess) 0 else 1
    }

    private fun resolvePath(basePath: String, repoPath: String): String {
        return if (repoPath.startsWith("/")) repoPath
        else if (basePath == ".") repoPath
        else "$basePath/$repoPath"
    }
}
