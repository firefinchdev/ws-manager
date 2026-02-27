package wsmanager.cli.commands

import wsmanager.cli.Command
import wsmanager.cli.CommandContext
import wsmanager.cli.output.Printer
import wsmanager.util.FileUtils

/**
 * Remote management across all repositories.
 * Subcommands: list, add, remove, set-url
 */
class RemoteCommand : Command {
    override val name = "remote"
    override val description = "Remote management across all repositories"
    override val usage = "ws-manager remote [list|add|remove|set-url] [--name <name>] [--url <url>]"

    override suspend fun execute(args: List<String>, context: CommandContext): Int {
        val config = context.requireConfig()
        val repos = config.repositories
        val subcommand = args.firstOrNull() ?: "list"

        return when (subcommand) {
            "list" -> listRemotes(repos, config, context, args.contains("-v") || args.contains("--verbose"))
            "add" -> addRemote(repos, config, context, args)
            "remove" -> removeRemote(repos, config, context, args)
            "set-url" -> setUrl(repos, config, context, args)
            else -> {
                Printer.error("Unknown remote subcommand: $subcommand")
                Printer.info("Available: list, add, remove, set-url")
                1
            }
        }
    }

    private suspend fun listRemotes(
        repos: List<wsmanager.core.models.Repository>,
        config: wsmanager.config.WorkspaceConfig,
        context: CommandContext,
        verbose: Boolean
    ): Int {
        Printer.header("Remotes")

        val result = context.engine.executeBestEffort(
            operationName = "list remotes",
            repositories = repos,
            operation = { repo ->
                val repoPath = resolvePath(config.basePath, repo.path)
                if (!FileUtils.isDirectory(repoPath) || !context.git.isGitRepository(repoPath)) {
                    return@executeBestEffort wsmanager.git.GitResult.failure("Repository not found")
                }
                context.git.listRemotes(repoPath, verbose)
            },
            onProgress = { repo, result ->
                Printer.subHeader(repo.displayName)
                if (result.isSuccess) {
                    if (result.output.isNotBlank()) {
                        result.output.lines().forEach { println("    $it") }
                    } else {
                        Printer.info("  No remotes configured")
                    }
                } else {
                    Printer.error(result.error)
                }
            }
        )

        Printer.newline()
        return if (result.isFullSuccess) 0 else 1
    }

    private suspend fun addRemote(
        repos: List<wsmanager.core.models.Repository>,
        config: wsmanager.config.WorkspaceConfig,
        context: CommandContext,
        args: List<String>
    ): Int {
        val name = getArgValue(args, "--name")
        val url = getArgValue(args, "--url")

        if (name == null || url == null) {
            Printer.error("Both --name and --url are required for 'remote add'")
            return 1
        }

        Printer.operationStart("Add remote '$name'", repos.size)

        val result = context.engine.executeBestEffort(
            operationName = "add remote",
            repositories = repos,
            operation = { repo ->
                val repoPath = resolvePath(config.basePath, repo.path)
                if (!FileUtils.isDirectory(repoPath) || !context.git.isGitRepository(repoPath)) {
                    return@executeBestEffort wsmanager.git.GitResult.failure("Repository not found")
                }
                context.git.addRemote(repoPath, name, url)
            },
            onProgress = { _, result -> Printer.repoResult(result) }
        )

        Printer.operationSummary(result)
        return if (result.isFullSuccess) 0 else 1
    }

    private suspend fun removeRemote(
        repos: List<wsmanager.core.models.Repository>,
        config: wsmanager.config.WorkspaceConfig,
        context: CommandContext,
        args: List<String>
    ): Int {
        val name = getArgValue(args, "--name") ?: args.getOrNull(1)

        if (name == null) {
            Printer.error("Remote name required for 'remote remove'. Use --name <name>")
            return 1
        }

        Printer.operationStart("Remove remote '$name'", repos.size)

        val result = context.engine.executeBestEffort(
            operationName = "remove remote",
            repositories = repos,
            operation = { repo ->
                val repoPath = resolvePath(config.basePath, repo.path)
                if (!FileUtils.isDirectory(repoPath) || !context.git.isGitRepository(repoPath)) {
                    return@executeBestEffort wsmanager.git.GitResult.failure("Repository not found")
                }
                context.git.removeRemote(repoPath, name)
            },
            onProgress = { _, result -> Printer.repoResult(result) }
        )

        Printer.operationSummary(result)
        return if (result.isFullSuccess) 0 else 1
    }

    private suspend fun setUrl(
        repos: List<wsmanager.core.models.Repository>,
        config: wsmanager.config.WorkspaceConfig,
        context: CommandContext,
        args: List<String>
    ): Int {
        val name = getArgValue(args, "--name")
        val url = getArgValue(args, "--url")

        if (name == null || url == null) {
            Printer.error("Both --name and --url are required for 'remote set-url'")
            return 1
        }

        Printer.operationStart("Set URL for remote '$name'", repos.size)

        val result = context.engine.executeBestEffort(
            operationName = "set remote URL",
            repositories = repos,
            operation = { repo ->
                val repoPath = resolvePath(config.basePath, repo.path)
                if (!FileUtils.isDirectory(repoPath) || !context.git.isGitRepository(repoPath)) {
                    return@executeBestEffort wsmanager.git.GitResult.failure("Repository not found")
                }
                context.git.setRemoteUrl(repoPath, name, url)
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

    private fun getArgValue(args: List<String>, flag: String): String? {
        val index = args.indexOf(flag)
        return if (index >= 0 && index + 1 < args.size) args[index + 1] else null
    }
}
