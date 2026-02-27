package wsmanager.cli.commands

import wsmanager.cli.Command
import wsmanager.cli.CommandContext
import wsmanager.cli.output.Printer
import wsmanager.engine.RepoOperation
import wsmanager.util.FileUtils

/**
 * Checkout a branch across all repositories.
 * Uses ATOMIC strategy since this is a write/state-changing operation.
 *
 * Flags:
 *   --default, -d   Checkout each repo's own defaultBranch (as set in workspace.json).
 *                   No branch name argument is required when this flag is used.
 *   --create, -b    Create the branch before checking it out.
 */
class CheckoutCommand : Command {
    override val name = "checkout"
    override val description = "Checkout a branch across all repositories"
    override val usage = "ws checkout <branch> [--create|-b]\n" +
            "       ws checkout --default|-d"

    override suspend fun execute(args: List<String>, context: CommandContext): Int {
        val config = context.requireConfig()
        val createFlag  = args.contains("--create") || args.contains("-b")
        val defaultFlag = args.contains("--default") || args.contains("-d")
        val branchArgs  = args.filter { !it.startsWith("-") }

        // Validate arguments
        if (!defaultFlag && branchArgs.isEmpty()) {
            Printer.error("Branch name required, or use --default to checkout each repo's default branch.")
            Printer.info("Usage: $usage")
            return 1
        }
        if (defaultFlag && createFlag) {
            Printer.error("--default and --create cannot be used together.")
            return 1
        }

        val fixedBranch = if (defaultFlag) null else branchArgs.first()
        val repos = config.repositories

        val operationName = when {
            defaultFlag -> "Checkout default branch"
            createFlag  -> "Create & Checkout '${fixedBranch!!}'"
            else        -> "Checkout '${fixedBranch!!}'"
        }
        Printer.operationStart(operationName, repos.size)

        val operation = RepoOperation<String>(
            name = operationName,
            captureSnapshot = { repo ->
                val repoPath = context.resolveRepoPath(repo)
                if (FileUtils.isDirectory(repoPath) && context.git.isGitRepository(repoPath)) {
                    context.git.currentBranch(repoPath).output
                } else null
            },
            execute = { repo ->
                val repoPath = context.resolveRepoPath(repo)
                // Each repo uses its own default branch when --default is set
                val branch = fixedBranch ?: repo.defaultBranch

                if (!FileUtils.isDirectory(repoPath) || !context.git.isGitRepository(repoPath)) {
                    return@RepoOperation wsmanager.git.GitResult.failure("Repository not found at $repoPath")
                }

                if (createFlag) {
                    val createResult = context.git.createBranch(repoPath, branch)
                    if (!createResult.success) return@RepoOperation createResult
                    context.git.checkout(repoPath, branch, repo.defaultRemote)
                } else {
                    context.git.checkout(repoPath, branch, repo.defaultRemote)
                }
            },
            rollback = { repo, previousBranch ->
                val repoPath = context.resolveRepoPath(repo)
                val branch = fixedBranch ?: repo.defaultBranch
                val checkoutResult = context.git.checkout(repoPath, previousBranch)
                if (createFlag) {
                    context.git.deleteBranch(repoPath, branch)
                }
                checkoutResult
            }
        )

        val result = context.engine.executeAtomic(
            operationName = operationName,
            repositories = repos,
            operation = operation,
            onProgress = { _, result -> Printer.repoResult(result) }
        )

        Printer.operationSummary(result)
        return if (result.isFullSuccess) 0 else 1
    }
}
