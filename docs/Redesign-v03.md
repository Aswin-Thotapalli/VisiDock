# VisiDock — living card redesign

Development design and implementation notes. Code present is distinguished from visual targets below; executed test results and release status are recorded in `docs/Verification.md`.

## Intent

A tactile personal collection, not a database form. The physical visiting card remains the visual anchor from camera to saved detail. Strong emerald, lime and deep blue identity, with readable white/light mode and equally deliberate dark mode. Motion has distinct scales: immediate control response, expressive card transitions, and a richer capture-to-collection sequence. Honor system disabled animations throughout.

## On-device intelligence — current implementation

- ML Kit reads each photograph and preserves original OCR plus normalized line positions separately for front and back.
- Gemma 4 E2B, running through LiteRT-LM, interprets the card images together with bounded OCR. It can propose multiple people, with separate review/save records linked to the source scan. Profile/account data is not part of extraction. Malformed responses are rejected; values not confirmed by OCR are flagged for review rather than silently presented as proven facts.
- A genuinely trainable personal companion uses frozen MiniLM sentence embeddings plus OCR layout and text features. Eligible corrections from a successful save automatically become encrypted local training examples. WorkManager runs bounded classifier training while idle and charging; held-out scan evidence and regression checks gate adoption. Its high-confidence labels accompany later visual inference as fallible hints. Gemma itself remains frozen.
- MiniLM also powers semantic search over saved cards. Search and learning are separate responsibilities even though they reuse the bundled encoder. Neither sends contact data to a model provider.

The earlier English BERT entity prototype is no longer the scan pipeline and its model assets are excluded from app packaging. Deterministic contact-field validation remains useful alongside visual interpretation; it does not determine visual field ownership. Preserve unmodified OCR per side, never inject a profile name, and avoid second-line company guesses. See `Visual-reading-and-learning.md` for the precise model, local training/privacy lifecycle, evidence thresholds and limitations.

Six fictional desktop image fixtures passed the initial Gemma evaluation. That does not establish physical-phone latency or accuracy on real photographs. Android learning/component tests and opt-in visual-inference results must be read from the verification record; merely adding a test is not a passing execution.

## Implemented surfaces — code status

The current code includes a profile-based home greeting, a replacement brand asset, image-led collection entries, grouped detail/review sections, shared image/identity transitions, press feedback and animated list placement. CameraX supplies in-app capture with a fixed framing guide and torch controls; gallery import remains available from the add flow. The guide does not perform edge detection or automatic cropping.

Front/back previews use a visible turn button, a 520 ms vertical-axis rotation, a midpoint face swap and counter-rotation. They **do not implement a finger-following swipe gesture**. Reading uses the current image and a sweep while work is active; successful persistence triggers a confirmation. Reduced animation behavior is implemented in dedicated card/button effects, with broader accessibility and frame-pacing acceptance still requiring verification.

This section describes implemented code, not a blanket assertion that every surface has passed visual, hardware or accessibility review. The choreography below is the design target; features mentioned there are not automatically shipped.

## Identity and copy — design direction

Mark: two offset, asymmetric card planes forming a V-shaped negative space and a short docking notch. The mark should work as a monochrome glyph and a launcher icon. Deep blue/emerald foundation, sharp lime used for capture and moments of confirmation.

First-use line: "Good meetings deserve a second chapter." Returning collection uses a user-entered display name, never a guessed email prefix. If missing, invite "What should we call you?" without blocking access to cards. Primary home title "Your next conversation." Supporting copy describes actual state, such as "12 people. Plenty to pick up on." No invented meeting dates, roles or relationship insights.

## Surfaces and choreography — design targets

- Auth: sculpted card mark, compact story, clear login, name collected on registration and editable in profile.
- Home: personal header, prominent search, image-led collection cards with name/company; pinned/favorites visibly distinct. Card opens from its own position. No perpetual decorative motion while reading.
- Capture: in-app camera, live framing corners, capture ring and brief tactile flash, gallery and torch; manual capture works regardless of detection. Never pretend the guide detects edges.
- Review: front/back card stage before editable details; optional back capture; animate flipping around vertical axis with correct face orientation and depth/shadow. The implemented turn button announces the selected side. Any future swipe gesture must retain this accessible button; gestures are not currently implemented.
- Reading: real captured image, scan sweep only while work is running, actual phase labels (reading / identifying details), no fake completion percentage. Keep editing available once work finishes.
- Save: distinguish saving from saved; only completion triggers confirmation and a card arrival transition. Preserve draft on errors; no success choreography before persistence succeeds.
- Detail: physical card first, then identity, direct contact actions and meeting notes; flip without losing scroll position. Edit details in meaningful groups instead of a wall of equal fields.
- Search: animated expansion, keyboard-aware layout, immediate lexical matches then semantic results, explain related matches honestly.
- Selection/favorites: brief scale/shape/tint response and optional haptic, stable list positions.
- Errors/offline/empty: intentional layouts and clear recovery, no looping celebration or blocking animation.
- Settings: account identity, verification state, appearance/motion/privacy/storage information in grouped sections.

## Motion acceptance — targets to verify

120–180ms control responses; 220–320ms navigation; 420–560ms card flips and capture continuity. Spring settling may be used for card arrival; no overshoot on text. Gesture-driven flips must follow the finger if implemented. Scale expensive shadows down on weaker devices. The reduced-motion target is stable views/crossfades. Do not claim comprehensive reduced-motion coverage from one disabled card animation. Measure frame timings on a physical device before claiming 60/120fps.

## Persistence and compatibility

Each card has a front original/preview/OCR and optional back original/preview/OCR. Preserve existing card IDs, account ownership, upload manifests and the Play signing key. Existing one-sided cards remain readable. Multiple contact values and extraction evidence need explicit schema validation and export mapping. No destructive migration of live cards.

## Confirmed palette constraint

No black in designed UI. Deep blue replaces black in backgrounds, text, overlays, shadows, capture controls and brand assets. Suggested base #071D49, elevated #102C60, text ink #10264B, light surface #F4F8FF. Camera/card photographs retain their natural colors.
