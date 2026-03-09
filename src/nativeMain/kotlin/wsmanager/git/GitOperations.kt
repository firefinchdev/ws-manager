package wsmanager.git

/**
 * Interface for Git operations.
 * Abstracts Git commands for testability and extensibility.
 */
interface GitOperations {

    // --- Repository Setup ---

    /** Clone a repository from a URL to a local path. */
    fun clone(url: String, path: String, branch: String? = null): GitResult

    /** Initialize a new git repository at the given path. */
    fun init(path: String): GitResult

    // --- Branch Operations ---

    /** Checkout a branch. Creates tracking branch from remote if local doesn't exist. */
    fun checkout(repoPath: String, branch: String, remote: String? = null): GitResult

    /** Create a new branch at the current HEAD. */
    fun createBranch(repoPath: String, branch: String, startPoint: String? = null): GitResult

    /** Delete a branch (local). */
    fun deleteBranch(repoPath: String, branch: String, force: Boolean = false): GitResult

    /** List branches. */
    fun listBranches(repoPath: String, all: Boolean = false): GitResult

    /** Get the current branch name. */
    fun currentBranch(repoPath: String): GitResult

    // --- Remote Operations ---

    /** Fetch from remote(s). */
    fun fetch(repoPath: String, remote: String? = null, prune: Boolean = false): GitResult

    /** Pull from remote. */
    fun pull(repoPath: String, remote: String? = null, branch: String? = null, rebase: Boolean = false): GitResult

    /** Push to remote. */
    fun push(repoPath: String, remote: String? = null, branch: String? = null, force: Boolean = false, setUpstream: Boolean = false): GitResult

    // --- Remote Management ---

    /** Add a remote. */
    fun addRemote(repoPath: String, name: String, url: String): GitResult

    /** Remove a remote. */
    fun removeRemote(repoPath: String, name: String): GitResult

    /** List remotes. */
    fun listRemotes(repoPath: String, verbose: Boolean = false): GitResult

    /** Set remote URL. */
    fun setRemoteUrl(repoPath: String, name: String, url: String): GitResult

    // --- Merge & Rebase ---

    /** Merge a branch into the current branch. */
    fun merge(repoPath: String, branch: String, noFf: Boolean = false, message: String? = null): GitResult

    /** Abort a merge in progress. */
    fun mergeAbort(repoPath: String): GitResult

    /** Rebase current branch onto a target branch. */
    fun rebase(repoPath: String, onto: String): GitResult

    /** Abort a rebase in progress. */
    fun rebaseAbort(repoPath: String): GitResult

    // --- Status & Info ---

    /** Get repository status. */
    fun status(repoPath: String, short: Boolean = true): GitResult

    /** Get the current HEAD commit hash. */
    fun headCommit(repoPath: String): GitResult

    /** Check if a branch exists locally. */
    fun branchExists(repoPath: String, branch: String): Boolean

    /** Check if a remote branch exists. */
    fun remoteBranchExists(repoPath: String, remote: String, branch: String): Boolean

    /** Check if the working directory is clean. */
    fun isClean(repoPath: String): Boolean

    /** Check if a directory is a git repository. */
    fun isGitRepository(repoPath: String): Boolean

    /**
     * Discard all local changes (staged + unstaged) to tracked files.
     * Equivalent to `git reset --hard HEAD`.
     * Does NOT remove untracked files — use [cleanUntracked] for that.
     */
    fun discardChanges(repoPath: String): GitResult

    /**
     * Remove all untracked files and directories from the working tree.
     * Equivalent to `git clean -fd`.
     * This is destructive — use with care.
     */
    fun cleanUntracked(repoPath: String): GitResult

    // --- Stash Operations ---

    /** Stash current changes. */
    fun stash(repoPath: String, message: String? = null): GitResult

    /** Pop the latest stash. */
    fun stashPop(repoPath: String): GitResult

    /** List stashes. */
    fun stashList(repoPath: String): GitResult

    /** Drop a stash entry. */
    fun stashDrop(repoPath: String, index: Int = 0): GitResult

    // --- Log ---

    /** Get log of recent commits. */
    fun log(repoPath: String, count: Int = 10, oneline: Boolean = true): GitResult

    /**
     * Returns true if the currently checked-out branch has at least one commit
     * that is NOT reachable from [baseBranch] (i.e., HEAD is ahead of baseBranch).
     *
     * Falls back to checking `origin/<baseBranch>` when [baseBranch] doesn't
     * exist locally. Returns false when the repo is on the default branch itself
     * or when neither ref can be resolved (e.g., fresh clone with no commits on
     * the base branch yet).
     */
    fun hasCommitsAheadOf(repoPath: String, baseBranch: String, defaultRemote: String): Boolean
}
