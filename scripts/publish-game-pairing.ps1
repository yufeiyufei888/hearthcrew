[CmdletBinding()]
param(
    [string]$RuntimeRoot,
    [string]$DestinationRoot,
    [int]$WaitSeconds = 60,
    [switch]$Helper,
    [string]$RequestPath
)

$ErrorActionPreference = 'Stop'

function Add-NativePathApi {
    if ('HearthCrew.PublishPathApi' -as [type]) { return }
    Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Text;
using Microsoft.Win32.SafeHandles;
using System.Runtime.InteropServices;

namespace HearthCrew {
    public static class PublishPathApi {
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern uint GetFinalPathNameByHandle(SafeFileHandle handle, StringBuilder path, uint length, uint flags);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern int GetCurrentPackageFullName(ref uint length, StringBuilder name);

        public static string FinalPath(string path) {
            using (var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete)) {
                var buffer = new StringBuilder(1024);
                uint length = GetFinalPathNameByHandle(stream.SafeFileHandle, buffer, (uint)buffer.Capacity, 0);
                if (length == 0) throw new IOException("GetFinalPathNameByHandle failed: " + Marshal.GetLastWin32Error());
                if (length >= buffer.Capacity) {
                    buffer = new StringBuilder((int)length + 1);
                    length = GetFinalPathNameByHandle(stream.SafeFileHandle, buffer, (uint)buffer.Capacity, 0);
                }
                if (length == 0) throw new IOException("GetFinalPathNameByHandle failed: " + Marshal.GetLastWin32Error());
                var value = buffer.ToString();
                return value.StartsWith(@"\\?\", StringComparison.Ordinal) ? value.Substring(4) : value;
            }
        }

        public static bool IsPackagedProcess() {
            uint length = 0;
            int result = GetCurrentPackageFullName(ref length, null);
            return result != 15700;
        }

        public static void ReplaceWithoutBackup(string source, string destination) {
            // PowerShell 5 converts $null string arguments to empty strings.
            File.Replace(source, destination, null);
        }
    }
}
'@
}

function Read-Pairing([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $null }
    try {
        if ((Get-Item -LiteralPath $Path).Length -gt 4096) { return $null }
        $parsed = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
        $port = [Convert]::ToInt32($parsed.port)
        $token = [string]$parsed.token
        if ($port -lt 1 -or $port -gt 65535 -or $token -cnotmatch '^[0-9a-f]{64}$') { return $null }
        return [ordered]@{ path = $Path; port = $port; token = $token }
    } catch { return $null }
}

function Atomic-Publish([string]$Source, [string]$Destination) {
    $sourcePairing = Read-Pairing $Source
    if ($null -eq $sourcePairing) { throw 'source pairing is missing or invalid' }
    $destinationDirectory = Split-Path -Parent $Destination
    New-Item -ItemType Directory -Path $destinationDirectory -Force | Out-Null
    $sourcePhysical = [HearthCrew.PublishPathApi]::FinalPath($Source)
    $destinationPhysical = $Destination
    if (Test-Path -LiteralPath $Destination -PathType Leaf) {
        try { $destinationPhysical = [HearthCrew.PublishPathApi]::FinalPath($Destination) } catch { $destinationPhysical = $Destination }
    }
    if ([StringComparer]::OrdinalIgnoreCase.Equals($sourcePhysical, $destinationPhysical)) {
        return [ordered]@{ status = 'already_physical'; sourcePhysical = $sourcePhysical; destinationPhysical = $destinationPhysical; port = $sourcePairing.port }
    }
    $temp = Join-Path $destinationDirectory ('.pairing.json.publish-{0}.tmp' -f ([Guid]::NewGuid().ToString('N')))
    try {
        Copy-Item -LiteralPath $Source -Destination $temp -Force
        if ($null -eq (Read-Pairing $temp)) { throw 'temporary pairing validation failed' }
        if (Test-Path -LiteralPath $Destination -PathType Leaf) {
            [HearthCrew.PublishPathApi]::ReplaceWithoutBackup($temp, $Destination)
        } else {
            [IO.File]::Move($temp, $Destination)
        }
        $destinationPhysical = [HearthCrew.PublishPathApi]::FinalPath($Destination)
        if ((Get-FileHash -LiteralPath $Source).Hash -ne (Get-FileHash -LiteralPath $Destination).Hash) { throw 'published pairing verification failed' }
        return [ordered]@{ status = 'published'; sourcePhysical = $sourcePhysical; destinationPhysical = $destinationPhysical; port = $sourcePairing.port }
    } finally {
        if (Test-Path -LiteralPath $temp -PathType Leaf) { Remove-Item -LiteralPath $temp -Force -ErrorAction SilentlyContinue }
    }
}

