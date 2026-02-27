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

        // Resolve and validate all repo paths before execution
        val validRepos = repos.mapNotNull { repo ->
            val repoPath = context.resolveRepoPath(repo)
            if (!FileUtils.isDirectory(repoPath)) {
                Printer.warning("Skipping ${repo.displayName}: directory not found at $repoPath")
                null
            } else repo.copy(path = repoPath)
        }

        if (validRepos.isEmpty()) {
            Printer.error("No valid repositories found.")
            return 1
        }

        val result = context.engine.executeForEach(
            repositories = validRepos,
            command = command,
            onProgress = { _, result ->
                Printer.repoResult(result)
            }
        )

        Printer.operationSummary(result)
        return if (result.isFullSuccess) 0 else 1
    }


}
