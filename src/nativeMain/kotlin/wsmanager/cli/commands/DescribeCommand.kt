package wsmanager.cli.commands

import wsmanager.cli.Command
import wsmanager.cli.CommandContext
import wsmanager.cli.output.Printer
import wsmanager.cli.output.TerminalColors
import wsmanager.util.FileUtils

/**
 * Describe the workspace configuration — shows all settings, repositories,
 * remotes, and local clone status from workspace.json.
 */
class DescribeCommand : Command {
    override val name = "describe"
    override val description = "Describe the workspace configuration and all repositories"
    override val usage = "ws describe [--json]"

    override suspend fun execute(args: List<String>, context: CommandContext): Int {
        val config = context.requireConfig()
        val c = TerminalColors
        val asJson = args.contains("--json")

        if (asJson) {
            // Raw JSON dump - just re-print the config file
            val raw = FileUtils.readFile(context.configPath) ?: run {
                Printer.error("Cannot read config file: ${context.configPath}")
                return 1
            }
            println(raw)
            return 0
        }

        // ── Header ───────────────────────────────────────────────────────────
        val line = "─".repeat(60)
        println()
        println(c.boldCyan(line))
        println(c.boldCyan("  Workspace: ${config.name}"))
        println(c.boldCyan(line))
        println()

        // ── Workspace-level settings ─────────────────────────────────────────
        println(c.bold("  SETTINGS"))
        println(c.dim("  ${"─".repeat(40)}"))
        printField("Config file",     FileUtils.absolutePath(context.configPath))
        printField("Workspace root",  config.basePath)
        printField("Max concurrency", "${config.maxConcurrency} parallel operations")
        printField("Repositories",    "${config.repositories.size} defined")
        println()

        // ── Per-repository detail ─────────────────────────────────────────────
        println(c.bold("  REPOSITORIES"))
        println(c.dim("  ${"─".repeat(40)}"))

        config.repositories.forEachIndexed { index, repo ->
            val repoPath    = context.resolveRepoPath(repo)
            val exists      = FileUtils.isDirectory(repoPath)
            val isGit       = exists && FileUtils.isDirectory("$repoPath/.git")
            val cloneStatus = when {
                isGit   -> c.green("cloned")
                exists  -> c.yellow("directory exists (not a git repo)")
                else    -> c.red("not cloned")
            }

            val prefix = c.dim("  ${index + 1}.")
            println()
            println("$prefix ${c.bold(c.cyan(repo.displayName))}  $cloneStatus")

            printField("  Path",           repo.path)
            printField("  Resolved",       repoPath)
            printField("  Default branch", repo.defaultBranch)
            printField("  Default remote", repo.defaultRemote)

            if (repo.remotes.isEmpty()) {
                printField("  Remotes", c.yellow("none configured"))
            } else {
                val first = repo.remotes.first()
                printField("  Remotes", "${c.cyan(first.alias)}  ${c.dim("→")}  ${first.url}")
                repo.remotes.drop(1).forEach { remote ->
                    // align subsequent remotes under the first
                    println("           ${" ".repeat(11)}${c.cyan(remote.alias)}  ${c.dim("→")}  ${remote.url}")
                }
            }
        }

        println()
        println(c.dim("  $line"))

        // ── Summary line ──────────────────────────────────────────────────────
        val cloned    = config.repositories.count { FileUtils.isDirectory("${context.resolveRepoPath(it)}/.git") }
        val notCloned = config.repositories.size - cloned
        val parts = mutableListOf(c.green("$cloned cloned"))
        if (notCloned > 0) parts.add(c.red("$notCloned not cloned"))
        println("  ${parts.joinToString("  |  ")}")
        println()

        return 0
    }

    private fun printField(label: String, value: String) {
        val c = TerminalColors
        println("  ${c.dim(label.padEnd(18))}  $value")
    }
}
