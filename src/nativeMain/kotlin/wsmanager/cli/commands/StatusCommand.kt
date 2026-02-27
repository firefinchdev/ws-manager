package wsmanager.cli.commands

import wsmanager.cli.Command
import wsmanager.cli.CommandContext
import wsmanager.cli.output.Printer
import wsmanager.util.FileUtils

/**
 * Show status of all repositories in the workspace.
 * Uses BEST_EFFORT strategy (read-only operation).
 */
class StatusCommand : Command {
    override val name = "status"
    override val description = "Show status of all repositories"
    override val usage = "ws-manager status"

    override suspend fun execute(args: List<String>, context: CommandContext): Int {
        val config = context.requireConfig()
        val repos = config.repositories

        Printer.header("Workspace Status: ${config.name}")

        var hasErrors = false

        for (repo in repos) {
            val repoPath = context.resolveRepoPath(repo)

            if (!FileUtils.isDirectory(repoPath)) {
                Printer.repoStatus(repo, "N/A", "")
                Printer.warning("  Repository not found at $repoPath")
                hasErrors = true
                continue
            }

            if (!context.git.isGitRepository(repoPath)) {
                Printer.repoStatus(repo, "N/A", "")
                Printer.warning("  Not a Git repository: $repoPath")
                hasErrors = true
                continue
            }

            val branchResult = context.git.currentBranch(repoPath)
            val branchInfo = if (branchResult.success) branchResult.output else "unknown"

            val statusResult = context.git.status(repoPath, short = true)
            val statusOutput = if (statusResult.success) statusResult.output else "Error getting status"

            Printer.repoStatus(repo, branchInfo, statusOutput)
        }

        Printer.newline()
        return if (hasErrors) 1 else 0
    }


}
