package wsmanager.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import wsmanager.core.models.Repository

/**
 * Root workspace configuration model.
 * Parsed from a JSON configuration file.
 */
@Serializable
data class WorkspaceConfig(
    val name: String = "workspace",
    @SerialName("max_concurrency")
    val maxConcurrency: Int = 4,
    @SerialName("base_path")
    val basePath: String = ".",
    val repositories: List<Repository> = emptyList()
) {
    companion object {
        /** The workspace metadata directory (lives at the workspace root). */
        const val WS_DIR = ".ws"

        /** The config filename inside [WS_DIR]. */
        const val CONFIG_FILE_NAME = "workspace.json"

        /** Relative path to the config file from the workspace root: `.ws/workspace.json` */
        const val DEFAULT_CONFIG_PATH = "$WS_DIR/$CONFIG_FILE_NAME"
    }
}
