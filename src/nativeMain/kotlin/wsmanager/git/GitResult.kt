package wsmanager.git

import wsmanager.util.ProcessResult

/**
 * Result of a Git operation.
 */
data class GitResult(
    val success: Boolean,
    val output: String = "",
    val error: String = "",
    val exitCode: Int = 0
) {
    companion object {
        fun fromProcessResult(result: ProcessResult): GitResult {
            return GitResult(
                success = result.isSuccess,
                output = result.output,
                error = result.errorOutput,
                exitCode = result.exitCode
            )
        }

        fun success(output: String = ""): GitResult {
            return GitResult(success = true, output = output)
        }

        fun failure(error: String, exitCode: Int = 1): GitResult {
            return GitResult(success = false, error = error, exitCode = exitCode)
        }
    }
}
