param()

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$RuntimeDir = Join-Path $Root ".runtime\firebase"
$RelayDir = Join-Path $Root "relay-cloudflare"
$EnvCmdPath = Join-Path $RuntimeDir "argus-firebase.cmd"
$GoogleServicesPath = Join-Path $RuntimeDir "google-services.json"
$PackageName = "com.example.babymonitor"
$WorkerHealthUrl = "https://baby-monitor-secure-relay.mosheschwartzberg.workers.dev/health"
$ServiceAccountId = "argus-fcm-sender"

New-Item -ItemType Directory -Force -Path $RuntimeDir | Out-Null
Set-Location $Root

function Write-Step([string]$Message) {
    Write-Host ""
    Write-Host "[$Message]" -ForegroundColor Cyan
}

function Invoke-FirebaseRaw {
    param(
        [Parameter(Mandatory=$true)][string[]]$Arguments,
        [switch]$AllowFailure
    )

    $output = & npx.cmd --yes firebase-tools@latest @Arguments 2>&1
    $rc = $LASTEXITCODE
    $text = ($output | ForEach-Object { [string]$_ }) -join "`n"

    if ($rc -ne 0 -and -not $AllowFailure) {
        throw "Firebase CLI failed (exit $rc).`n$text"
    }

    return [pscustomobject]@{
        ExitCode = $rc
        Text = $text
    }
}

function Convert-FirebaseJson([string]$Text) {
    if ([string]::IsNullOrWhiteSpace($Text)) { return $null }
    $start = $Text.IndexOf('{')
    $end = $Text.LastIndexOf('}')
    if ($start -lt 0 -or $end -lt $start) { return $null }
    $jsonText = $Text.Substring($start, $end - $start + 1)
    return $jsonText | ConvertFrom-Json
}

function Invoke-FirebaseJson {
    param(
        [Parameter(Mandatory=$true)][string[]]$Arguments,
        [switch]$AllowFailure
    )

    $argsWithJson = @($Arguments) + "--json"
    $raw = Invoke-FirebaseRaw -Arguments $argsWithJson -AllowFailure:$AllowFailure
    if ($raw.ExitCode -ne 0) { return $null }
    return Convert-FirebaseJson $raw.Text
}

function Resolve-Gcloud {
    $cmd = Get-Command gcloud.cmd -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }

    $cmd = Get-Command gcloud -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }

    $candidates = @(
        (Join-Path $env:LOCALAPPDATA "Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"),
        (Join-Path ${env:ProgramFiles(x86)} "Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"),
        (Join-Path $env:ProgramFiles "Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd")
    ) | Where-Object { $_ -and (Test-Path $_) }

    if ($candidates.Count -gt 0) { return $candidates[0] }

    $winget = Get-Command winget.exe -ErrorAction SilentlyContinue
    if ($winget) {
        Write-Host "[SETUP] Google Cloud CLI is missing. Installing it with winget..." -ForegroundColor Yellow
        & winget.exe install --id Google.CloudSDK --exact --accept-package-agreements --accept-source-agreements
        if ($LASTEXITCODE -ne 0) {
            throw "Google Cloud CLI installation failed. Install Google Cloud SDK, then run setup-firebase.cmd again."
        }

        $candidates = @(
            (Join-Path $env:LOCALAPPDATA "Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"),
            (Join-Path ${env:ProgramFiles(x86)} "Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"),
            (Join-Path $env:ProgramFiles "Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd")
        ) | Where-Object { $_ -and (Test-Path $_) }

        if ($candidates.Count -gt 0) { return $candidates[0] }
    }

    throw "Google Cloud CLI (gcloud) is required for the server-side FCM service account. Install Google Cloud SDK and run setup-firebase.cmd again."
}

function Invoke-GcloudChecked {
    param(
        [Parameter(Mandatory=$true)][string]$Gcloud,
        [Parameter(Mandatory=$true)][string[]]$Arguments
    )

    & $Gcloud @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "gcloud failed: gcloud $($Arguments -join ' ')"
    }
}

