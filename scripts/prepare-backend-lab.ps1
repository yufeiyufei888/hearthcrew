param(
    [string]$UpstreamRepository = (Join-Path $PSScriptRoot '..\..\minecraft-numen'),
    [string]$JavaHome = (Join-Path $env:APPDATA '.minecraft\runtime\java-runtime-delta'),
    [switch]$Build
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$lock = Get-Content -LiteralPath (Join-Path $projectRoot 'backend-lab\upstream-lock.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$sourceRoot = [IO.Path]::GetFullPath($UpstreamRepository)
$labRoot = Join-Path $projectRoot '.runtime\backend-v03'
$archive = Join-Path $labRoot 'numen-947f0064.zip'
$checkout = Join-Path $labRoot 'numen-947f0064'
New-Item -ItemType Directory -Force -Path $labRoot | Out-Null
& git -C $sourceRoot cat-file -e ($lock.commit + '^{commit}')
if ($LASTEXITCODE -ne 0) { throw 'Pinned commit unavailable; do not substitute a branch or local working tree' }
if (!(Test-Path -LiteralPath $archive)) {
    & git -C $sourceRoot archive '--format=zip' "--output=$archive" $lock.commit
    if ($LASTEXITCODE -ne 0) { throw 'Pinned source archive failed' }
}
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $lock.sourceArchiveSha256) { throw 'Pinned source archive hash mismatch' }
if (!(Test-Path -LiteralPath $checkout)) { Expand-Archive -LiteralPath $archive -DestinationPath $checkout }
# Verify all committed files, not just the original zip, before reusing a checkout.
$zip = [IO.Compression.ZipFile]::OpenRead($archive)
try {
    foreach ($entry in $zip.Entries) {
        if ($entry.FullName.EndsWith('/')) { continue }
        $file = [IO.Path]::GetFullPath((Join-Path $checkout $entry.FullName))
        if (!$file.StartsWith($checkout + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Archive path escaped checkout' }
        if (!(Test-Path -LiteralPath $file)) { throw "Pinned source missing: $($entry.FullName)" }
        $stream = $entry.Open()
        try { $expected = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream)) }
        finally { $stream.Dispose() }
        if ((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $expected) { throw "Pinned source modified: $($entry.FullName)" }
    }
} finally { $zip.Dispose() }
if ($Build) {
    $gradle = Join-Path $projectRoot '..\.tool-cache\gradle-9.2.0\bin\gradle.bat'
    $priorJavaHome = $env:JAVA_HOME
    $priorGradleHome = $env:GRADLE_USER_HOME
    try {
        $env:JAVA_HOME = $JavaHome
        $env:GRADLE_USER_HOME = [IO.Path]::GetFullPath((Join-Path $projectRoot '..\.tool-cache\gradle-user-home'))
        Push-Location $checkout
        try {
            & $gradle ':core:neoforge:jar' ':api:neoforge:jar' '--console=plain' '--no-daemon' "-Pneoforge_version=$($lock.neoforgeBuildOverride)"
            if ($LASTEXITCODE -ne 0) { throw 'Pinned upstream build failed; no installation allowed' }
        } finally { Pop-Location }
    } finally {
        $env:JAVA_HOME = $priorJavaHome
        $env:GRADLE_USER_HOME = $priorGradleHome
    }
}
Write-Output "Prepared pinned dependency at $checkout. Research only; no PCL installation."
