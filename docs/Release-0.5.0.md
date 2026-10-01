# VisiDock 0.5.0 - physical interface preview

Package `com.thotapalli.visidock`, version code **8**, existing Google Play upload identity.

## Case, light and interaction

The collection uses parallel card leaves in a deeper leather case, replacing the rotated carousel. The shell has a cached relief texture lit from the upper left; its lining, rolled edge, seam, thread and foreground lip have separate construction. The personal inscription is drawn as recessed glyph tooling, with a readable minimum size and a long-name fallback. Photographs retain their original material detail.

Shared controls and card plates use pressure-dependent contact/cast shadows and material-specific release springs. The scan card has a separate projected shadow on its support plane. Collection/detail photo bounds use one critically damped spring, with competing parent slide/scale removed. This is an analytic physical visual and interaction model, not a rigid-body simulation or a declaration that every part of the app has reached final visual quality. `Physical-interface-contract.md` records the complete acceptance inventory and remaining work.

## Capture, scan and review

- The scan presentation always establishes the front, runs a four-second optical sweep, turns continuously, and sweeps the back. Slow OCR repeats the sweep; fast processing cannot skip a side. Actual recognized regions respond as the sweep reaches them.
- After OCR, the card lifts into a review handoff. Joint interpretation has its own honest waiting state. Finished extraction asks the user to check details and save; presentation never invents a completed extraction.
- Cropping compares edge/material proposals at two resolutions, rejects printed inner frames, refines the selected boundary at higher resolution, and preserves up to 3600 pixels in the corrected source. Confident proposals open straightened preview; uncertain proposals retain adjustment.
- Small or uncertain OCR lines trigger at most two bounded native-resolution regional rereads. Positions, OCR confidence, agreement and conflicting alternatives accompany the photographs and text into the same on-device model call. Conflicts remain reviewable; alternatives do not silently populate fields.
- Re-reading photographs now performs fresh OCR. Address cleanup preserves suffixes supported by detail OCR alternatives without using those alternatives to invent an address or assign it to a person.

## Verification

166 JVM test executions (83 per build variant), release lint, bundle validation, persistent upload signature, package/version and static native alignment checks passed. The remote ARM API35 suite passed 46/46 tests. After final control-only visual cleanup, all 5 targeted follow-up tests also passed. Actual native small-print OCR and strict crop fixtures passed; front/turn/back/review frames and final editor/collection renders were inspected. Full evidence and the exact scope of each run are in `Verification.md`.

## Limits and distribution

The new regional OCR fixture is a bounded check, not a broad accuracy benchmark. Entirely missed text may provide no region to reread. The model still uses a limited visual encoding budget; new evidence does not establish excellent accuracy or lower Galaxy S26 latency. Physical camera captures, difficult real-world backgrounds, every motion interruption, accessibility state and full app visual acceptance require further evidence. The prior model results apply to the previous build, not automatically to this modified prompt.

The signed AAB remains in the project's `artifacts` folder. GitHub publishes source only, excluding API keys, client configuration, generated authentication code, signing material and application binaries.