Write-Host "============================================================"
Write-Host "               ARGUS FIREBASE / FCM SETUP"
Write-Host "============================================================"
Write-Host ""
Write-Host "This sets up the Firebase Android app for $PackageName,"
Write-Host "stores the four build values locally, and configures the"
Write-Host "Cloudflare Worker to send FCM reconnect messages."

Write-Step "PREREQUISITES"
if (-not (Get-Command node.exe -ErrorAction SilentlyContinue) -and -not (Get-Command node -ErrorAction SilentlyContinue)) {
    throw "Node.js is not installed. ARGUS already uses Node for Cloudflare; install Node.js 18+ and run this again."
}
if (-not (Get-Command npx.cmd -ErrorAction SilentlyContinue) -and -not (Get-Command npx -ErrorAction SilentlyContinue)) {
    throw "npx was not found. Install/reinstall Node.js, then run this again."
}
Write-Host "[OK] Node/npm are available. Firebase CLI will run through npx; no global Firebase install is required."

Write-Step "FIREBASE LOGIN"
$loginJson = Invoke-FirebaseJson -Arguments @("login:list") -AllowFailure
$loggedIn = $false
if ($loginJson -and $loginJson.result) {
    $accounts = @($loginJson.result)
    if ($accounts.Count -gt 0) { $loggedIn = $true }
}

if (-not $loggedIn) {
    Write-Host "[LOGIN] A browser window will open for your Google/Firebase account."
    $login = Invoke-FirebaseRaw -Arguments @("login")
    if ($login.ExitCode -ne 0) { throw "Firebase login failed." }
} else {
    Write-Host "[OK] Firebase CLI is already signed in."
}

Write-Step "FIREBASE PROJECT"
$projectsJson = Invoke-FirebaseJson -Arguments @("projects:list")
$projects = @()
if ($projectsJson -and $projectsJson.result) { $projects = @($projectsJson.result) }

if ($projects.Count -gt 0) {
    Write-Host "Firebase projects available to this account:"
    for ($i = 0; $i -lt $projects.Count; $i++) {
        $p = $projects[$i]
        $name = if ($p.displayName) { $p.displayName } else { $p.projectId }
        Write-Host ("  {0}. {1}  [{2}]" -f ($i + 1), $name, $p.projectId)
    }
    Write-Host ""
}

$choice = Read-Host "Enter a project ID/number to use, or press ENTER to create a new ARGUS Firebase project"
$ProjectId = $null

if ([string]::IsNullOrWhiteSpace($choice)) {
    $suffix = Get-Random -Minimum 100000 -Maximum 999999
    $ProjectId = "argus-$((Get-Date).ToString('yyyyMMdd'))-$suffix"
    Write-Host "[CREATE] Creating Firebase project $ProjectId ..."
    Invoke-FirebaseRaw -Arguments @("projects:create", $ProjectId, "--display-name", "ARGUS") | Out-Null
} elseif ($choice -match '^\d+$' -and [int]$choice -ge 1 -and [int]$choice -le $projects.Count) {
    $ProjectId = [string]$projects[[int]$choice - 1].projectId
} else {
    $ProjectId = $choice.Trim()
}

if ([string]::IsNullOrWhiteSpace($ProjectId)) { throw "No Firebase project was selected." }

$knownProject = $projects | Where-Object { $_.projectId -eq $ProjectId } | Select-Object -First 1
if (-not $knownProject -and -not [string]::IsNullOrWhiteSpace($choice)) {
    Write-Host "[INFO] '$ProjectId' was not in Firebase projects:list. Trying to add Firebase to that Google Cloud project..."
    $add = Invoke-FirebaseRaw -Arguments @("projects:addfirebase", $ProjectId) -AllowFailure
    if ($add.ExitCode -ne 0) {
        throw "Could not use Google Cloud project '$ProjectId'. Make sure it exists and your Google account has permission to add Firebase."
    }
}
Write-Host "[OK] Firebase project: $ProjectId"

