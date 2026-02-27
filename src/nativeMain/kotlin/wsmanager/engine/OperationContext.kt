package wsmanager.engine

import wsmanager.core.models.Repository
import wsmanager.git.GitResult

/**
 * Defines a repository operation that can be executed by the engine.
 *
 * @param T The type of snapshot used for rollback (e.g., branch name, commit hash)
 */
data class RepoOperation<T>(
    /**
     * Human-readable name of the operation.
     */
    val name: String,

    /**
     * Capture state before the operation for potential rollback.
     * Returns null if snapshot is not applicable.
     */
    val captureSnapshot: (Repository) -> T?,

    /**
     * Execute the operation on the given repository.
     */
    val execute: (Repository) -> GitResult,

    /**
     * Rollback the operation using the captured snapshot.
     * Returns null if rollback is not supported for this operation.
     */
    val rollback: ((Repository, T) -> GitResult)? = null
)

/**
 * Represents a snapshot of a repository state before an operation.
 */
data class RepoSnapshot<T>(
    val repository: Repository,
    val data: T
)
