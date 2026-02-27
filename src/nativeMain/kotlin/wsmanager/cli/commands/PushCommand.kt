package wsmanager.cli.commands

import wsmanager.cli.Command
import wsmanager.cli.CommandContext
import wsmanager.cli.output.Printer
import wsmanager.engine.RepoOperation
import wsmanager.util.FileUtils

/**
 * Push to remote across all repositories.
 * Uses ATOMIC strategy (write operation with potential for partial state).
 */
class PushCommand : Command {
    override val name = "push"
    override val description = "Push to remote across all repositories"
    override val usage = "ws-manager push [--remote <remote>] [--force] [--set-upstream]"

    override suspend fun execute(args: List<String>, context: CommandContext): Int {
        val config = context.requireConfig()
        val repos = config.repositories
        val force = args.contains("--force") || args.contains("-f")
        val setUpstream = args.contains("--set-upstream") || args.contains("-u")
        val remote = getArgValue(args, "--remote")

        if (force) {
            Printer.warning("Force push requested. This uses --force-with-lease for safety.")
        }

        Printer.operationStart("Push", repos.size)

        // Push is ATOMIC: we don't want some repos pushed and others not
        val operation = RepoOperation<String>(
            name = "push",
            captureSnapshot = { repo ->
                // Capture HEAD for potential reference (push can't truly be rolled back)
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

                val currentBranch = context.git.currentBranch(repoPath)
                val branchName = if (currentBranch.success) currentBranch.output else null

                context.git.push(
                    repoPath = repoPath,
                    remote = remote ?: repo.defaultRemote,
                    branch = branchName,
                    force = force,
                    setUpstream = setUpstream
                )
            },
            // Push rollback is not truly possible - we log it but can't undo a push
            rollback = null
        )

        val result = context.engine.executeAtomic(
            operationName = "push",
            repositories = repos,
            operation = operation,
            onProgress = { _, result -> Printer.repoResult(result) }
        )

        Printer.operationSummary(result)

        if (!result.isFullSuccess && result.succeeded.isNotEmpty()) {
            Printer.warning("Some repositories were pushed but others failed. Manual intervention may be needed.")
        }

        return if (result.isFullSuccess) 0 else 1
    }



    private fun getArgValue(args: List<String>, flag: String): String? {
        val index = args.indexOf(flag)
        return if (index >= 0 && index + 1 < args.size) args[index + 1] else null
    }
}
