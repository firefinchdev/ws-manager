package wsmanager.git

import wsmanager.cli.output.Printer
import wsmanager.util.ProcessRunner

/**
 * Concrete implementation of GitOperations using process-based Git command execution.
 * Shells out to the `git` binary for all operations.
 */
class GitCommandExecutor : GitOperations {

    private fun git(repoPath: String, vararg args: String): GitResult {
        val command = listOf("git") + args.toList()
        val result = ProcessRunner.execute(command, workingDir = repoPath)
        return GitResult.fromProcessResult(result)
    }

    private fun gitGlobal(vararg args: String): GitResult {
        val command = listOf("git") + args.toList()
        val result = ProcessRunner.execute(command)
        return GitResult.fromProcessResult(result)
    }

    // --- Repository Setup ---

    override fun clone(url: String, path: String, branch: String?): GitResult {
        val args = mutableListOf("clone")
        if (branch != null) {
            args.addAll(listOf("-b", branch))
        }
        args.add(url)
        args.add(path)
        val command = listOf("git") + args
        val result = ProcessRunner.execute(command)
        return GitResult.fromProcessResult(result)
    }

    override fun init(path: String): GitResult {
        val command = listOf("git", "init", path)
        val result = ProcessRunner.execute(command)
        return GitResult.fromProcessResult(result)
    }

    // --- Branch Operations ---

    override fun checkout(repoPath: String, branch: String, remote: String?): GitResult {
        // First, try a simple checkout
        val simpleResult = git(repoPath, "checkout", branch)
        if (simpleResult.success) return simpleResult

        // If local branch doesn't exist, try to create tracking branch from remote
        if (remote != null && !branchExists(repoPath, branch)) {
            // Fetch from remote first to make sure we have the latest refs
            fetch(repoPath, remote)

            // Check if remote branch exists
            if (remoteBranchExists(repoPath, remote, branch)) {
                return git(repoPath, "checkout", "-b", branch, "--track", "$remote/$branch")
            }
        }

        // If no remote specified, try to find the branch on any remote
        if (remote == null && !branchExists(repoPath, branch)) {
            val fetchResult = fetch(repoPath)
            if (fetchResult.success) {
                // Try checkout again - Git will auto-track if exactly one remote has it
                val retryResult = git(repoPath, "checkout", branch)
                if (retryResult.success) return retryResult
            }
        }

        return simpleResult // Return original error
    }

    override fun createBranch(repoPath: String, branch: String, startPoint: String?): GitResult {
        val args = mutableListOf("branch", branch)
        if (startPoint != null) args.add(startPoint)
        return git(repoPath, *args.toTypedArray())
    }

    override fun deleteBranch(repoPath: String, branch: String, force: Boolean): GitResult {
        val flag = if (force) "-D" else "-d"
        return git(repoPath, "branch", flag, branch)
    }

    override fun listBranches(repoPath: String, all: Boolean): GitResult {
        return if (all) {
            git(repoPath, "branch", "-a")
        } else {
            git(repoPath, "branch")
        }
    }

    override fun currentBranch(repoPath: String): GitResult {
        return git(repoPath, "rev-parse", "--abbrev-ref", "HEAD")
    }

    // --- Remote Operations ---

    override fun fetch(repoPath: String, remote: String?, prune: Boolean): GitResult {
        val args = mutableListOf("fetch")
        if (remote != null) {
            args.add(remote)
        } else {
            args.add("--all")
        }
        if (prune) args.add("--prune")
        return git(repoPath, *args.toTypedArray())
    }

    override fun pull(repoPath: String, remote: String?, branch: String?, rebase: Boolean): GitResult {
        val args = mutableListOf("pull")
        if (rebase) args.add("--rebase")
        if (remote != null) args.add(remote)
        if (branch != null) args.add(branch)
        return git(repoPath, *args.toTypedArray())
    }

    override fun push(repoPath: String, remote: String?, branch: String?, force: Boolean, setUpstream: Boolean): GitResult {
        val args = mutableListOf("push")
        if (force) args.add("--force")
        if (setUpstream) args.add("--set-upstream")
        if (remote != null) args.add(remote)
        if (branch != null) args.add(branch)
        return git(repoPath, *args.toTypedArray())
    }

    // --- Remote Management ---

