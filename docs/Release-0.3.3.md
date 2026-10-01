# VisiDock 0.3.3 — internal testing

Android package `com.thotapalli.visidock`, version code **5**. The locally delivered AAB uses the existing upload certificate for Google Play updates.

## Capture and images

- Capture and crop the front, choose whether to add the back, then read the complete card once. Both prepared images survive activity recreation.
- Manual entry remains available from capture review, retaining both cropped photos if reading is cancelled or cannot finish.
- Automatic card-boundary proposals are shown as a straightened preview. Adjust corners when needed; uncertain detection leaves the full image available for manual selection.
- Add or replace a saved side, or re-crop either saved image, without deleting the contact or replacing its details.
- Existing cards retain their previous images until replacement uploads are ready. Versioned image objects, durable rollback and deletion manifests support retry and cleanup.
- Re-cropping starts from the stored cropped original. Pixels excluded by an earlier crop are not recoverable; use Replace photo to capture a wider view.
- Account-scoped encoded and decoded image caches reduce repeated downloads and decoding, with replacement keys and sign-out invalidation.

## Reading and corrections

- Read OCR from the detailed source image; normalize printed full-width and spaced `@` symbols.
- Validate email and website separately and repair swapped fields when format evidence permits. Ambiguous missing symbols still need review.
- Show actual correction history separately from examples eligible for local learning. Store history encrypted on this device.
- Save visible corrections before optional companion training work. Generation checks discard stale training after newer edits, disabling learning or signing out.
- Reuse the local visual-model engine briefly during consecutive scans; close each conversation and release idle weights after one minute. Front and back share one visual-model request.

## Interface and motion

Photo-led collection, deep-blue/cobalt/cyan surfaces, shared card transitions, front/back turns, side-specific reading stages, responsive crop preview, tactile controls, grouped editing and refreshed account/settings screens. Motion follows the Android animation-duration setting.

Two-sided cards follow horizontal finger movement, settle using release velocity, and support interruption or accessible tap controls. Card angle drives restrained depth and reflection. Shared photo bounds and button releases use physical springs; confirmed saves show the persisted card thumbnail and a drawn check stroke. Per-frame drawing state is kept out of whole-screen composition, and image producers are keyed to prevent stale-photo frames.

Capture review, reading and editing now share the same photograph identity even when recognition changes the contact ID. Camera and crop handoffs wait for the next screen's data without forcing a timed delay. Crop preview straightens the selected quadrilateral into the actual card rectangle; the source remains visible while preparation runs. Navigation appearance, warning panels, phone-row editing and validation participate in the same motion system. Rapid flip reversals preserve momentum, and validation focus is tied to an explicit save attempt rather than subsequent typing.

## Distribution and verification

The signed AAB is delivered in the local `artifacts` directory. GitHub hosts source only: Firebase client configuration, generated authentication bundle, signing material and application binaries remain excluded.

Verification results will be recorded after the integrated build and Android checks. No Galaxy S26 latency or universal OCR-accuracy claim is made from emulator results.
