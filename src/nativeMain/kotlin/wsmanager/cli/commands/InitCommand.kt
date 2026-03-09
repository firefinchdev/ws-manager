@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package wsmanager.cli.commands

import platform.posix.getpid
import wsmanager.cli.Command
import wsmanager.cli.CommandContext
import wsmanager.cli.output.Printer
import wsmanager.config.ConfigParser
import wsmanager.config.ConfigParseException
import wsmanager.config.WorkspaceConfig
import wsmanager.core.models.Remote
import wsmanager.core.models.Repository
import wsmanager.util.FileUtils
import wsmanager.util.HttpFetcher
import wsmanager.util.HttpFetchResult
import wsmanager.util.ProcessRunner

/**
 * Initialize a new workspace configuration.
 *
 * Two modes:
 *   1. Sample config  (no --url)   — writes a skeleton workspace.json for manual editing.
 *   2. Remote config  (--url <u>)  — fetches a real workspace.json from a URL.
 *
 * Remote mode auto-detects the fetch strategy based on the URL shape:
 *   • URL ends with `.json`, contains `/raw/`, or is a raw.githubusercontent.com URL
 *     → direct HTTPS download via Ktor (no curl required)
 *   • Anything else
 *     → `git clone --depth=1` the manifest repository, then extract the file
 */
class InitCommand : Command {
    override val name = "init"
    override val description = "Initialize a new workspace configuration"
    override val usage = """
        ws init [--name <name>]
        ws init --url <url> [--branch <branch>] [--file <path>] [--force]
    """.trimIndent()

    override suspend fun execute(args: List<String>, context: CommandContext): Int {
        val url = getArgValue(args, "--url") ?: getArgValue(args, "-u")
        return if (url != null) {
            initFromUrl(url, args, context)
        } else {
            initSample(args, context)
        }
    }

    // ─── Mode 1: sample config ────────────────────────────────────────────────

    private fun initSample(args: List<String>, context: CommandContext): Int {
        val workspaceName = getArgValue(args, "--name") ?: "my-workspace"
        val configPath = context.configPath

        if (FileUtils.exists(configPath)) {
            Printer.warning("Configuration file already exists at: $configPath")
            Printer.info("Use a different path with --config option to create a new one.")
            return 1
        }

        val sampleConfig = WorkspaceConfig(
            name = workspaceName,
            maxConcurrency = 4,
            basePath = ".",
            repositories = listOf(
                Repository(
                    name = "example-repo",
                    path = "./example-repo",
                    defaultBranch = "main",
                    defaultRemote = "origin",
                    remotes = listOf(
                        Remote(alias = "origin", url = "https://github.com/user/example-repo.git")
                    )
                )
            )
        )

        return try {
            ConfigParser.write(sampleConfig, configPath)
            Printer.success("Workspace initialized!")
            Printer.info("Created: $configPath")
            Printer.info("The .ws/ directory is your workspace home — add other workspace files here.")
            Printer.newline()
            Printer.info("Next steps:")
            Printer.debug("  1. Edit ${WorkspaceConfig.DEFAULT_CONFIG_PATH} to define your repositories")
            Printer.debug("  2. Run 'ws clone' to clone all repos")
            Printer.debug("  3. Run 'ws describe' to verify the workspace setup")
            Printer.newline()
            Printer.info("Configuration fields:")
            Printer.debug("  name             workspace name")
            Printer.debug("  max_concurrency  parallel operations limit")
            Printer.debug("  repositories     list of repos (name, path, default_branch, default_remote, remotes)")
            0
        } catch (e: Exception) {
            Printer.error("Failed to create configuration: ${e.message}")
            1
        }
    }

    // ─── Mode 2: remote config ────────────────────────────────────────────────

    private suspend fun initFromUrl(url: String, args: List<String>, context: CommandContext): Int {
        val force = args.contains("--force") || args.contains("-f")
        val configPath = context.configPath

        if (FileUtils.exists(configPath) && !force) {
            Printer.warning("Configuration already exists at: $configPath")
            Printer.info("Use --force to overwrite the existing config.")
            return 1
        }

        val isRaw = isRawFileUrl(url)
        return if (isRaw) {
            fetchRawConfig(url, configPath)
        } else {
            cloneAndExtractConfig(url, args, configPath)
        }
    }

    /**
     * Raw mode: download the JSON file directly via Ktor — no curl required.
     * Works on all platforms (macOS: NSURLSession, Linux: CIO, Windows: WinHttp).
     */
    private suspend fun fetchRawConfig(url: String, configPath: String): Int {
        Printer.info("Fetching workspace config from:")
        Printer.debug("  $url")
        Printer.newline()

        Printer.info("Downloading…")
        val fetchResult = HttpFetcher.fetch(url)

        val content = when (fetchResult) {
            is HttpFetchResult.Success -> fetchResult.content
            is HttpFetchResult.Failure -> {
                Printer.error("Failed to download config from URL.")
                Printer.debug("  ${fetchResult.error}")
                return 1
            }
        }

        // Write to temp file so ConfigParser.parseAndValidate can read it from disk
        val tmpFile = tmpPath("ws_fetch", "json")
        return try {
            FileUtils.writeFile(tmpFile, content)
            validateAndInstall(tmpFile, configPath, url)
        } finally {
            FileUtils.deleteFile(tmpFile)
        }
    }

