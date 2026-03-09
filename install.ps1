# ─────────────────────────────────────────────────────────────────────────────
# ws-manager installer — Windows (PowerShell)
#
# One-liner install (run in PowerShell as Administrator or regular user):
#   irm https://raw.githubusercontent.com/firefinchdev/ws-manager/main/install.ps1 | iex
#
# Install a specific version:
#   $env:WS_VERSION = "v1.2.3"
#   irm https://...install.ps1 | iex
#
# Override install directory:
#   $env:WS_INSTALL_DIR = "C:\Tools"
#   irm https://...install.ps1 | iex
# ─────────────────────────────────────────────────────────────────────────────

[CmdletBinding()]
param(
    [string]$Version  = $env:WS_VERSION,
    [string]$InstallDir = $env:WS_INSTALL_DIR
)

$ErrorActionPreference = 'Stop'

# ── Configuration ─────────────────────────────────────────────────────────────
$REPO        = "firefinchdev/ws-manager"
$BINARY_NAME = "ws.exe"
$API_URL     = "https://api.github.com/repos/$REPO/releases/latest"
$DL_BASE     = "https://github.com/$REPO/releases/download"

# ── Helpers ───────────────────────────────────────────────────────────────────
function Write-Info    { param($msg) Write-Host "  -> " -NoNewline -ForegroundColor Cyan;  Write-Host $msg }
function Write-Success { param($msg) Write-Host "  OK " -NoNewline -ForegroundColor Green; Write-Host $msg }
function Write-Warn    { param($msg) Write-Host "  !! " -NoNewline -ForegroundColor Yellow;Write-Host $msg -ForegroundColor Yellow }
function Write-Fail    { param($msg) Write-Host "  !! $msg" -ForegroundColor Red; exit 1 }

# ── Architecture check ────────────────────────────────────────────────────────
$arch = [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture
if ($arch -ne [System.Runtime.InteropServices.Architecture]::X64) {
    Write-Fail "Only Windows x64 is supported. Detected: $arch"
}
$binaryFile = "ws-windows-x64.exe"

# ── Fetch latest version ──────────────────────────────────────────────────────
Write-Host ""
Write-Host "  ws-manager installer" -ForegroundColor White
Write-Host ""

if (-not $Version) {
    Write-Info "Fetching latest release version..."
    try {
        $release = Invoke-RestMethod -Uri $API_URL -UseBasicParsing
        $Version = $release.tag_name
    } catch {
        Write-Fail "Could not fetch latest release: $_"
    }
}

if (-not $Version) { Write-Fail "Could not determine version." }
Write-Info "Version:  $Version"

# ── Resolve install directory ─────────────────────────────────────────────────
if (-not $InstallDir) {
    $InstallDir = Join-Path $env:LOCALAPPDATA "Programs\ws-manager"
}

# ── Temp workspace ────────────────────────────────────────────────────────────
$tmpDir = Join-Path ([System.IO.Path]::GetTempPath()) "ws-manager-install-$(Get-Random)"
New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null

try {
    $binaryDest  = Join-Path $tmpDir $binaryFile
    $shaDest     = Join-Path $tmpDir "$binaryFile.sha256"

    # ── Download binary ───────────────────────────────────────────────────────
    Write-Info "Downloading $binaryFile..."
    $binaryUrl = "$DL_BASE/$Version/$binaryFile"
    Invoke-WebRequest -Uri $binaryUrl -OutFile $binaryDest -UseBasicParsing

    # ── Verify checksum (best-effort) ─────────────────────────────────────────
    Write-Info "Verifying checksum..."
    $shaUrl = "$DL_BASE/$Version/$binaryFile.sha256"
    try {
        Invoke-WebRequest -Uri $shaUrl -OutFile $shaDest -UseBasicParsing
        $expected = ((Get-Content $shaDest) -split '\s+')[0].ToLower()
        $actual   = (Get-FileHash $binaryDest -Algorithm SHA256).Hash.ToLower()

        if ($expected -ne $actual) {
            Write-Fail "Checksum mismatch!`n  expected: $expected`n  actual:   $actual"
        }
        Write-Success "Checksum OK"
    } catch {
        Write-Warn "Could not download checksum file — skipping verification"
    }

    # ── Install ───────────────────────────────────────────────────────────────
    Write-Info "Installing to $InstallDir..."
    New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
    $finalPath = Join-Path $InstallDir $BINARY_NAME
    Copy-Item $binaryDest -Destination $finalPath -Force

    # ── Add to User PATH if not already present ───────────────────────────────
    $userPath = [System.Environment]::GetEnvironmentVariable("PATH", "User") ?? ""
    if ($userPath -notlike "*$InstallDir*") {
        $newPath = ($userPath.TrimEnd(';') + ";$InstallDir").TrimStart(';')
        [System.Environment]::SetEnvironmentVariable("PATH", $newPath, "User")
        # Also update the current session PATH so ws is immediately usable
        $env:PATH = "$env:PATH;$InstallDir"
        Write-Warn "Added $InstallDir to your User PATH (restart terminal if ws is not found)"
    }

    Write-Success "Installed ws $Version -> $finalPath"

} finally {
    # Clean up temp directory
    Remove-Item -Recurse -Force $tmpDir -ErrorAction SilentlyContinue
}

Write-Host ""
Write-Host "  Run " -NoNewline
Write-Host "ws --help" -ForegroundColor Green -NoNewline
Write-Host " to get started."
Write-Host ""
