package wsmanager.core.models

import kotlinx.serialization.Serializable

/**
 * Represents a Git remote with an alias and URL.
 */
@Serializable
data class Remote(
    val alias: String,
    val url: String
) {
    override fun toString(): String = "$alias -> $url"
}
