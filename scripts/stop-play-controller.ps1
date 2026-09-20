[CmdletBinding()]
param(
    [string]$RuntimeRoot,
    [int]$TimeoutSeconds = 30
)

$ErrorActionPreference = 'Stop'
$localAppData = if ($env:LOCALAPPDATA) { $env:LOCALAPPDATA } else { Join-Path $HOME 'AppData\Local' }
$baseRoot = Join-Path $localAppData 'HearthCrew'
if (-not $RuntimeRoot) { $RuntimeRoot = Join-Path $baseRoot 'runtime' }
$RuntimeRoot = [IO.Path]::GetFullPath($RuntimeRoot)
if ($TimeoutSeconds -lt 1 -or $TimeoutSeconds -gt 300) { throw 'TimeoutSeconds must be between 1 and 300.' }
$metadataPath = Join-Path $RuntimeRoot 'launcher-metadata.json'
$stopPath = Join-Path $RuntimeRoot 'stop.request'
if (-not (Test-Path -LiteralPath $metadataPath -PathType Leaf)) {
    Write-Output "No standalone launcher metadata found: $RuntimeRoot"
    exit 0
}
$metadata = Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json
$pidValue = 0
try { $pidValue = [Convert]::ToInt32($metadata.pid) } catch { $pidValue = 0 }
if ($pidValue -lt 1 -or [string]$metadata.mode -ne 'standalone' -or [IO.Path]::GetFullPath([string]$metadata.state) -ne $RuntimeRoot) {
    throw 'Launcher metadata is invalid or belongs to a different runtime directory; no process was touched.'
}

# A process which already exited is a successful idempotent stop.  We do not
# touch stale locks or remove any files in this case.
$processInfo = Get-CimInstance Win32_Process -Filter "ProcessId = $pidValue" -ErrorAction SilentlyContinue
if (-not $processInfo) {
    Write-Output "HearthCrew standalone controller already stopped: PID $pidValue"
    exit 0
}

# Verify the complete owned invocation before writing a stop request.  A live
# or unrelated PID is never terminated and never receives a request.
$commandLine = [string]$processInfo.CommandLine
$normalize = { param([string]$Value) ($Value -replace '["'']', '' -replace '/', '\').ToLowerInvariant() }
$normalizedCommand = & $normalize $commandLine
$expectedNode = & $normalize ([string]$metadata.node)
$expectedPlayRuntime = & $normalize ([string]$metadata.playRuntime)
$expectedState = & $normalize ([string]$metadata.state)
$expectedCodexRuntime = & $normalize ([string]$metadata.codexRuntime)
$expectedWorkspace = & $normalize ([string]$metadata.workspace)
$expectedCodex = & $normalize ([string]$metadata.codexCommand)
$expectedFragments = @(
    $expectedNode, $expectedPlayRuntime, '--standalone', "--state $expectedState",
    "--runtime $expectedCodexRuntime", "--workspace $expectedWorkspace", "--codex-command $expectedCodex"
)
$matchesExpected = $expectedFragments -notcontains $null -and $expectedFragments.Count -gt 0 -and (($expectedFragments | Where-Object { [string]::IsNullOrWhiteSpace($_) -or $normalizedCommand.IndexOf($_, [StringComparison]::OrdinalIgnoreCase) -lt 0 }).Count -eq 0)
if (-not $matchesExpected -or [string]::IsNullOrWhiteSpace([string]$metadata.playRuntime)) {
    throw "The recorded PID $pidValue is not the matching HearthCrew standalone controller; no process was touched."
}
$request = [ordered]@{ schema = 1; requestedAt = (Get-Date).ToUniversalTime().ToString('o'); requestedBy = 'stop-play-controller' }
$request | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath $stopPath -Encoding UTF8

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
do {
    Start-Sleep -Milliseconds 250
    $running = Get-Process -Id $pidValue -ErrorAction SilentlyContinue
} while ($running -and (Get-Date) -lt $deadline)
if ($running) {
    throw "Stop request was written, but PID $pidValue did not exit within $TimeoutSeconds seconds. No force termination was attempted."
}
Write-Output "HearthCrew standalone controller stopped gracefully: PID $pidValue"
