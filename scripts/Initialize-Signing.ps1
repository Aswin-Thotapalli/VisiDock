$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$signingDirectory = Join-Path $projectRoot 'signing'
$keystore = Join-Path $signingDirectory 'visidock-upload.p12'
$propertiesFile = Join-Path $signingDirectory 'upload-signing.properties'
if ((Test-Path $keystore) -or (Test-Path $propertiesFile)) { throw 'Signing material already exists. Reuse it; do not replace the upload key for updates.' }
New-Item -ItemType Directory -Force -Path $signingDirectory | Out-Null
$identity = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
& icacls.exe $signingDirectory /inheritance:r /grant:r "${identity}:(OI)(CI)F" 'SYSTEM:(OI)(CI)F' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not restrict signing directory access.' }
$password = [Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$env:VISIDOCK_NEW_KEY_PASSWORD = $password
try {
    & keytool -genkeypair -keystore $keystore -storetype PKCS12 -alias visidock-upload -keyalg RSA -keysize 4096 -sigalg SHA256withRSA -validity 10950 -dname 'CN=VisiDock Upload' -storepass:env VISIDOCK_NEW_KEY_PASSWORD -keypass:env VISIDOCK_NEW_KEY_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw 'Upload key generation failed.' }
    [IO.File]::WriteAllText($propertiesFile, "storeFile=signing/visidock-upload.p12`nstorePassword=$password`nkeyAlias=visidock-upload`nkeyPassword=$password`n")
    & keytool -exportcert -rfc -keystore $keystore -alias visidock-upload -storepass:env VISIDOCK_NEW_KEY_PASSWORD -file (Join-Path $signingDirectory 'upload-certificate.pem')
    if ($LASTEXITCODE -ne 0) { throw 'Certificate export failed.' }
} finally { Remove-Item Env:VISIDOCK_NEW_KEY_PASSWORD -ErrorAction SilentlyContinue; $password = $null }
Write-Output 'Upload key created. Back up the entire private signing directory securely. No password was printed.'
