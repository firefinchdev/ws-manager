package wsmanager.cli.commands

import wsmanager.cli.Command
import wsmanager.cli.CommandContext
import wsmanager.cli.output.Printer
import wsmanager.util.FileUtils

/**
 * Pull from remote across all repositories.
 * Uses BEST_EFFORT strategy (safe read-update operation).
 *
 * Options
 * ──────────────────────────────────────────────────────────────────────────
 *   --rebase          Use rebase instead of merge
 *   --remote <alias>  Pull from a specific remote (default: each repo's default_remote)
 *   --current | -c    Explicitly pass the current branch name to git pull.
 *                     Instead of `git pull origin`, runs `git pull origin <branch>`.
 *                     Useful when the upstream tracking ref is not configured, or when
 *                     you want to be explicit about which branch is being pulled.
 */
class PullCommand : Command {
    override val name = "pull"
    override val description = "Pull from remote across all repositories"
    override val usage = "ws pull [--rebase] [--remote <remote>] [--current]"

    override suspend fun execute(args: List<String>, context: CommandContext): Int {
        val config = context.requireConfig()
        val repos = config.repositories
        val useRebase    = args.contains("--rebase")
        val pullCurrent  = args.contains("--current") || args.contains("-c")
        val remote       = getArgValue(args, "--remote")

        val label = buildString {
            append("Pull")
            if (useRebase)   append(" --rebase")
            if (pullCurrent) append(" (current branch)")
        }
        Printer.operationStart(label, repos.size)

        val result = context.engine.executeBestEffort(
            operationName = "pull",
            repositories = repos,
            operation = { repo ->
                val repoPath = context.resolveRepoPath(repo)

                if (!FileUtils.isDirectory(repoPath) || !context.git.isGitRepository(repoPath)) {
                    return@executeBestEffort wsmanager.git.GitResult.failure("Repository not found at $repoPath")
                }

                // When --current is set, resolve the live branch name for this repo
                // and pass it explicitly to git pull so the command is unambiguous.
                val branch: String? = if (pullCurrent) {
                    val branchResult = context.git.currentBranch(repoPath)
                    if (!branchResult.success || branchResult.output.isBlank()) {
                        return@executeBestEffort wsmanager.git.GitResult.failure(
                            "Could not determine current branch: ${branchResult.output}"
                        )
                    }
                    branchResult.output
                } else null

                context.git.pull(
                    repoPath = repoPath,
                    remote   = remote ?: repo.defaultRemote,
                    branch   = branch,
                    rebase   = useRebase
                )
            },
            onProgress = { _, result -> Printer.repoResult(result) }
        )

        Printer.operationSummary(result)
        return if (result.isFullSuccess) 0 else 1
    }



    private fun getArgValue(args: List<String>, flag: String): String? {
        val index = args.indexOf(flag)
        return if (index >= 0 && index + 1 < args.size) args[index + 1] else null
    }
}