function Invoke-HelperRequest([string]$Path) {
    if ([String]::IsNullOrWhiteSpace($Path) -or -not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw 'publish helper request is missing' }
    $request = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
    $source = [IO.Path]::GetFullPath([string]$request.source)
    $destination = [IO.Path]::GetFullPath([string]$request.destination)
    $resultPath = [IO.Path]::GetFullPath([string]$request.result)
    $result = [ordered]@{ status = 'FAIL'; writtenAt = (Get-Date).ToUniversalTime().ToString('o') }
    try {
        if ([HearthCrew.PublishPathApi]::IsPackagedProcess()) { throw 'helper must use the same unpackaged filesystem view as PCL' }
        if ([IO.Path]::GetFileName($source) -ne 'pairing.json' -or [IO.Path]::GetFileName($destination) -ne 'pairing.json') { throw 'unexpected pairing filename' }
        $result = Atomic-Publish $source $destination
        $result.writtenAt = (Get-Date).ToUniversalTime().ToString('o')
    } catch { $result.error = 'publish helper failed'; $result.writtenAt = (Get-Date).ToUniversalTime().ToString('o') }
    $result | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $resultPath -Encoding UTF8
    exit $(if ($result.status -in @('published', 'already_physical')) { 0 } else { 1 })
}

if ($Helper) { Add-NativePathApi; Invoke-HelperRequest $RequestPath }

$userProfile = $env:USERPROFILE
if ([String]::IsNullOrWhiteSpace($userProfile)) { throw 'USERPROFILE is unavailable' }
if (-not $RuntimeRoot) { $RuntimeRoot = Join-Path $userProfile 'AppData\Local\HearthCrew\runtime' }
if (-not $DestinationRoot) { $DestinationRoot = $RuntimeRoot }
$RuntimeRoot = [IO.Path]::GetFullPath($RuntimeRoot)
$DestinationRoot = [IO.Path]::GetFullPath($DestinationRoot)
if ($WaitSeconds -lt 1 -or $WaitSeconds -gt 300) { throw 'WaitSeconds must be between 1 and 300.' }
Add-NativePathApi

$normalSource = Join-Path $RuntimeRoot 'pairing.json'
$destination = Join-Path $DestinationRoot 'pairing.json'
$packagedProcess = [HearthCrew.PublishPathApi]::IsPackagedProcess()
# A child can retain a redirected filesystem view without reporting package
# identity. Probe the destination itself rather than trusting identity alone.
New-Item -ItemType Directory -Path $DestinationRoot -Force | Out-Null
$visibilityProbe = Join-Path $DestinationRoot ('.hearthcrew-visibility-{0}.tmp' -f ([Guid]::NewGuid().ToString('N')))
try {
    [IO.File]::WriteAllText($visibilityProbe, '')
    $redirectedDestination = -not [StringComparer]::OrdinalIgnoreCase.Equals([HearthCrew.PublishPathApi]::FinalPath($visibilityProbe), $visibilityProbe)
} finally {
    if (Test-Path -LiteralPath $visibilityProbe -PathType Leaf) { Remove-Item -LiteralPath $visibilityProbe -Force }
}
$desktopHelper = $packagedProcess -or $redirectedDestination
$deadline = (Get-Date).AddSeconds($WaitSeconds)
$pairing = $null
$source = $null
do {
    $pairing = Read-Pairing $normalSource
    if ($pairing) { $source = $normalSource }
    if ($pairing) { break }
    Start-Sleep -Milliseconds 250
} while ((Get-Date) -lt $deadline)
if ($null -eq $pairing -or $null -eq $source) { throw 'no valid HearthCrew pairing became available within the requested wait period' }

