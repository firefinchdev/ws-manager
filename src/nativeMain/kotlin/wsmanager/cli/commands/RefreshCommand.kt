package wsmanager.cli.commands

import wsmanager.cli.Command
import wsmanager.cli.CommandContext
import wsmanager.cli.output.Printer
import wsmanager.git.GitResult
import wsmanager.util.FileUtils

/**
 * Refresh workspace to a clean, latest state — the "start a new task" command.
 *
 * For each **existing** repository (in order):
 *   1. Discard all local changes  : git reset --hard HEAD
 *   2. (opt) Remove untracked files: git clean -fd         [--clean-untracked]
 *   3. Checkout default branch     : git checkout <defaultBranch>
 *   4. Sync remotes                : add missing, remove extra, update changed URLs
 *   5. Fetch                       : git fetch --all --prune
 *   6. Pull                        : git pull <defaultRemote> <defaultBranch>
 *
 * For each **missing** repository:
 *   - Clones from the default remote and adds any extra remotes from config.
 *
 * Strategy: BEST_EFFORT — all repositories are processed; individual failures
 * do not halt other repos.
 */
class RefreshCommand : Command {
    override val name = "refresh"
    override val description = "Clean state + pull latest across all repos (new-task workflow)"
    override val usage = "ws-manager refresh [--clean-untracked]"

    override suspend fun execute(args: List<String>, context: CommandContext): Int {
        val config = context.requireConfig()
        val repos = config.repositories
        val cleanUntracked = args.contains("--clean-untracked")

        val label = if (cleanUntracked) "Refresh (+ clean untracked)" else "Refresh"
        Printer.operationStart(label, repos.size)

        val result = context.engine.executeBestEffort(
            operationName = "refresh",
            repositories = repos,
            operation = { repo ->
                val repoPath = context.resolveRepoPath(repo)

                // ── Missing repo: clone it ───────────────────────────────────
                if (!FileUtils.isDirectory(repoPath) || !context.git.isGitRepository(repoPath)) {
                    return@executeBestEffort cloneRepo(repo, repoPath, context)
                }

                val steps = mutableListOf<String>()

                // Step 1 ─ Discard staged + unstaged changes to tracked files
                val discardResult = context.git.discardChanges(repoPath)
                if (!discardResult.success) {
                    return@executeBestEffort GitResult.failure(
                        "[step 1/discard] ${discardResult.error}"
                    )
                }
                steps.add("changes discarded")

                // Step 2 ─ (Optional) Remove untracked files and directories
                if (cleanUntracked) {
                    val cleanResult = context.git.cleanUntracked(repoPath)
                    if (!cleanResult.success) {
                        return@executeBestEffort GitResult.failure(
                            "[step 2/clean] ${cleanResult.error}"
                        )
                    }
                    steps.add("untracked cleaned")
                }

                // Step 3 ─ Checkout the repo's own default branch
                val checkoutResult = context.git.checkout(
                    repoPath = repoPath,
                    branch = repo.defaultBranch,
                    remote = repo.defaultRemote
                )
                if (!checkoutResult.success) {
                    return@executeBestEffort GitResult.failure(
                        "[step 3/checkout ${repo.defaultBranch}] ${checkoutResult.error}"
                    )
                }
                steps.add("on ${repo.defaultBranch}")

                // Step 4 ─ Sync remotes: add missing, remove extra, update changed URLs
                val remoteSyncSummary = syncRemotes(repo, repoPath, context)
                if (remoteSyncSummary.isNotEmpty()) steps.add(remoteSyncSummary)

                // Step 5 ─ Fetch all remotes, prune deleted remote-tracking branches
                val fetchResult = context.git.fetch(repoPath, prune = true)
                if (!fetchResult.success) {
                    return@executeBestEffort GitResult.failure(
                        "[step 5/fetch] ${fetchResult.error}"
                    )
                }

                // Step 6 ─ Pull from default remote on default branch
                val pullResult = context.git.pull(
                    repoPath = repoPath,
                    remote = repo.defaultRemote,
                    branch = repo.defaultBranch
                )
                if (!pullResult.success) {
                    return@executeBestEffort GitResult.failure(
                        "[step 6/pull] ${pullResult.error}"
                    )
                }

                val pullSummary = pullResult.output.lines().firstOrNull { it.isNotBlank() }
                    ?.trim() ?: "up to date"
                steps.add(pullSummary)

                GitResult.success(steps.joinToString("  ·  "))
            },
            onProgress = { _, result -> Printer.repoResult(result) }
        )

        Printer.operationSummary(result)
        return if (result.isFullSuccess) 0 else 1
    }

    /**
     * Clone a missing repository and add any extra remotes from config.
     */
    private fun cloneRepo(
        repo: wsmanager.core.models.Repository,
        repoPath: String,
        context: CommandContext
    ): GitResult {
        val defaultRemote = repo.getDefaultRemote()
            ?: return GitResult.failure("No default remote configured for '${repo.name}'")

        val cloneResult = context.git.clone(
            url = defaultRemote.url,
            path = repoPath,
            branch = repo.defaultBranch
        )
        if (!cloneResult.success) return cloneResult

        // Add any additional remotes beyond the one that was cloned
        repo.remotes
            .filter { it.alias != repo.defaultRemote }
            .forEach { remote -> context.git.addRemote(repoPath, remote.alias, remote.url) }

        return GitResult.success("cloned from ${defaultRemote.url}")
    }

    /**
     * Sync the local git remotes against the workspace config:
     *  - Add remotes that are in config but missing locally
     *  - Update URLs that have changed
     *  - Remove remotes that exist locally but are NOT in config
     *
     * Returns a short human-readable summary of what was changed (empty if nothing).
     */
    private fun syncRemotes(
        repo: wsmanager.core.models.Repository,
        repoPath: String,
        context: CommandContext
    ): String {
        val listResult = context.git.listRemotes(repoPath)
        if (!listResult.success) return ""    // Can't sync; skip silently

        val currentRemoteNames = listResult.output
            .lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val configRemoteNames = repo.remotes.map { it.alias }.toSet()

        var added = 0; var removed = 0; var updated = 0

        // Add missing / update changed-URL remotes
        for (remote in repo.remotes) {
            when {
                remote.alias !in currentRemoteNames -> {
                    context.git.addRemote(repoPath, remote.alias, remote.url)
                    added++
                }
                else -> {
                    // Always push the canonical URL from config (idempotent)
                    context.git.setRemoteUrl(repoPath, remote.alias, remote.url)
                    updated++
                }
            }
        }

        // Remove remotes not present in config
        for (remoteAlias in currentRemoteNames) {
            if (remoteAlias !in configRemoteNames) {
                context.git.removeRemote(repoPath, remoteAlias)
                removed++
            }
        }

        return buildString {
            val parts = mutableListOf<String>()
            if (added > 0)   parts.add("+$added remote${if (added > 1) "s" else ""}")
            if (removed > 0) parts.add("-$removed remote${if (removed > 1) "s" else ""}")
            if (parts.isNotEmpty()) append("remotes: ${parts.joinToString(" ")}")
        }
    }
}
