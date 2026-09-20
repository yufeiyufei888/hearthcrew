[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$ReleaseManifest)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$release=Get-Content -LiteralPath $ReleaseManifest -Encoding UTF8 -Raw | ConvertFrom-Json
if($release.version -ne '0.3.2' -or $release.executionProtocol -ne 6 -or -not $release.gatesPassed){throw 'Release gates are not complete'}
$root=[IO.Path]::GetFullPath([string]$release.versionRoot)
if([IO.Path]::GetFileName($root) -ne '1.21.1-NeoForge_21.1.249'){throw 'Unexpected instance'}
$mods=Join-Path $root 'mods'
$managedPath=Join-Path $root 'hearthcrew-managed-installation.json'
$previousManifest=$null
if(Test-Path -LiteralPath $managedPath){$previousManifest=Get-Content -LiteralPath $managedPath -Encoding UTF8 -Raw | ConvertFrom-Json}
$games=@(Get-CimInstance Win32_Process -Filter "Name='javaw.exe' OR Name='java.exe'" | Where-Object {$_.CommandLine -match 'cpw\.mods\.bootstraplauncher|net\.minecraft\.client\.main\.Main|net\.neoforged\.devlaunch\.Main'})
if($games.Count){throw 'Minecraft is running; no files were changed'}
function Hash([string]$Path){return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()}
function SafeName([string]$Name){if([IO.Path]::GetFileName($Name) -ne $Name -or $Name -notmatch '^[a-zA-Z0-9_.-]+\.jar$'){throw 'Invalid managed filename'}}
$desired=@($release.artifacts)
if($desired.Count -ne 2 -or @($desired | Where-Object {$_.kind -eq 'mod'}).Count -ne 1 -or @($desired | Where-Object {$_.kind -eq 'dependency'}).Count -ne 1){throw 'Expected one HearthCrew JAR and one registered runtime library'}
foreach($entry in $desired){
    SafeName $entry.name
    if((Hash ([string]$entry.source)) -ne $entry.sha256){throw 'Release source hash mismatch'}
    if($entry.kind -eq 'mod' -and $entry.name -ne 'hearthcrew-neoforge-1.21.1-0.3.2.jar'){throw 'Unexpected HearthCrew artifact'}
    if($entry.kind -eq 'dependency' -and $entry.name -ne 'hearthcrew-execution-library-core-947f0064-mediafree.jar'){throw 'Unregistered dependency'}
}
# Existing dependencies are replaceable only with a matching prior installation record.
$old=@(Get-ChildItem -LiteralPath $mods -File | Where-Object {$_.Name -match '^hearthcrew-neoforge-1\.21\.1-\d+\.\d+\.\d+(?:-[a-zA-Z0-9.-]+)?\.jar$'})
foreach($entry in @($previousManifest.artifacts)){
    if(-not $entry){continue};SafeName $entry.name
    $path=Join-Path $mods $entry.name
    if(Test-Path -LiteralPath $path){if((Hash $path) -ne $entry.sha256){throw 'A previously managed dependency changed; stopping for review'};if($old.FullName -notcontains $path){$old+=Get-Item -LiteralPath $path}}
}
foreach($file in Get-ChildItem -LiteralPath $mods -File -Filter '*.jar'){
    if($old.FullName -contains $file.FullName){continue}
    $zip=[IO.Compression.ZipFile]::OpenRead($file.FullName)
    try{
        $descriptor=$zip.GetEntry('META-INF/neoforge.mods.toml')
        if($descriptor){$reader=[IO.StreamReader]::new($descriptor.Open());try{$text=$reader.ReadToEnd()}finally{$reader.Dispose()}
            if($text -match 'modId\s*=\s*["'']numen(?:_api)?["'']'){throw ('Existing Numen dependency is not managed by HearthCrew: '+$file.Name)}}
    }finally{$zip.Dispose()}
    if($desired.name -contains $file.Name){throw 'Destination already exists and is not managed'}
}
$other=@{};foreach($file in Get-ChildItem -LiteralPath $mods -File){if($old.FullName -notcontains $file.FullName){$other[$file.Name]=Hash $file.FullName}}
$backup=Join-Path $root ('hearthcrew-mod-backup\'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'-0.3.2')
New-Item -ItemType Directory -Path $backup | Out-Null
$oldRecords=@();$written=@()
try{
    foreach($file in $old){
        $absolute=[IO.Path]::GetFullPath($file.FullName);if([IO.Path]::GetDirectoryName($absolute) -ne $mods){throw 'Old artifact escaped the mods directory'}
        $saved=Join-Path $backup $file.Name;Copy-Item -LiteralPath $absolute -Destination $saved
        $hash=Hash $absolute;if((Hash $saved) -ne $hash){throw 'Backup verification failed'}
        $oldRecords+=@{name=$file.Name;sha256=$hash}
    }
    if(Test-Path -LiteralPath $managedPath){Copy-Item -LiteralPath $managedPath -Destination (Join-Path $backup 'previous-managed-installation.json')}
    foreach($entry in $desired){$target=Join-Path $mods $entry.name;Copy-Item -LiteralPath $entry.source -Destination $target -Force;$written+=$entry.name;if((Hash $target) -ne $entry.sha256){throw 'Installed hash mismatch'}}
    foreach($file in $old){if($desired.name -notcontains $file.Name){Remove-Item -LiteralPath $file.FullName}}
    foreach($name in $other.Keys){if((Hash (Join-Path $mods $name)) -ne $other[$name]){throw 'Unrelated mod changed'}}
    $record=[ordered]@{version='0.3.2';label='DEVELOPMENT_NOT_LONG_PLAY_ACCEPTED';executionProtocol=6;backend='numen';commit=$release.commit;artifacts=@($desired | ForEach-Object {@{name=$_.name;sha256=$_.sha256;kind=$_.kind}});previous=$oldRecords;backup=$backup;otherModsVerified=$other.Count;installedAt=(Get-Date).ToUniversalTime().ToString('o')}
    $record | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $managedPath -Encoding UTF8
    Copy-Item -LiteralPath $managedPath -Destination (Join-Path $backup 'installation.json')
    $record | ConvertTo-Json -Depth 8
}catch{
    # Roll back only the exact artifacts written by this attempt; never recurse or touch saves.
    foreach($name in $written){$path=Join-Path $mods $name;if(Test-Path -LiteralPath $path){Remove-Item -LiteralPath $path}}
    foreach($entry in $oldRecords){Copy-Item -LiteralPath (Join-Path $backup $entry.name) -Destination (Join-Path $mods $entry.name) -Force}
    $savedManifest=Join-Path $backup 'previous-managed-installation.json'
    if(Test-Path -LiteralPath $savedManifest){Copy-Item -LiteralPath $savedManifest -Destination $managedPath -Force}
    elseif(-not $previousManifest -and (Test-Path -LiteralPath $managedPath)){Remove-Item -LiteralPath $managedPath}
    throw
}
