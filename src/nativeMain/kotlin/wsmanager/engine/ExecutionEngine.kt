package wsmanager.engine

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import wsmanager.core.models.*
import wsmanager.git.GitResult
import kotlin.coroutines.coroutineContext
import kotlin.time.Clock
import kotlin.time.TimeSource

/**
 * Core execution engine that orchestrates parallel operations across repositories.
 * Supports ATOMIC (all-or-nothing with rollback) and BEST_EFFORT (continue on failure) strategies.
 */
class ExecutionEngine(
    private val maxConcurrency: Int = 4
) {

    /**
     * Execute an operation across all repositories using the BEST_EFFORT strategy.
     * Continues even if some repositories fail.
     */
    suspend fun executeBestEffort(
        operationName: String,
        repositories: List<Repository>,
        operation: (Repository) -> GitResult,
        onProgress: ((Repository, RepoOperationResult) -> Unit)? = null
    ): WorkspaceOperationResult {
        val startTime = currentTimeMillis()
        val semaphore = Semaphore(maxConcurrency)

        val results = coroutineScope {
            repositories.map { repo ->
                async(Dispatchers.Default) {
                    semaphore.withPermit {
                        val repoStart = currentTimeMillis()
                        val result = try {
                            val gitResult = operation(repo)
                            if (gitResult.success) {
                                RepoOperationResult(
                                    repository = repo,
                                    status = ExecutionStatus.SUCCESS,
                                    message = "Completed successfully",
                                    output = gitResult.output,
                                    durationMs = currentTimeMillis() - repoStart
                                )
                            } else {
                                RepoOperationResult(
                                    repository = repo,
                                    status = ExecutionStatus.FAILED,
                                    message = "Operation failed",
                                    error = gitResult.error.ifEmpty { gitResult.output },
                                    durationMs = currentTimeMillis() - repoStart
                                )
                            }
                        } catch (e: Exception) {
                            RepoOperationResult(
                                repository = repo,
                                status = ExecutionStatus.FAILED,
                                message = "Exception during execution",
                                error = e.message ?: "Unknown error",
                                durationMs = currentTimeMillis() - repoStart
                            )
                        }
                        onProgress?.invoke(repo, result)
                        result
                    }
                }
            }.awaitAll()
        }

        return WorkspaceOperationResult(
            operation = operationName,
            results = results,
            strategy = ExecutionStrategy.BEST_EFFORT,
            durationMs = currentTimeMillis() - startTime
        )
    }

    /**
     * Execute an operation across all repositories using the ATOMIC strategy.
     * If any repository fails, attempts to rollback all successful ones.
     *
     * @param T The type of snapshot data used for rollback
     */
    suspend fun <T> executeAtomic(
        operationName: String,
        repositories: List<Repository>,
        operation: RepoOperation<T>,
        onProgress: ((Repository, RepoOperationResult) -> Unit)? = null
    ): WorkspaceOperationResult {
        val startTime = currentTimeMillis()
        val semaphore = Semaphore(maxConcurrency)

        // Phase 1: Capture snapshots for all repositories
        val snapshots = mutableMapOf<String, T>()
        for (repo in repositories) {
            try {
                val snapshot = operation.captureSnapshot(repo)
                if (snapshot != null) {
                    snapshots[repo.name] = snapshot
                }
            } catch (e: Exception) {
                // If we can't snapshot, we can't guarantee rollback
                // Continue anyway - rollback will be best-effort
            }
        }

        // Phase 2: Execute operations in parallel
        val results = coroutineScope {
            repositories.map { repo ->
                async(Dispatchers.Default) {
                    semaphore.withPermit {
                        val repoStart = currentTimeMillis()
                        try {
                            val gitResult = operation.execute(repo)
                            if (gitResult.success) {
                                RepoOperationResult(
                                    repository = repo,
                                    status = ExecutionStatus.SUCCESS,
                                    message = "Completed successfully",
                                    output = gitResult.output,
                                    durationMs = currentTimeMillis() - repoStart
                                )
                            } else {
                                RepoOperationResult(
                                    repository = repo,
                                    status = ExecutionStatus.FAILED,
                                    message = "Operation failed",
                                    error = gitResult.error.ifEmpty { gitResult.output },
                                    durationMs = currentTimeMillis() - repoStart
                                )
                            }
                        } catch (e: Exception) {
                            RepoOperationResult(
                                repository = repo,
                                status = ExecutionStatus.FAILED,
                                message = "Exception during execution",
                                error = e.message ?: "Unknown error",
                                durationMs = currentTimeMillis() - repoStart
                            )
                        }
                    }
                }
            }.awaitAll()
        }.toMutableList()

        // Phase 3: Check if any failed - if so, rollback successful ones
        val hasFailures = results.any { it.isFailed }

        if (hasFailures && operation.rollback != null) {
            val successfulRepos = results.filter { it.isSuccess }
            val rollbackEntries = successfulRepos.mapNotNull { result ->
                val snapshot = snapshots[result.repository.name]
                if (snapshot != null) {
                    RollbackManager.RollbackEntry(
                        repository = result.repository,
                        snapshot = snapshot,
                        rollbackFn = operation.rollback
                    )
                } else null
            }

            if (rollbackEntries.isNotEmpty()) {
                val rollbackResults = RollbackManager.rollback(rollbackEntries)

                // Replace SUCCESS results with rollback results
                for (rollbackResult in rollbackResults) {
                    val index = results.indexOfFirst {
                        it.repository.name == rollbackResult.repository.name
                    }
                    if (index >= 0) {
                        results[index] = rollbackResult
                    }
                }
            }

            // Notify all results: failed repos first (so user sees WHY it failed),
            // then rollback/rollback-failed repos.
            val orderedResults = results.sortedBy { r ->
                when (r.status) {
                    ExecutionStatus.FAILED -> 0
                    ExecutionStatus.ROLLED_BACK -> 1
                    ExecutionStatus.ROLLBACK_FAILED -> 2
                    ExecutionStatus.SUCCESS -> 3
                    ExecutionStatus.SKIPPED -> 4
                }
            }
            orderedResults.forEach { result ->
                onProgress?.invoke(result.repository, result)
            }
        } else {
            // All succeeded (or no rollback) - notify in config order
            results.forEach { result ->
                onProgress?.invoke(result.repository, result)
            }
        }

        return WorkspaceOperationResult(
            operation = operationName,
            results = results,
            strategy = ExecutionStrategy.ATOMIC,
            durationMs = currentTimeMillis() - startTime
        )
    }

    /**
     * Execute an arbitrary shell command across all repositories (BEST_EFFORT).
     */
    suspend fun executeForEach(
        repositories: List<Repository>,
        command: String,
        onProgress: ((Repository, RepoOperationResult) -> Unit)? = null
    ): WorkspaceOperationResult {
        return executeBestEffort(
            operationName = "foreach: $command",
            repositories = repositories,
            operation = { repo ->
                val result = wsmanager.util.ProcessRunner.execute(
                    command = listOf("sh", "-c", command),
                    workingDir = repo.path
                )
                GitResult(
                    success = result.isSuccess,
                    output = result.output,
                    error = result.errorOutput,
                    exitCode = result.exitCode
                )
            },
            onProgress = onProgress
        )
    }
}

/**
 * Get current time in milliseconds using monotonic time source.
 */
private fun currentTimeMillis(): Long {
    return Clock.System.now().toEpochMilliseconds()
}
