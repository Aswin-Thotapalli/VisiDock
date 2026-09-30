# VisiDock 0.3.1 — internal testing

The visiting-card workflow now supports front/back photographs, separate contacts from a multi-person card, company-only cards, on-device visual reading and automatic private correction learning. The interface has a new identity, deep-blue light/dark themes, integrated camera, grouped review screens, card-turn transitions and save confirmation animations.

**Package:** `com.thotapalli.visidock`. **Version code:** 3. The signed bundle uses the existing upload key, so upload it to the existing Google Play app's internal-testing track.

## Included

- Local Gemma image interpretation with a GPU-to-CPU compatibility fallback and bounded memory use. Download the visual model once in Settings (2.6 GB); photographs are not sent to an AI provider.
- Original front/back photographs and previews, per-person review/save, private R2 image storage and retry-safe uploads/deletion.
- Encrypted, account-scoped learning from eligible corrections. A trainable companion classifier improves field-label hints after validation; Gemma's weights stay frozen. Training runs automatically when idle and charging.
- Whole-token OCR grounding and conflict handling so partial matches and contradictory previous corrections cannot be treated as confirmed evidence.
- Camera framing guide, torch, capture rotation, permission recovery, download cancellation and stable save controls. The camera guide does not perform automatic edge detection or perspective correction.
- Refreshed verification sessions, resend cooldown/error handling and a deployed **Verification help** page that recovers supported malformed email links.

## Validation

- 36 release JVM tests and Android lint passed.
- 14 Android tests passed, including actual image inference on single-person, two-person and front/back fixtures, plus saving, editing, camera, image preservation, search and learning checks.
- 8 auth-handler tests and 14 Firestore-rule tests passed; updated hosting and rules are deployed.
- Bundletool validation, all 727 payload signatures and all 16 native-library alignment checks passed.

The visual fixtures ran on a 4 GB Android emulator. Real-phone accuracy, latency, thermal behavior and animation frame pacing still need internal testing. Firebase still uses its default email page because it rejected activating the branded callback; the in-app verification-help route is available. The user's original resend error has not been reproduced exactly. See [Verification.md](Verification.md) and [Auth-verification.md](Auth-verification.md).

## Bundle

`artifacts/VisiDock-0.3.1-3-signed.aab` — 105,384,829 bytes.

SHA-256: `09154e146db8f14be894c714b8391658a48b914f0abb0a890e27530dc1a1f80c`.

This supersedes the earlier code 2 candidate. The AAB and checksum are retained locally and attached to the GitHub release. Follow [Play-internal-testing.md](Play-internal-testing.md). Private signing material remains outside version control.
