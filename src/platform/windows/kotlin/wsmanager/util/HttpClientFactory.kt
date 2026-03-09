package wsmanager.util

import io.ktor.client.HttpClient
import io.ktor.client.engine.winhttp.WinHttp

/**
 * Windows engine: uses WinHTTP — the OS-native Windows HTTP Services API.
 * HTTPS, proxy settings, and certificate validation are handled by Windows.
 */
internal fun createPlatformHttpClient(): HttpClient = HttpClient(WinHttp)
