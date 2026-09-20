param([Parameter(Mandatory=$true)][string]$RequestPath)
$ErrorActionPreference = 'Stop'
$request = Get-Content -LiteralPath $RequestPath -Raw | ConvertFrom-Json
try {
    $root = [IO.Path]::GetFullPath([string]$request.versionRoot)
    if ([IO.Path]::GetFileName($root) -ne '1.21.1-NeoForge_21.1.249') { throw 'Unexpected Minecraft version directory' }
    $mods = Join-Path $root 'mods'
    $source = [IO.Path]::GetFullPath([string]$request.source)
    $name = [IO.Path]::GetFileName($source)
    if ($name -notmatch '^hearthcrew-neoforge-1\.21\.1-\d+\.\d+\.\d+(?:-[a-zA-Z0-9.-]+)?\.jar$') { throw 'Unexpected versioned HearthCrew filename' }
    if ([IO.Path]::GetFileName($source) -ne $name) { throw 'Unexpected development artifact' }
    $target = [IO.Path]::GetFullPath((Join-Path $mods $name))
    if ([IO.Path]::GetDirectoryName($target) -ne $mods) { throw 'Target escaped mods directory' }
    $games = @(Get-CimInstance Win32_Process -Filter "Name='javaw.exe' OR Name='java.exe'" | Where-Object { $_.CommandLine -match 'cpw\.mods\.bootstraplauncher|net\.minecraft\.client\.main\.Main|net\.neoforged\.devlaunch\.Main' })
    if ($games.Count -gt 0) { throw 'Minecraft is running; installation was not performed' }
    if ((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -ne [string]$request.sha256) { throw 'Source hash mismatch' }
    $previous = @(Get-ChildItem -LiteralPath $mods -File | Where-Object { $_.Name -match '^hearthcrew-neoforge-1\.21\.1-\d+\.\d+\.\d+(?:-[a-zA-Z0-9.-]+)?\.jar$' })
    $otherBefore = @{}
    foreach ($file in (Get-ChildItem -LiteralPath $mods -File)) {
        if ($previous.Name -notcontains $file.Name) { $otherBefore[$file.Name] = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash }
    }
    $backup = Join-Path $root ('hearthcrew-mod-backup\' + [string]$request.stamp + '-versioned-untested')
    if (Test-Path -LiteralPath $backup) { throw 'Backup already exists' }
    New-Item -ItemType Directory -Path $backup | Out-Null
    $oldArtifacts = @()
    foreach ($old in $previous) {
        $oldPath = [IO.Path]::GetFullPath($old.FullName)
        if ([IO.Path]::GetDirectoryName($oldPath) -ne $mods) { throw 'Previous artifact escaped mods directory' }
        $oldHash = (Get-FileHash -LiteralPath $oldPath -Algorithm SHA256).Hash
        $saved = Join-Path $backup $old.Name
        Copy-Item -LiteralPath $oldPath -Destination $saved
        if ((Get-FileHash -LiteralPath $saved -Algorithm SHA256).Hash -ne $oldHash) { throw 'Backup hash mismatch' }
        $oldArtifacts += [ordered]@{ name=$old.Name; sha256=$oldHash }
    }
    Copy-Item -LiteralPath $source -Destination $target -Force
    $installedHash = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash
    if ($installedHash -ne [string]$request.sha256) { throw 'Installed artifact hash mismatch' }
    foreach ($old in $previous) {
        if ($old.Name -eq $name) { continue }
        $oldPath = [IO.Path]::GetFullPath($old.FullName)
        if ([IO.Path]::GetDirectoryName($oldPath) -ne $mods) { throw 'Previous artifact escaped mods directory' }
        $saved = Join-Path $backup $old.Name
        if ((Get-FileHash -LiteralPath $oldPath).Hash -ne (Get-FileHash -LiteralPath $saved).Hash) { throw 'Previous artifact changed before removal' }
        Remove-Item -LiteralPath $oldPath
    }
    foreach ($entry in $otherBefore.GetEnumerator()) {
        if ((Get-FileHash -LiteralPath (Join-Path $mods $entry.Key) -Algorithm SHA256).Hash -ne $entry.Value) { throw 'Another mod changed during installation' }
    }
    $result = [ordered]@{status='installed';label='UNTESTED_DEVELOPMENT';sourceCommit=[string]$request.commit;installedJar=$target;sha256=$installedHash;previousArtifacts=$oldArtifacts;backup=$backup;otherModsVerified=$otherBefore.Count;testsRun=[bool]$request.testsRun;installedAt=(Get-Date).ToUniversalTime().ToString('o')}
    $result | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $backup 'installation.json') -Encoding UTF8
    $result | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath ([string]$request.result) -Encoding UTF8
} catch {
    [ordered]@{status='failed';reason=$_.Exception.Message} | ConvertTo-Json | Set-Content -LiteralPath ([string]$request.result) -Encoding UTF8
    exit 1
}
