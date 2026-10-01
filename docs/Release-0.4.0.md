# VisiDock 0.4.0 — card case

Package `com.thotapalli.visidock`, version code **6**, existing Google Play upload identity.

## Collection and materials

The default collection is a layered upright card case with a recessed well, foreground lip, shaped rim, matte shell grain, woven lining and paper edges. Actual card photographs remain unaltered. Swipe through cards or use accessible previous/next controls; open the focused card or switch to List for dense browsing. Search and favorites use the same collection modes.

The case retains card identity across content updates and return navigation, limits active image composition to neighbouring cards, and prevents taps through the opaque front lip. Controls wrap on narrow or large-text layouts. An empty collection retains the same case construction.

The card photograph follows shared lifted bounds between collection and detail. Confirmed saves return to the case with the saved card selected, while multi-person review retains the next person's draft. Failed saves remain editable. Field panels unfold beneath the reference photograph; the app does not fabricate exact OCR-to-field flight paths.

## Capture and recognition

- A directional-edge/quadrilateral detector supplements background segmentation for automatic corner proposals. Crop review shows the straightened result and retains manual adjustment.
- Scanning shows one front image with a luminous sweep, flips to the back, and continues on that face while organizing the combined details. No duplicate thumbnails or three-stage control bar.
- OCR retains approximately 2400-pixel detail, avoiding unnecessary 3000-to-1500 downsampling.
- Single-person extraction retains a uniquely grounded OCR email across differing model responses; conflicts are flagged, and uncertain multi-person addresses are not assigned arbitrarily.
- A source-grounding check removes a trailing address completion only when the remaining address matches a substantial printed OCR span; it preserves printed country and multiline address components and flags the removal for review.
- Memory admission protects against known undersized-device configurations and retains editable OCR fallback. This does not guarantee sufficient peak memory on every device.

## Validation and distribution

Validation is in progress. Host tests, Android physical-device results, rendered inspection and final artifact verification are recorded in `Verification.md`; this note is not a completion assertion.

Remote Android testing uses the user's existing no-billing Firebase project, disposable demo contacts and fictional scan fixtures. Production account credentials and user card photographs are not test fixtures. Local emulator retries have been replaced by Firebase Test Lab physical-device checks.

Signed application binaries remain local. GitHub source publication excludes client configuration, generated authentication code, API keys and signing material.
