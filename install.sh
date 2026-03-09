#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# ws-manager installer — macOS & Linux
#
# One-liner install:
#   curl -fsSL https://raw.githubusercontent.com/firefinchdev/ws-manager/main/install.sh | bash
#
# Install a specific version:
#   curl -fsSL ...install.sh | bash -s -- --version v1.2.3
#
# Override install directory:
#   curl -fsSL ...install.sh | bash -s -- --dir ~/.bin
#
# Environment variable override:
#   WS_VERSION=v1.2.3 bash install.sh
# ─────────────────────────────────────────────────────────────────────────────

set -euo pipefail

# ── Configuration ─────────────────────────────────────────────────────────────
REPO="firefinchdev/ws-manager"
BINARY_NAME="ws"
API_URL="https://api.github.com/repos/${REPO}/releases/latest"
DOWNLOAD_BASE="https://github.com/${REPO}/releases/download"

# ── Colours ───────────────────────────────────────────────────────────────────
# Use $'...' (ANSI-C quoting) so the variables hold the actual ESC byte (0x1B),
# not the 7-character literal string \033[...  This works reliably even when
# the script is piped through bash (curl | bash) with no interactive TTY on
# stdin, and avoids relying on printf to interpret the octal escape.
if [ -t 1 ]; then
    BOLD=$'\033[1m';    RESET=$'\033[0m'
    GREEN=$'\033[0;32m'; CYAN=$'\033[0;36m'
    YELLOW=$'\033[0;33m'; RED=$'\033[0;31m'
else
    BOLD=''; RESET=''; GREEN=''; CYAN=''; YELLOW=''; RED=''
fi

info()    { printf "  ${CYAN}->  ${RESET}%s\n" "$1"; }
success() { printf "  ${GREEN}OK  ${RESET}${BOLD}%s${RESET}\n" "$1"; }
warn()    { printf "  ${YELLOW}!!  ${RESET}%s\n" "$1" >&2; }
die()     { printf "  ${RED}ERR ${RESET}%s\n" "$1" >&2; exit 1; }

# ── Temp dir — declared GLOBAL so the EXIT trap can always reach it ───────────
# (a 'local' variable inside main() goes out of scope before the trap fires)
TMP_DIR=""
trap '[[ -n "$TMP_DIR" ]] && rm -rf "$TMP_DIR"' EXIT

# ── Argument parsing ──────────────────────────────────────────────────────────
VERSION="${WS_VERSION:-}"
INSTALL_DIR=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --version|-v) VERSION="$2"; shift 2 ;;
        --dir|-d)     INSTALL_DIR="$2"; shift 2 ;;
        --help|-h)
            echo "Usage: install.sh [--version v1.2.3] [--dir /usr/local/bin]"
            exit 0 ;;
        *) die "Unknown argument: $1" ;;
    esac
done

# ── Platform detection ────────────────────────────────────────────────────────
detect_platform() {
    local os arch
    os=$(uname -s)
    arch=$(uname -m)

    case "$os" in
        Darwin)
            case "$arch" in
                arm64)         echo "macos-arm64" ;;
                x86_64)        echo "macos-x64" ;;
                *)             die "Unsupported macOS architecture: $arch" ;;
            esac ;;
        Linux)
            case "$arch" in
                x86_64)        echo "linux-x64" ;;
                aarch64|arm64) echo "linux-arm64" ;;
                *)             die "Unsupported Linux architecture: $arch" ;;
            esac ;;
        *)
            die "Unsupported OS: $os — on Windows, run install.ps1 instead." ;;
    esac
}

# ── HTTP downloader (curl or wget) ────────────────────────────────────────────
http_get() {
    local url="$1" dest="$2"
    if command -v curl &>/dev/null; then
        curl -fsSL --progress-bar -o "$dest" "$url"
    elif command -v wget &>/dev/null; then
        wget -q --show-progress -O "$dest" "$url"
    else
        die "Neither curl nor wget is available. Please install one and retry."
    fi
}

http_get_quiet() {   # for small text files (API responses, .sha256 sidecars)
    local url="$1" dest="$2"
    if command -v curl &>/dev/null; then
        curl -fsSL -o "$dest" "$url"
    else
        wget -qO "$dest" "$url"
    fi
}

# ── Fetch latest release version from GitHub API ─────────────────────────────
fetch_latest_version() {
    local response
    if command -v curl &>/dev/null; then
        response=$(curl -fsSL "$API_URL")
    else
        response=$(wget -qO- "$API_URL")
    fi
    echo "$response" | grep '"tag_name"' | sed 's/.*"tag_name": *"\([^"]*\)".*/\1/'
}

