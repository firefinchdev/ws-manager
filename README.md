# ws-manager

**Multi-Repository Workspace Manager CLI** &mdash; Manage multiple Git repositories as a single logical workspace.

`ws-manager` is a Kotlin Native CLI tool that enables developers to execute Git operations across many repositories simultaneously, with parallel execution, atomic rollback guarantees, and structured terminal output.

---

## Table of Contents

- [Features](#features)
- [Installation](#installation)
- [Quick Start](#quick-start)
- [Configuration](#configuration)
  - [Configuration File Format](#configuration-file-format)
  - [Configuration Fields](#configuration-fields)
  - [Validation Rules](#validation-rules)
- [Global Options](#global-options)
- [Workspace Auto-Discovery](#workspace-auto-discovery)
- [Commands](#commands)
  - [Workspace Commands](#workspace-commands)
    - [init](#init)
    - [describe](#describe)
    - [clone](#clone)
    - [sync](#sync)
    - [refresh](#refresh)
    - [status](#status)
    - [foreach](#foreach)
    - [log](#log)
  - [Git Commands](#git-commands)
    - [checkout](#checkout)
    - [pull](#pull)
    - [push](#push)
    - [fetch](#fetch)
    - [merge](#merge)
    - [rebase](#rebase)
    - [branch](#branch)
    - [remote](#remote)
    - [stash](#stash)
- [Execution Strategies](#execution-strategies)
  - [ATOMIC Strategy](#atomic-strategy)
  - [BEST_EFFORT Strategy](#best_effort-strategy)
- [Rollback Framework](#rollback-framework)
- [Architecture](#architecture)
- [Building from Source](#building-from-source)
- [Typical Developer Workflow](#typical-developer-workflow)
- [Exit Codes](#exit-codes)

---

## Features

- **Parallel execution** across all repositories with configurable concurrency
- **ATOMIC strategy** for write operations &mdash; all-or-nothing with automatic rollback
- **BEST_EFFORT strategy** for read operations &mdash; continue on failure, report everything
- **Intelligent Git handling** &mdash; auto-creates tracking branches, handles multiple remotes
- **JSON configuration** with strict validation before execution
- **Workspace auto-discovery** &mdash; finds `workspace.json` by walking up the directory tree, just like `git` finds `.git`
- **`describe`** &mdash; inspect the full workspace config: repos, remotes, clone status, all in one view
- **`refresh`** &mdash; one-command "clean slate" for starting a new task: discard changes, checkout default branches, sync remotes, pull latest
- **`checkout --default`** &mdash; each repo checks out its own configured default branch
- **Colored terminal output** with progress indicators and structured summaries
- **Shell command execution** across all repos via `foreach`
- **Cross-platform** &mdash; compiles to native binary for macOS, Linux, and Windows
- **Zero runtime dependencies** &mdash; single self-contained binary

---

## Installation

### Building from Source

Requires JDK 11+ (for Gradle build only; the output binary has no JVM dependency).

```bash
git clone <repo-url> ws-manager
cd ws-manager
./gradlew nativeBinaries
```

The binary is produced at:
- **Release**: `build/bin/native/releaseExecutable/ws-manager.kexe`
- **Debug**: `build/bin/native/debugExecutable/ws-manager.kexe`

Copy the binary to a location on your `$PATH`:

```bash
cp build/bin/native/releaseExecutable/ws-manager.kexe /usr/local/bin/ws-manager
```

---

## Quick Start

```bash
# 1. Initialize a new workspace
ws-manager init --name my-project

# 2. Edit workspace.json to add your repositories (see Configuration below)

# 3. Clone all repositories
ws-manager clone

# 4. Inspect the workspace — repos, remotes, clone status
ws-manager describe

# 5. Check status across all repos
ws-manager status

# 6. Create a feature branch across all repos
ws-manager checkout feature/my-feature --create

# 7. Pull latest changes
ws-manager pull --rebase

# 8. Run tests across all repos
ws-manager foreach -- make test

# 9. Push changes
ws-manager push --set-upstream

# 10. Done with the feature? Reset everything to a clean baseline
ws-manager refresh
```

---

## Configuration

### Configuration File Format

`ws-manager` uses a JSON configuration file (default: `workspace.json` in the current directory).

```json
{
    "name": "my-microservices",
    "max_concurrency": 4,
    "base_path": ".",
    "repositories": [
        {
            "name": "api-gateway",
            "path": "./api-gateway",
            "default_branch": "main",
            "default_remote": "origin",
            "remotes": [
                {
                    "alias": "origin",
                    "url": "git@github.com:myorg/api-gateway.git"
                },
                {
                    "alias": "upstream",
                    "url": "git@github.com:upstream/api-gateway.git"
                }
            ]
        },
        {
            "name": "user-service",
            "path": "./user-service",
            "default_branch": "main",
            "default_remote": "origin",
            "remotes": [
                {
                    "alias": "origin",
                    "url": "git@github.com:myorg/user-service.git"
                }
            ]
        },
        {
            "name": "shared-libs",
            "path": "./shared-libs",
            "default_branch": "develop",
            "default_remote": "origin",
            "remotes": [
                {
                    "alias": "origin",
                    "url": "git@github.com:myorg/shared-libs.git"
                }
            ]
        }
    ]
}
```

### Configuration Fields

| Field | Type | Default | Description |
|---|---|---|---|
| `name` | string | `"workspace"` | Human-readable workspace name |
| `max_concurrency` | int | `4` | Maximum number of parallel operations |
| `base_path` | string | `"."` | Base directory for resolving relative repo paths |
| `repositories` | array | `[]` | List of repository definitions |

**Repository fields:**

| Field | Type | Default | Description |
|---|---|---|---|
| `name` | string | *(required)* | Unique name for the repository |
| `path` | string | *(required)* | Local filesystem path (absolute or relative to `base_path`) |
| `default_branch` | string | `"main"` | Default branch name |
| `default_remote` | string | `"origin"` | Default remote alias for push/pull/fetch operations |
| `remotes` | array | `[]` | List of remotes (`alias` + `url` pairs) |

### Validation Rules

The configuration is strictly validated before any command executes:

- **Workspace name** must not be blank
- **Max concurrency** must be at least 1
- **At least one repository** must be defined
- **Repository names** must be unique across the workspace
- **Repository name and path** must not be blank
- **At least one remote** must be defined per repository
- **Remote aliases** must be unique within each repository
- **Remote alias and URL** must not be blank
- **Default remote** must match one of the repository's defined remote aliases

Validation errors are reported with precise field paths (e.g., `repositories[0].remotes[1].alias`).

---

## Global Options

These options apply to all commands and must be placed before the command name:

| Option | Short | Description |
|---|---|---|
| `--config <path>` | `-c` | Explicit config file path. If omitted, auto-discovered by walking up the directory tree |
| `--concurrency <n>` | `-j` | Override max parallel operations from config |
| `--help` | `-h` | Show help (or command-specific help when used with a command) |
| `--version` | `-v` | Show version |

**Examples:**

```bash
# Use a custom config file
ws-manager -c ~/projects/workspace.json status

# Override concurrency to 8 parallel operations
ws-manager -j 8 fetch --prune

# Get help for a specific command
ws-manager checkout --help
```

---

## Workspace Auto-Discovery

When `--config` is not provided, `ws-manager` discovers the nearest `workspace.json` by walking **up** the directory tree from the current working directory — exactly the same way `git` discovers the `.git` directory.

This means you can run `ws-manager` from **anywhere inside your workspace**, including deep inside one of the managed repositories, and it will always find the right config.

### How it works

Given this directory structure:

```
~/projects/my-platform/       ← workspace.json lives here
    workspace.json
    api-gateway/              ← a managed repo
        src/
            controllers/
                auth/         ← you are here
    user-service/
    shared-libs/
```

Running `ws-manager status` from `~/projects/my-platform/api-gateway/src/controllers/auth/` will:

1. Check `auth/workspace.json` — not found
2. Check `controllers/workspace.json` — not found
3. Check `src/workspace.json` — not found
4. Check `api-gateway/workspace.json` — not found
5. Check `my-platform/workspace.json` — **found!**

The resolved workspace is used and a dim hint is printed to stderr so you always know which config is active:

```
  ↑ workspace: /home/user/projects/my-platform/workspace.json
```

No hint is shown when you run from the workspace root itself (the config is in the current directory).

### Override at any time

You can always bypass discovery and point to a specific config:

```bash
ws-manager -c /path/to/other/workspace.json status
```

---

## Commands

### Workspace Commands

#### init

Initialize a new workspace configuration file.

```
ws-manager init [--name <name>]
```

| Option | Description |
|---|---|
| `--name <name>` | Workspace name (default: `"my-workspace"`) |

Creates a `workspace.json` file with a sample repository entry. Edit this file to define your actual repositories before running other commands.

```bash
ws-manager init --name my-platform
```

---

#### describe

Describe the full workspace configuration — all settings, repositories, remotes, and local clone status.

```
ws-manager describe [--json]
```

| Option | Description |
|---|---|
| `--json` | Print the raw `workspace.json` content (useful for scripting/piping) |

**Strategy:** n/a (read-only, no git operations)

Displays:
- **Workspace settings** &mdash; config file path (absolute), workspace root, max concurrency, repository count
- **Per-repository detail** &mdash; relative path, resolved absolute path, default branch, default remote, all remotes with their URLs
- **Clone status** per repo &mdash; `cloned` (green), `directory exists (not a git repo)` (yellow), or `not cloned` (red)
- **Summary line** &mdash; totals at the bottom (e.g., `3 cloned | 1 not cloned`)

```bash
# Human-readable workspace overview
ws-manager describe

# Raw JSON (pipe to jq, etc.)
ws-manager describe --json | jq '.repositories[].name'
```

---

#### clone

Clone all repositories defined in the workspace configuration.

```
ws-manager clone
```

**Strategy:** BEST_EFFORT

- Clones each repository from its **default remote** URL
- Checks out the configured **default branch**
- Skips repositories that are already cloned
- Adds any additional remotes defined in the config after cloning

```bash
ws-manager clone
```

---

#### sync

Synchronize the workspace: clone missing repositories and update existing ones.

```
ws-manager sync [--rebase]
```

| Option | Description |
|---|---|
| `--rebase` | Use rebase instead of merge when pulling |

**Strategy:** BEST_EFFORT

For each repository:
- **If not cloned:** Clones from the default remote
- **If already cloned:** Fetches with `--prune`, syncs missing remotes from config, then pulls

```bash
# Sync all repos, using rebase for existing
ws-manager sync --rebase
```

---

#### refresh

Reset every repository to a clean, up-to-date state. Designed for the **"start a new task"** developer workflow.

```
ws-manager refresh [--clean-untracked]
```

| Option | Description |
|---|---|
| `--clean-untracked` | Also remove untracked files and directories (`git clean -fd`) |

**Strategy:** BEST_EFFORT

For each **existing** repository, executes these steps in order:

| # | Step | Git equivalent |
|---|---|---|
| 1 | Discard staged + unstaged changes to tracked files | `git reset --hard HEAD` |
| 2 | *(optional)* Remove untracked files and dirs | `git clean -fd` |
| 3 | Checkout this repo's own `default_branch` | `git checkout <defaultBranch>` |
| 4 | Sync remotes against config: add missing, remove extra, update changed URLs | `git remote add/remove/set-url` |
| 5 | Fetch all remotes, prune deleted remote-tracking branches | `git fetch --all --prune` |
| 6 | Pull from default remote on default branch | `git pull <defaultRemote> <defaultBranch>` |

For each **missing** repository, clones it from the default remote (same as `clone`).

Each repository's success line shows a compact per-step summary:

```
✓ api-gateway — success  changes discarded  ·  on main  ·  remotes: +1 remote  ·  Already up to date.
```

> **Note:** Step 1 (`git reset --hard HEAD`) removes staged and unstaged changes to tracked files but does **not** remove untracked files. Add `--clean-untracked` to also wipe those.

```bash
# Standard refresh: discard changes, checkout defaults, sync remotes, pull
ws-manager refresh

# Also remove untracked build artifacts, generated files, etc.
ws-manager refresh --clean-untracked
```

---

#### status

Show the status of all repositories in the workspace.

```
ws-manager status
```

Displays per-repository:
- Current branch name
- Working tree status (clean or list of changed files)
- Warnings for missing or non-Git directories

Output uses Git's short status format with color-coded indicators:
- Green: staged files
- Yellow: modified files
- Red: deleted files
- Gray: untracked files

```bash
ws-manager status
```

---

#### foreach

Execute an arbitrary shell command inside each repository's directory.

```
ws-manager foreach -- <command>
```

**Strategy:** BEST_EFFORT

- Runs the command in parallel across all repo directories
- Aggregates exit codes and output
- Reports per-repo success/failure
- Skips repos whose directories don't exist
- Blocks obviously dangerous commands (`rm -rf /`, `mkfs`, etc.)

```bash
# Run tests across all repos
ws-manager foreach -- npm test

# Check Go module versions
ws-manager foreach -- go version

# Custom build script
ws-manager foreach -- make build

# Git command not covered by ws-manager
ws-manager foreach -- git stash show -p
```

---

#### log

Show recent commits across all repositories.

```
ws-manager log [--count <n>]
```

| Option | Description |
|---|---|
| `--count <n>` | Number of commits to show per repo (default: `5`) |

**Strategy:** BEST_EFFORT

```bash
# Show last 10 commits per repo
ws-manager log --count 10
```

---

### Git Commands

#### checkout

Checkout a branch across all repositories.

```
ws-manager checkout <branch> [--create|-b]
ws-manager checkout --default|-d
```

| Option | Short | Description |
|---|---|---|
| `--create` | `-b` | Create the branch before checking out |
| `--default` | `-d` | Checkout each repo's own `default_branch` (no branch name argument needed) |

**Strategy:** ATOMIC

- If the branch exists locally, checks it out
- If the branch doesn't exist locally but exists on the default remote, creates a tracking branch
- If `--create` is specified, creates the branch at current HEAD first
- `--default` and `--create` cannot be combined

**Rollback on failure:** Checks out the previous branch. If `--create` was used, also deletes the newly created branch.

##### Checkout a named branch

```bash
# Checkout existing branch across all repos
ws-manager checkout develop

# Create and checkout a new feature branch across all repos
ws-manager checkout feature/auth-v2 --create
```

##### Checkout each repo's default branch

Each repository checks out its own `default_branch` as declared in `workspace.json`. Repos with different defaults (e.g. `main` vs `develop`) each get the right branch independently.

```bash
# All repos return to their configured default branch
ws-manager checkout --default

# Short form
ws-manager checkout -d
```

This is useful after finishing work on a feature branch and wanting to return every repo to its baseline without remembering which branch each one uses.

---

#### pull

Pull from remote across all repositories.

```
ws-manager pull [--rebase] [--remote <remote>]
```

| Option | Description |
|---|---|
| `--rebase` | Use rebase instead of merge |
| `--remote <remote>` | Pull from a specific remote (default: each repo's `default_remote`) |

**Strategy:** BEST_EFFORT

```bash
# Pull with rebase from default remotes
ws-manager pull --rebase

# Pull from upstream remote
ws-manager pull --remote upstream
```

---

#### push

Push to remote across all repositories.

```
ws-manager push [--remote <remote>] [--force] [--set-upstream]
```

| Option | Short | Description |
|---|---|---|
| `--remote <remote>` | | Push to a specific remote (default: each repo's `default_remote`) |
| `--force` | `-f` | Force push (uses `--force-with-lease` for safety) |
| `--set-upstream` | `-u` | Set upstream tracking reference |

**Strategy:** ATOMIC (but note: push cannot be truly rolled back once sent to remote)

- Automatically detects current branch and pushes it
- Force push uses `--force-with-lease` instead of `--force` to prevent overwriting others' work
- Warns if partial push occurs (some repos pushed, others failed)

```bash
# Push all repos to default remote
ws-manager push

# Push and set upstream tracking
ws-manager push --set-upstream

# Force push with lease
ws-manager push --force
```

---

#### fetch

Fetch from remote across all repositories.

```
ws-manager fetch [--remote <remote>] [--prune]
```

| Option | Description |
|---|---|
| `--remote <remote>` | Fetch from a specific remote (default: each repo's `default_remote`) |
| `--prune` | Remove stale remote-tracking references |

**Strategy:** BEST_EFFORT

```bash
# Fetch all with prune
ws-manager fetch --prune

# Fetch from a specific remote
ws-manager fetch --remote upstream
```

---

#### merge

Merge a branch into the current branch across all repositories.

```
ws-manager merge <branch> [--no-ff] [--message <msg>]
```

| Option | Short | Description |
|---|---|---|
| `--no-ff` | | Create a merge commit even for fast-forward merges |
| `--message <msg>` | `-m` | Custom merge commit message |

**Strategy:** ATOMIC

**Rollback on failure:** Aborts the merge if still in progress, or resets to the previous HEAD commit if the merge completed (e.g., fast-forward).

```bash
# Merge develop into current branch
ws-manager merge develop

# Merge with no-ff and custom message
ws-manager merge feature/auth --no-ff --message "Merge auth feature"
```

---

#### rebase

Rebase the current branch onto a target branch across all repositories.

```
ws-manager rebase <onto-branch>
```

**Strategy:** ATOMIC

**Rollback on failure:** Aborts the rebase if still in progress, or resets to the previous HEAD commit.

```bash
# Rebase onto main
ws-manager rebase main
```

---

#### branch

Branch management across all repositories. Supports listing, creating, and deleting branches.

```
ws-manager branch [<name>] [--create] [--delete] [--force] [--all]
```

##### List branches

```bash
# List local branches across all repos
ws-manager branch

# List all branches (including remote-tracking)
ws-manager branch --all
```

**Strategy:** BEST_EFFORT

##### Create a branch

```bash
ws-manager branch feature/new-api --create
```

| Option | Short | Description |
|---|---|---|
| `--create` | `-c` | Create the named branch |

**Strategy:** ATOMIC

**Rollback on failure:** Deletes the branch from repos where creation succeeded.

##### Delete a branch

```bash
# Safe delete (only if fully merged)
ws-manager branch feature/old --delete

# Force delete
ws-manager branch feature/old --delete --force
```

| Option | Short | Description |
|---|---|---|
| `--delete` | `-d` | Delete the named branch |
| `--force` | `-f` | Force delete (use `-D` internally) |

**Strategy:** ATOMIC

**Rollback on failure:** Recreates the branch at the same commit it pointed to before deletion.

---

#### remote

Remote management across all repositories. Supports listing, adding, removing, and updating remotes.

```
ws-manager remote [list|add|remove|set-url] [options]
```

##### List remotes

```bash
# List remote names
ws-manager remote list

# List remotes with URLs
ws-manager remote list --verbose
```

##### Add a remote

```bash
ws-manager remote add --name upstream --url git@github.com:upstream/project.git
```

##### Remove a remote

```bash
ws-manager remote remove --name upstream
```

##### Set remote URL

```bash
ws-manager remote set-url --name origin --url git@github.com:neworg/project.git
```

All remote subcommands use **BEST_EFFORT** strategy.

---

#### stash

Stash operations across all repositories. Supports push, pop, list, and drop.

```
ws-manager stash [push|pop|list|drop] [options]
```

##### Stash push (save changes)

```bash
# Stash all changes
ws-manager stash push

# Stash with a message
ws-manager stash push --message "WIP: auth changes"
```

**Strategy:** ATOMIC

Skips clean repositories (no changes to stash). On failure, pops the stash from repos where push succeeded.

##### Stash pop (restore changes)

```bash
ws-manager stash pop
```

**Strategy:** ATOMIC

Skips repos with no stash entries. On failure, re-stashes changes in repos where pop succeeded.

##### Stash list

```bash
ws-manager stash list
```

**Strategy:** BEST_EFFORT

##### Stash drop

```bash
# Drop the latest stash entry
ws-manager stash drop

# Drop a specific stash entry
ws-manager stash drop --index 2
```

**Strategy:** BEST_EFFORT

---

## Execution Strategies

Every operation uses one of two execution strategies. The strategy determines how the tool handles partial failures across repositories.

### ATOMIC Strategy

**Used for:** checkout, push, merge, rebase, branch create, branch delete, stash push, stash pop

**Behavior:**

1. **Snapshot phase** &mdash; Capture the current state of each repository (branch name, HEAD commit, etc.) before the operation
2. **Execute phase** &mdash; Run the operation in parallel across all repositories
3. **Verify phase** &mdash; If all succeed, the operation is complete
4. **Rollback phase** &mdash; If any repository fails:
   - All successful repositories are rolled back to their captured snapshot
   - Rollback is best-effort and clearly reported
   - The operation returns failure

**Guarantee:** The workspace will not be left in an inconsistent partial state (to the extent rollback is possible).

### BEST_EFFORT Strategy

**Used for:** clone, sync, refresh, status, pull, fetch, log, foreach, branch list, remote operations, stash list, stash drop

**Behavior:**

1. Execute the operation in parallel across all repositories
2. Collect all results regardless of individual success/failure
3. Report which repos succeeded and which failed
4. Continue even if some repositories fail

**Guarantee:** Maximum information retrieval with tolerance for partial failure.

---

## Rollback Framework

The rollback system is operation-specific. Each ATOMIC operation defines:

| Operation | Snapshot Data | Rollback Action |
|---|---|---|
| **checkout** | Previous branch name | Checkout previous branch; delete created branch if `--create` |
| **merge** | HEAD commit hash | Abort merge or `git reset --hard` to previous HEAD |
| **rebase** | HEAD commit hash | Abort rebase or `git reset --hard` to previous HEAD |
| **branch create** | *(none needed)* | Delete the created branch |
| **branch delete** | Commit hash branch pointed to | Recreate branch at same commit |
| **stash push** | Whether repo had changes | Pop the stash |
| **stash pop** | Stash list | Re-stash the changes |
| **push** | HEAD commit hash | *(cannot rollback a push &mdash; warning issued)* |

The operation summary clearly reports:
- Which repos **succeeded**
- Which repos **failed**
- Which repos were **rolled back**
- Which repos had a **rollback failure**

---

## Architecture

```
src/nativeMain/kotlin/wsmanager/
├── Main.kt                          # Entry point
├── cli/
│   ├── Command.kt                   # Command interface + CommandContext
│   ├── WsManagerApp.kt              # CLI dispatcher, global option parsing
│   ├── commands/
│   │   ├── InitCommand.kt           # Initialize workspace config
│   │   ├── DescribeCommand.kt       # Describe workspace config + clone status
│   │   ├── CloneCommand.kt          # Clone all repositories
│   │   ├── SyncCommand.kt           # Clone missing + update existing
│   │   ├── RefreshCommand.kt        # Clean slate + pull latest (new-task workflow)
│   │   ├── StatusCommand.kt         # Show workspace status
│   │   ├── CheckoutCommand.kt       # Checkout branch / default branch (ATOMIC)
│   │   ├── PullCommand.kt           # Pull from remote
│   │   ├── PushCommand.kt           # Push to remote (ATOMIC)
│   │   ├── FetchCommand.kt          # Fetch from remote
│   │   ├── MergeCommand.kt          # Merge branch (ATOMIC)
│   │   ├── RebaseCommand.kt         # Rebase branch (ATOMIC)
│   │   ├── BranchCommand.kt         # Branch list/create/delete
│   │   ├── RemoteCommand.kt         # Remote list/add/remove/set-url
│   │   ├── StashCommand.kt          # Stash push/pop/list/drop
│   │   ├── ForeachCommand.kt        # Execute shell commands
│   │   └── LogCommand.kt            # Show recent commits
│   └── output/
│       ├── Printer.kt               # Output formatting and UX
│       └── TerminalColors.kt        # ANSI color codes
├── config/
│   ├── WorkspaceConfig.kt           # Config data model (serializable)
│   ├── ConfigParser.kt              # JSON parsing + writing
│   └── ConfigValidator.kt           # Validation rules
├── core/models/
│   ├── Repository.kt                # Repository data model
│   ├── Remote.kt                    # Remote data model
│   └── ExecutionResult.kt           # Result types + ExecutionStrategy enum
├── engine/
│   ├── ExecutionEngine.kt           # Parallel execution with coroutines
│   ├── OperationContext.kt          # RepoOperation<T> definition
│   └── RollbackManager.kt          # Rollback orchestration
├── git/
│   ├── GitOperations.kt             # Git abstraction interface
│   ├── GitCommandExecutor.kt        # Process-based Git implementation
│   └── GitResult.kt                 # Git operation result type
└── util/
    ├── ProcessRunner.kt             # Cross-platform process execution
    └── FileUtils.kt                 # File I/O via POSIX APIs
```

### Key Design Principles

- **Separation of concerns** &mdash; Git operations, execution orchestration, CLI parsing, and output formatting are fully independent modules
- **Testable abstractions** &mdash; `GitOperations` is an interface; `GitCommandExecutor` can be swapped for a mock
- **Typed rollback snapshots** &mdash; `RepoOperation<T>` is generic over snapshot type, enabling type-safe rollback data (branch names, commit hashes, booleans, etc.)
- **Coroutine-based parallelism** &mdash; `kotlinx.coroutines` with `Semaphore` for bounded concurrency
- **Extensible command system** &mdash; Adding a new command requires only implementing the `Command` interface and registering it in `WsManagerApp`

---

## Building from Source

### Prerequisites

- **JDK 11+** (for Gradle build tooling)
- **Kotlin/Native** is automatically downloaded by Gradle on first build

### Build Commands

```bash
# Compile only (fast check)
./gradlew compileKotlinNative

# Build debug + release binaries
./gradlew nativeBinaries

# Build release binary only
./gradlew linkReleaseExecutableNative
```

### Output Locations

| Build Type | Path |
|---|---|
| Debug | `build/bin/native/debugExecutable/ws-manager.kexe` |
| Release | `build/bin/native/releaseExecutable/ws-manager.kexe` |

---

## Typical Developer Workflow

### Setting up a new workspace

```bash
# Create workspace config
ws-manager init --name platform-services

# Edit workspace.json to add all your repos
# (See Configuration section above)

# Clone everything
ws-manager clone

# Verify all repos are set up
ws-manager status
```

### Starting a new task (clean slate)

After completing a feature, use `refresh` to instantly put every repo in a clean, up-to-date state ready for the next task:

```bash
# Discard all local changes, checkout each repo's default branch,
# sync remotes, and pull the latest — all in one command
ws-manager refresh

# If you also want untracked build artifacts removed
ws-manager refresh --clean-untracked
```

### Daily development

```bash
# Start the day: sync everything
ws-manager sync --rebase

# Check workspace configuration at a glance
ws-manager describe

# Create a feature branch across all repos
ws-manager checkout feature/payment-v2 --create

# Check what's changed
ws-manager status

# Stash work-in-progress before switching context
ws-manager stash push --message "WIP: payment integration"

# Switch to hotfix branch
ws-manager checkout hotfix/login-fix

# Come back and restore work
ws-manager checkout feature/payment-v2
ws-manager stash pop

# Return all repos to their individual default branches
ws-manager checkout --default
```

### Preparing a release

```bash
# Make sure everything is up to date
ws-manager fetch --prune
ws-manager pull --rebase

# Merge feature branch into main
ws-manager checkout main
ws-manager merge feature/payment-v2 --no-ff --message "Release: payment v2"

# Push everything
ws-manager push

# Run tests across all repos
ws-manager foreach -- make test

# Clean up feature branches
ws-manager branch feature/payment-v2 --delete
```

### Running batch operations

```bash
# Install dependencies everywhere
ws-manager foreach -- npm install

# Check for security vulnerabilities
ws-manager foreach -- npm audit

# Run linters
ws-manager foreach -- ./scripts/lint.sh

# Show recent commits
ws-manager log --count 3
```

---

## Exit Codes

| Code | Meaning |
|---|---|
| `0` | All operations completed successfully |
| `1` | One or more operations failed, or a configuration/argument error occurred |

---

## Supported Platforms

| Platform | Architecture | Status |
|---|---|---|
| macOS | arm64 (Apple Silicon) | Supported |
| macOS | x86_64 | Supported |
| Linux | x86_64 | Supported |
| Linux | arm64 | Supported |
| Windows | x86_64 (MinGW) | Supported |

The build automatically detects the host platform and compiles for it.

---

## License

MIT
