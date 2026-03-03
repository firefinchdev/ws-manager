package wsmanager.util

/**
 * Utilities for parsing Git remote URLs and constructing browser-viewable URLs.
 *
 * Handles both SSH and HTTPS remote formats across all major Git providers.
 *
 * Supported remote URL formats
 * ─────────────────────────────────────────────────────────────
 *   SCP-style SSH   git@github.com:user/repo.git
 *   ssh:// URI      ssh://git@github.com/user/repo.git
 *   HTTPS           https://github.com/user/repo.git
 *   HTTP            http://github.com/user/repo.git
 *   HTTPS + user    https://user@dev.azure.com/org/project/_git/repo
 *   Azure SSH       git@ssh.dev.azure.com:v3/org/project/repo
 *
 * Supported providers
 * ─────────────────────────────────────────────────────────────
 *   GitHub          github.com
 *   GitLab          gitlab.com + self-hosted (any hostname containing "gitlab")
 *   Bitbucket       bitbucket.org
 *   Azure DevOps    dev.azure.com  /  *.visualstudio.com
 *   Gitea / Forgejo any self-hosted (generic fallback)
 *   SourceHut       sr.ht  /  *.sr.ht
 */
object GitRemoteUrl {

    /**
     * Normalised representation of a parsed remote URL.
     *
     * @property host  e.g. `github.com`, `gitlab.mycompany.com`
     * @property path  e.g. `user/repo` (no leading slash, no `.git` suffix)
     */
    data class ParsedRemote(val host: String, val path: String)

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Parse a Git remote URL into its [ParsedRemote] components, or return `null`
     * if the format is not recognised.
     */
    fun parse(remoteUrl: String): ParsedRemote? {
        val url = remoteUrl.trim()
        return when {
            // Azure DevOps SSH: git@ssh.dev.azure.com:v3/org/project/repo
            // Must be checked BEFORE the generic git@ case.
            url.startsWith("git@ssh.dev.azure.com:") ->
                parseAzureSsh(url)

            // SCP-style SSH: git@github.com:user/repo.git
            url.startsWith("git@") ->
                parseScp(url)

            // ssh:// URI: ssh://git@github.com/user/repo.git
            url.startsWith("ssh://") ->
                parseSshUri(url)

            // HTTPS / HTTP: https://github.com/user/repo.git
            url.startsWith("https://") || url.startsWith("http://") ->
                parseHttp(url)

            else -> null
        }
    }

    /**
     * Construct a browser-viewable URL for [remoteUrl] pointing at the given
     * [branch].  Returns `null` if the remote URL cannot be parsed.
     *
     * When [branch] is `null` the returned URL points at the repository root
     * (i.e., the default branch as chosen by the hosting provider).
     */
    fun toBrowserUrl(remoteUrl: String, branch: String? = null): String? {
        val p = parse(remoteUrl) ?: return null
        val host = p.host.lowercase()
        return when {
            // GitHub ─────────────────────────────────────────────────────────
            host == "github.com" || host.endsWith(".github.com") ->
                buildUrl("https://github.com/${p.path}", branch, style = UrlStyle.TREE)

            // GitLab (gitlab.com + any self-hosted instance whose hostname
            // contains "gitlab", e.g. gitlab.mycompany.io) ──────────────────
            host == "gitlab.com" || "gitlab" in host ->
                buildUrl("https://$host/${p.path}", branch, style = UrlStyle.GITLAB_TREE)

            // Bitbucket ──────────────────────────────────────────────────────
            host == "bitbucket.org" ->
                buildUrl("https://bitbucket.org/${p.path}", branch, style = UrlStyle.BITBUCKET_SRC)

            // Azure DevOps ───────────────────────────────────────────────────
            host == "dev.azure.com" || host == "ssh.dev.azure.com"
                    || host.endsWith(".visualstudio.com") ->
                buildAzureUrl(p, branch)

            // SourceHut ──────────────────────────────────────────────────────
            host == "sr.ht" || host.endsWith(".sr.ht") ->
                buildUrl("https://$host/${p.path}", branch, style = UrlStyle.TREE)

            // Generic / self-hosted Gitea, Forgejo, Gogs, etc. ───────────────
            // GitHub-style `tree/` works for Gitea/Forgejo/Gogs out of the box.
            else ->
                buildUrl("https://$host/${p.path}", branch, style = UrlStyle.TREE)
        }
    }

