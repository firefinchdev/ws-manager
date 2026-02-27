package wsmanager.cli.commands

import wsmanager.cli.Command
import wsmanager.cli.CommandContext
import wsmanager.cli.output.Printer
import wsmanager.util.FileUtils

/**
 * Fetch from remote across all repositories.
 * Uses BEST_EFFORT strategy (safe read operation).
 */
class FetchCommand : Command {
    override val name = "fetch"
    override val description = "Fetch from remote across all repositories"
    override val usage = "ws-manager fetch [--remote <remote>] [--prune]"

    override suspend fun execute(args: List<String>, context: CommandContext): Int {
        val config = context.requireConfig()
        val repos = config.repositories
        val prune = args.contains("--prune")
        val remote = getArgValue(args, "--remote")

        Printer.operationStart("Fetch", repos.size)

        val result = context.engine.executeBestEffort(
            operationName = "fetch",
            repositories = repos,
            operation = { repo ->
                val repoPath = resolvePath(config.basePath, repo.path)

                if (!FileUtils.isDirectory(repoPath) || !context.git.isGitRepository(repoPath)) {
                    return@executeBestEffort wsmanager.git.GitResult.failure("Repository not found at $repoPath")
                }

                context.git.fetch(
                    repoPath = repoPath,
                    remote = remote ?: repo.defaultRemote,
                    prune = prune
                )
            },
            onProgress = { _, result -> Printer.repoResult(result) }
        )

        Printer.operationSummary(result)
        return if (result.isFullSuccess) 0 else 1
    }

    private fun resolvePath(basePath: String, repoPath: String): String {
        return if (repoPath.startsWith("/")) repoPath
        else if (basePath == ".") repoPath
        else "$basePath/$repoPath"
    }

    private fun getArgValue(args: List<String>, flag: String): String? {
        val index = args.indexOf(flag)
        return if (index >= 0 && index + 1 < args.size) args[index + 1] else null
    }
}
