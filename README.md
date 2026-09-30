# VisiDock

Native Android visiting-card manager. Kotlin + Jetpack Compose, Firebase accounts/card records, private Cloudflare R2 images, on-device OCR, optional visual card reading, private adaptive field recognition and on-device semantic search.

Application ID: **com.thotapalli.visidock**. The isolated demo is **com.thotapalli.visidock.demo**.

## Try the app

The demo build uses fictional contacts and disposable in-memory data. It supports browsing, favorites, manual entry, camera/import, review, editing, deletion and bundled semantic search. Changes reset when the app process restarts. Use sample images in this build.

Build from PowerShell in this directory:

```powershell
.\scripts\Build.ps1
```

The script runs the demo build, unit tests and lint, then copies the APK to `artifacts/VisiDock-demo-debug.apk`. With an emulator or device connected, use `-DeviceTests` to run Android instrumentation tests as well.

The current workstation has its SDK under `.tools/android-sdk` and project Gradle downloads under `.gradle-home`. All app source and generated project artifacts remain in this directory. Java and Node are host tools. Android debug signing may use the host's standard debug keystore; it is not a release signing credential.

## Reproducible toolchain

- Gradle 8.13 wrapper, distribution SHA-256 pinned in `gradle/wrapper/gradle-wrapper.properties`.
- Android Gradle Plugin 8.13.2 and Kotlin/Compose compiler 2.3.20.
- Android SDK 36 and Build Tools 35.0.0. Minimum Android 8 (API 26).
- JDK 17 or 21. Verified local toolchain uses Temurin JDK 21.
- JavaScript security tests use Node 22 and locked npm dependencies (`npm ci`).

On another machine, install the SDK and set its path in `local.properties` (not committed). Android Studio can also supply this file. The wrapper downloads Gradle automatically. Existing Java/Android tool installs may be reused.

## Cloud configuration

Firebase project `visidock-thotapalli` is created and bound in `.firebaserc`. Android `com.thotapalli.visidock` is registered and its client configuration is installed. Email/password authentication is enabled. Standard Firestore `(default)` is live in Mumbai (`asia-south1`) with the included rules deployed. The owner selected Cloudflare R2 for images; Firebase Storage is no longer the active image backend. Cloudflare R2 bucket `visidock-card-images` and Worker `visidock-private-images` are deployed with public bucket access disabled. The HTTPS endpoint is configured in `gradle.properties`. See `docs/R2-storage.md`. The live API smoke test passed 60 checks across two temporary accounts, including image lifecycle and account isolation.

1. Sign in with the official Firebase CLI or connected Firebase MCP.
2. Create/select the owner-controlled Firebase project and register Android package `com.thotapalli.visidock`.
3. Save its Android client configuration as `app/google-services.json`. This is Firebase client configuration, not an Admin private key. Never put an Admin key or model-provider secret in the app.
4. Firebase email/password and Firestore are provisioned. Connect Cloudflare and deploy the private R2 image Worker described in `docs/R2-storage.md`.
5. Run the Firestore rules and R2 Worker tests. Deploy Firestore rules and the R2 Worker; the retained Firebase Storage rules are historical and are not used by R2.
6. Build with `.\scripts\Build.ps1 -Cloud -ImageApiUrl 'https://YOUR-WORKER.YOUR-SUBDOMAIN.workers.dev'`. It still validates the demo and also produces `artifacts/VisiDock-cloud-debug.apk`.
7. Exercise registration, verification/reset email, upload, edit, delete, sign-out and account deletion with synthetic data before using private contacts.

Without client configuration, the cloud build displays a setup screen. It does not silently switch to demo storage. The demo remains a separate package.

## Features and privacy

