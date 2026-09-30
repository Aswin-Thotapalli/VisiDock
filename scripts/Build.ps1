param([switch]$DeviceTests, [switch]$Cloud, [string]$ImageApiUrl)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectRoot
try {
    $env:GRADLE_USER_HOME = Join-Path $projectRoot '.gradle-home'
    if (!(Get-Command java -ErrorAction SilentlyContinue)) { throw 'Install JDK 17 or 21 and add java to PATH.' }
    if (!(Test-Path local.properties)) {
        $sdkPath = Join-Path $projectRoot '.tools/android-sdk'
        if (!(Test-Path (Join-Path $sdkPath 'platforms/android-36/android.jar'))) { throw 'Install Android SDK 36. Set sdk.dir in local.properties to the SDK directory.' }
        $escapedSdk = $sdkPath.Replace('\','/').Replace(':','\:')
        [System.IO.File]::WriteAllText((Join-Path $projectRoot 'local.properties'), "sdk.dir=$escapedSdk`n")
    }
    $tasks = @(':app:assembleDemoDebug', ':app:testDemoDebugUnitTest', ':app:lintDemoDebug')
    if ($DeviceTests) { $tasks += ':app:connectedDemoDebugAndroidTest' }
    if ($Cloud) {
        if (!(Test-Path app/google-services.json)) { throw 'Add the com.thotapalli.visidock Firebase configuration at app/google-services.json first.' }
        $tasks += ':app:assembleCloudDebug'
    }
    if ($ImageApiUrl) {
        $endpoint = [Uri]$ImageApiUrl
        if (!$endpoint.IsAbsoluteUri -or $endpoint.Scheme -ne 'https' -or $endpoint.UserInfo -or $endpoint.Query -or $endpoint.Fragment) { throw 'ImageApiUrl must be an HTTPS endpoint without credentials, query or fragment.' }
        $tasks += "-Pvisidock.imageApiUrl=$ImageApiUrl"
    }
    & .\gradlew.bat @tasks --console=plain --max-workers=2
    if ($LASTEXITCODE -ne 0) { throw 'Build or verification failed. Inspect the Gradle output.' }
    New-Item -ItemType Directory -Force artifacts | Out-Null
    Copy-Item app/build/outputs/apk/demo/debug/app-demo-debug.apk artifacts/VisiDock-demo-debug.apk
    if ($Cloud) { Copy-Item app/build/outputs/apk/cloud/debug/app-cloud-debug.apk artifacts/VisiDock-cloud-debug.apk }
} finally { Pop-Location }
