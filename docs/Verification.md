# Verification

## Version 0.3.2 / code 4 — candidate, 30 September 2026

- Release and demo builds succeeded. All 48 release JVM tests and 48 demo JVM tests passed, including separate-phone extraction, migration, JSON round trips, secondary-phone learning, complete address parsing, footer evidence retention and JSON completion boundaries. Release lint passed.
- Fifteen Firestore rule tests passed and the optional phone-list rules were deployed, including maximum-length entries and a full twelve-number, two-sided document.
- Local signed AAB: `artifacts/VisiDock-0.3.2-4-signed.aab`, 105,419,001 bytes. SHA-256 `b4c95ba3b7c8d448dff7309488644462fd455514f98e816994071b5c0ccdd920`. All 727 payload entries match the existing upload certificate; bundletool and all 16 native 16 KB alignment checks passed.
- Android checks remain **unverified**. The full suite, installed-build retry and focused crop/phone retry each ended in a process-startup ANR before tests ran. The host was under 96–99% memory load, and Android's launcher also reported an ANR. The failure logs do not establish an app regression or prove that the implementation works. A successful device run is still required. No new model latency or image-accuracy result is claimed.
- Evidence: `.tools/v032-final-build.log`, `.tools/final-v032-device.log`, `.tools/v032-device-retry.log`, `.tools/v032-crop-device.log`, `.tools/v032-final-startup-failure.log`, `.tools/v032-signature.log`, `.tools/v032-bundle-validation.log`, `.tools/v032-alignment.json`.
- Client config and generated auth JavaScript were removed from GitHub's current branch and release tag; the old AAB release attachment was removed. The former commit is still retrievable by exact ID on GitHub and needs a server-side Support purge. No API key rotation was performed. Local client config remains intact, and future AABs stay local. A staged-source privacy check and CI guard prevent these files/key patterns being recommitted.

## Version 0.3.1 / code 3 — 30 September 2026

- Final release build, lint and all 36 release JVM tests pass. The demo build and 36 demo JVM tests also pass, including company-only cards, whole-token grounding and conflicting learning labels.
- Signed AAB: `artifacts/VisiDock-0.3.1-3-signed.aab`, 105,384,829 bytes; SHA-256 `09154e146db8f14be894c714b8391658a48b914f0abb0a890e27530dc1a1f80c`. Package `com.thotapalli.visidock`, version code 3, version name 0.3.1. All 727 payload entries verify against the existing upload certificate, without duplicate entries. Bundletool validation and all 16 bundled 64-bit ELF 16 KB alignment checks pass. This is a static alignment check, not execution on 16 KB hardware.
- Actual Android image inference passed on the 4 GB API 35 x86_64 emulator: single-person fixture 76.538 seconds including OCR, two-person columns fixture 47.485 seconds. Assertions cover exact identity/contact fields and ownership. The first case exercised GPU failure followed by CPU vision; the second used the remembered CPU preference. This establishes these fictional fixtures on this emulator, not physical-phone performance or general card accuracy.
- Runtime now bounds context to 4,096 tokens, vision to 280 tokens per image, output to 1,200 tokens and image slots to one or two. It checks memory before native initialization. The earlier unbounded 8,192-token CPU run was killed by Android's low-memory manager; it did not pass. Logs are retained locally.
- Eight auth parser/DOM tests and 14 Firestore rule tests pass. Hosted malformed-link recovery and company-only rules are deployed. Synthetic live verification/reset recovery passed and temporary accounts were removed; no real user email was sent.
- Final combined Android run: **14 tests passed** in 150.225 seconds. Thirteen cover front/back presentation, native camera opening/closing, original/EXIF preservation, contact CRUD and company-only saves, recreation, search/favorites, multiple-contact storage independence, encrypted corrections, real MiniLM/companion inference and reset during training. The fourteenth performs actual visual inference on three fixtures: single person (61.669 seconds), two people in columns (29.506 seconds), and front/back (26.759 seconds), including OCR. It checks contact ownership, combined fields and unchanged per-side OCR. These are emulator timings, not physical-phone latency estimates.
- Evidence remains locally in `.tools/release-v031-final.log`, `.tools/final-v031-device.log`, `.tools/v031-signature.log`, `.tools/v031-bundle-validation.log`, `.tools/v031-alignment.json` and `.tools/v031-manifest.xml`. No known failed release gate remains; real user cards, physical camera quality, thermal/battery behavior and animation frame pacing remain internal-testing acceptance work.

## Version 0.3.0 / code 2 — 30 September 2026