Write-Step "ANDROID APP"
$appsJson = Invoke-FirebaseJson -Arguments @("apps:list", "ANDROID", "--project", $ProjectId)
$apps = @()
if ($appsJson -and $appsJson.result) { $apps = @($appsJson.result) }
$app = $apps | Where-Object { $_.packageName -eq $PackageName } | Select-Object -First 1

if (-not $app) {
    Write-Host "[CREATE] Registering Android app $PackageName ..."
    $created = Invoke-FirebaseJson -Arguments @("apps:create", "ANDROID", "ARGUS Android", "--package-name", $PackageName, "--project", $ProjectId)
    if (-not $created -or -not $created.result -or -not $created.result.appId) {
        throw "Firebase created/returned an unexpected Android app response."
    }
    $AppId = [string]$created.result.appId
} else {
    $AppId = [string]$app.appId
    Write-Host "[OK] Existing Firebase Android app found."
}

if ([string]::IsNullOrWhiteSpace($AppId)) { throw "Could not determine Firebase Android App ID." }
Write-Host "[OK] Firebase App ID: $AppId"

Write-Step "CLIENT CONFIG"
if (Test-Path $GoogleServicesPath) { Remove-Item $GoogleServicesPath -Force }
$sdk = Invoke-FirebaseRaw -Arguments @("apps:sdkconfig", "ANDROID", $AppId, "--project", $ProjectId, "-o", $GoogleServicesPath)
if ($sdk.ExitCode -ne 0 -or -not (Test-Path $GoogleServicesPath)) {
    throw "Could not download Firebase Android configuration."
}

$config = Get-Content $GoogleServicesPath -Raw | ConvertFrom-Json
$SenderId = [string]$config.project_info.project_number
$ConfigProjectId = [string]$config.project_info.project_id
$client = @($config.client) | Where-Object { $_.client_info.android_client_info.package_name -eq $PackageName } | Select-Object -First 1
if (-not $client) { throw "google-services.json does not contain package $PackageName." }
$ConfigAppId = [string]$client.client_info.mobilesdk_app_id
$ApiKey = [string](@($client.api_key)[0].current_key)

if ([string]::IsNullOrWhiteSpace($ApiKey) -or
    [string]::IsNullOrWhiteSpace($ConfigAppId) -or
    [string]::IsNullOrWhiteSpace($ConfigProjectId) -or
    [string]::IsNullOrWhiteSpace($SenderId)) {
    throw "Firebase config is missing one or more values ARGUS needs."
}

$envLines = @(
    "set `"ARGUS_FIREBASE_API_KEY=$ApiKey`"",
    "set `"ARGUS_FIREBASE_APP_ID=$ConfigAppId`"",
    "set `"ARGUS_FIREBASE_PROJECT_ID=$ConfigProjectId`"",
    "set `"ARGUS_FIREBASE_SENDER_ID=$SenderId`""
)
[IO.File]::WriteAllLines($EnvCmdPath, $envLines, (New-Object Text.UTF8Encoding($false)))

# Client Firebase config values are identifiers/configuration used by the Android app,
# not the private FCM service-account credential. Persist them for future CMD sessions.
[Environment]::SetEnvironmentVariable("ARGUS_FIREBASE_API_KEY", $ApiKey, "User")
[Environment]::SetEnvironmentVariable("ARGUS_FIREBASE_APP_ID", $ConfigAppId, "User")
[Environment]::SetEnvironmentVariable("ARGUS_FIREBASE_PROJECT_ID", $ConfigProjectId, "User")
[Environment]::SetEnvironmentVariable("ARGUS_FIREBASE_SENDER_ID", $SenderId, "User")

$env:ARGUS_FIREBASE_API_KEY = $ApiKey
$env:ARGUS_FIREBASE_APP_ID = $ConfigAppId
$env:ARGUS_FIREBASE_PROJECT_ID = $ConfigProjectId
$env:ARGUS_FIREBASE_SENDER_ID = $SenderId

