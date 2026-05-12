package wsmanager.cli.commands

import wsmanager.cli.Command
import wsmanager.cli.CommandContext
import wsmanager.cli.output.Printer
import wsmanager.engine.RepoOperation
import wsmanager.util.FileUtils

/**
 * Merge a branch across all repositories.
 * Uses ATOMIC strategy (write operation with rollback capability).
 *
 * Flags:
 *   --default, -d   Merge each repo's own defaultBranch (as set in workspace.json)
 *                   into the current branch. No branch name argument is required.
 *   --no-ff         Create a merge commit even when fast-forward is possible.
 *   --message, -m   Use the given message for the merge commit.
 */
class MergeCommand : Command {
    override val name = "merge"
    override val description = "Merge a branch across all repositories"
    override val usage = "ws merge <branch> [--no-ff] [--message <msg>]\n" +
            "       ws merge --default|-d [--no-ff] [--message <msg>]"

    override suspend fun execute(args: List<String>, context: CommandContext): Int {
        val config = context.requireConfig()
        val noFf = args.contains("--no-ff")
        val defaultFlag = args.contains("--default") || args.contains("-d")
        val message = getArgValue(args, "--message") ?: getArgValue(args, "-m")
        val branchArgs = args.filter { !it.startsWith("-") && it != message }

        if (!defaultFlag && branchArgs.isEmpty()) {
            Printer.error("Branch name required, or use --default to merge each repo's default branch.")
            Printer.info("Usage: $usage")
            return 1
        }

        val fixedBranch = if (defaultFlag) null else branchArgs.first()
        val repos = config.repositories

        val operationLabel = if (defaultFlag) "Merge default branch" else "Merge '$fixedBranch'"
        Printer.operationStart(operationLabel, repos.size)

        val operation = RepoOperation<String>(
            name = "merge",
            captureSnapshot = { repo ->
                // Capture HEAD commit for rollback via reset
                val repoPath = context.resolveRepoPath(repo)
                if (FileUtils.isDirectory(repoPath) && context.git.isGitRepository(repoPath)) {
                    context.git.headCommit(repoPath).output
                } else null
            },
            execute = { repo ->
                val repoPath = context.resolveRepoPath(repo)
                val branch = fixedBranch ?: repo.defaultBranch

                if (!FileUtils.isDirectory(repoPath) || !context.git.isGitRepository(repoPath)) {
                    return@RepoOperation wsmanager.git.GitResult.failure("Repository not found at $repoPath")
                }

                context.git.merge(
                    repoPath = repoPath,
                    branch = branch,
                    noFf = noFf,
                    message = message
                )
            },
            rollback = { repo, headCommit ->
                // Rollback: abort merge if in progress, or reset to previous HEAD
                val repoPath = context.resolveRepoPath(repo)
                val abortResult = context.git.mergeAbort(repoPath)
                if (abortResult.success) {
                    abortResult
                } else {
                    // If merge completed (fast-forward), reset to previous HEAD
                    val resetCmd = listOf("git", "reset", "--hard", headCommit)
                    val result = wsmanager.util.ProcessRunner.execute(resetCmd, workingDir = repoPath)
                    wsmanager.git.GitResult.fromProcessResult(result)
                }
            }
        )

        val result = context.engine.executeAtomic(
            operationName = operationLabel,
            repositories = repos,
            operation = operation,
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
