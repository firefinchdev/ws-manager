package wsmanager.cli.commands

import wsmanager.cli.Command
import wsmanager.cli.CommandContext
import wsmanager.cli.output.Printer
import wsmanager.util.FileUtils

/**
 * Show git log across all repositories.
 * Uses BEST_EFFORT strategy (read-only).
 */
class LogCommand : Command {
    override val name = "log"
    override val description = "Show recent commits across all repositories"
    override val usage = "ws log [--count <n>]"

    override suspend fun execute(args: List<String>, context: CommandContext): Int {
        val config = context.requireConfig()
        val repos = config.repositories
        val count = getArgValue(args, "--count")?.toIntOrNull() ?: 5

        Printer.header("Recent Commits (last $count)")

        val result = context.engine.executeBestEffort(
            operationName = "log",
            repositories = repos,
            operation = { repo ->
                val repoPath = context.resolveRepoPath(repo)
                if (!FileUtils.isDirectory(repoPath) || !context.git.isGitRepository(repoPath)) {
                    return@executeBestEffort wsmanager.git.GitResult.failure("Repository not found")
                }
                context.git.log(repoPath, count, oneline = true)
            },
            onProgress = { repo, result ->
                Printer.subHeader(repo.displayName)
                if (result.isSuccess && result.output.isNotBlank()) {
                    result.output.lines().forEach { line ->
                        println("    $line")
                    }
                } else if (result.isFailed) {
                    Printer.error("  ${result.error}")
                }
            }
        )

        Printer.newline()
        return if (result.isFullSuccess) 0 else 1
    }



    private fun getArgValue(args: List<String>, flag: String): String? {
        val index = args.indexOf(flag)
        return if (index >= 0 && index + 1 < args.size) args[index + 1] else null
    }
}
