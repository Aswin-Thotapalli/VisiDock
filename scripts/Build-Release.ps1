param(
    [Parameter(Mandatory=$true)][ValidateRange(1,2100000000)][int]$VersionCode,
    [Parameter(Mandatory=$true)][ValidatePattern('^[0-9]+\.[0-9]+\.[0-9]+([.-][A-Za-z0-9]+)*$')][string]$VersionName,
    [string]$GradleUserHome = '',
    [ValidateRange(1,8)][int]$MaxWorkers = 2
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectRoot
try {
    if (!(Test-Path signing/upload-signing.properties)) { throw 'Run scripts/Initialize-Signing.ps1 once, then securely back up the signing folder.' }
    if (!(Test-Path app/google-services.json)) { throw 'Missing Firebase Android client configuration.' }
    $destination = "artifacts/VisiDock-$VersionName-$VersionCode-signed.aab"
    if (Test-Path $destination) { throw 'This release artifact already exists. Use a higher VersionCode for a new release.' }
    $env:GRADLE_USER_HOME = if ($GradleUserHome) { [IO.Path]::GetFullPath($GradleUserHome) } else { Join-Path $projectRoot '.gradle-home' }
    & ./gradlew.bat :app:bundleCloudRelease :app:lintCloudRelease :app:testCloudReleaseUnitTest "-Pvisidock.versionCode=$VersionCode" "-Pvisidock.versionName=$VersionName" --console=plain "--max-workers=$MaxWorkers" --no-watch-fs '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8' '-Pkotlin.compiler.execution.strategy=in-process'
    if ($LASTEXITCODE -ne 0) { throw 'Release build or checks failed.' }
    $bundle = 'app/build/outputs/bundle/cloudRelease/app-cloud-release.aab'
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead((Join-Path $projectRoot $bundle))
    try {
        $unexpectedModels = @($archive.Entries | Where-Object {
            $_.FullName -like 'base/assets/extraction/*' -or $_.FullName -like '*.litertlm'
        })
        if ($unexpectedModels.Count -gt 0) { throw 'Unused or download-only models were packaged in the bundle.' }
    } finally { $archive.Dispose() }
    $verification = & jarsigner -verify $bundle 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0 -or $verification -notmatch 'jar verified') { throw 'Bundle signature verification failed.' }
    New-Item -ItemType Directory -Force artifacts | Out-Null
    Copy-Item $bundle $destination
    Copy-Item signing/upload-certificate.pem artifacts/VisiDock-upload-certificate.pem
    $hash = (Get-FileHash $destination -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash  $([IO.Path]::GetFileName($destination))" | Set-Content "$destination.sha256"
    Write-Output "Signed release bundle: $destination"
} finally { Pop-Location }