    override fun addRemote(repoPath: String, name: String, url: String): GitResult {
        return git(repoPath, "remote", "add", name, url)
    }

    override fun removeRemote(repoPath: String, name: String): GitResult {
        return git(repoPath, "remote", "remove", name)
    }

    override fun listRemotes(repoPath: String, verbose: Boolean): GitResult {
        return if (verbose) {
            git(repoPath, "remote", "-v")
        } else {
            git(repoPath, "remote")
        }
    }

    override fun setRemoteUrl(repoPath: String, name: String, url: String): GitResult {
        return git(repoPath, "remote", "set-url", name, url)
    }

    // --- Merge & Rebase ---

    override fun merge(repoPath: String, branch: String, noFf: Boolean, message: String?): GitResult {
        val args = mutableListOf("merge")
        if (noFf) args.add("--no-ff")
        if (message != null) {
            args.addAll(listOf("-m", message))
        }
        args.add(branch)
        return git(repoPath, *args.toTypedArray())
    }

    override fun mergeAbort(repoPath: String): GitResult {
        return git(repoPath, "merge", "--abort")
    }

    override fun rebase(repoPath: String, onto: String): GitResult {
        return git(repoPath, "rebase", onto)
    }

    override fun rebaseAbort(repoPath: String): GitResult {
        return git(repoPath, "rebase", "--abort")
    }

    // --- Status & Info ---

    override fun status(repoPath: String, short: Boolean): GitResult {
        return if (short) {
            git(repoPath, "status", "--short", "--branch")
        } else {
            git(repoPath, "status")
        }
    }

    override fun headCommit(repoPath: String): GitResult {
        return git(repoPath, "rev-parse", "HEAD")
    }

    override fun branchExists(repoPath: String, branch: String): Boolean {
        val result = git(repoPath, "rev-parse", "--verify", "refs/heads/$branch")
        return result.success
    }

    override fun remoteBranchExists(repoPath: String, remote: String, branch: String): Boolean {
        val result = git(repoPath, "rev-parse", "--verify", "refs/remotes/$remote/$branch")
        return result.success
    }

    override fun isClean(repoPath: String): Boolean {
        val result = git(repoPath, "status", "--porcelain")
        return result.success && result.output.isBlank()
    }

    override fun isGitRepository(repoPath: String): Boolean {
        val result = git(repoPath, "rev-parse", "--is-inside-work-tree")
        return result.success && result.output.trim() == "true"
    }

    override fun discardChanges(repoPath: String): GitResult {
        return git(repoPath, "reset", "--hard", "HEAD")
    }

    override fun cleanUntracked(repoPath: String): GitResult {
        return git(repoPath, "clean", "-fd")
    }

    // --- Stash Operations ---

    override fun stash(repoPath: String, message: String?): GitResult {
        val args = mutableListOf("stash", "push")
        if (message != null) {
            args.addAll(listOf("-m", message))
        }
        return git(repoPath, *args.toTypedArray())
    }

    override fun stashPop(repoPath: String): GitResult {
        return git(repoPath, "stash", "pop")
    }

    override fun stashList(repoPath: String): GitResult {
        return git(repoPath, "stash", "list")
    }

    override fun stashDrop(repoPath: String, index: Int): GitResult {
        return git(repoPath, "stash", "drop", "stash@{$index}")
    }

    // --- Log ---

    override fun log(repoPath: String, count: Int, oneline: Boolean): GitResult {
        val args = mutableListOf("log", "-n", count.toString())
        if (oneline) args.add("--oneline")
        return git(repoPath, *args.toTypedArray())
    }

    override fun hasCommitsAheadOf(repoPath: String, baseBranch: String, defaultRemote: String): Boolean {
        // `git log <base>..HEAD --oneline` — lists commits on HEAD not reachable from base.
        // Non-empty output means the current branch is ahead of baseBranch.
        val local = git(repoPath, "log", "$baseBranch..HEAD", "--oneline")
        if (local.success && local.output.isNotBlank()) return true

        // baseBranch doesn't exist locally (e.g., never been checked out) —
        // retry against the tracking ref on the default remote.
        val remote = git(repoPath, "log", "$defaultRemote/$baseBranch..HEAD", "--oneline")
        if (remote.success && remote.output.isNotBlank()) return true

        return false
    }
}
