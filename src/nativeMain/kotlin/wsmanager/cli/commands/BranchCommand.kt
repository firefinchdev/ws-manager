package wsmanager.cli.commands

import wsmanager.cli.Command
import wsmanager.cli.CommandContext
import wsmanager.cli.output.Printer
import wsmanager.engine.RepoOperation
import wsmanager.util.FileUtils

/**
 * Branch management across all repositories.
 * - List branches (BEST_EFFORT)
 * - Create branches (ATOMIC)
 * - Delete branches (ATOMIC)
 */
class BranchCommand : Command {
    override val name = "branch"
    override val description = "Branch management across all repositories"
    override val usage = "ws-manager branch [<name>] [--create] [--delete] [--force] [--all]"

    override suspend fun execute(args: List<String>, context: CommandContext): Int {
        val config = context.requireConfig()
        val repos = config.repositories
        val create = args.contains("--create") || args.contains("-c")
        val delete = args.contains("--delete") || args.contains("-d")
        val force = args.contains("--force") || args.contains("-f")
        val all = args.contains("--all") || args.contains("-a")
        val branchArgs = args.filter { !it.startsWith("-") }
        val branchName = branchArgs.firstOrNull()

        return when {
            create && branchName != null -> createBranch(branchName, repos, config, context)
            delete && branchName != null -> deleteBranch(branchName, force, repos, config, context)
            branchName == null -> listBranches(all, repos, config, context)
            else -> {
                Printer.error("Ambiguous arguments. Use --create or --delete with a branch name.")
                1
            }
        }
    }

    private suspend fun listBranches(
        all: Boolean,
        repos: List<wsmanager.core.models.Repository>,
        config: wsmanager.config.WorkspaceConfig,
        context: CommandContext
    ): Int {
        Printer.header("Branches")

        val result = context.engine.executeBestEffort(
            operationName = "list branches",
            repositories = repos,
            operation = { repo ->
                val repoPath = context.resolveRepoPath(repo)
                if (!FileUtils.isDirectory(repoPath) || !context.git.isGitRepository(repoPath)) {
                    return@executeBestEffort wsmanager.git.GitResult.failure("Repository not found")
                }
                context.git.listBranches(repoPath, all)
            },
            onProgress = { repo, result ->
                Printer.subHeader(repo.displayName)
                if (result.isSuccess && result.output.isNotBlank()) {
                    println(result.output)
                } else if (result.isFailed) {
                    Printer.error(result.error)
                }
            }
        )

        Printer.newline()
        return if (result.isFullSuccess) 0 else 1
    }

    private suspend fun createBranch(
        branchName: String,
        repos: List<wsmanager.core.models.Repository>,
        config: wsmanager.config.WorkspaceConfig,
        context: CommandContext
    ): Int {
        Printer.operationStart("Create branch '$branchName'", repos.size)

        val operation = RepoOperation<Unit>(
            name = "create branch",
            captureSnapshot = { },
            execute = { repo ->
                val repoPath = context.resolveRepoPath(repo)
                if (!FileUtils.isDirectory(repoPath) || !context.git.isGitRepository(repoPath)) {
                    return@RepoOperation wsmanager.git.GitResult.failure("Repository not found")
                }
                context.git.createBranch(repoPath, branchName)
            },
            rollback = { repo, _ ->
                val repoPath = context.resolveRepoPath(repo)
                context.git.deleteBranch(repoPath, branchName, force = true)
            }
        )

        val result = context.engine.executeAtomic(
            operationName = "create branch",
            repositories = repos,
            operation = operation,
            onProgress = { _, result -> Printer.repoResult(result) }
        )

        Printer.operationSummary(result)
        return if (result.isFullSuccess) 0 else 1
    }

    private suspend fun deleteBranch(
        branchName: String,
        force: Boolean,
        repos: List<wsmanager.core.models.Repository>,
        config: wsmanager.config.WorkspaceConfig,
        context: CommandContext
    ): Int {
        Printer.operationStart("Delete branch '$branchName'", repos.size)

        val operation = RepoOperation<String>(
            name = "delete branch",
            captureSnapshot = { repo ->
                // Capture the commit the branch points to for recreation
                val repoPath = context.resolveRepoPath(repo)
                if (FileUtils.isDirectory(repoPath) && context.git.isGitRepository(repoPath)) {
                    val result = wsmanager.util.ProcessRunner.execute(
                        listOf("git", "rev-parse", branchName),
                        workingDir = repoPath
                    )
                    if (result.isSuccess) result.output else null
                } else null
            },
            execute = { repo ->
                val repoPath = context.resolveRepoPath(repo)
                if (!FileUtils.isDirectory(repoPath) || !context.git.isGitRepository(repoPath)) {
                    return@RepoOperation wsmanager.git.GitResult.failure("Repository not found")
                }
                context.git.deleteBranch(repoPath, branchName, force)
            },
            rollback = { repo, commitHash ->
                // Recreate the branch at the same commit
                val repoPath = context.resolveRepoPath(repo)
                context.git.createBranch(repoPath, branchName, commitHash)
            }
        )

        val result = context.engine.executeAtomic(
            operationName = "delete branch",
            repositories = repos,
            operation = operation,
            onProgress = { _, result -> Printer.repoResult(result) }
        )

        Printer.operationSummary(result)
        return if (result.isFullSuccess) 0 else 1
    }


}