    /**
     * Git-clone mode: `git clone --depth=1` the manifest repository into a temp
     * directory, extract the config file, validate, install, then clean up.
     *
     * This mirrors `repo init -u <url>` from Android's repo tool.
     */
    private fun cloneAndExtractConfig(url: String, args: List<String>, configPath: String): Int {
        val branch = getArgValue(args, "--branch") ?: getArgValue(args, "-b")
        val filePath = getArgValue(args, "--file") ?: "workspace.json"

        Printer.info("Initializing workspace from manifest repository:")
        Printer.debug("  $url")
        if (branch != null) Printer.debug("  branch : $branch")
        Printer.debug("  file   : $filePath")
        Printer.newline()

        val tmpDir = tmpPath("ws_manifest", "dir")

        return try {
            // Build the clone command
            val cloneCmd = buildList {
                add("git"); add("clone"); add("--depth=1")
                if (branch != null) { add("--branch"); add(branch) }
                add(url); add(tmpDir)
            }

            Printer.info("Cloning manifest repository…")
            val cloneResult = ProcessRunner.execute(cloneCmd)
            if (!cloneResult.isSuccess) {
                Printer.error("Failed to clone manifest repository.")
                if (cloneResult.errorOutput.isNotBlank()) Printer.debug("  ${cloneResult.errorOutput}")
                return 1
            }

            // Locate the config file inside the cloned repo
            val sourceFile = "$tmpDir/$filePath"
            if (!FileUtils.exists(sourceFile)) {
                Printer.error("Config file '$filePath' not found in the manifest repository.")
                Printer.info("Use --file <path> to specify a different path within the repository.")
                return 1
            }

            // Show the branch that was actually cloned (useful when branch was auto-detected)
            val actualBranch = ProcessRunner.execute(
                listOf("git", "rev-parse", "--abbrev-ref", "HEAD"),
                workingDir = tmpDir
            ).output.ifBlank { branch ?: "HEAD" }
            Printer.debug("  cloned branch: $actualBranch")
            Printer.newline()

            validateAndInstall(sourceFile, configPath, url, branch = actualBranch)
        } finally {
            // Always clean up the temp clone, even on failure
            ProcessRunner.execute(listOf("rm", "-rf", tmpDir))
        }
    }

    /**
     * Parse, validate, and install the config from [sourcePath] to [destPath].
     * Preserves the original file content (formatting, comments) rather than
     * re-serialising through ConfigParser.
     */
    private fun validateAndInstall(
        sourcePath: String,
        destPath: String,
        sourceUrl: String,
        branch: String? = null
    ): Int {
        val content = FileUtils.readFile(sourcePath)
        if (content.isNullOrBlank()) {
            Printer.error("Config file is empty or unreadable.")
            return 1
        }

        // Parse + validate before installing — fail early with a clear message
        val config = try {
            ConfigParser.parseAndValidate(sourcePath)
        } catch (e: ConfigParseException) {
            Printer.error("Invalid workspace configuration: ${e.message}")
            return 1
        } catch (e: Exception) {
            Printer.error("Unexpected error validating config: ${e.message}")
            return 1
        }

        // Write the original content (preserves formatting)
        try {
            FileUtils.writeFile(destPath, content)
        } catch (e: Exception) {
            Printer.error("Failed to write config: ${e.message}")
            return 1
        }

        // Success output
        Printer.success("Workspace '${config.name}' initialized!")
        Printer.newline()
        val c = wsmanager.cli.output.TerminalColors
        println("  ${c.bold("Config")}   $destPath")
        println("  ${c.bold("Source")}   $sourceUrl${if (branch != null) " @ $branch" else ""}")
        println("  ${c.bold("Repos")}    ${config.repositories.size} defined")
        Printer.newline()
        Printer.info("Next steps:")
        Printer.debug("  1. Run 'ws describe' to review the workspace")
        Printer.debug("  2. Run 'ws clone' to clone all ${config.repositories.size} repositories")
        return 0
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Returns true when the URL clearly points at a raw JSON file rather than a
     * git repository. Heuristics:
     *   - URL path ends with `.json`
     *   - URL contains `/raw/` (GitHub/GitLab raw file links)
     *   - URL host is `raw.githubusercontent.com` or `raw.gitlab.com`
     */
    private fun isRawFileUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.endsWith(".json")
            || lower.contains("/raw/")
            || lower.startsWith("https://raw.githubusercontent.com")
            || lower.startsWith("https://raw.gitlab")
    }

    /** Generate a unique temp path under /tmp. */
    private fun tmpPath(prefix: String, suffix: String): String {
        val pid = getpid()
        val ts  = kotlin.time.TimeSource.Monotonic.markNow().hashCode().and(0xFFFFF)
        return "/tmp/${prefix}_${pid}_$ts.$suffix"
    }

    private fun getArgValue(args: List<String>, flag: String): String? {
        val index = args.indexOf(flag)
        return if (index >= 0 && index + 1 < args.size) args[index + 1] else null
    }
}