$requestRoot = Join-Path $env:TEMP ('hearthcrew-publish-{0}' -f ([Guid]::NewGuid().ToString('N')))
$requestPath = Join-Path $requestRoot 'request.json'
$resultPath = Join-Path $requestRoot 'result.json'
New-Item -ItemType Directory -Path $requestRoot -Force | Out-Null
$markerPath = Join-Path $requestRoot 'publisher.marker'
[IO.File]::WriteAllText($markerPath, 'HearthCrew pairing publisher')
# The Explorer helper must receive physical paths; its TEMP view is different.
$requestRoot = Split-Path -Parent ([HearthCrew.PublishPathApi]::FinalPath($markerPath))
$requestPath = Join-Path $requestRoot 'request.json'
$resultPath = Join-Path $requestRoot 'result.json'
$markerPath = Join-Path $requestRoot 'publisher.marker'
try {
    $request = [ordered]@{ schema = 1; source = [HearthCrew.PublishPathApi]::FinalPath($source); destination = $destination; result = $resultPath }
    if ($desktopHelper) {
        $request | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $requestPath -Encoding UTF8
        $shellWindows = (New-Object -ComObject Shell.Application).Windows()
        $desktopHandle = 0
        $desktop = $shellWindows.FindWindowSW(0, 0, 8, [ref]$desktopHandle, 1)
        if ($null -eq $desktop) { throw 'Windows desktop Explorer is unavailable for pairing publication' }
        $scriptPath = [HearthCrew.PublishPathApi]::FinalPath($MyInvocation.MyCommand.Path)
        $powershell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
        $helperArguments = '-NoProfile -NonInteractive -WindowStyle Hidden -File "{0}" -Helper -RequestPath "{1}"' -f $scriptPath, $requestPath
        $desktop.Document.Application.ShellExecute($powershell, $helperArguments, (Split-Path -Parent $scriptPath), 'open', 0)
        $helperDeadline = (Get-Date).AddSeconds([Math]::Min($WaitSeconds, 60))
        while (-not (Test-Path -LiteralPath $resultPath -PathType Leaf) -and (Get-Date) -lt $helperDeadline) { Start-Sleep -Milliseconds 250 }
        if (-not (Test-Path -LiteralPath $resultPath -PathType Leaf)) { throw 'unpackaged pairing helper did not return a result' }
        $result = Get-Content -LiteralPath $resultPath -Raw | ConvertFrom-Json
        if ([string]$result.status -notin @('published', 'already_physical')) { throw 'unpackaged pairing helper reported failure' }
    } else {
        $result = Atomic-Publish $source $destination
    }
    [ordered]@{ status = [string]$result.status; destination = $destination; destinationPhysical = [string]$result.destinationPhysical; port = $pairing.port; desktopHelper = $desktopHelper; publishedAt = (Get-Date).ToUniversalTime().ToString('o') } | ConvertTo-Json -Depth 4
} finally {
    # Only remove the three exact helper files; never recursively delete TEMP.
    foreach ($ownedPath in @($requestPath, $resultPath, $markerPath)) {
        if ((Split-Path -Parent ([IO.Path]::GetFullPath($ownedPath))) -ne $requestRoot) { throw 'unexpected helper cleanup path' }
        if (Test-Path -LiteralPath $ownedPath -PathType Leaf) { Remove-Item -LiteralPath $ownedPath -Force -ErrorAction SilentlyContinue }
    }
    try { [IO.Directory]::Delete($requestRoot, $false) } catch { }
}
