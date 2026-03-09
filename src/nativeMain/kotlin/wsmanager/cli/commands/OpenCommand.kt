package wsmanager.cli.commands

import wsmanager.cli.Command
import wsmanager.cli.CommandContext
import wsmanager.cli.output.Printer
import wsmanager.cli.output.TerminalColors
import wsmanager.util.FileUtils
import wsmanager.util.GitRemoteUrl
import wsmanager.util.ProcessRunner

/**
 * Open one or more workspace repositories in the default browser.
 *
 * For each repository the command:
 *   1. Resolves the remote URL (config value, not live git config)
 *   2. Detects the current branch from the local clone (falls back to default_branch)
 *   3. Constructs a provider-specific browser URL (GitHub/GitLab/Bitbucket/Azure/generic)
 *   4. Opens it with the OS browser launcher (macOS `open`, Linux `xdg-open`)
 *
 * Arguments / Options
 * ──────────────────────────────────────────────────────────────────
 *   (no args)           Open only repos whose current branch has commits ahead of
 *                       their configured default_branch — i.e., repos with active work.
 *   all                 Open ALL repositories (previous default behaviour).
 *   <repo-name>         Open a specific repository by name or alias.
 *   --remote  <alias>   Remote alias to use (default: each repo's default_remote)
 *   --branch  <name>    Branch to show (default: current branch, falls back to default_branch)
 *   --print             Print URLs only — do not open the browser
 */
class OpenCommand : Command {
    override val name = "open"
    override val description = "Open repositories with active work in browser (use 'all' to open all)"
    override val usage = "ws open [all|<repo-name>] [--remote <remote>] [--branch <branch>] [--print]"

    override suspend fun execute(args: List<String>, context: CommandContext): Int {
        val config = context.requireConfig()
        val c      = TerminalColors

        val remoteOverride = getArgValue(args, "--remote")
        val branchOverride = getArgValue(args, "--branch")
        val printOnly      = args.contains("--print")

        // Positional arg: anything that isn't a flag or a flag's value
        val flagValues    = listOfNotNull(remoteOverride, branchOverride)
        val positional    = args.filter { !it.startsWith("-") && it !in flagValues }.firstOrNull()
        val openAll       = positional == "all"
        val repoName      = if (openAll) null else positional

        // ── Resolve the candidate repo list ───────────────────────────────────
        val repos = when {
            repoName != null -> {
                // Specific repo by name or alias
                val match = config.findByNameOrAlias(repoName)
                if (match == null) {
                    Printer.error("Repository '$repoName' not found in workspace config.")
                    val available = config.repositories.map { r ->
                        if (r.aliases.isEmpty()) r.name
                        else "${r.name} (${r.aliases.joinToString(", ")})"
                    }
                    Printer.info("Available: ${available.joinToString(", ")}")
                    return 1
                }
                listOf(match)
            }
            openAll -> config.repositories
            else -> {
                // Default: only repos with commits ahead of their default branch
                config.repositories.filter { repo ->
                    val repoPath = context.resolveRepoPath(repo)
                    FileUtils.isDirectory(repoPath) &&
                        context.git.isGitRepository(repoPath) &&
                        context.git.hasCommitsAheadOf(repoPath, baseBranch = repo.defaultBranch, defaultRemote = repo.defaultRemote)
                }.also { filtered ->
                    if (filtered.isEmpty()) {
                        Printer.info("No repositories have commits ahead of their default branch.")
                        Printer.info("Use 'ws open all' to open all repositories.")
                        return 0
                    }
                }
            }
        }

        val headerLabel = when {
            repoName != null -> "Open in Browser: $repoName"
            openAll          -> "Open in Browser: ${config.name} (all repos)"
            else             -> "Open in Browser: ${config.name} (repos with active work)"
        }
        Printer.header(headerLabel)

        var exitCode   = 0
        var openedCount = 0

        for (repo in repos) {
            val repoPath = context.resolveRepoPath(repo)

            // ── 1. Resolve remote URL from workspace config ────────────────
            val remoteAlias = remoteOverride ?: repo.defaultRemote
            val remoteUrl   = repo.getRemote(remoteAlias)?.url
            if (remoteUrl == null) {
                println()
                println("  ${c.red("✗")} ${c.bold(repo.displayName)}")
                println("    ${c.red("Remote '$remoteAlias' is not defined in workspace config")}")
                exitCode = 1
                continue
            }

            // ── 2. Resolve branch ─────────────────────────────────────────
            val branch: String = branchOverride
                ?: if (FileUtils.isDirectory(repoPath) && context.git.isGitRepository(repoPath)) {
                    val result = context.git.currentBranch(repoPath)
                    if (result.success) result.output else repo.defaultBranch
                } else {
                    // Not yet cloned — use the configured default branch
                    repo.defaultBranch
                }

            // ── 3. Build browser URL ──────────────────────────────────────
            val url = GitRemoteUrl.toBrowserUrl(remoteUrl, branch)
            if (url == null) {
                println()
                println("  ${c.red("✗")} ${c.bold(repo.displayName)}")
                println("    ${c.red("Cannot parse remote URL: $remoteUrl")}")
                exitCode = 1
                continue
            }

            // ── 4. Display ────────────────────────────────────────────────
            println()
            val branchLabel = "  ${c.dim("@ $branch")}"
            println("  ${c.boldCyan("▸")} ${c.bold(repo.displayName)}$branchLabel")
            println("    ${c.dim(url)}")

            if (!printOnly) {
                if (openInBrowser(url)) {
                    println("    ${c.green("↗ opened")}")
                } else {
                    println("    ${c.yellow("⚠  could not launch browser — copy the URL above")}")
                }
            }

            openedCount++
        }

        println()
        if (openedCount > 0 && !printOnly) {
            val noun = if (openedCount == 1) "repository" else "repositories"
            Printer.success("Opened $openedCount $noun in the browser")
        }

        return exitCode
    }

    // ── Browser launcher ──────────────────────────────────────────────────────

    /**
     * Open [url] in the system default browser.
     *
     * Tries (in order):
     *   1. macOS  — `open <url>`
     *   2. Linux  — `xdg-open <url>`
     *
     * Returns `true` if the launch command exited successfully.
     */
    private fun openInBrowser(url: String): Boolean {
        // macOS
        if (ProcessRunner.execute(listOf("open", url)).isSuccess) return true
        // Linux (Freedesktop)
        if (ProcessRunner.execute(listOf("xdg-open", url)).isSuccess) return true
        return false
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun getArgValue(args: List<String>, flag: String): String? {
        val index = args.indexOf(flag)
        return if (index >= 0 && index + 1 < args.size) args[index + 1] else null
    }
}
