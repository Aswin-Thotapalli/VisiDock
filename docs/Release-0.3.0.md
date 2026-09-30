# VisiDock 0.3.0 — internal testing

Historical candidate: Android package `com.thotapalli.visidock`, version code **2**. The signed AAB and checksum remain in the project `artifacts/` folder. It was shared locally, not published as a GitHub release. The finalization work is tracked in [Release-0.3.1.md](Release-0.3.1.md).

## Changes

- Deep-blue light/dark interface, folded-card identity, personalized collection copy, grouped editing, shared photo transitions, front/back card flips and success animation after persistence.
- Integrated CameraX capture with framing guides, torch and front/back capture. The frame is a guide, not automatic edge detection or perspective correction.
- Front/back originals and previews, separate review/save for multiple people detected on one card, and retry-safe private image uploads/deletion.
- Optional 2.6 GB checksum-verified Gemma visual model using CPU decoding and GPU vision. This candidate does not include the subsequent CPU vision retry or native-runtime guard. Bundled OCR and semantic search also stay on device. Without the visual model, the app explicitly offers basic text suggestions for review.
- Automatic encrypted local learning from grounded corrections, including later edits to saved cards. A small companion classifier learns field labels using semantic and layout features; Gemma itself stays frozen. Training runs when idle and charging, and candidates must pass held-out checks before activation.
- Verification resend refreshes the session/token, handles rate limits and network/session failures, applies a short cooldown, and exposes registration email-delivery failures instead of hiding them.

## Test scope and limits

Executed checks and exact results are recorded in [Verification.md](Verification.md). Six clean fictional desktop vision fixtures passed; that is not a real-world accuracy guarantee. Physical-phone camera quality, inference latency, thermal behavior and animation frame pacing still need testing. Multiple phone values can be retained, while additional email/website values may require manual review.

The originally reported verification-page/resend error has not been reproduced with the user's exact error. Synthetic fresh verification/reset links work. A custom branded handler is deployed, but Firebase rejected switching the live email callback with `EMAIL_TEMPLATE_UPDATE_NOT_ALLOWED`, so new messages still use Firebase's default page. See [Auth-verification.md](Auth-verification.md).

Private upload keys and signing passwords are intentionally not published. Back up the existing `signing/` folder securely to preserve future release access.
