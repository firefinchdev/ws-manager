package wsmanager.cli.commands

import wsmanager.cli.Command
import wsmanager.cli.CommandContext
import wsmanager.cli.output.Printer
import wsmanager.util.FileUtils

/**
 * Sync workspace: clone missing repositories and update existing ones (fetch + pull).
 */
class SyncCommand : Command {
    override val name = "sync"
    override val description = "Sync workspace: clone missing repos and update existing ones"
    override val usage = "ws-manager sync [--rebase]"

    override suspend fun execute(args: List<String>, context: CommandContext): Int {
        val config = context.requireConfig()
        val repos = config.repositories
        val useRebase = args.contains("--rebase")

        Printer.operationStart("Sync", repos.size)

        val result = context.engine.executeBestEffort(
            operationName = "sync",
            repositories = repos,
            operation = { repo ->
                val repoPath = context.resolveRepoPath(repo)

                if (FileUtils.isDirectory(repoPath) && context.git.isGitRepository(repoPath)) {
                    // Existing repo: fetch and pull
                    val fetchResult = context.git.fetch(repoPath, prune = true)
                    if (!fetchResult.success) {
                        return@executeBestEffort fetchResult
                    }

                    // Sync remotes - add any missing remotes from config
                    for (remote in repo.remotes) {
                        val existingRemotes = context.git.listRemotes(repoPath)
                        if (existingRemotes.success && !existingRemotes.output.contains(remote.alias)) {
                            context.git.addRemote(repoPath, remote.alias, remote.url)
                        }
                    }

                    // Pull current branch
                    context.git.pull(
                        repoPath = repoPath,
                        remote = repo.defaultRemote,
                        rebase = useRebase
                    )
                } else {
                    // Missing repo: clone
                    val defaultRemote = repo.getDefaultRemote()
                        ?: return@executeBestEffort wsmanager.git.GitResult.failure(
                            "No default remote configured for ${repo.name}"
                        )

                    val cloneResult = context.git.clone(
                        url = defaultRemote.url,
                        path = repoPath,
                        branch = repo.defaultBranch
                    )

                    if (cloneResult.success) {
                        repo.remotes
                            .filter { it.alias != repo.defaultRemote }
                            .forEach { remote ->
                                context.git.addRemote(repoPath, remote.alias, remote.url)
                            }
                    }

                    cloneResult
                }
            },
            onProgress = { _, result -> Printer.repoResult(result) }
        )

        Printer.operationSummary(result)
        return if (result.isFullSuccess) 0 else 1
    }


}