    // ── Parsers ───────────────────────────────────────────────────────────────

    /** git@github.com:user/repo.git */
    private fun parseScp(url: String): ParsedRemote? {
        val withoutPrefix = url.removePrefix("git@")
        val colon = withoutPrefix.indexOf(':')
        if (colon < 0) return null
        val host = withoutPrefix.substring(0, colon)
        val path = withoutPrefix.substring(colon + 1).removeSuffix(".git").trimStart('/')
        return ParsedRemote(host, path)
    }

    /** git@ssh.dev.azure.com:v3/org/project/repo */
    private fun parseAzureSsh(url: String): ParsedRemote? {
        val raw = url.removePrefix("git@ssh.dev.azure.com:")
            .removePrefix("v3/")
            .removeSuffix(".git")
        val parts = raw.split("/")
        if (parts.size < 3) return null
        // Treat as org/project/repo (no _git/ in SSH path)
        return ParsedRemote("ssh.dev.azure.com", parts.take(3).joinToString("/"))
    }

    /** ssh://git@github.com/user/repo.git */
    private fun parseSshUri(url: String): ParsedRemote? {
        var body = url.removePrefix("ssh://")
        // strip optional "git@" user part
        if (body.startsWith("git@")) body = body.removePrefix("git@")
        val slash = body.indexOf('/')
        if (slash < 0) return null
        val host = body.substring(0, slash)
        val path = body.substring(slash + 1).removeSuffix(".git").trimStart('/')
        return ParsedRemote(host, path)
    }

    /** https://user@github.com/user/repo.git  or  http://github.com/user/repo.git */
    private fun parseHttp(url: String): ParsedRemote? {
        var body = url.removePrefix("https://").removePrefix("http://")
        // Strip embedded username (e.g.  org@dev.azure.com → dev.azure.com)
        val atInHost = body.indexOf('@')
        val firstSlash = body.indexOf('/')
        if (atInHost in 0 until (firstSlash.takeIf { it >= 0 } ?: body.length)) {
            body = body.substring(atInHost + 1)
        }
        val slash = body.indexOf('/')
        if (slash < 0) return null
        val host = body.substring(0, slash)
        val path = body.substring(slash + 1).removeSuffix(".git").trimStart('/')
        return ParsedRemote(host, path)
    }

    // ── URL builders ─────────────────────────────────────────────────────────

    private enum class UrlStyle { TREE, GITLAB_TREE, BITBUCKET_SRC }

    private fun buildUrl(base: String, branch: String?, style: UrlStyle): String {
        if (branch == null) return base
        return when (style) {
            UrlStyle.TREE         -> "$base/tree/$branch"
            UrlStyle.GITLAB_TREE  -> "$base/-/tree/$branch"
            UrlStyle.BITBUCKET_SRC -> "$base/src/$branch"
        }
    }

    /**
     * Azure DevOps browser URL builder.
     *
     * SSH path:   org/project/repo          → dev.azure.com/org/project/_git/repo
     * HTTPS path: org/project/_git/repo     → dev.azure.com/org/project/_git/repo
     * Legacy VS:  org.visualstudio.com/proj/_git/repo (keep original host)
     *
     * Branch is appended as ?version=GB<branch>.
     */
    private fun buildAzureUrl(p: ParsedRemote, branch: String?): String {
        val host = p.host.lowercase()

        // Normalise path to always contain _git/
        val normPath = if ("_git" !in p.path) {
            // SSH format: "org/project/repo"
            val parts = p.path.split("/")
            if (parts.size >= 3) "${parts[0]}/${parts[1]}/_git/${parts[2]}"
            else p.path
        } else {
            p.path
        }

        val base = if (host.endsWith(".visualstudio.com")) {
            "https://$host/$normPath"
        } else {
            "https://dev.azure.com/$normPath"
        }

        return if (branch != null) "$base?version=GB$branch" else base
    }
}
