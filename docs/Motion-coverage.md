# Whole-app motion coverage

This is an implementation and acceptance checklist, not a claim that every row has passed. The motion expansion is in progress. Final evidence belongs in `Verification.md`.

## Version 0.5.0 integration (validation in progress)

The new physical-interface contract and material implementation are documented in `Physical-interface-contract.md` and `Physical-system.md`. The historical coverage below is not the acceptance record for this candidate. The scan now completes optical activity after OCR, then presents a deliberate review handoff while joint interpretation continues. Slow OCR repeats the optical sweep. Fast processing cannot skip the front or back presentation. The prior two-segment shared bounds path is replaced by one critically damped spring, and competing parent translation is removed for photo journeys.

## Card-case revision, versions 0.4.0–0.4.1

The current collection uses a layered case, matte shell grain, woven lining and paper edges. Real photos travel along shared lifted bounds into detail and back to the case after confirmed saves; card selection follows a bounded lazy pager with visual overlap. The scan stage keeps one actual side visible, flips to the other and retains its optical sweep during joint interpretation. The source image is never covered with artificial texture.

Physical Pixel 9 renders confirmed the new case, corrected inherited text contrast, consumed system insets, clear collection controls and front/back photo identity. Initial device tests uncovered the final-page scroll-range defect and a test touch intercepted by the Add FAB; version 0.4.1 includes the repairs. Final confirmation results are in `Verification.md`. The inventory below records the earlier bounded coverage and outstanding states; it is not an assertion of exhaustive motion acceptance.

## Shared contract

Every interactive element needs an intentional response to press, release, cancellation, focus, selection and disabled state where applicable. Interaction callbacks run immediately and exactly once. Animation must never manufacture processing progress. The ordered scan presentation intentionally retains each side for its optical sweep even if the backend finishes faster, then hands off the ready result; reduced motion removes that duration. Motion may be interrupted and retargeted. Gesture movement follows the finger. Haptics distinguish interaction from confirmation without firing twice.

Whole-screen composition should not run for every drawing frame. Images must remain source-correct during transitions. Large text, keyboard changes, landscape/tablet layouts, screen-reader actions and the Android animation-duration setting are part of acceptance. Deep blue supplies shadows and dark surfaces.

## Interaction inventory

| Area | Required coverage | Validation |
| --- | --- | --- |
| App shell | Screen entry/exit, bottom navigation and rail selection, back navigation, add action, busy/error/snackbar appearance and dismissal | Collection/detail/back/settings/editor rendering passed; remaining state and layout coverage open |
| Collection | Card press/cancel/open, favorite selection, image loading/ready/failure/retry, scroll return, result placement | Open/return, scroll preservation and favorites passed; image failure/retry and visual acceptance open |
| Search | Field focus/clear/keyboard, search progress, results change, no results, cancellation of superseded queries | Search clearing and offline semantic matching passed; remaining states open |
| Card detail | Shared image/identity transition, finger-driven turn, interrupted settle, accessible turn, action rows, expandable source text | Two-sided identity, finger tracking, interrupted settle and rapid reversal passed; rendered shadow repair pending rebuild |
| Contact actions | Copy, phone/email/website/address actions, add-to-contacts confirmation, unavailable external handler | Pending integrated UI run |
| Photo management | Sheet opening/dismissal, side selection, add/replace/import/re-crop, failed image load | Core saved-side addition/re-crop passed; sheet and failure-state visual coverage open |
| Camera | Permission/denial/settings, close, focus, light toggle, shutter press/cancel/capture, error/retry, transition to crop | In-app open/close passed; physical capture and remaining hardware/error states open |
| Crop | Detection/proposal, preview/adjust switch, corner select/drag/release/nudge, invalid boundary feedback, reset, retake, accept/cancel | Three UI tests passed for busy controls/callbacks, drag/reset/confirmation and accessible adjustment; detector/warp core tests passed; intermediate-frame acceptance open |
| Paired capture | Front completion, add/import/replace back, front-only continuation, both-side readiness, manual entry, discard | Core both-crops-before-extraction/restoration and capture-review rendering passed; remaining interactions open |
| Reading | Actual front/back image stages, side turn, combined interpretation, progress changes, cancellation, failure/recovery | Source-correct front/back/joint stages and cancelled handoff passed; clipped heading repair pending rebuild; actual VLM evaluation failed under memory pressure |
| Editor | Field focus/keyboard/validation, grouped reflow, add/remove phone, source expansion, save/cancel/discard, next person | Invalid-save focus, three separate phones, field/IME semantics, CRUD and recreation passed; remaining states open |
| Save | Pending upload, confirmed image/record continuity, success receipt, failure/retry; never celebrate an unacknowledged save | Demo save/receipt and validation passed; full cloud UI failure/retry and motion acceptance open |
| Favorites | Toggle on/off, collection reflow, empty favorites, selection maintained on return | Explicit favorites and search-clear behavior passed; all intermediate/empty-state visuals not established |
| Authentication | Sign-in/register switch, field focus, password visibility, submit pending/error/success, reset action | Mode switching retains email; live auth pending/error/reset flows not established by this suite |
| Profile/settings | Profile edit/save, learning switch, correction-history dialog/clear, model download/progress/cancel, retry sync | Settings rendering and core private correction/learning/reset tests passed; remaining UI states open |
| Account actions | Verification/resend/refresh, sign-out, deletion confirmation/pending/failure | Pending integrated UI run |
| Dialogs/sheets | Opening and dismissal, backdrop/back action, focus placement, confirmation/cancellation and interruption | Pending integrated UI run |
| Empty/error states | First collection, no results, no image, setup, network/operation failure, retry and dismissal | Pending integrated UI run |

