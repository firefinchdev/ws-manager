package wsmanager.cli.output

import wsmanager.core.models.*

/**
 * Handles all output formatting and display for the CLI.
 * Provides structured output with colors, progress indicators, and summaries.
 */
object Printer {

    private val c = TerminalColors

    // --- Message Types ---

    fun info(message: String) {
        println("${c.blue("ℹ")} $message")
    }

    fun success(message: String) {
        println("${c.green("✓")} $message")
    }

    fun warning(message: String) {
        println("${c.yellow("⚠")} $message")
    }

    fun error(message: String) {
        println("${c.red("✗")} $message")
    }

    fun progress(repo: String, message: String) {
        println("${c.cyan("▸")} ${c.bold(repo)}: $message")
    }

    fun debug(message: String) {
        println("${c.gray("  $message")}")
    }

    // --- Headers ---

    fun header(title: String) {
        val line = "─".repeat(60)
        println()
        println(c.boldCyan(line))
        println(c.boldCyan("  $title"))
        println(c.boldCyan(line))
    }

    fun subHeader(title: String) {
        println()
        println(c.bold("  $title"))
        println(c.dim("  ${"─".repeat(40)}"))
    }

    // --- Operation Progress ---

    fun operationStart(operation: String, repoCount: Int) {
        header("$operation across $repoCount repositories")
    }

    fun repoResult(result: RepoOperationResult) {
        val icon = when (result.status) {
            ExecutionStatus.SUCCESS -> c.green("✓")
            ExecutionStatus.FAILED -> c.red("✗")
            ExecutionStatus.ROLLED_BACK -> c.yellow("↩")
            ExecutionStatus.ROLLBACK_FAILED -> c.boldRed("⚠")
            ExecutionStatus.SKIPPED -> c.gray("○")
        }
        val name = c.bold(result.repository.displayName)
        val duration = if (result.durationMs > 0) c.dim(" (${formatDuration(result.durationMs)})") else ""
        val status = when (result.status) {
            ExecutionStatus.SUCCESS -> c.green("success")
            ExecutionStatus.FAILED -> c.red("failed")
            ExecutionStatus.ROLLED_BACK -> c.yellow("rolled back")
            ExecutionStatus.ROLLBACK_FAILED -> c.boldRed("rollback failed")
            ExecutionStatus.SKIPPED -> c.gray("skipped")
        }

        println("  $icon $name ─ $status$duration")

        // Show output for non-empty results
        if (result.output.isNotBlank() && result.isSuccess) {
            result.output.lines().take(5).forEach { line ->
                println("    ${c.dim(line)}")
            }
            val totalLines = result.output.lines().size
            if (totalLines > 5) {
                println("    ${c.dim("... and ${totalLines - 5} more lines")}")
            }
        }

        // Always show errors
        if (result.error.isNotBlank()) {
            result.error.lines().take(5).forEach { line ->
                println("    ${c.red(line)}")
            }
        }
    }

    // --- Summary ---

    fun operationSummary(result: WorkspaceOperationResult) {
        println()
        val line = "─".repeat(60)
        println(c.dim(line))

        val strategyLabel = when (result.strategy) {
            ExecutionStrategy.ATOMIC -> c.magenta("ATOMIC")
            ExecutionStrategy.BEST_EFFORT -> c.blue("BEST_EFFORT")
        }

        println("  Strategy: $strategyLabel  |  Duration: ${c.dim(formatDuration(result.durationMs))}")
        println()

        val parts = mutableListOf<String>()

        if (result.successCount > 0) {
            parts.add(c.green("${result.successCount} succeeded"))
        }
        if (result.failureCount > 0) {
            parts.add(c.red("${result.failureCount} failed"))
        }
        if (result.rolledBack.isNotEmpty()) {
            parts.add(c.yellow("${result.rolledBack.size} rolled back"))
        }
        if (result.rollbackFailed.isNotEmpty()) {
            parts.add(c.boldRed("${result.rollbackFailed.size} rollback failed"))
        }
        if (result.skipped.isNotEmpty()) {
            parts.add(c.gray("${result.skipped.size} skipped"))
        }

        val statusIcon = when {
            result.isFullSuccess -> c.boldGreen("✓")
            result.isPartialSuccess -> c.boldYellow("⚠")
            else -> c.boldRed("✗")
        }

        val statusText = when {
            result.isFullSuccess -> c.boldGreen("All operations completed successfully")
            result.isPartialSuccess -> c.boldYellow("Partial success")
            else -> c.boldRed("All operations failed")
        }

        println("  $statusIcon $statusText")
        println("  ${parts.joinToString("  |  ")}")
        println(c.dim(line))
        println()
    }

