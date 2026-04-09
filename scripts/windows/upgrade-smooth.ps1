param(
    [string]$BaseUrl,
    [int]$TimeoutMs = 30000,
    [switch]$SkipPostRestartCheck,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Resolve-Path (Join-Path $scriptDir '..\..')
$repoRootPath = $repoRoot.Path
$distSecretsFile = Join-Path $repoRootPath 'dist\mindos-windows-server\mindos-secrets.properties'
$localOverrideFile = if ($env:MINDOS_LOCAL_SECRETS_FILE) {
    if ([System.IO.Path]::IsPathRooted($env:MINDOS_LOCAL_SECRETS_FILE)) {
        $env:MINDOS_LOCAL_SECRETS_FILE
    } else {
        Join-Path $repoRootPath $env:MINDOS_LOCAL_SECRETS_FILE
    }
} else {
    Join-Path $repoRootPath 'mindos-secrets.local.properties'
}
$memoryDir = Join-Path $repoRootPath 'data\memory-sync'
$backupDir = Join-Path $repoRootPath 'data\memory-backups'
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$backupFile = Join-Path $backupDir ("memory-backup-$timestamp.zip")
$tempBackupFile = Join-Path $backupDir ("memory-backup-$timestamp.tmp.zip")
$providedBaseUrl = $BaseUrl

function Normalize-BaseUrl {
    param([Parameter(Mandatory = $true)][string]$Url)
    return $Url.TrimEnd('/')
}

function Get-MindOsAdminHeaders {
    $headers = @{}
    $token = if ($null -ne $env:MINDOS_SECURITY_RISKY_OPS_ADMIN_TOKEN) { $env:MINDOS_SECURITY_RISKY_OPS_ADMIN_TOKEN.Trim() } else { '' }
    $headerName = if ($null -ne $env:MINDOS_SECURITY_RISKY_OPS_ADMIN_TOKEN_HEADER) { $env:MINDOS_SECURITY_RISKY_OPS_ADMIN_TOKEN_HEADER.Trim() } else { '' }
    if ([string]::IsNullOrWhiteSpace($headerName) -or (Is-PlaceholderValue $headerName)) {
        $headerName = 'X-MindOS-Admin-Token'
    }
    if (-not [string]::IsNullOrWhiteSpace($token) -and -not (Is-PlaceholderValue $token)) {
        $headers[$headerName] = $token
    }
    return $headers
}

function Import-MindOsProperties {
    param(
        [Parameter(Mandatory = $true)][string]$Path
    )
    if (-not (Test-Path $Path)) {
        return
    }
    Write-Host "[INFO] Loading properties: $Path"
    Get-Content -Path $Path -Encoding UTF8 | ForEach-Object {
        $line = $_.Trim().TrimStart([char]0xFEFF)
        if ([string]::IsNullOrWhiteSpace($line)) { return }
        if ($line.StartsWith('#') -or $line.StartsWith(';')) { return }
        $idx = $line.IndexOf('=')
        if ($idx -lt 1) {
            Write-Host "[WARN] Ignoring malformed property line in $Path: $line"
            return
        }
        $key = $line.Substring(0, $idx).Trim()
        $value = $line.Substring($idx + 1).Trim()
        if ($key -match '^[A-Za-z_][A-Za-z0-9_.-]*$') {
            Set-Item -Path "Env:$key" -Value $value
        } else {
            Write-Host "[WARN] Ignoring invalid property key in $Path: $key"
        }
    }
}

function Is-PlaceholderValue {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return $false }
    $upper = $Value.Trim().ToUpperInvariant()
    return $upper.StartsWith('REPLACE_WITH_') -or $upper.StartsWith('YOUR_')
}

function Get-JsonField {
    param(
        [Parameter(Mandatory = $true)][string]$Json,
        [Parameter(Mandatory = $true)][string]$Field
    )
    try {
        $parsed = $Json | ConvertFrom-Json -ErrorAction Stop
        return $parsed.$Field
    } catch {
        return $null
    }
}

