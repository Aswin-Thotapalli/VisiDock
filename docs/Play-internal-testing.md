# Google Play internal testing

## Current phone-testing candidate: 0.3.2 / code 4

Use the local `artifacts/VisiDock-0.3.2-4-signed.aab` for the existing Play app, package `com.thotapalli.visidock`. The original upload certificate is retained. Build, lint, 48 release unit tests, 48 demo unit tests, signature, bundle and native alignment checks passed. Android behavior remains unverified because three emulator runs timed out before executing tests; the user chose phone testing. See [Release-0.3.2.md](Release-0.3.2.md) for the checksum and limitations.

Do not upload the AAB to GitHub: client API keys are embedded in Android binaries. Keep it local for Google Play upload.

## Previous release: 0.3.1 / code 3

Package **com.thotapalli.visidock**. Signed bundle: `artifacts/VisiDock-0.3.1-3-signed.aab` (105,384,829 bytes).

SHA-256: `09154e146db8f14be894c714b8391658a48b914f0abb0a890e27530dc1a1f80c`.

The release build, 36 cloud JVM tests, lint, bundle validation, original-key signature verification for all 727 payload entries and 16 native alignment checks passed. All 14 final Android tests passed in 150.225 seconds: 13 UI/component tests and one real-model test covering single-person, two-person and front/back cards on the 4 GB API 35 x86_64 emulator. These are not physical-phone performance or universal accuracy guarantees. See [Release-0.3.1.md](Release-0.3.1.md) and [Verification.md](Verification.md) for the final recorded result.

Upload this AAB to the existing **Internal testing** track. Keep the same Play app and upload certificate so Play-installed testers receive an update. Do not upload a debug APK or create a new package/signing identity.

Google Play App Signing signs the device APKs generated from this upload-key-signed bundle. Add testers to the track, roll out the release and share the opt-in link. Testers install through Play once; subsequent updates arrive through Play subject to their update settings. If a tester previously installed a debug APK with the same package, its different certificate requires a one-time uninstall before installing the Play version. Preserve unsaved drafts first; uninstalling does not delete account-owned cloud records.

## Preserved release history

- `VisiDock-0.3.0-2-signed.aab`: previously shared code 2 candidate, retained with its checksum; see [Release-0.3.0.md](Release-0.3.0.md).
- `VisiDock-0.2.0-1-signed.aab`: initial code 1 release, retained with its checksum.

Use the code 4 candidate for this update. The older bundles are retained locally as history.

## Signing material

Private files are in `signing/`, protected by Windows permissions and excluded from version control:

- `visidock-upload.p12`: encrypted private upload key.
- `upload-signing.properties`: alias and passwords used by Gradle.
- `upload-certificate.pem`: public certificate (also copied into artifacts for convenience).

**Securely back up the entire signing folder outside this machine**, such as in encrypted storage. The properties file contains the keystore password. Keep it private; do not include signing/ in source-sharing ZIPs. No passwords are printed by the build scripts. Reuse this key for all updates; do not rerun key initialization or generate a replacement casually. If an upload key is lost, Play App Signing provides an upload-key reset process.

## Subsequent builds

From the project folder:

```powershell
.\scripts\Build-Release.ps1 -VersionCode 5 -VersionName 0.3.3
```

Each uploaded release needs a version code greater than every previously uploaded one. The script refuses to overwrite an existing artifact, builds the cloud release, runs release unit tests and lint, verifies the JAR signature and writes an AAB plus SHA-256 checksum. Upload each new AAB to the same Play app and testing track. Google Play creates architecture-specific installs from the bundle.

Release minification is deliberately disabled for these internal builds to preserve the behavior tested so far. The AAB is non-debuggable, has a release upload signature and targets Android 16 (API 36). App identity and signing persist independently of version names.

References: https://support.google.com/googleplay/android-developer/answer/9842756 and https://support.google.com/googleplay/android-developer/answer/9845334.
