[CmdletBinding()]
param(
    [string]$CodexCommand = 'codex',
    [Alias('RuntimeRoot')]
    [string]$CodexHome,
    [string]$WorkspaceRoot
)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$localAppData = $env:LOCALAPPDATA
if (-not $localAppData) { $localAppData = Join-Path $HOME 'AppData\Local' }
$baseRoot = Join-Path $localAppData 'HearthCrew'
if (-not $CodexHome) { $CodexHome = Join-Path $baseRoot 'codex-runtime' }
if (-not $WorkspaceRoot) { $WorkspaceRoot = Join-Path $baseRoot 'workspace' }
$CodexHome = [IO.Path]::GetFullPath($CodexHome)
$WorkspaceRoot = [IO.Path]::GetFullPath($WorkspaceRoot)
$cli = Join-Path $projectRoot 'bridge\dist\bridge\src\cli.js'

$node = Get-Command node.exe -ErrorAction SilentlyContinue
if (-not $node) { $node = Get-Command node -ErrorAction SilentlyContinue }
if (-not $node) { throw 'Node.js 24 or newer was not found on PATH.' }
if (-not (Test-Path -LiteralPath $cli -PathType Leaf)) {
    throw "Compiled bridge CLI is missing: $cli. From bridge run: npm ci; npm run build."
}

[Console]::Error.WriteLine('HearthCrew: starting the official Codex device-auth login in the foreground.')
[Console]::Error.WriteLine('HearthCrew: complete the displayed login flow; this script does not start Minecraft or the game controller.')

# Keep official device-auth prompts in the foreground. The CLI owns the
# dedicated CODEX_HOME and does not receive the desktop global environment.
$arguments = @($cli, 'login', '--runtime', $CodexHome, '--workspace', $WorkspaceRoot, '--codex-command', $CodexCommand)
& $node.Source @arguments
exit $LASTEXITCODE
