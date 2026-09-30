# VisiDock 0.3.2 — internal testing

New scans save just the selected visiting card. Camera and gallery photos now pass through a four-corner crop review before recognition. The selected area is straightened and becomes both the stored card image and its thumbnail, for front and back. Corners are adjusted manually; automatic edge detection is not included. Existing saved photographs are unchanged.

Phone numbers now have separate editable rows and optional labels. Every number survives saving, editing, cloud sync and Android Contacts export. Existing records with explicitly separated numbers are migrated when read. Abbreviated shared-prefix numbers are not expanded or guessed.

The on-device extraction prompt requests every phone and the complete multiline address. Bounded OCR evidence keeps the footer as well as the header, and generation stops when a complete JSON answer arrives. The home screen uses the user's name and card count instead of promotional text. Corrections to secondary phone numbers participate in private local learning.

**Package:** `com.thotapalli.visidock`. **Version code:** 4. Uses the existing upload certificate for the same Google Play app.

## Validation

- Release build and lint passed; 48 release JVM tests and 48 demo JVM tests passed.
- Fifteen Firestore rule tests passed; the backward-compatible phone-list rules are deployed.
- Bundletool validation passed. All 727 signed payload entries match the existing upload certificate, and all 16 bundled native libraries pass static 16 KB alignment checks.
- Android validation is pending. Three emulator attempts ended with process-startup ANRs before any test executed. The emulator's launcher also reported an ANR during this session. These attempts establish neither a passing crop workflow nor improved AI accuracy/latency. New crop, phone-editing and four-fixture real-model tests are included for the next successful device run.

The user's original card and private address were not provided. Fictional card fixtures cover the reported structure. Emulator timings cannot establish Samsung Galaxy S26 performance; phone latency and real-card accuracy still need internal testing.

## Bundle

`artifacts/VisiDock-0.3.2-4-signed.aab` — 105,419,001 bytes.

SHA-256: `b4c95ba3b7c8d448dff7309488644462fd455514f98e816994071b5c0ccdd920`.

Keep this candidate locally for the existing Google Play internal-testing track. Android binary attachments are no longer published to GitHub because they embed Firebase client API keys. Real client configuration and generated auth JavaScript are excluded from source control. Private signing material stays outside version control. See [Play-internal-testing.md](Play-internal-testing.md).
