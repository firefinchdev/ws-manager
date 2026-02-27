package wsmanager.cli.commands

import wsmanager.cli.Command
import wsmanager.cli.CommandContext
import wsmanager.cli.output.Printer
import wsmanager.util.FileUtils

/**
 * Pull from remote across all repositories.
 * Uses BEST_EFFORT strategy (safe read-update operation).
 */
class PullCommand : Command {
    override val name = "pull"
    override val description = "Pull from remote across all repositories"
    override val usage = "ws-manager pull [--rebase] [--remote <remote>]"

    override suspend fun execute(args: List<String>, context: CommandContext): Int {
        val config = context.requireConfig()
        val repos = config.repositories
        val useRebase = args.contains("--rebase")
        val remote = getArgValue(args, "--remote")

        val label = buildString {
            append("Pull")
            if (useRebase) append(" --rebase")
        }
        Printer.operationStart(label, repos.size)

        val result = context.engine.executeBestEffort(
            operationName = "pull",
            repositories = repos,
            operation = { repo ->
                val repoPath = resolvePath(config.basePath, repo.path)

                if (!FileUtils.isDirectory(repoPath) || !context.git.isGitRepository(repoPath)) {
                    return@executeBestEffort wsmanager.git.GitResult.failure("Repository not found at $repoPath")
                }

                context.git.pull(
                    repoPath = repoPath,
                    remote = remote ?: repo.defaultRemote,
                    rebase = useRebase
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
