[CmdletBinding()]
param(
    [int]$TimeoutSeconds = 30
)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$configPath = Join-Path $projectRoot '.runtime\pcl-launch.json'
$stopScript = Join-Path $projectRoot 'scripts\stop-play-controller.ps1'

if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
    throw "Missing .runtime\pcl-launch.json. The project launcher configuration has not been prepared; no process was touched."
}
if (-not (Test-Path -LiteralPath $stopScript -PathType Leaf)) {
    throw "Missing launcher script: $stopScript"
}
try {
    $config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
} catch {
    throw "Cannot read launcher configuration: $configPath"
}
if ([Convert]::ToInt32($config.schema) -ne 1) {
    throw "Unsupported launcher configuration schema in $configPath"
}
$runtimeRoot = [string]$config.runtimeRoot
if ([String]::IsNullOrWhiteSpace($runtimeRoot)) {
    throw "Launcher configuration is missing 'runtimeRoot'; no process was touched."
}

& powershell.exe -NoProfile -NonInteractive -File $stopScript -RuntimeRoot $runtimeRoot -TimeoutSeconds $TimeoutSeconds
exit $LASTEXITCODE