    // --- Status Display ---

    fun repoStatus(repo: Repository, branchInfo: String, statusOutput: String) {
        println()
        println("  ${c.boldCyan("▸")} ${c.bold(repo.displayName)} ${c.dim("(${repo.path})")}")
        println("    ${c.blue("Branch:")} $branchInfo")
        if (statusOutput.isBlank()) {
            println("    ${c.green("Working tree clean")}")
        } else {
            statusOutput.lines().filter { it.isNotBlank() }.forEach { line ->
                val formatted = formatStatusLine(line)
                println("    $formatted")
            }
        }
    }

    // --- Utility ---

    private fun formatDuration(ms: Long): String {
        return when {
            ms < 1000 -> "${ms}ms"
            ms < 60_000 -> {
                val seconds = ms / 1000.0
                "${(seconds * 10).toInt() / 10.0}s"
            }
            else -> {
                val minutes = ms / 60_000
                val seconds = (ms % 60_000) / 1000
                "${minutes}m ${seconds}s"
            }
        }
    }

    private fun formatStatusLine(line: String): String {
        if (line.length < 2) return line
        return when {
            line.startsWith("##") -> c.cyan(line)
            line.startsWith("M ") || line.startsWith(" M") -> c.yellow(line)
            line.startsWith("A ") || line.startsWith("AM") -> c.green(line)
            line.startsWith("D ") || line.startsWith(" D") -> c.red(line)
            line.startsWith("??") -> c.gray(line)
            line.startsWith("UU") -> c.boldRed(line)
            else -> line
        }
    }

    /**
     * Print a table of repositories.
     */
    fun repoTable(repos: List<Repository>) {
        val maxName = (repos.maxOfOrNull { it.name.length } ?: 10).coerceAtLeast(10)
        val maxPath = (repos.maxOfOrNull { it.path.length } ?: 10).coerceAtLeast(10)

        val headerFmt = "  %-${maxName}s  %-${maxPath}s  %-8s  %s"
        val rowFmt = "  %-${maxName}s  %-${maxPath}s  %-8s  %s"

        println(c.bold(headerFmt.format("Name", "Path", "Branch", "Remote")))
        println(c.dim("  ${"─".repeat(maxName + maxPath + 30)}"))

        for (repo in repos) {
            val remoteInfo = repo.remotes.joinToString(", ") { it.alias }
            println(rowFmt.format(repo.name, repo.path, repo.defaultBranch, remoteInfo))
        }
        println()
    }

    /**
     * Print a simple newline.
     */
    fun newline() {
        println()
    }

    /**
     * Format a String.format style (Kotlin/Native compatible).
     */
    private fun String.format(vararg args: Any?): String {
        var result = this
        var argIndex = 0
        val regex = Regex("%-?\\d*[sd]")
        result = regex.replace(result) { match ->
            if (argIndex < args.size) {
                val arg = args[argIndex++]
                val spec = match.value
                when {
                    spec.contains('d') -> (arg as? Number)?.toString() ?: arg.toString()
                    spec.contains('s') -> {
                        val str = arg?.toString() ?: ""
                        val width = spec.removePrefix("%-").removePrefix("%").removeSuffix("s").toIntOrNull()
                        if (width != null) {
                            if (spec.startsWith("%-")) str.padEnd(width)
                            else str.padStart(width)
                        } else str
                    }
                    else -> arg.toString()
                }
            } else match.value
        }
        return result
    }
}
