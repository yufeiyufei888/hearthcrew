[CmdletBinding()]
param(
    [string]$RunId,
    [string]$CodexCommand = 'codex',
    [string]$CodexRuntime,
    [string]$Workspace,
    [int]$TimeoutSeconds = 240,
    [switch]$AllowRealModel,
    [switch]$PlanOnly
)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ([string]::IsNullOrWhiteSpace($RunId)) { $RunId = 'contract-{0}-{1}' -f (Get-Date -Format 'yyyyMMdd-HHmmss'), ([Guid]::NewGuid().ToString('N').Substring(0, 8)) }
if ($RunId -notmatch '^[A-Za-z0-9-]{1,48}$') { throw 'RunId must match [A-Za-z0-9-]{1,48}.' }
if ($TimeoutSeconds -lt 30) { throw 'TimeoutSeconds must be at least 30.' }
if (-not $PlanOnly -and -not $AllowRealModel) { throw 'Real model execution is opt-in. Re-run with -AllowRealModel.' }

$runtimeRoot = Join-Path $projectRoot '.runtime'
$runRoot = Join-Path $runtimeRoot ('codex-contract-{0}' -f $RunId)
$resultRoot = Join-Path $runRoot 'result'
$reportPath = Join-Path $resultRoot 'report.json'
$stdoutPath = Join-Path $runRoot 'probe.stdout.log'
$stderrPath = Join-Path $runRoot 'probe.stderr.log'
$probePath = Join-Path $projectRoot 'bridge\dist\bridge\src\codex-capability-probe.js'
$localAppData = if ($env:LOCALAPPDATA) { $env:LOCALAPPDATA } else { Join-Path $HOME 'AppData\Local' }
if (-not $CodexRuntime) { $CodexRuntime = Join-Path $localAppData 'HearthCrew\codex-runtime' }
if (-not $Workspace) { $Workspace = Join-Path $localAppData 'HearthCrew\workspace' }

function Quote-Argument([string]$Value) {
    if ($Value -notmatch '[\s"]') { return $Value }
    return '"' + $Value.Replace('"', '\"') + '"'
}

if ($PlanOnly) {
    Write-Output "runId=$RunId"
    Write-Output "runRoot=$runRoot"
    Write-Output "resultRoot=$resultRoot"
    Write-Output "report=$reportPath"
    Write-Output 'realModelOptInRequired=true'
    Write-Output ('probe: node.exe {0} --run-id {1} --run-root {2} --codex-command {3} --codex-runtime {4} --workspace {5} --timeout-seconds {6}' -f (Quote-Argument $probePath), $RunId, (Quote-Argument $runRoot), (Quote-Argument $CodexCommand), (Quote-Argument $CodexRuntime), (Quote-Argument $Workspace), $TimeoutSeconds)
    exit 0
}

$node = (Get-Command node.exe -ErrorAction Stop).Source
if (-not (Test-Path -LiteralPath $probePath -PathType Leaf)) { throw "Compiled capability probe is missing: $probePath. Run npm.cmd run build first." }
if (Test-Path -LiteralPath $runRoot -PathType Container) { throw "Runtime output already exists; choose a new RunId: $runRoot" }
New-Item -ItemType Directory -Path $runtimeRoot -Force | Out-Null
New-Item -ItemType Directory -Path $runRoot -Force | Out-Null

$probe = $null
$failure = $null
try {
    $arguments = @(
        (Quote-Argument $probePath), '--run-id', $RunId, '--run-root', (Quote-Argument $runRoot),
        '--codex-command', (Quote-Argument $CodexCommand), '--codex-runtime', (Quote-Argument $CodexRuntime),
        '--workspace', (Quote-Argument $Workspace), '--timeout-seconds', $TimeoutSeconds
    )
    $probe = Start-Process -FilePath $node -ArgumentList $arguments -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath -PassThru
    if (-not $probe.WaitForExit($TimeoutSeconds * 1000)) {
        $failure = "probe exceeded timeout ${TimeoutSeconds}s"
        throw $failure
    }
    $exitCode = $probe.ExitCode
    $status = $null
    if (Test-Path -LiteralPath $reportPath -PathType Leaf) {
        try { $status = (Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json).status } catch { $status = 'INVALID_REPORT' }
    }
    if ($exitCode -ne 0 -or $status -ne 'PASS') { throw "contract failed: probeExit=$exitCode reportStatus=$status" }
    Write-Output "PASS runId=$RunId report=$reportPath"
} catch {
    if (-not $failure) { $failure = $_.Exception.Message }
    [Console]::Error.WriteLine("FAIL runId=$RunId reason=$failure evidence=$runRoot")
    throw
} finally {
    if ($null -ne $probe) {
        try { $probe.Refresh() } catch { }
        if (-not $probe.HasExited) { & taskkill.exe /PID $probe.Id /T /F *> $null }
    }
}
