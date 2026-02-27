package wsmanager.cli.commands

import wsmanager.cli.Command
import wsmanager.cli.CommandContext
import wsmanager.cli.output.Printer
import wsmanager.engine.RepoOperation
import wsmanager.util.FileUtils

/**
 * Rebase current branch onto a target branch across all repositories.
 * Uses ATOMIC strategy with rebase --abort as rollback.
 */
class RebaseCommand : Command {
    override val name = "rebase"
    override val description = "Rebase current branch onto a target across all repositories"
    override val usage = "ws rebase <onto-branch>"

    override suspend fun execute(args: List<String>, context: CommandContext): Int {
        val config = context.requireConfig()
        val branchArgs = args.filter { !it.startsWith("-") }

        if (branchArgs.isEmpty()) {
            Printer.error("Target branch required. Usage: $usage")
            return 1
        }

        val onto = branchArgs.first()
        val repos = config.repositories

        Printer.operationStart("Rebase onto '$onto'", repos.size)

        val operation = RepoOperation<String>(
            name = "rebase",
            captureSnapshot = { repo ->
                val repoPath = context.resolveRepoPath(repo)
                if (FileUtils.isDirectory(repoPath) && context.git.isGitRepository(repoPath)) {
                    context.git.headCommit(repoPath).output
                } else null
            },
            execute = { repo ->
                val repoPath = context.resolveRepoPath(repo)

                if (!FileUtils.isDirectory(repoPath) || !context.git.isGitRepository(repoPath)) {
                    return@RepoOperation wsmanager.git.GitResult.failure("Repository not found at $repoPath")
                }

                context.git.rebase(repoPath, onto)
            },
            rollback = { repo, headCommit ->
                val repoPath = context.resolveRepoPath(repo)
                // Try aborting rebase first
                val abortResult = context.git.rebaseAbort(repoPath)
                if (abortResult.success) {
                    abortResult
                } else {
                    // Force reset to previous HEAD
                    val resetCmd = listOf("git", "reset", "--hard", headCommit)
                    val result = wsmanager.util.ProcessRunner.execute(resetCmd, workingDir = repoPath)
                    wsmanager.git.GitResult.fromProcessResult(result)
                }
            }
        )

        val result = context.engine.executeAtomic(
            operationName = "rebase",
            repositories = repos,
            operation = operation,
            onProgress = { _, result -> Printer.repoResult(result) }
        )

        Printer.operationSummary(result)
        return if (result.isFullSuccess) 0 else 1
    }


}
