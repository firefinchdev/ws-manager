package wsmanager.core.models

/**
 * Status of a single repository operation execution.
 */
enum class ExecutionStatus {
    SUCCESS,
    FAILED,
    ROLLED_BACK,
    ROLLBACK_FAILED,
    SKIPPED
}

/**
 * Result of executing an operation on a single repository.
 */
data class RepoOperationResult(
    val repository: Repository,
    val status: ExecutionStatus,
    val message: String = "",
    val output: String = "",
    val error: String = "",
    val durationMs: Long = 0
) {
    val isSuccess: Boolean get() = status == ExecutionStatus.SUCCESS
    val isFailed: Boolean get() = status == ExecutionStatus.FAILED
    val isRolledBack: Boolean get() = status == ExecutionStatus.ROLLED_BACK
    val isRollbackFailed: Boolean get() = status == ExecutionStatus.ROLLBACK_FAILED
    val isSkipped: Boolean get() = status == ExecutionStatus.SKIPPED
}

/**
 * Aggregated result of executing an operation across the workspace.
 */
data class WorkspaceOperationResult(
    val operation: String,
    val results: List<RepoOperationResult>,
    val strategy: ExecutionStrategy,
    val durationMs: Long = 0
) {
    val succeeded: List<RepoOperationResult> get() = results.filter { it.isSuccess }
    val failed: List<RepoOperationResult> get() = results.filter { it.isFailed }
    val rolledBack: List<RepoOperationResult> get() = results.filter { it.isRolledBack }
    val rollbackFailed: List<RepoOperationResult> get() = results.filter { it.isRollbackFailed }
    val skipped: List<RepoOperationResult> get() = results.filter { it.isSkipped }

    val isFullSuccess: Boolean get() = results.all { it.isSuccess }
    val isPartialSuccess: Boolean get() = succeeded.isNotEmpty() && failed.isNotEmpty()
    val isFullFailure: Boolean get() = results.all { !it.isSuccess }

    val successCount: Int get() = succeeded.size
    val failureCount: Int get() = failed.size
    val totalCount: Int get() = results.size
}

/**
 * Execution strategy for workspace operations.
 */
enum class ExecutionStrategy {
    /**
     * All-or-nothing: if any repo fails, attempt rollback on successful ones.
     */
    ATOMIC,

    /**
     * Continue on failure: collect all results and report.
     */
    BEST_EFFORT
}
