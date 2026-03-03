package wsmanager.config

/**
 * Validates a WorkspaceConfig and returns a list of validation errors.
 * An empty list means the config is valid.
 */
object ConfigValidator {

    data class ValidationError(
        val field: String,
        val message: String
    ) {
        override fun toString(): String = "[$field] $message"
    }

    /**
     * Validate the workspace configuration.
     * Returns a list of validation errors (empty if valid).
     */
    fun validate(config: WorkspaceConfig): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()

        // Workspace-level validations
        if (config.name.isBlank()) {
            errors.add(ValidationError("name", "Workspace name must not be blank"))
        }

        if (config.maxConcurrency < 1) {
            errors.add(ValidationError("max_concurrency", "Max concurrency must be at least 1, got ${config.maxConcurrency}"))
        }

        if (config.repositories.isEmpty()) {
            errors.add(ValidationError("repositories", "At least one repository must be defined"))
        }

        // Pre-collect all repo names for global alias conflict detection
        val allRepoNames = config.repositories.map { it.name }.toSet()

        // Per-repository validations
        // Also used for global alias uniqueness across repos
        val repoNames        = mutableSetOf<String>()
        val globalAliasIndex = mutableMapOf<String, String>()   // alias → ownerRepoName

        config.repositories.forEachIndexed { index, repo ->
            val prefix = "repositories[$index]"

            if (repo.name.isBlank()) {
                errors.add(ValidationError("$prefix.name", "Repository name must not be blank"))
            }

            if (repo.path.isBlank()) {
                errors.add(ValidationError("$prefix.path", "Repository path must not be blank"))
            }

            if (!repoNames.add(repo.name)) {
                errors.add(ValidationError("$prefix.name", "Duplicate repository name: '${repo.name}'"))
            }

            if (repo.remotes.isEmpty()) {
                errors.add(ValidationError("$prefix.remotes", "At least one remote must be defined for repository '${repo.name}'"))
            }

            // Remote alias uniqueness within repo
            val remoteAliases = mutableSetOf<String>()
            repo.remotes.forEachIndexed { rIndex, remote ->
                val rPrefix = "$prefix.remotes[$rIndex]"

                if (remote.alias.isBlank()) {
                    errors.add(ValidationError("$rPrefix.alias", "Remote alias must not be blank"))
                }

                if (remote.url.isBlank()) {
                    errors.add(ValidationError("$rPrefix.url", "Remote URL must not be blank"))
                }

                if (!remoteAliases.add(remote.alias)) {
                    errors.add(ValidationError(
                        "$rPrefix.alias",
                        "Duplicate remote alias '${remote.alias}' in repository '${repo.name}'"
                    ))
                }
            }

            // Default remote must exist in repo's remotes
            if (repo.remotes.isNotEmpty() && repo.getDefaultRemote() == null) {
                errors.add(ValidationError(
                    "$prefix.default_remote",
                    "Default remote '${repo.defaultRemote}' not found in remotes for repository '${repo.name}'"
                ))
            }

            // Alias validations
            val repoAliasSet = mutableSetOf<String>()
            repo.aliases.forEachIndexed { aIdx, alias ->
                val aPrefix = "$prefix.aliases[$aIdx]"

                if (alias.isBlank()) {
                    errors.add(ValidationError(aPrefix, "Alias must not be blank"))
                    return@forEachIndexed
                }

                // Alias must not duplicate the repo's own name (redundant)
                if (alias == repo.name) {
                    errors.add(ValidationError(aPrefix, "Alias '$alias' is identical to the repository name — remove it"))
                    return@forEachIndexed
                }

                // Alias must not conflict with any other repo's name
                if (alias in allRepoNames) {
                    errors.add(ValidationError(aPrefix, "Alias '$alias' conflicts with an existing repository name"))
                    return@forEachIndexed
                }

                // Alias must be unique within this repo
                if (!repoAliasSet.add(alias)) {
                    errors.add(ValidationError(aPrefix, "Duplicate alias '$alias' in repository '${repo.name}'"))
                    return@forEachIndexed
                }

                // Alias must be globally unique across all repos
                val existingOwner = globalAliasIndex[alias]
                if (existingOwner != null) {
                    errors.add(ValidationError(
                        aPrefix,
                        "Alias '$alias' is already used by repository '$existingOwner'"
                    ))
                } else {
                    globalAliasIndex[alias] = repo.name
                }
            }
        }

        return errors
    }

    /**
     * Validate and throw if invalid.
     */
    fun validateOrThrow(config: WorkspaceConfig) {
        val errors = validate(config)
        if (errors.isNotEmpty()) {
            val errorMessage = buildString {
                appendLine("Workspace configuration is invalid:")
                errors.forEach { appendLine("  - $it") }
            }
            throw ConfigValidationException(errorMessage, errors)
        }
    }
}

/**
 * Exception thrown when configuration validation fails.
 */
class ConfigValidationException(
    message: String,
    val errors: List<ConfigValidator.ValidationError>
) : Exception(message)