# ── SHA-256 hash of a file ────────────────────────────────────────────────────
sha256_of() {
    local file="$1"
    if command -v sha256sum &>/dev/null; then
        sha256sum "$file" | awk '{print $1}'
    elif command -v shasum &>/dev/null; then
        shasum -a 256 "$file" | awk '{print $1}'
    else
        echo ""  # no tool available — caller will skip verification
    fi
}

# ── Install the binary ────────────────────────────────────────────────────────
install_binary() {
    local src="$1"
    chmod +x "$src"

    if [[ -n "$INSTALL_DIR" ]]; then
        mkdir -p "$INSTALL_DIR"
        mv "$src" "$INSTALL_DIR/$BINARY_NAME"
        echo "$INSTALL_DIR"
        return
    fi

    # Try /usr/local/bin (with or without sudo)
    if [[ -w "/usr/local/bin" ]]; then
        mv "$src" "/usr/local/bin/$BINARY_NAME"
        echo "/usr/local/bin"
    elif command -v sudo &>/dev/null && sudo -n true 2>/dev/null; then
        sudo mv "$src" "/usr/local/bin/$BINARY_NAME"
        echo "/usr/local/bin"
    elif command -v sudo &>/dev/null; then
        info "Root privileges required to install to /usr/local/bin"
        sudo mv "$src" "/usr/local/bin/$BINARY_NAME"
        echo "/usr/local/bin"
    else
        # Fall back to ~/.local/bin (no root required)
        mkdir -p "$HOME/.local/bin"
        mv "$src" "$HOME/.local/bin/$BINARY_NAME"
        echo "$HOME/.local/bin"
    fi
}

# ── Main ──────────────────────────────────────────────────────────────────────
main() {
    printf "\n${BOLD}  ws-manager installer${RESET}\n\n"

    # 1 ── Detect platform
    local platform
    platform=$(detect_platform)
    info "Detected: ${BOLD}$platform${RESET}"

    # 2 ── Resolve version
    if [[ -z "$VERSION" ]]; then
        info "Fetching latest release version..."
        VERSION=$(fetch_latest_version)
        [[ -z "$VERSION" ]] && die "Could not determine the latest release. Check your internet connection."
    fi
    info "Version:  ${BOLD}$VERSION${RESET}"

    # 3 ── Build filenames / URLs
    local binary_file="ws-${platform}"
    local binary_url="${DOWNLOAD_BASE}/${VERSION}/${binary_file}"
    local sha_url="${DOWNLOAD_BASE}/${VERSION}/${binary_file}.sha256"

    # 4 ── Create temp workspace (global TMP_DIR so EXIT trap can clean it up)
    TMP_DIR=$(mktemp -d)

    # 5 ── Download binary
    info "Downloading ${binary_file}..."
    http_get "$binary_url" "$TMP_DIR/$binary_file"

    # 6 ── Verify checksum (best-effort: warn and continue if unavailable)
    info "Verifying checksum..."
    if http_get_quiet "$sha_url" "$TMP_DIR/$binary_file.sha256" 2>/dev/null; then
        local expected actual
        expected=$(awk '{print $1}' "$TMP_DIR/$binary_file.sha256")
        actual=$(sha256_of "$TMP_DIR/$binary_file")

        if [[ -z "$actual" ]]; then
            warn "No SHA-256 tool found — skipping checksum verification"
        elif [[ "$expected" == "$actual" ]]; then
            success "Checksum OK"
        else
            die "Checksum mismatch!  expected: $expected  actual: $actual"
        fi
    else
        warn "Could not download checksum file — skipping verification"
    fi

    # 7 ── Install
    info "Installing..."
    local install_dir
    install_dir=$(install_binary "$TMP_DIR/$binary_file")

    success "Installed ws ${VERSION} -> ${install_dir}/${BINARY_NAME}"

    # 8 ── PATH reminder when falling back to ~/.local/bin
    if [[ "$install_dir" == "$HOME/.local/bin" ]] && \
       ! printf '%s\n' "${PATH//:/$'\n'}" | grep -qx "$HOME/.local/bin"; then
        warn "~/.local/bin is not in your PATH. Add this to your shell profile:"
        printf "\n  %s\n" "echo 'export PATH=\"\$HOME/.local/bin:\$PATH\"' >> ~/.bashrc"
        printf "  %s\n\n" "source ~/.bashrc"
    fi

    printf "\n  Run ${BOLD}ws --help${RESET} to get started.\n\n"
}

main "$@"