## Evidence standards

- A control wrapper is not complete coverage until the actual call site uses it.
- End-state screenshots do not demonstrate animation quality. Inspect intermediate frames and exercise interrupted gestures as well.
- Emulator checks establish tested behaviour and rendered layout; physical-phone frame pacing, thermal load and subjective motion quality remain separate measurements.
- Keep a single coordinated visual review and repair pass once the integrated implementation is ready; do not substitute repeated design descriptions for a rendered build.

## Continuity refinement, 1 October 2026

The first implementation passed compilation and JVM tests but did not establish the requested visual quality. Review identified these concrete gaps and prompted a second implementation pass:

- Reading now participates in the same shared-photo transition as capture review and editing. Local image identity follows the source path through recognition, which can change the contact ID. Outgoing scenes retain their own photo/phase snapshot and cannot cancel a subsequent operation.
- Navigation and add controls animate their appearance and occupied space. Local save/auth/profile progress no longer duplicates the global progress banner.
- Camera staging keeps the camera surface visible until the crop source is ready, with cancellation and error recovery. Crop acceptance keeps the current preview until preparation of the next screen finishes; failure retains the source for retry.
- Crop preview straightens the actual selected quadrilateral into a rectangle, keeping the source visible while the warp is prepared. Intermediate frames still require inspection.
- Interrupted card turns retain velocity; toggles no longer change translation direction mid-release. Field focus keeps entered text stationary, and rejected-save focus happens once per explicit attempt.

The earlier 2 GB emulator failed before instrumentation began and does not establish rendered correctness. A subsequent runtime-repair build passed 122 JVM tests, release lint, APK/test APK assembly and cloud bundle generation (`.tools/v033-runtime-repair-build.log`). The repaired UI suite then passed **20/20 tests** in 288.353 seconds (`.tools/v033-ui-device.log`), and the separate core suite passed **17/17 tests** in 21.857 seconds (`.tools/v033-core-light-device.log`). Table entries above describe that bounded coverage, not exhaustive acceptance of each row. Shared-control tests also verified cancelled presses do not commit, rapid presses commit once each, and animated toggles/fields retain editing and IME semantics.

The subsequent visual-language-model run was killed under Android LOW_MEMORY pressure; logcat records the app process killed at 09:34:22 with approximately 2.56 GB RSS (`.tools/v033-vision-crash-logcat.txt`). Actual image-model evaluation remains unresolved despite the successful lighter companion-learning checks.

Rendered inspection found a flip-shadow defect and clipped scanning heading. Their repairs compiled into the signed candidate; final frame inspection remains pending. Dark mode with 150% text and the tablet presentation checks each passed before those repairs. The candidate passed 130 JVM tests, release lint, signature/bundle/version and static native-alignment checks. Final Android confirmation is pending adequate host memory. Physical-camera behavior, intermediate-frame visual acceptance and physical-phone pacing remain open.
