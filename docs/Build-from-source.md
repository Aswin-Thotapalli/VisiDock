# Build from source

The repository contains the Android app, private-image Worker, hosted auth-handler source, security rules, model provenance, and test fixtures. The Gradle wrapper and the 23 MB quantized MiniLM semantic-search model are included, so semantic search does not require a separate model download. The visual model downloads on device with the integrity checks described in `Visual-model-manifest.json`; its large runtime binary is not committed. The unused legacy NER model is excluded from both the source repository and Android packaging.

## Local Android setup

Install JDK 17 and an Android SDK supporting the app's configured compile SDK. Configure `local.properties` with your own SDK path. Obtain `app/google-services.json` for the Firebase Android application `com.thotapalli.visidock` in your project; this machine-specific client configuration is not committed. The checked-in `gradle.properties` contains a public image-service origin, not a credential. Use your own origin and Firebase project when making an independent deployment.

Use `scripts/Build.ps1` for the development build, or the checked-in Gradle wrapper directly. Consult `app/build.gradle.kts` for supported demo/cloud variants. Deployment instructions live in `backend/r2/README.md` and `backend/auth/README.md`. The Firebase web config bundled in the hosted handler is intentionally public client configuration; authorization is enforced by Firebase rules and token validation, not by hiding that API key.

## Signing and releases

Private upload keys, signing passwords, signing properties, local SDK paths, caches, debug logs, downloaded tools and generated APK/AAB files are excluded from Git. Never add the `signing/` directory or print its properties. Back up the existing upload key separately; replacing it can prevent future updates unless Google Play's upload-key reset process is completed.

For authorized release work, `scripts/Build-Release.ps1 -VersionCode <integer> -VersionName <version>` produces a signed AAB and checksum in `artifacts/`. Follow `docs/Play-internal-testing.md` before distributing it. An AAB is an upload bundle for Google Play and is not directly installable like an APK.

Release binaries belong in GitHub Release assets, alongside their SHA-256 checksum, rather than in Git history. Git Credential Manager on the configured machine can authenticate both Git pushes and GitHub's release API. No token needs to be stored in the repository. Publish a release only after the final build, tests and release notes are approved by the task owner.
