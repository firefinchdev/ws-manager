package wsmanager.util

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess

/**
 * Result of an HTTP fetch operation.
 */
sealed class HttpFetchResult {
    data class Success(val content: String) : HttpFetchResult()
    data class Failure(val error: String)   : HttpFetchResult()
}

/**
 * Thin HTTP download wrapper built on Ktor.
 *
 * The underlying [HttpClient] engine is supplied by [createPlatformHttpClient],
 * which is defined per-platform in `src/platform/<os>/kotlin/`:
 *   - macOS   → Darwin (NSURLSession)
 *   - Linux   → CIO   (POSIX sockets + kotlinx-io TLS)
 *   - Windows → WinHttp (Windows HTTP Services API)
 *
 * No system utilities (curl, wget, Invoke-WebRequest, etc.) are required.
 */
object HttpFetcher {

    /**
     * Download [url] and return its body as a [String].
     *
     * Must be called from a coroutine context (e.g., inside a `suspend` function
     * or `runBlocking`). Uses the platform HTTP client so HTTPS works out-of-the-box
     * on every supported OS.
     */
    suspend fun fetch(url: String): HttpFetchResult {
        return try {
            createPlatformHttpClient().use { client ->
                val response = client.get(url)
                if (response.status.isSuccess()) {
                    HttpFetchResult.Success(response.bodyAsText())
                } else {
                    HttpFetchResult.Failure(
                        "HTTP ${response.status.value} ${response.status.description}"
                    )
                }
            }
        } catch (e: Exception) {
            HttpFetchResult.Failure(e.message ?: "Unknown HTTP error")
        }
    }
}