- Integrated CameraX capture with a live framing guide, torch and front/back capture, plus JPEG/PNG/WebP import bounded to 20 MB. The guide is not automatic edge detection. Images are decoded at a bounded size off the main thread, with EXIF orientation applied.
- Latin-script ML Kit OCR runs on-device and preserves text positions. The review screen retains original readings from both sides alongside editable fields and notes. Model proposals can be wrong and remain reviewable.
- Camera and gallery images pass through a four-corner crop review before recognition. Cloud saves contain the confirmed, perspective-corrected card JPEG and a separate 1600-pixel preview, for both sides. Corner placement is manual; uncropped gallery originals are left untouched. Saved cards can flip between front and back. A scan containing multiple people can produce separately reviewed and saved records. Manual cards need no image.
- Phone numbers have separate editable rows and optional printed labels. All numbers survive cloud storage, draft restoration and Android Contacts export.
- Saved-card editing, favorites and explicit duplicate warnings based on email/phone in the user's VisiDock collection.
- Android contact editor handoff maps name, role, company, phone, email, address, website and notes. The user confirms Save in the contacts app. VisiDock does not read the phone contact list or silently write Google Contacts.
- Email/password accounts, verification email, password reset, sign-out and password-confirmed account deletion are implemented. Live service verification is still required.
- UID-scoped document and image rules validate field types, limits and image ownership paths. Backup/device transfer is excluded. Cloud screenshots are blocked and Firestore uses a memory cache.
- Cloud images and data are not end-to-end encrypted. Original/draft images exist in app-private storage until saved/discarded, with stale-file cleanup.
- Upload/deletion manifests make interrupted operations discoverable. Delete removes images before the manifest. Cleanup retries at sign-in/reload; uploads unfinished for 24 hours are eligible for cleanup. A server janitor may later be appropriate for abandoned accounts, but no server is required for search.

## Visual reading and private learning

The optional visual model is a separate approximately 2.6 GB download. It examines card photographs alongside OCR on the device and can propose more than one person. Its output is constrained to structured contact fields and checked against source evidence, but association and recognition errors remain possible. Model installation is optional; without it, the app supplies basic OCR suggestions and asks the user to fill uncertain identity fields. Downloading a model does not upload card images to a model provider.

Eligible corrections saved by the user automatically become examples for a small companion field classifier. Examples and personal weights are account-scoped, encrypted with Android Keystore-backed AES-GCM and excluded from backup. WorkManager schedules training while the device is idle, charging and not low on battery. Candidate weights are evaluated against retained validation examples before activation; insufficient or ambiguous examples do not force an update. The companion supplies fallible field hints grounded in current OCR. **Gemma’s own weights remain frozen.** This is local companion-model training, not continuous fine-tuning of the vision model.

Normal use requires no training screen or approval step. Optional privacy controls disable or clear learning. Accuracy improvement across real card layouts, multilingual input and physical-phone performance still needs device testing; automated checks do not establish those claims.

## Smart search

The bundled MiniLM sentence embedding model runs through ONNX Runtime. No query, contact field or embedding is sent to a model provider. No API key, backend or runtime model download is required.

Names and short queries use lexical matching. Longer queries combine exact matching and semantic similarity; explicit month filters remain enforced. Long card text is chunked. Related matches are suggestions and can be wrong. English is the initial supported semantic language. See `docs/Model-provenance.md` for model revision, license and hashes.

## Security tests

```powershell
npm ci
npm run test:rules
```

These start isolated Firestore and Storage emulators using the `demo-visidock` project. Legacy emulator ports are defined in `firebase.emulators.json`. They test ownership boundaries, anonymous access, malformed writes, immutable paths/dates, deletion lifecycle, upload manifests, file types and size limits. They do not validate a live deployed project.

## Design and verification

- `PRODUCT.md`: confirmed product direction and ownership.
- `DESIGN.md`: palette, type, components, responsive behavior and motion contract.
- `docs/Initial-audit.md`: original ZIP assessment, retained as history.
- `docs/Verification.md`: latest executed checks and remaining gaps.

Release signing, publishing, Play policy review, billing/service ownership and real-device acceptance testing remain with the owner. This is a development build, not a production security certification.





## Google Play internal testing

Use the signed release AAB and persistent upload key described in [docs/Play-internal-testing.md](docs/Play-internal-testing.md). Build future releases with scripts/Build-Release.ps1 and a higher version code. Do not upload the demo or debug APK to the testing track.
