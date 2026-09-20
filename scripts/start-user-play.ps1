[CmdletBinding()]
param(
    [switch]$Build
)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$configPath = Join-Path $projectRoot '.runtime\pcl-launch.json'
$startScript = Join-Path $projectRoot 'scripts\start-play-controller.ps1'

if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
    throw "Missing .runtime\pcl-launch.json. The project launcher configuration has not been prepared; no controller was started."
}
if (-not (Test-Path -LiteralPath $startScript -PathType Leaf)) {
    throw "Missing launcher script: $startScript"
}
try {
    $config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
} catch {
    throw "Cannot read launcher configuration: $configPath"
}
if ([Convert]::ToInt32($config.schema) -ne 1) {
    throw "Unsupported launcher configuration schema in $configPath"
}

$required = @('runtimeRoot', 'codexRuntime', 'workspaceRoot', 'codexCommand', 'gamePairingRoot')
foreach ($name in $required) {
    $value = [string]$config.$name
    if ([String]::IsNullOrWhiteSpace($value)) {
        throw "Launcher configuration is missing '$name'; no path or credential was guessed."
    }
}

$arguments = @(
    '-NoProfile', '-NonInteractive', '-File', $startScript,
    '-CodexCommand', [string]$config.codexCommand,
    '-RuntimeRoot', [string]$config.runtimeRoot,
    '-WorkspaceRoot', [string]$config.workspaceRoot,
    '-CodexRuntime', [string]$config.codexRuntime,
    '-GamePairingRoot', [string]$config.gamePairingRoot
)
if($config.executionBackend){$arguments+=@('-ExecutionBackend',[string]$config.executionBackend)}
if ($Build) { $arguments += '-Build' }
& powershell.exe @arguments
exit $LASTEXITCODE
