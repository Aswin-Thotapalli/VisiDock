# Build from source

The repository contains the Android app, private-image Worker, hosted auth-handler source, security rules, model provenance, and test fixtures. The Gradle wrapper and the 23 MB quantized MiniLM semantic-search model are included, so semantic search does not require a separate model download. The visual model downloads on device with the integrity checks described in `Visual-model-manifest.json`; its large runtime binary is not committed. The unused legacy NER model is excluded from both the source repository and Android packaging.

## Local Android setup

Install JDK 17 and an Android SDK supporting the app's configured compile SDK. Configure `local.properties` with your own SDK path. Obtain `app/google-services.json` for the Firebase Android application `com.thotapalli.visidock` in your project; this machine-specific client configuration is not committed. The checked-in `gradle.properties` contains a public image-service origin, not a credential. Use your own origin and Firebase project when making an independent deployment.

Use `scripts/Build.ps1` for the development build, or the checked-in Gradle wrapper directly. Consult `app/build.gradle.kts` for supported demo/cloud variants. Deployment instructions live in `backend/r2/README.md` and `backend/auth/README.md`. For the auth handler, copy `backend/auth/public-config.example.json` to the ignored `public-config.json` and fill it locally. The generated `backend/auth/public/app.js` is also ignored. Never commit the real client configuration or generated bundle.

## Signing and releases

Private upload keys, signing passwords, signing properties, local SDK paths, caches, debug logs, downloaded tools and generated APK/AAB files are excluded from Git. Never add the `signing/` directory or print its properties. Back up the existing upload key separately; replacing it can prevent future updates unless Google Play's upload-key reset process is completed.

For authorized release work, `scripts/Build-Release.ps1 -VersionCode <integer> -VersionName <version>` produces a signed AAB and checksum in `artifacts/`. Follow `docs/Play-internal-testing.md` before distributing it. An AAB is an upload bundle for Google Play and is not directly installable like an APK.

Keep signed AABs and their checksums in the local `artifacts/` directory for upload to Google Play. Do not attach Android binaries to GitHub releases: they embed Firebase client API keys. GitHub releases contain source and release notes only. Before pushing, run `python scripts/Check-PublishSource.py` against the staged source; GitHub Actions repeats this check. No authentication token belongs in the repository.