function Invoke-MindOsGetJson {
    param([Parameter(Mandatory = $true)][string]$Url)
    $headers = Get-MindOsAdminHeaders
    if ($headers.Count -gt 0) {
        return Invoke-RestMethod -Method Get -Uri $Url -Headers $headers -TimeoutSec ([Math]::Ceiling($TimeoutMs / 1000))
    }
    return Invoke-RestMethod -Method Get -Uri $Url -TimeoutSec ([Math]::Ceiling($TimeoutMs / 1000))
}

function Invoke-MindOsPostJson {
    param([Parameter(Mandatory = $true)][string]$Url)
    $headers = Get-MindOsAdminHeaders
    if ($headers.Count -gt 0) {
        return Invoke-RestMethod -Method Post -Uri $Url -Headers $headers -ContentType 'application/json' -TimeoutSec ([Math]::Ceiling($TimeoutMs / 1000))
    }
    return Invoke-RestMethod -Method Post -Uri $Url -ContentType 'application/json' -TimeoutSec ([Math]::Ceiling($TimeoutMs / 1000))
}

function Wait-ForReadiness {
    param(
        [Parameter(Mandatory = $true)][bool]$ExpectedReady,
        [Parameter(Mandatory = $true)][string]$Url,
        [Parameter(Mandatory = $true)][int]$TimeoutMs
    )
    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    $lastReady = $null
    $lastInflight = $null
    $lastActiveDispatches = $null
    $lastAcceptingRequests = $null
    while ($stopwatch.ElapsedMilliseconds -lt $TimeoutMs) {
        try {
            $body = Invoke-MindOsGetJson -Url $Url
            $lastReady = [bool]$body.ready
            $lastInflight = $body.inflight
            $lastActiveDispatches = $body.activeDispatches
            $lastAcceptingRequests = $body.acceptingRequests
            Write-Host "[INFO] readiness=$lastReady inflight=$lastInflight activeDispatches=$lastActiveDispatches acceptingRequests=$lastAcceptingRequests"
            if ($lastReady -eq $ExpectedReady) {
                $stopwatch.Stop()
                return $true
            }
        } catch {
            Write-Host "[WARN] Failed to read readiness: $($_.Exception.Message)"
        }
        Start-Sleep -Seconds 1
    }
    $stopwatch.Stop()
    Write-Host "[WARN] Timed out after $($stopwatch.ElapsedMilliseconds)ms waiting for readiness=$ExpectedReady. Last observed ready=$lastReady inflight=$lastInflight activeDispatches=$lastActiveDispatches acceptingRequests=$lastAcceptingRequests"
    return $false
}

if (-not [string]::IsNullOrWhiteSpace($BaseUrl)) {
    $env:MINDOS_UPGRADE_API_BASE_URL = $BaseUrl
}
if (Test-Path $distSecretsFile) {
    Import-MindOsProperties -Path $distSecretsFile
} else {
    Write-Host "[WARN] Dist secrets file not found: $distSecretsFile"
}
if (Test-Path $localOverrideFile) {
    Import-MindOsProperties -Path $localOverrideFile
}

if (-not [string]::IsNullOrWhiteSpace($providedBaseUrl)) {
    $BaseUrl = $providedBaseUrl
} elseif (-not [string]::IsNullOrWhiteSpace($env:MINDOS_UPGRADE_API_BASE_URL)) {
    $BaseUrl = $env:MINDOS_UPGRADE_API_BASE_URL
} elseif (-not [string]::IsNullOrWhiteSpace($env:MINDOS_SERVER_BASE_URL)) {
    $BaseUrl = $env:MINDOS_SERVER_BASE_URL
} elseif (-not [string]::IsNullOrWhiteSpace($env:MINDOS_SERVER)) {
    $BaseUrl = $env:MINDOS_SERVER
} else {
    $BaseUrl = 'http://localhost:8080'
}
$BaseUrl = Normalize-BaseUrl -Url $BaseUrl
if ([string]::IsNullOrWhiteSpace($env:MINDOS_SECURITY_RISKY_OPS_ADMIN_TOKEN_HEADER)) {
    $env:MINDOS_SECURITY_RISKY_OPS_ADMIN_TOKEN_HEADER = 'X-MindOS-Admin-Token'
}
if ([string]::IsNullOrWhiteSpace($env:MINDOS_SECURITY_RISKY_OPS_ADMIN_TOKEN)) {
    $env:MINDOS_SECURITY_RISKY_OPS_ADMIN_TOKEN = ''
}