- Signed cloud AAB: `artifacts/VisiDock-0.3.0-2-signed.aab`, 105,377,122 bytes. SHA-256 `0876a31b10946ed7457c2c4d6cbff5293f068837b161aef5f82dd0cd3e0bd42b`.
- Package `com.thotapalli.visidock`, version code 2, target SDK 36. Existing upload certificate retained. All 727 payload entries verify against that certificate, without duplicate ZIP entries. Bundletool validation passed.
- All 16 bundled 64-bit ELF libraries pass static 16 KB load-segment alignment checks. This is not a run on 16 KB-page hardware.
- Release lint and 32 release JVM tests passed. Demo build and 32 demo JVM tests passed. Legacy BERT assets and the download-only visual model are absent from the actual AAB; required semantic-search assets remain bundled. Release script now rejects accidentally bundled legacy/download-only models.
- Live front/back storage: 60 checks passed; 13 Firestore-rule tests, 12 Worker tests and 3 hosted-action-handler test groups passed. Synthetic cloud records/accounts were cleaned up. See `Auth-verification.md` for deployment and callback restrictions.
- Rendered demo collection inspected in light mode, deep-blue dark mode and 1.5x font scale. Screenshots remain locally in `artifacts/redesign-*.png`. Physical camera quality, frame pacing and full accessibility acceptance are not established by these screenshots.
- Twelve Android component/UI regression tests passed. The optional image-model test failed to compile its GPU vision encoder on the emulator. Code 2 did not contain the later CPU-vision retry and memory bounds.

### Remaining acceptance work

Physical-phone visual inference, actual user-card accuracy, thermal/battery cost and animation smoothness still need measurement. The user's original invalid-mode/resend failure has not been reproduced; refreshed-session handling and clearer errors are implemented, but Firebase blocked activating the branded custom email callback. No claim of universal device compatibility or guaranteed learning gains is made.

## Previous verification — 20 September 2026 (India)

All source, backend code, downloaded project tools and APK deliverables remain under the VisiDock project folder.

## Local checks

- Demo/cloud debug builds, unit tests and lint passed after the R2 migration (`.tools/r2-final-android.log`).
- JVM tests: 12 passed, including HTTPS endpoint/path validation and safe image-service errors.
- Android demo instrumentation: 6 passed together on API 35 x86_64. Covers original image/EXIF preservation, invalid-image cleanup, card CRUD, draft recreation, favorites/search and real ONNX inference.
- Worker tests: 11 passed (`.tools/r2-worker-tests.log`), including real RSA token verification, caller-derived keys, manifest gates, image limits/signatures and concurrent-deletion cleanup. Wrangler bundling and deployment succeeded.
- Historical Firebase emulator suite: 12 passed. Firestore remains active; Firebase Storage rules/tests are historical only and isolated in firebase.emulators.json.
- Lint: zero errors; dependency/target-version warnings remain.
- Visual checks previously completed: light/dark phone, tablet split detail, narrow display and enlarged text. Screenshots are in artifacts/screenshots.

## Live services

Firebase project visidock-thotapalli: email/password enabled; Standard default Firestore in Mumbai (asia-south1); included Firestore rules deployed.

R2 bucket visidock-card-images: Standard storage, apac location hint. Public r2.dev access disabled, no public custom bucket domains. Worker https://visidock-private-images.aswin-ec3.workers.dev is deployed and configured in the Android build. Firebase Blaze is not needed for this architecture.

Live API smoke test: 36 checks passed with zero failures (.tools/r2-live-smoke.log). Two temporary accounts uploaded originals/previews, read exact bytes, were denied cross-account reads, and deleted their objects and records. Missing-token reads were rejected. Both temporary accounts were deleted successfully.

## Outstanding verification

Physical Android camera and ARM inference, release frame timing/performance, email verification/reset delivery and large-scale abuse/load testing remain unverified. R2 location hints do not guarantee India-only image storage. Free allowances are account-wide; Worker requests and Firestore reads have separate limits. Per-user quotas and an orphan-image reconciler remain future operational work.

Debug APKs are development builds, not signed release deliverables. The demo uses disposable in-memory fictional contacts. These checks are not a production-readiness certification.

Live Android repository integration also passed: 1 opt-in cloud instrumentation test on API 35, with a temporary account, real PNG/JPEG upload, exact preview-byte download, Android bitmap decoding, metadata update, deletion and account cleanup. Output: .tools/r2-cloud-device.log. The connected APK is artifacts/VisiDock-cloud-debug.apk.


## Signed Play bundle — verified 30 September 2026

Artifact: artifacts/VisiDock-0.2.0-1-signed.aab (82,031,869 bytes), package com.thotapalli.visidock, version 0.2.0/code 1, target SDK 36, non-debuggable. SHA-256: 861d914832029ae1412cfd4337bf87a6fc72b87f4724607a4b842f99e1007c24.

Release build, 12 release JVM tests and release lint passed. Bundletool 1.18.3 validation passed. JAR signature verifies and matches the stored RSA-4096 upload certificate. All 10 bundled 64-bit ELF libraries have 16 KB-compatible load segments; bundle config requests PAGE_ALIGNMENT_16K. These are static alignment checks, not a test on 16 KB hardware. The upload certificate is self-signed as expected for Android signing. The AGP bundle's manifest entry ordering also causes a streaming JarInputStream warning in jarsigner; random-access JarFile signature verification validates each payload entry.

Private signing material is in signing/, excluded from source control and protected by Windows permissions. Back it up securely. Instructions: docs/Play-internal-testing.md. This bundle has not been uploaded to Play Console or installed through Google Play by this task.