Write-Host "[OK] Saved ARGUS Firebase build config to .runtime\firebase\argus-firebase.cmd"
Write-Host "[OK] Saved the same four client values to your Windows user environment."

Write-Step "FCM SERVER CHECK"
$fcmAlreadyLive = $false
try {
    $health = Invoke-RestMethod -Uri ("$WorkerHealthUrl?ts=" + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()) -Method Get -TimeoutSec 15
    $fcmAlreadyLive = [bool]$health.fcmWake
} catch {
    Write-Host "[WARN] Could not read the live Worker health endpoint yet." -ForegroundColor Yellow
}

if ($fcmAlreadyLive) {
    Write-Host "[OK] Cloudflare already reports fcmWake=true. Existing server credential will be reused."
} else {
    Write-Host "[SETUP] Cloudflare does not currently report FCM wake as active. Configuring it now..."

    $Gcloud = Resolve-Gcloud
    Write-Host "[OK] gcloud: $Gcloud"

    $activeAccount = (& $Gcloud auth list --filter="status:ACTIVE" --format="value(account)" 2>$null | Select-Object -First 1)
    if ([string]::IsNullOrWhiteSpace([string]$activeAccount)) {
        Write-Host "[LOGIN] A browser window will open for Google Cloud authentication."
        Invoke-GcloudChecked -Gcloud $Gcloud -Arguments @("auth", "login")
    } else {
        Write-Host "[OK] Google Cloud CLI is already signed in as $activeAccount"
    }

    Invoke-GcloudChecked -Gcloud $Gcloud -Arguments @("config", "set", "project", $ProjectId)

    Write-Host "[API] Enabling Firebase Cloud Messaging API..."
    Invoke-GcloudChecked -Gcloud $Gcloud -Arguments @(
        "services", "enable",
        "fcm.googleapis.com",
        "iam.googleapis.com",
        "serviceusage.googleapis.com",
        "--project", $ProjectId,
        "--quiet"
    )

    $ServiceAccountEmail = "$ServiceAccountId@$ProjectId.iam.gserviceaccount.com"
    & $Gcloud iam service-accounts describe $ServiceAccountEmail --project $ProjectId --format="value(email)" *> $null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[CREATE] Creating least-purpose ARGUS FCM sender service account..."
        Invoke-GcloudChecked -Gcloud $Gcloud -Arguments @(
            "iam", "service-accounts", "create", $ServiceAccountId,
            "--project", $ProjectId,
            "--display-name", "ARGUS FCM Sender",
            "--description", "Sends authenticated ARGUS reconnect messages through Firebase Cloud Messaging"
        )
        Start-Sleep -Seconds 3
    } else {
        Write-Host "[OK] Existing ARGUS FCM service account found."
    }

    Write-Host "[IAM] Granting only the Firebase Cloud Messaging API Admin role required to send FCM messages..."
    Invoke-GcloudChecked -Gcloud $Gcloud -Arguments @(
        "projects", "add-iam-policy-binding", $ProjectId,
        "--member", "serviceAccount:$ServiceAccountEmail",
        "--role", "roles/firebasecloudmessaging.admin",
        "--quiet"
    )

    $beforeKeys = @(& $Gcloud iam service-accounts keys list --iam-account $ServiceAccountEmail --managed-by user --project $ProjectId --format="value(name)" 2>$null)
    $KeyFile = Join-Path $RuntimeDir ("fcm-service-account-" + [DateTimeOffset]::UtcNow.ToUnixTimeSeconds() + ".json")
    $newKeyName = $null
    $secretUploaded = $false

    try {
        Write-Host "[KEY] Creating a service-account key temporarily on this PC..."
        Invoke-GcloudChecked -Gcloud $Gcloud -Arguments @(
            "iam", "service-accounts", "keys", "create", $KeyFile,
            "--iam-account", $ServiceAccountEmail,
            "--project", $ProjectId,
            "--key-file-type", "json"
        )

        $afterKeys = @(& $Gcloud iam service-accounts keys list --iam-account $ServiceAccountEmail --managed-by user --project $ProjectId --format="value(name)" 2>$null)
        $newKeyName = $afterKeys | Where-Object { $_ -and ($_ -notin $beforeKeys) } | Select-Object -First 1

        if (-not (Test-Path $KeyFile)) { throw "gcloud did not create the expected service-account JSON file." }

        Push-Location $RelayDir
        try {
            if (-not (Test-Path "node_modules\.bin\wrangler.cmd")) {
                Write-Host "[SETUP] Installing Cloudflare Worker dependencies..."
                & npm.cmd install --no-audit --no-fund
                if ($LASTEXITCODE -ne 0) { throw "npm install failed in relay-cloudflare." }
            }

            & npx.cmd wrangler whoami *> $null
            if ($LASTEXITCODE -ne 0) {
                Write-Host "[LOGIN] A browser window will open for Cloudflare authentication."
                & npx.cmd wrangler login
                if ($LASTEXITCODE -ne 0) { throw "Cloudflare Wrangler login failed." }
            } else {
                Write-Host "[OK] Saved Cloudflare login found."
            }

            Write-Host "[SECRET] Uploading the service account JSON directly to Cloudflare as an encrypted Worker secret..."
            $secretJson = [IO.File]::ReadAllText($KeyFile)
            $secretJson | & npx.cmd wrangler secret put FCM_SERVICE_ACCOUNT_JSON
            if ($LASTEXITCODE -ne 0) { throw "Could not store FCM_SERVICE_ACCOUNT_JSON in Cloudflare." }
            $secretUploaded = $true

            Write-Host "[DEPLOY] Deploying the Worker with FCM wake support..."
            & npx.cmd wrangler deploy
            if ($LASTEXITCODE -ne 0) { throw "Cloudflare Worker deployment failed." }
        } finally {
            Pop-Location
        }
    } finally {
        if (Test-Path $KeyFile) {
            Remove-Item $KeyFile -Force
            Write-Host "[SECURITY] Deleted the local service-account JSON. The private credential now exists only as the Cloudflare secret."
        }

        if (-not $secretUploaded -and $newKeyName) {
            $newKeyId = ($newKeyName -split '/')[-1]
            if ($newKeyId) {
                Write-Host "[SECURITY] Setup failed before Cloudflare accepted the secret; removing the unused Google service-account key..." -ForegroundColor Yellow
                & $Gcloud iam service-accounts keys delete $newKeyId --iam-account $ServiceAccountEmail --project $ProjectId --quiet *> $null
            }
        }
    }

    Write-Step "VERIFY FCM"
    $verified = $false
    for ($attempt = 1; $attempt -le 6; $attempt++) {
        try {
            $health = Invoke-RestMethod -Uri ("$WorkerHealthUrl?ts=" + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()) -Method Get -TimeoutSec 15
            if ([bool]$health.fcmWake) {
                $verified = $true
                break
            }
        } catch { }
        Start-Sleep -Seconds 3
    }

    if (-not $verified) {
        throw "Firebase client config was created, but the live Cloudflare /health endpoint still does not report fcmWake=true."
    }
    Write-Host "[OK] Live Cloudflare Worker reports fcmWake=true."
}

Write-Step "DONE"
Write-Host "Firebase Android package : $PackageName"
Write-Host "Firebase project         : $ConfigProjectId"
Write-Host "Firebase app ID          : $ConfigAppId"
Write-Host "Firebase sender/project# : $SenderId"
Write-Host "Cloudflare FCM wake      : configured"
Write-Host ""
Write-Host "No service-account private key was committed to GitHub."
Write-Host "The local Android build values are under .runtime/ (already gitignored)."
Write-Host ""
Write-Host "Next command:"
Write-Host '  publish-update.cmd "Restart recovery and remote wake update"' -ForegroundColor Green
