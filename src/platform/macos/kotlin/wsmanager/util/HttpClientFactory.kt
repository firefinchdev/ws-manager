package wsmanager.util

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

/**
 * macOS engine: uses NSURLSession via Apple's Darwin networking stack.
 * Full HTTPS support, certificate validation, and system proxy settings
 * come for free from the OS.
 */
internal fun createPlatformHttpClient(): HttpClient = HttpClient(Darwin)
