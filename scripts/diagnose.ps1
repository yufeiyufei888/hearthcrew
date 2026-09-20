[CmdletBinding()]
param(
    [string]$CodexCommand = 'codex',
    [string]$RuntimeRoot,
    [string]$WorkspaceRoot,
    [string]$JavaHome
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

function PathEvidence([string]$Path) {
    $item = Get-Item -LiteralPath $Path -ErrorAction SilentlyContinue
    if (-not $item) { return [ordered]@{ status = 'missing'; path = $Path } }
    return [ordered]@{ status = 'present'; path = $Path; kind = if ($item.PSIsContainer) { 'directory' } else { 'file' }; bytes = if ($item.PSIsContainer) { $null } else { $item.Length } }
}

function CommandPath([string]$Name) {
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if (-not $command) { return $null }
    return $command.Source
}

function VersionEvidence([string]$Name, [string[]]$Arguments, [string]$Pattern, [int]$ExpectedMajor = 0) {
    $path = CommandPath $Name
    if (-not $path) { return [ordered]@{ status = 'missing'; command = $Name; version = $null } }
    $raw = @()
    try { $raw = & $path @Arguments 2>$null } catch { $raw = @() }
    $text = ($raw -join "`n")
    $match = [regex]::Match($text, $Pattern, [Text.RegularExpressions.RegexOptions]::IgnoreCase)
    $version = if ($match.Success) { $match.Groups[1].Value } else { $null }
    $status = 'unavailable'
    if ($version) {
        $major = 0
        [void][int]::TryParse(([regex]::Match($version, '^\d+')).Value, [ref]$major)
        $status = if ($ExpectedMajor -gt 0 -and $major -ne $ExpectedMajor) { 'unsupported' } else { 'present' }
    }
    return [ordered]@{ status = $status; command = $path; version = $version; expectedMajor = if ($ExpectedMajor -gt 0) { $ExpectedMajor } else { $null } }
}

function JavaEvidence([string]$RequestedHome) {
    $path = $null
    $source = 'PATH'
    if ($RequestedHome) {
        $candidate = Join-Path ([IO.Path]::GetFullPath($RequestedHome)) 'bin\java.exe'
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { $path = $candidate; $source = 'JavaHome' }
        else { return [ordered]@{ status = 'missing'; source = 'JavaHome'; javaHome = $RequestedHome; version = $null } }
    } else {
        $defaultHome = Join-Path $env:APPDATA '.minecraft\runtime\java-runtime-delta'
        $defaultJava = Join-Path $defaultHome 'bin\java.exe'
        if (Test-Path -LiteralPath $defaultJava -PathType Leaf) { $path = $defaultJava; $source = 'MinecraftJavaRuntime' }
        else { $path = CommandPath 'java.exe'; if (-not $path) { $path = CommandPath 'java' } }
    }
    if (-not $path) { return [ordered]@{ status = 'missing'; source = $source; javaHome = $null; version = $null } }
    $raw = @()
    try { $raw = & $path -version 2>&1 } catch { $raw = @() }
    $text = ($raw -join "`n")
    $match = [regex]::Match($text, 'version\s+"([^"]+)"', [Text.RegularExpressions.RegexOptions]::IgnoreCase)
    $version = if ($match.Success) { $match.Groups[1].Value } else { $null }
    $status = 'unavailable'
    if ($version) {
        $major = 0
        [void][int]::TryParse(([regex]::Match($version, '^\d+')).Value, [ref]$major)
        $status = if ($major -ne 21) { 'unsupported' } else { 'present' }
    }
    return [ordered]@{ status = $status; source = $source; command = $path; version = $version; expectedMajor = 21 }
}

function LockEvidence([string]$Path) {
    $item = Get-Item -LiteralPath $Path -ErrorAction SilentlyContinue
    if (-not $item) { return [ordered]@{ status = 'missing'; path = $Path; pid = $null; alive = $false } }
    try { $lock = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json } catch { return [ordered]@{ status = 'invalid'; path = $Path; pid = $null; alive = $null } }
    if (-not ($lock.pid -is [int] -or $lock.pid -is [long]) -or $lock.pid -lt 1) { return [ordered]@{ status = 'invalid'; path = $Path; pid = $null; alive = $null } }
    $alive = $false
    $processName = $null
    try { $process = Get-Process -Id ([int]$lock.pid) -ErrorAction Stop; $alive = $true; $processName = $process.ProcessName } catch { }
    return [ordered]@{ status = 'present'; path = $Path; pid = [int]$lock.pid; alive = $alive; process = $processName }
}

function JarEvidence([string]$Root) {
    if (-not (Test-Path -LiteralPath $Root -PathType Container)) { return [ordered]@{ status = 'missing'; directory = $Root; jars = @() } }
    $jars = @()
    foreach ($file in @(Get-ChildItem -LiteralPath $Root -Filter '*hearthcrew*.jar' -File -ErrorAction SilentlyContinue)) {
        $hash = $null
        try { $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash } catch { }
        $jars += [ordered]@{ name = $file.Name; path = $file.FullName; bytes = $file.Length; sha256 = $hash }
    }
    return [ordered]@{ status = 'present'; directory = $Root; jars = $jars }
}

$runtimeConfig = Join-Path $projectRoot 'bridge\src\runtime-config.ts'
$fixedVersion = $null
if (Test-Path -LiteralPath $runtimeConfig -PathType Leaf) {
    $source = Get-Content -LiteralPath $runtimeConfig -Raw
    $match = [regex]::Match($source, 'PINNED_CODEX_CLI_VERSION\s*=\s*"([^"]+)"')
    if ($match.Success) { $fixedVersion = $match.Groups[1].Value }
}

$buildFiles = @(
    (Join-Path $projectRoot 'build.gradle'), (Join-Path $projectRoot 'settings.gradle'),
    (Join-Path $projectRoot 'gradlew.bat'), (Join-Path $projectRoot 'kernel\build.gradle'),
    (Join-Path $projectRoot 'mod\build.gradle'), (Join-Path $projectRoot 'bridge\package.json'),
    (Join-Path $projectRoot 'bridge\dist\bridge\src\play-runtime.js')
) | ForEach-Object { PathEvidence $_ }

$modRoots = @(
    (Join-Path $projectRoot 'mod\build\libs'),
    (Join-Path $env:APPDATA '.minecraft\mods'),
    (Join-Path $localAppData 'HearthCrew\instance\mods')
) | Select-Object -Unique
$report = [ordered]@{
    generatedAt = (Get-Date).ToUniversalTime().ToString('o')
    project = [ordered]@{ root = $projectRoot; buildFiles = $buildFiles }
    directories = @((PathEvidence $RuntimeRoot), (PathEvidence $codexRuntime), (PathEvidence $WorkspaceRoot))
    node = VersionEvidence 'node.exe' @('--version') '^v([^\s]+)' 24
    java = JavaEvidence $JavaHome
    codexCli = [ordered]@{
        configuredCommand = $CodexCommand
        fixedVersion = if ($fixedVersion) { $fixedVersion } else { 'missing' }
        detected = (VersionEvidence $CodexCommand @('--version') 'codex-cli\s+([^\s]+)')
    }
    controllerLock = LockEvidence (Join-Path $RuntimeRoot 'controller.lock')
    pairingFile = PathEvidence (Join-Path $RuntimeRoot 'pairing.json')
    installedModJars = @($modRoots | ForEach-Object { JarEvidence $_ })
    note = 'Read-only evidence. Pairing contents, tokens, credentials, saves, and private journals are not read or printed. A live PID is reported only; this script never terminates processes.'
}
Write-Output ($report | ConvertTo-Json -Depth 8)
