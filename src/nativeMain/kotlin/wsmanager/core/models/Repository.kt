package wsmanager.core.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents a Git repository within the workspace.
 */
@Serializable
data class Repository(
    val name: String,
    val path: String,
    @SerialName("default_branch")
    val defaultBranch: String = "main",
    @SerialName("default_remote")
    val defaultRemote: String = "origin",
    val remotes: List<Remote> = emptyList(),
    /**
     * Optional short names / nicknames for this repository.
     * Any alias can be used wherever a repo name is accepted in CLI commands.
     * Aliases must be unique across the entire workspace (no two repos may share
     * an alias, and no alias may duplicate another repo's [name]).
     */
    val aliases: List<String> = emptyList()
) {
    /**
     * Returns the remote matching the default remote alias, or null if not found.
     */
    fun getDefaultRemote(): Remote? = remotes.find { it.alias == defaultRemote }

    /**
     * Returns a remote by alias, or null if not found.
     */
    fun getRemote(alias: String): Remote? = remotes.find { it.alias == alias }

    /**
     * Display name for output purposes.
     */
    val displayName: String get() = name.ifEmpty { path.split("/").last() }

    /**
     * Returns `true` if [query] matches this repository's [name] or any of its [aliases].
     * Comparison is case-sensitive to match standard Git tooling conventions.
     */
    fun matchesNameOrAlias(query: String): Boolean =
        name == query || aliases.any { it == query }
}
