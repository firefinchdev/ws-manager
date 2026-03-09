package wsmanager.util

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO

/**
 * Linux engine: pure-Kotlin Coroutine I/O (CIO) using POSIX sockets.
 * Handles HTTP and HTTPS via kotlinx-io TLS without any native system library.
 */
internal fun createPlatformHttpClient(): HttpClient = HttpClient(CIO)
