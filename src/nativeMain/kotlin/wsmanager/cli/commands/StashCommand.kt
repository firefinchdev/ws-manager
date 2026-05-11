package wsmanager.cli.commands

import wsmanager.cli.Command
import wsmanager.cli.CommandContext
import wsmanager.cli.output.Printer
import wsmanager.engine.RepoOperation
import wsmanager.util.FileUtils

/**
 * Stash operations across all repositories.
 * Subcommands: push (default), pop, list, drop
 */
class StashCommand : Command {
    override val name = "stash"
    override val description = "Stash operations across all repositories"
    override val usage = "ws stash [push|pop|list|drop] [--message <msg>]"

    companion object {
        /** Prefix used to identify stash entries created by ws-manager */
        const val WS_STASH_PREFIX = "ws-manager: "
    }

    override suspend fun execute(args: List<String>, context: CommandContext): Int {
        val config = context.requireConfig()
        val repos = config.repositories
        val subcommand = args.firstOrNull() ?: "push"

        return when (subcommand) {
            "push", "save" -> stashPush(repos, config, context, args)
            "pop" -> stashPop(repos, config, context)
            "list" -> stashList(repos, config, context)
            "drop" -> stashDrop(repos, config, context, args)
            else -> {
                // If it's not a known subcommand, treat as "push" with a message
                stashPush(repos, config, context, args)
            }
        }
    }

    private suspend fun stashPush(
        repos: List<wsmanager.core.models.Repository>,
        config: wsmanager.config.WorkspaceConfig,
        context: CommandContext,
        args: List<String>
    ): Int {
        val userMessage = getArgValue(args, "--message") ?: getArgValue(args, "-m")
        val stashMessage = WS_STASH_PREFIX + (userMessage ?: "ws stash push")

        Printer.operationStart("Stash push", repos.size)

        // Stash is ATOMIC - we want all repos stashed or none
        val operation = RepoOperation<Boolean>(
            name = "stash push",
            captureSnapshot = { repo ->
                val repoPath = context.resolveRepoPath(repo)
                if (FileUtils.isDirectory(repoPath) && context.git.isGitRepository(repoPath)) {
                    // Track whether there were changes to stash
                    !context.git.isClean(repoPath)
                } else null
            },
            execute = { repo ->
                val repoPath = context.resolveRepoPath(repo)
                if (!FileUtils.isDirectory(repoPath) || !context.git.isGitRepository(repoPath)) {
                    return@RepoOperation wsmanager.git.GitResult.failure("Repository not found")
                }

                if (context.git.isClean(repoPath)) {
                    wsmanager.git.GitResult.success("No local changes to save (skipped)")
                } else {
                    context.git.stash(repoPath, stashMessage)
                }
            },
            rollback = { repo, hadChanges ->
                val repoPath = context.resolveRepoPath(repo)
                if (hadChanges) {
                    // Pop the stash we just pushed
                    context.git.stashPop(repoPath)
                } else {
                    wsmanager.git.GitResult.success("Nothing to rollback")
                }
            }
        )

        val result = context.engine.executeAtomic(
            operationName = "stash push",
            repositories = repos,
            operation = operation,
            onProgress = { _, result -> Printer.repoResult(result) }
        )

        Printer.operationSummary(result)
        return if (result.isFullSuccess) 0 else 1
    }

    private suspend fun stashPop(
        repos: List<wsmanager.core.models.Repository>,
        config: wsmanager.config.WorkspaceConfig,
        context: CommandContext
    ): Int {
        Printer.operationStart("Stash pop", repos.size)

        // Stash pop is ATOMIC
        val operation = RepoOperation<String>(
            name = "stash pop",
            captureSnapshot = { repo ->
                val repoPath = context.resolveRepoPath(repo)
                if (FileUtils.isDirectory(repoPath) && context.git.isGitRepository(repoPath)) {
                    // Capture stash list before popping
                    context.git.stashList(repoPath).output
                } else null
            },
            execute = { repo ->
                val repoPath = context.resolveRepoPath(repo)
                if (!FileUtils.isDirectory(repoPath) || !context.git.isGitRepository(repoPath)) {
                    return@RepoOperation wsmanager.git.GitResult.failure("Repository not found")
                }

                val stashList = context.git.stashList(repoPath)
                if (stashList.success && stashList.output.isBlank()) {
                    wsmanager.git.GitResult.success("No stash entries")
                } else {
                    // Only pop if the top stash entry was created by ws-manager
                    val topEntry = stashList.output.lines().firstOrNull() ?: ""
                    if (!topEntry.contains(WS_STASH_PREFIX)) {
                        wsmanager.git.GitResult.success("Top stash not created by ws-manager, skipped")
                    } else {
                        context.git.stashPop(repoPath)
                    }
                }
            },
            rollback = { repo, _ ->
                // After a stash pop, we can re-stash to undo
                val repoPath = context.resolveRepoPath(repo)
                context.git.stash(repoPath, "rollback: re-stash after failed pop")
            }
        )

        val result = context.engine.executeAtomic(
            operationName = "stash pop",
            repositories = repos,
            operation = operation,
            onProgress = { _, result -> Printer.repoResult(result) }
        )

        Printer.operationSummary(result)
        return if (result.isFullSuccess) 0 else 1
    }

    private suspend fun stashList(
        repos: List<wsmanager.core.models.Repository>,
        config: wsmanager.config.WorkspaceConfig,
        context: CommandContext
    ): Int {
        Printer.header("Stash List")

        val result = context.engine.executeBestEffort(
            operationName = "stash list",
            repositories = repos,
            operation = { repo ->
                val repoPath = context.resolveRepoPath(repo)
                if (!FileUtils.isDirectory(repoPath) || !context.git.isGitRepository(repoPath)) {
                    return@executeBestEffort wsmanager.git.GitResult.failure("Repository not found")
                }
                context.git.stashList(repoPath)
            },
            onProgress = { repo, result ->
                Printer.subHeader(repo.displayName)
                if (result.isSuccess) {
                    if (result.output.isNotBlank()) {
                        result.output.lines().forEach { println("    $it") }
                    } else {
                        Printer.info("  No stash entries")
                    }
                }
            }
        )

        Printer.newline()
        return if (result.isFullSuccess) 0 else 1
    }

    private suspend fun stashDrop(
        repos: List<wsmanager.core.models.Repository>,
        config: wsmanager.config.WorkspaceConfig,
        context: CommandContext,
        args: List<String>
    ): Int {
        val index = getArgValue(args, "--index")?.toIntOrNull() ?: 0

        Printer.operationStart("Stash drop (index: $index)", repos.size)

        val result = context.engine.executeBestEffort(
            operationName = "stash drop",
            repositories = repos,
            operation = { repo ->
                val repoPath = context.resolveRepoPath(repo)
                if (!FileUtils.isDirectory(repoPath) || !context.git.isGitRepository(repoPath)) {
                    return@executeBestEffort wsmanager.git.GitResult.failure("Repository not found")
                }
                context.git.stashDrop(repoPath, index)
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
