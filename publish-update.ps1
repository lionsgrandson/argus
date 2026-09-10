param(
    [string]$Notes = "Bug fixes and improvements.",
    [switch]$Required
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$RelayDir = Join-Path $Root "relay-cloudflare"
$BuildFile = Join-Path $Root "app\build.gradle"
$ApkPath = Join-Path $Root "OUTPUT\ARGUS-debug.apk"
$Bucket = "moshebackup"
$Prefix = "argus-updates"
$ManifestPath = Join-Path $env:TEMP "argus-latest-update.json"
$SigningDir = Join-Path $Root ".signing"
$CurrentDebugKey = Join-Path $env:USERPROFILE ".android\debug.keystore"
$SigningBackup = Join-Path $SigningDir "argus-debug.keystore"

function Write-Step([string]$Message) {
    Write-Host ""
    Write-Host "[$Message]" -ForegroundColor Cyan
}

function Invoke-Checked {
    param(
        [Parameter(Mandatory=$true)][string]$FilePath,
        [Parameter(ValueFromRemainingArguments=$true)][string[]]$Arguments
    )

    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$FilePath failed with exit code $LASTEXITCODE"
    }
}

Set-Location $Root
Write-Host "============================================================"
Write-Host "              ARGUS REMOTE UPDATE PUBLISHER"
Write-Host "============================================================"

Write-Step "SIGNING"
if (-not (Test-Path $SigningDir)) {
    New-Item -ItemType Directory -Path $SigningDir | Out-Null
}

if ((Test-Path $SigningBackup) -and -not (Test-Path $CurrentDebugKey)) {
    $currentDir = Split-Path -Parent $CurrentDebugKey
    if (-not (Test-Path $currentDir)) {
        New-Item -ItemType Directory -Path $currentDir | Out-Null
    }
    Copy-Item $SigningBackup $CurrentDebugKey
    Write-Host "[OK] Restored the preserved signing key."
}

if (-not (Test-Path $CurrentDebugKey)) {
    throw "The Android debug signing key is missing. Do not publish with a new key because installed phones would reject the update."
}

if (-not (Test-Path $SigningBackup)) {
    Copy-Item $CurrentDebugKey $SigningBackup
    Write-Host "[OK] Preserved the current signing key in the local .signing folder."
} else {
    $currentHash = (Get-FileHash $CurrentDebugKey -Algorithm SHA256).Hash
    $backupHash = (Get-FileHash $SigningBackup -Algorithm SHA256).Hash
    if ($currentHash -ne $backupHash) {
        throw "Signing key mismatch. Publishing was stopped so existing installations are not broken."
    }
    Write-Host "[OK] Signing key matches the preserved updater key."
}

Write-Step "BUILD"
$env:ARGUS_PUBLISHING = "1"
try {
    & (Join-Path $Root "buildapp.cmd")
    if ($LASTEXITCODE -ne 0) {
        throw "Android build failed with exit code $LASTEXITCODE"
    }
} finally {
    Remove-Item Env:ARGUS_PUBLISHING -ErrorAction SilentlyContinue
}

if (-not (Test-Path $ApkPath)) {
    throw "Build completed but APK was not found at $ApkPath"
}

$gradle = Get-Content $BuildFile -Raw
$versionCodeMatch = [regex]::Match($gradle, 'versionCode\s+(\d+)')
$versionNameMatch = [regex]::Match($gradle, "versionName\s+['\"]([^'\"]+)['\"]")
if (-not $versionCodeMatch.Success -or -not $versionNameMatch.Success) {
    throw "Could not read versionCode/versionName from app/build.gradle"
}

$VersionCode = [int]$versionCodeMatch.Groups[1].Value
$VersionName = $versionNameMatch.Groups[1].Value
$ApkHash = (Get-FileHash $ApkPath -Algorithm SHA256).Hash.ToLowerInvariant()
$ApkSize = (Get-Item $ApkPath).Length
$ObjectKey = "$Prefix/app-v$VersionCode.apk"

$manifest = [ordered]@{
    enabled = $true
    versionCode = $VersionCode
    versionName = $VersionName
    objectKey = $ObjectKey
    sha256 = $ApkHash
    sizeBytes = $ApkSize
    required = [bool]$Required
    notes = $Notes
}

$json = $manifest | ConvertTo-Json -Compress
[System.IO.File]::WriteAllText(
    $ManifestPath,
    $json,
    (New-Object System.Text.UTF8Encoding($false))
)

Write-Host "[OK] Version: $VersionName ($VersionCode)"
Write-Host "[OK] SHA-256: $ApkHash"
Write-Host "[OK] Size: $ApkSize bytes"

Write-Step "CLOUDFLARE"
if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    throw "Node.js is not installed or is not in PATH."
}
if (-not (Get-Command npm -ErrorAction SilentlyContinue)) {
    throw "npm is not installed or is not in PATH."
}

Push-Location $RelayDir
try {
    if (-not (Test-Path "node_modules\.bin\wrangler.cmd")) {
        Write-Host "[SETUP] Installing Cloudflare deploy tools..."
        Invoke-Checked "npm.cmd" "install"
    }

    & "npx.cmd" "wrangler" "whoami" *> $null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[FIRST TIME ONLY] Cloudflare sign-in is required."
        Invoke-Checked "npx.cmd" "wrangler" "login"
    } else {
        Write-Host "[OK] Saved Cloudflare login found."
    }

    Write-Host "[DEPLOY] Ensuring the update endpoints and R2 binding are live..."
    Invoke-Checked "npx.cmd" "wrangler" "deploy"

    Write-Host "[UPLOAD] Uploading signed APK to R2..."
    Invoke-Checked "npx.cmd" "wrangler" "r2" "object" "put" "$Bucket/$ObjectKey" "--file" $ApkPath "--content-type" "application/vnd.android.package-archive" "--cache-control" "no-store" "--remote"

    Write-Host "[UPLOAD] Publishing latest update manifest..."
    Invoke-Checked "npx.cmd" "wrangler" "r2" "object" "put" "$Bucket/$Prefix/latest.json" "--file" $ManifestPath "--content-type" "application/json" "--cache-control" "no-store" "--remote"
} finally {
    Pop-Location
}

Write-Step "VERIFY"
$UpdateUrl = "https://baby-monitor-secure-relay.mosheschwartzberg.workers.dev/app-update"
$published = Invoke-RestMethod -Uri $UpdateUrl -Method Get -TimeoutSec 30
if ([int]$published.versionCode -ne $VersionCode) {
    throw "Cloudflare verification returned versionCode $($published.versionCode), expected $VersionCode"
}
if ($published.sha256 -ne $ApkHash) {
    throw "Cloudflare verification returned a different APK hash"
}

Write-Host ""
Write-Host "============================================================" -ForegroundColor Green
Write-Host "REMOTE UPDATE PUBLISHED" -ForegroundColor Green
Write-Host "============================================================" -ForegroundColor Green
Write-Host "Version $VersionName ($VersionCode) is now available to installed apps."
Write-Host "Users will receive the in-app update prompt/notification automatically."
Write-Host ""
