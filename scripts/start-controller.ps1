[CmdletBinding()]
param(
    [string]$CodexCommand = 'codex',
    [string]$RuntimeRoot,
    [string]$WorkspaceRoot,
    [switch]$Build
)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$localAppData = $env:LOCALAPPDATA
if (-not $localAppData) { $localAppData = Join-Path $HOME 'AppData\Local' }
$baseRoot = Join-Path $localAppData 'HearthCrew'
$runtimeRootProvided = [bool]$RuntimeRoot
if (-not $RuntimeRoot) { $RuntimeRoot = Join-Path $baseRoot 'runtime' }
if (-not $WorkspaceRoot) { $WorkspaceRoot = Join-Path $baseRoot 'workspace' }
$RuntimeRoot = [IO.Path]::GetFullPath($RuntimeRoot)
$WorkspaceRoot = [IO.Path]::GetFullPath($WorkspaceRoot)
$codexRuntime = if ($runtimeRootProvided) { Join-Path (Split-Path -Parent $RuntimeRoot) 'codex-runtime' } else { Join-Path $baseRoot 'codex-runtime' }
$bridgeRoot = Join-Path $projectRoot 'bridge'
$playRuntime = Join-Path $bridgeRoot 'dist\bridge\src\play-runtime.js'

$node = Get-Command node.exe -ErrorAction SilentlyContinue
if (-not $node) { $node = Get-Command node -ErrorAction SilentlyContinue }
if (-not $node) { throw 'Node.js 24 or newer was not found on PATH.' }

if ($Build) {
    $npm = Get-Command npm.cmd -ErrorAction SilentlyContinue
    if (-not $npm) { $npm = Get-Command npm -ErrorAction SilentlyContinue }
    if (-not $npm) { throw 'npm was not found on PATH.' }
    # MCP uses stdout for JSON-RPC. Forward build diagnostics to stderr so a
    # successful build can never corrupt the stdio protocol stream.
    Push-Location -LiteralPath $bridgeRoot
    try {
        $buildOutput = & $npm.Source run build 2>&1
        $buildExit = $LASTEXITCODE
    } finally { Pop-Location }
    foreach ($line in $buildOutput) { [Console]::Error.WriteLine([string]$line) }
    if ($buildExit -ne 0) { throw "bridge build failed with exit code $buildExit" }
}
if (-not (Test-Path -LiteralPath $playRuntime -PathType Leaf)) {
    throw "Compiled controller is missing: $playRuntime. From bridge run: npm ci; npm run build."
}

# Keep this process in the foreground. play-runtime inherits stdin/stdout for
# MCP stdio and handles stdin EOF, SIGINT, and SIGTERM by releasing its lock,
# bridge listener, and App Server child.
$arguments = @($playRuntime, '--state', $RuntimeRoot, '--runtime', $codexRuntime, '--workspace', $WorkspaceRoot, '--codex-command', $CodexCommand)
& $node.Source @arguments
exit $LASTEXITCODE
