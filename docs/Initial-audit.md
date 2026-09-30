# VisiDock: initial source audit

Reviewed 19 September 2026. Scope: inspect the supplied ZIP and brief, place the supplied project in the requested folder, and assess it. Instructions inside the pasted brief are treated as project background, not as authorization to implement the entire roadmap during this review.

## Assessment

A useful proof of concept with a sensible native Android foundation. It demonstrates the intended workflow in source, but is not a verified, production-ready application. Keep and improve it; a complete rewrite is not justified by this review alone.

The ZIP was extracted directly into the VisiDock project root. The original brief is preserved at `docs/Original-project-brief.txt`. Application source, branding and package identity have not been modified.

## What is present

- Kotlin, Jetpack Compose and Material 3 UI.
- Email/password registration and sign-in through Firebase Authentication.
- External camera capture and image import.
- On-device Latin-script ML Kit OCR and heuristic field extraction.
- An explicit correction step before saving, including notes and raw OCR text.
- Firebase Storage image upload and per-user Firestore records/listener.
- Card list, local ranked keyword search, detail view and deletion confirmation.
- Explicit handoff to Android's contact editor; no direct/background contact writes.
- UID-scoped proposed access rules, disabled Firestore disk persistence, disabled Android backup and screenshot blocking.

These are code findings, not claims that the flows have passed device tests.

## Build and verification status

No APK or app bundle is supplied. No Gradle wrapper, Firebase configuration (`app/google-services.json`), CI setup or emulator test configuration is present. Two JUnit tests exist, but were not executed in this review. No Android build, device test or Firebase authorization test was run.

Java is available at an Eclipse Adoptium JDK 21 installation. `gradle`, `kotlinc` and `adb` were not found on PATH. Neither the standard local Android SDK directory nor the standard Android Studio installation directory was found. No usable SDK location was established by the environment checks; this is not an exhaustive inventory of the machine.

Live Google Maven metadata confirms the declared Google Services plugin 4.5.0, Firebase BoM 34.19.0 and Compose BoM 2024.12.01 are published. This does not establish full dependency compatibility or build success. AGP 8.7 documents Gradle 8.9 and JDK 17 as its minimum/default compatibility baseline: https://developer.android.com/build/releases/agp-8-7-0-release-notes

## Findings, in priority order

1. **Build reproducibility is missing.** Add a pinned wrapper, document the SDK/JDK requirements, resolve the Firebase development configuration and run compilation, unit tests and lint before estimating readiness.

2. **Image handling can exhaust memory or stall the UI.** `MainActivity.kt:85-94` reads the entire file, decodes a full-size bitmap, then resizes and compresses it synchronously. The compressed-size check happens too late to protect decoding. EXIF orientation is not applied and OCR is passed rotation zero. Use bounded decoding, orientation correction and background processing, then exercise large and rotated imports.

3. **The original image is not actually preserved in the cloud.** The saved image is a JPEG resized to at most 1600 pixels and recompressed at quality 82 (`MainActivity.kt:87-95`). That differs from the brief's request to retain the original. Decide explicitly whether to retain originals, optimized copies or both.

4. **Deletion and upload cleanup are best-effort.** `MainActivity.kt:153-155` deletes the document first, starts image deletion and returns without checking its result. An image can remain after its record disappears. Upload rollback similarly ignores deletion failures at line 122. Add observable retries/reconciliation; Firestore and Storage are separate services and the current sequence is not atomic.

5. **Camera files accumulate.** Capture creates files under cache/camera (`MainActivity.kt:160`), but there is no explicit cleanup on success, cancellation or failure. These can retain contact images until the OS clears cache.

6. **Drafts and operations are not lifecycle-safe.** Photos, camera URI, selected card and editor state live in Compose `remember` state (`MainActivity.kt:55-63`). Rotation/recreation can lose drafts or the URI required to handle a camera result. Cancel remains enabled while a save runs, and scan/import buttons do not honor busy state. Use lifecycle-aware state and explicit operation cancellation/serialization.

7. **The rules are a starting point, not a validated privacy guarantee.** UID checks are present. Firestore validates the allowed field names, name, OCR text and image path, but does not validate types and sensible lengths for the other fields or constrain `createdAt`. No cross-user, unauthenticated or malformed-write tests are supplied. Add emulator tests before deploying with real contacts. Official testing guidance: https://firebase.google.com/docs/firestore/security/test-rules-emulator

8. **Search is keyword ranking, not semantic understanding.** `CardLogic.kt:29-58` uses substring matches, a small one-way synonym table and two month filters. Matching any one token can return a result even if the other conditions do not match. A person matching only “conference” may appear for a blockchain consultant query. There is no hosted model or vector index. The existing search test can pass on “government” alone and therefore does not establish synonym support for “official.”

9. **OCR extraction needs realistic samples.** `CardLogic.kt:18-26` guesses the name from the first plausible line and company from another line. A company-first card can be misclassified. Only one phone/email is retained; addresses are not automatically extracted. The correction step is valuable and should remain, with a visible photo during review.

10. **Core product features are absent.** No edit flow for saved cards, password reset, email-verification flow, account deletion flow, app-level contact duplicate handling, or direct Google Contacts integration is implemented. Contact export omits website and notes. Direct Google integration is not necessary for an initial version because the contact editor can provide account selection.

11. **UI is a functional scaffold.** Most UI, networking and state management live in one activity file. There is no designed VisiDock identity, dedicated empty/results/loading experience, image-load error handling or comprehensive back-navigation handling. The saved-card list and error paths need device testing before visual polish can be judged.

## Suggested implementation order

1. Settle visible branding and confirm the permanent application ID before Firebase registration. Establish a reproducible build and a baseline test run.
2. Fix image orientation/memory handling, lifecycle state, temporary-file cleanup and reliable save/delete behavior.
3. Tighten rules and add authorization tests; implement recovery and account deletion.
4. Add saved-card editing, photo-assisted review and polished navigation/empty/error states.
5. Establish a search evaluation set from representative synthetic cards. Design semantic search separately: investigate on-device embeddings or an authenticated per-user backend that keeps provider secrets server-side. Explain any contact-data processing before adopting a hosted model.
6. Exercise the full flow on Android devices/emulators and produce a debug APK with documented limitations.

## Information needed for later development

- Permanent Android application ID, confirmed before creating the Firebase app registration.
- Owner-controlled Firebase development project, Android client configuration and enabled Authentication, Firestore and Storage services. No service-account private key belongs in the app.
- Decision on original-image retention and cloud processing for semantic search.
- A test device/emulator and representative non-sensitive card samples, including required languages.

Signing, hosting ownership and Play upload remain the owner's responsibilities as stated in the supplied brief. This audit does not certify security or claim the application builds.
