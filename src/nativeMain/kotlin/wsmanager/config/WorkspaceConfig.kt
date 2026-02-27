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
        const val DEFAULT_FILE_NAME = "workspace.json"
    }
}
