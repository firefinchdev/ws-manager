package wsmanager.cli.commands

import wsmanager.cli.Command
import wsmanager.cli.CommandContext
import wsmanager.cli.output.Printer
import wsmanager.config.ConfigParser
import wsmanager.config.WorkspaceConfig
import wsmanager.core.models.Remote
import wsmanager.core.models.Repository
import wsmanager.util.FileUtils

/**
 * Initialize a new workspace configuration file.
 */
class InitCommand : Command {
    override val name = "init"
    override val description = "Initialize a new workspace configuration"
    override val usage = "ws init [--name <name>] [--path <config-path>]"

    override suspend fun execute(args: List<String>, context: CommandContext): Int {
        val workspaceName = getArgValue(args, "--name") ?: "my-workspace"
        val configPath = context.configPath

        // Check if config already exists
        if (FileUtils.exists(configPath)) {
            Printer.warning("Configuration file already exists at: $configPath")
            Printer.info("Use a different path with --config option to create a new one.")
            return 1
        }

        // Create sample config
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

        try {
            ConfigParser.write(sampleConfig, configPath)
            Printer.success("Workspace initialized!")
            Printer.info("Configuration written to: $configPath")
            Printer.info("Edit the configuration to add your repositories, then run 'ws clone' to set up.")
            Printer.newline()
            Printer.info("Example configuration structure:")
            Printer.debug("  name: workspace name")
            Printer.debug("  max_concurrency: number of parallel operations")
            Printer.debug("  repositories: list of repo definitions")
            Printer.debug("    - name, path, default_branch, default_remote, remotes")
            return 0
        } catch (e: Exception) {
            Printer.error("Failed to create configuration: ${e.message}")
            return 1
        }
    }

    private fun getArgValue(args: List<String>, flag: String): String? {
        val index = args.indexOf(flag)
        return if (index >= 0 && index + 1 < args.size) args[index + 1] else null
    }
}
