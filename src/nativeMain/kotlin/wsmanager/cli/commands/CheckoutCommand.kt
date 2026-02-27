package wsmanager.cli.commands

import wsmanager.cli.Command
import wsmanager.cli.CommandContext
import wsmanager.cli.output.Printer
import wsmanager.engine.RepoOperation
import wsmanager.util.FileUtils

/**
 * Checkout a branch across all repositories.
 * Uses ATOMIC strategy since this is a write/state-changing operation.
 */
class CheckoutCommand : Command {
    override val name = "checkout"
    override val description = "Checkout a branch across all repositories"
    override val usage = "ws-manager checkout <branch> [--create]"

    override suspend fun execute(args: List<String>, context: CommandContext): Int {
        val config = context.requireConfig()
        val createFlag = args.contains("--create") || args.contains("-b")
        val branchArgs = args.filter { !it.startsWith("-") }

        if (branchArgs.isEmpty()) {
            Printer.error("Branch name required. Usage: $usage")
            return 1
        }

        val branch = branchArgs.first()
        val repos = config.repositories

        val operationName = if (createFlag) "Create & Checkout '$branch'" else "Checkout '$branch'"
        Printer.operationStart(operationName, repos.size)

        val operation = RepoOperation<String>(
            name = operationName,
            captureSnapshot = { repo ->
                val repoPath = resolvePath(config.basePath, repo.path)
                if (FileUtils.isDirectory(repoPath) && context.git.isGitRepository(repoPath)) {
                    context.git.currentBranch(repoPath).output
                } else null
            },
            execute = { repo ->
                val repoPath = resolvePath(config.basePath, repo.path)

                if (!FileUtils.isDirectory(repoPath) || !context.git.isGitRepository(repoPath)) {
                    return@RepoOperation wsmanager.git.GitResult.failure("Repository not found at $repoPath")
                }

                if (createFlag) {
                    // Create new branch and checkout
                    val createResult = context.git.createBranch(repoPath, branch)
                    if (!createResult.success) {
                        return@RepoOperation createResult
                    }
                    context.git.checkout(repoPath, branch, repo.defaultRemote)
                } else {
                    context.git.checkout(repoPath, branch, repo.defaultRemote)
                }
            },
            rollback = { repo, previousBranch ->
                val repoPath = resolvePath(config.basePath, repo.path)
                // Rollback: checkout previous branch
                val checkoutResult = context.git.checkout(repoPath, previousBranch)
                // If we created the branch, delete it
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

    private fun resolvePath(basePath: String, repoPath: String): String {
        return if (repoPath.startsWith("/")) repoPath
        else if (basePath == ".") repoPath
        else "$basePath/$repoPath"
    }
}
