[CmdletBinding()]
param(
    [string]$CodexCommand = 'codex',
    [string]$RuntimeRoot,
    [string]$WorkspaceRoot,
    [string]$CodexRuntime,
    [string]$GamePairingRoot,
    [ValidateSet('legacy','numen')][string]$ExecutionBackend='legacy',
    [switch]$Build
)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$localAppData = if ($env:LOCALAPPDATA) { $env:LOCALAPPDATA } else { Join-Path $HOME 'AppData\Local' }
$baseRoot = Join-Path $localAppData 'HearthCrew'
if (-not $RuntimeRoot) { $RuntimeRoot = Join-Path $baseRoot 'runtime' }
if (-not $WorkspaceRoot) { $WorkspaceRoot = Join-Path $baseRoot 'workspace' }
if (-not $CodexRuntime) { $CodexRuntime = Join-Path $baseRoot 'codex-runtime' }
if (-not $GamePairingRoot) { $GamePairingRoot = $RuntimeRoot }
$RuntimeRoot = [IO.Path]::GetFullPath($RuntimeRoot)
$WorkspaceRoot = [IO.Path]::GetFullPath($WorkspaceRoot)
$CodexRuntime = [IO.Path]::GetFullPath($CodexRuntime)
$GamePairingRoot = [IO.Path]::GetFullPath($GamePairingRoot)
New-Item -ItemType Directory -Path $RuntimeRoot -Force | Out-Null
$logStamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$buildLogPath = Join-Path $RuntimeRoot ("build.{0}.log" -f $logStamp)
$bridgeRoot = Join-Path $projectRoot 'bridge'
$playRuntime = Join-Path $bridgeRoot 'dist\bridge\src\play-runtime.js'
$publishScript = Join-Path $projectRoot 'scripts\publish-game-pairing.ps1'
$metadataPath = Join-Path $RuntimeRoot 'launcher-metadata.json'

function Resolve-Executable([string]$Name, [string]$Label) {
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if (-not $command) { throw "$Label was not found: $Name" }
    return [IO.Path]::GetFullPath($command.Source)
}
function Quote-ProcessArgument([string]$Value) {
    if ($Value -notmatch '[\s"]') { return $Value }
    return '"' + $Value.Replace('"', '\"') + '"'
}
function Wait-AndPublishPairing([int]$ExpectedPid) {
    $runtimeMetadataPath = Join-Path $RuntimeRoot 'runtime-metadata.json'
    $deadline = (Get-Date).AddSeconds(60)
    $ready = $false
    while ((Get-Date) -lt $deadline) {
        try {
            $runtimeMetadata = Get-Content -LiteralPath $runtimeMetadataPath -Raw | ConvertFrom-Json
            $port = 0
            try { $port = [Convert]::ToInt32($runtimeMetadata.bridgePort) } catch { $port = 0 }
            if ([Convert]::ToInt32($runtimeMetadata.pid) -eq $ExpectedPid -and [string]$runtimeMetadata.mode -eq 'standalone' -and [string]$runtimeMetadata.status -eq 'ready' -and $port -ge 1 -and $port -le 65535) {
                $actualBackend=if($runtimeMetadata.executionBackend){[string]$runtimeMetadata.executionBackend}else{'legacy'}
                if($actualBackend -ne $ExecutionBackend){break}
                $ready = $true; break
            }
        } catch { }
        Start-Sleep -Milliseconds 250
    }
    if (-not $ready) { throw "standalone controller PID $ExpectedPid is not ready for backend $ExecutionBackend; stop the old controller before restarting" }
    if (-not (Test-Path -LiteralPath $publishScript -PathType Leaf)) { throw "Missing pairing publisher: $publishScript" }
    $publishOutput = & powershell.exe -NoProfile -NonInteractive -File $publishScript -RuntimeRoot $RuntimeRoot -DestinationRoot $GamePairingRoot -WaitSeconds 60 2>&1
    $publishExit = $LASTEXITCODE
    foreach ($line in $publishOutput) { [Console]::WriteLine([string]$line) }
    if ($publishExit -ne 0) { throw "game pairing publication failed with exit code $publishExit" }
}

$node = Resolve-Executable 'node.exe' 'Node.js 24'
$codex = Resolve-Executable $CodexCommand 'Codex CLI'
if ($Build) {
    $npm = Resolve-Executable 'npm.cmd' 'npm'
    Push-Location -LiteralPath $bridgeRoot
    try {
        & $npm run build 2>&1 | Tee-Object -FilePath $buildLogPath
        if ($LASTEXITCODE -ne 0) { throw "bridge build failed with exit code $LASTEXITCODE" }
    } finally { Pop-Location }
}
if (-not (Test-Path -LiteralPath $playRuntime -PathType Leaf)) {
    throw "Compiled controller is missing: $playRuntime. Run bridge npm build first or pass -Build."
}
$lockPath = Join-Path $RuntimeRoot 'controller.lock'
if (Test-Path -LiteralPath $lockPath -PathType Leaf) {
    try { $lock = Get-Content -LiteralPath $lockPath -Raw | ConvertFrom-Json } catch { $lock = $null }
    $lockPid = 0
    try { $lockPid = [Convert]::ToInt32($lock.pid) } catch { $lockPid = 0 }
    if ($lockPid -gt 0 -and (Get-Process -Id $lockPid -ErrorAction SilentlyContinue)) {
        Wait-AndPublishPairing $lockPid
        Write-Output "HearthCrew standalone controller already running: PID $lockPid"
        Write-Output "State: $RuntimeRoot"
        exit 0
    }
    # Stale or malformed locks are deliberately left for play-runtime.ts to
    # validate and reclaim using its exact PID identity check.
}
$stdoutLogPath = Join-Path $RuntimeRoot ("controller.stdout.{0}.log" -f $logStamp)
$stderrLogPath = Join-Path $RuntimeRoot ("controller.stderr.{0}.log" -f $logStamp)
$arguments = @((Quote-ProcessArgument $playRuntime), '--standalone', '--execution-backend', $ExecutionBackend, '--state', (Quote-ProcessArgument $RuntimeRoot), '--runtime', (Quote-ProcessArgument $CodexRuntime), '--workspace', (Quote-ProcessArgument $WorkspaceRoot), '--codex-command', (Quote-ProcessArgument $codex))
$process = Start-Process -FilePath $node -ArgumentList $arguments -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput $stdoutLogPath -RedirectStandardError $stderrLogPath -PassThru

# This metadata is launcher-owned and contains no pairing token, credentials or
# journal content.  The runtime writes the authoritative port/status metadata.
$metadata = [ordered]@{
    schema = 1
    mode = 'standalone'
    pid = $process.Id
    projectRoot = $projectRoot
    state = $RuntimeRoot
    playRuntime = $playRuntime
    codexRuntime = $CodexRuntime
    workspace = $WorkspaceRoot
    gamePairingRoot = $GamePairingRoot
    node = $node
    codexCommand = $codex
    stdoutLog = $stdoutLogPath
    stderrLog = $stderrLogPath
    buildLog = $buildLogPath
    startedAt = (Get-Date).ToUniversalTime().ToString('o')
    status = 'starting'
}
$metadata | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $RuntimeRoot 'launcher-metadata.json') -Encoding UTF8
Wait-AndPublishPairing $process.Id
Write-Output "HearthCrew standalone controller started: PID $($process.Id)"
Write-Output "State: $RuntimeRoot"
Write-Output 'Use scripts\stop-play-controller.ps1 to stop it safely.'
