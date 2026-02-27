package wsmanager.engine

import wsmanager.core.models.*

/**
 * Manages rollback of operations on repositories that succeeded
 * when an ATOMIC operation has partial failures.
 */
object RollbackManager {

    /**
     * Rollback entry containing the repo, its snapshot, and the rollback function.
     */
    data class RollbackEntry<T>(
        val repository: Repository,
        val snapshot: T,
        val rollbackFn: (Repository, T) -> wsmanager.git.GitResult
    )

    /**
     * Attempt rollback on all entries. Returns results for each attempt.
     */
    fun <T> rollback(entries: List<RollbackEntry<T>>): List<RepoOperationResult> {
        return entries.map { entry ->
            try {
                val result = entry.rollbackFn(entry.repository, entry.snapshot)
                if (result.success) {
                    RepoOperationResult(
                        repository = entry.repository,
                        status = ExecutionStatus.ROLLED_BACK,
                        message = "Successfully rolled back",
                        output = result.output
                    )
                } else {
                    RepoOperationResult(
                        repository = entry.repository,
                        status = ExecutionStatus.ROLLBACK_FAILED,
                        message = "Rollback failed",
                        error = result.error
                    )
                }
            } catch (e: Exception) {
                RepoOperationResult(
                    repository = entry.repository,
                    status = ExecutionStatus.ROLLBACK_FAILED,
                    message = "Rollback threw exception",
                    error = e.message ?: "Unknown error"
                )
            }
        }
    }
}
