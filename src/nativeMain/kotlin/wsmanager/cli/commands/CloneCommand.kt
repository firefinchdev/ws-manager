package wsmanager.cli.commands

import wsmanager.cli.Command
import wsmanager.cli.CommandContext
import wsmanager.cli.output.Printer
import wsmanager.util.FileUtils

/**
 * Clone all repositories defined in the workspace configuration.
 */
class CloneCommand : Command {
    override val name = "clone"
    override val description = "Clone all repositories in the workspace"
    override val usage = "ws-manager clone"

    override suspend fun execute(args: List<String>, context: CommandContext): Int {
        val config = context.requireConfig()
        val repos = config.repositories

        Printer.operationStart("Clone", repos.size)

        val result = context.engine.executeBestEffort(
            operationName = "clone",
            repositories = repos,
            operation = { repo ->
                val repoPath = resolvePath(config.basePath, repo.path)

                // Check if already cloned
                if (FileUtils.isDirectory(repoPath) && context.git.isGitRepository(repoPath)) {
                    wsmanager.git.GitResult.success("Already cloned at $repoPath")
                } else {
                    val defaultRemote = repo.getDefaultRemote()
                        ?: return@executeBestEffort wsmanager.git.GitResult.failure(
                            "No default remote '${repo.defaultRemote}' configured for ${repo.name}"
                        )

                    val cloneResult = context.git.clone(
                        url = defaultRemote.url,
                        path = repoPath,
                        branch = repo.defaultBranch
                    )

                    // If clone succeeded and there are additional remotes, add them
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

    private fun resolvePath(basePath: String, repoPath: String): String {
        return if (repoPath.startsWith("/")) repoPath
        else if (basePath == ".") repoPath
        else "$basePath/$repoPath"
    }
}