Write-Host "[INFO] ROOT_DIR=$repoRootPath"
Write-Host "[INFO] MEMORY_DIR=$memoryDir"
Write-Host "[INFO] BACKUP_FILE=$backupFile"
Write-Host "[INFO] API_BASE_URL=$BaseUrl"
Write-Host "[INFO] TIMEOUT_MS=$TimeoutMs"

if (-not (Test-Path $memoryDir)) {
    Write-Host "[WARN] Memory directory does not exist: $memoryDir"
    Write-Host "[INFO] Nothing to backup. You may still proceed to start the new version."
    exit 0
}

if ($DryRun) {
    Write-Host "[INFO] DRY-RUN: would create backup archive at $backupFile"
    Write-Host "[INFO] DRY-RUN: would POST $BaseUrl/admin/drain?timeoutMs=$TimeoutMs"
    Write-Host "[INFO] DRY-RUN: would poll $BaseUrl/health/readiness until ready=false, then create a backup archive, prompt for restart, and verify ready=true"
    exit 0
}

$drainUrl = "$BaseUrl/admin/drain?timeoutMs=$TimeoutMs"
Write-Host "[INFO] Sending drain request to $drainUrl"
$drainResponse = Invoke-MindOsPostJson -Url $drainUrl
Write-Host ("[INFO] Drain response: " + ($drainResponse | ConvertTo-Json -Compress))

Write-Host '[INFO] Waiting for readiness to turn false...'
if (-not (Wait-ForReadiness -ExpectedReady:$false -Url "$BaseUrl/health/readiness" -TimeoutMs $TimeoutMs)) {
    Write-Host "[WARN] Readiness did not flip to false within timeout. Continue manually if you are about to restart."
}

New-Item -ItemType Directory -Force -Path $backupDir | Out-Null
Write-Host "[INFO] Creating backup archive after drain..."
try {
    if (Test-Path $tempBackupFile) {
        Remove-Item $tempBackupFile -Force
    }
    Compress-Archive -Path $memoryDir -DestinationPath $tempBackupFile -Force
    if (Test-Path $backupFile) {
        Remove-Item $backupFile -Force
    }
    Move-Item -Path $tempBackupFile -Destination $backupFile -Force
    Write-Host "[INFO] Backup created: $backupFile"
    Get-Item $backupFile | Select-Object FullName, Length, LastWriteTime | Format-Table -AutoSize | Out-String | Write-Host
} catch {
    if (Test-Path $tempBackupFile) {
        Remove-Item $tempBackupFile -Force -ErrorAction SilentlyContinue
    }
    throw "Failed to create backup archive after drain: $($_.Exception.Message)"
}

if (-not $SkipPostRestartCheck) {
    if (-not [Environment]::UserInteractive -or [Console]::IsInputRedirected) {
        Write-Host '[WARN] Non-interactive session detected; skipping post-restart readiness prompt.'
        $SkipPostRestartCheck = $true
    }
}

if (-not $SkipPostRestartCheck) {
    Write-Host '[INFO] Restart or replace the running process with the new version now.'
    [void](Read-Host 'Press Enter after the new process is up to verify readiness')
    if (-not (Wait-ForReadiness -ExpectedReady:$true -Url "$BaseUrl/health/readiness" -TimeoutMs $TimeoutMs)) {
        Write-Host "[ERROR] Service did not become ready within timeout. Check logs and $backupFile before retrying."
        exit 1
    }
    Write-Host '[INFO] Post-restart readiness verified.'
} else {
    Write-Host '[INFO] Skipping post-restart readiness verification per flag.'
}

Write-Host "[INFO] Smooth upgrade prep complete. Backup: $backupFile"
Write-Host 'Next step: restart or replace the running process with the new version if you have not already done so.'
exit 0

