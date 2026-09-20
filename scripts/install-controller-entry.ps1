[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$config = Get-Content -LiteralPath (Join-Path $projectRoot '.runtime\pcl-launch.json') -Raw | ConvertFrom-Json
$entryRoot = Split-Path -Parent ([IO.Path]::GetFullPath([string]$config.gamePairingRoot))
if ([IO.Path]::GetFileName($entryRoot) -ne 'HearthCrew') { throw 'Unexpected runtime root' }
$source = Join-Path $projectRoot 'scripts\start-user-play.ps1'
if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw 'Starter missing' }
$entry = Join-Path $entryRoot 'start-installed-controller.ps1'
New-Item -ItemType Directory -Path $entryRoot -Force | Out-Null
if (Test-Path -LiteralPath $entry) { Copy-Item -LiteralPath $entry -Destination ($entry + '.' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.bak') }
$quoted = "'" + $source.Replace("'", "''") + "'"
$content = @'
$ErrorActionPreference = 'Stop'
$gate = [Threading.Mutex]::new($false, 'Local\HearthCrewControllerStart')
$held = $false
try {
    try { $held = $gate.WaitOne(60000) } catch [Threading.AbandonedMutexException] { $held = $true }
    if (-not $held) { throw 'Another controller start is still in progress' }
    & powershell.exe -NoProfile -NonInteractive -WindowStyle Hidden -File __SOURCE__
    $code = $LASTEXITCODE
} finally { if ($held) { $gate.ReleaseMutex() }; $gate.Dispose() }
exit $code
'@
$content.Replace('__SOURCE__', $quoted) | Set-Content -LiteralPath $entry -Encoding UTF8
Write-Output "Installed fixed H-panel controller entry: $entry"
