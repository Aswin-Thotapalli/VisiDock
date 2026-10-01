# VisiDock design system

## Direction

The physical visiting card remains the visual anchor from capture to collection. The owner approved an app-wide motion pass, two-sided capture before recognition, editable saved photographs, and deep blue wherever the interface would otherwise be black. This replaces the earlier provisional green theme. The interface is an Android productivity tool: immediate controls and readable details, with expressive moments concentrated on handling the card itself.

The collection starts with a solid deep-blue identity panel, the signed-in person's card collection, search, and large actual card photographs. Card content supplies the visual variety. No marketing slogans, generic dashboard metrics or decorative loading delay.

## Tokens

Material roles live in `VisiDockTheme.kt`.

| Role | Light | Dark |
| --- | --- | --- |
| Primary | #2455CE | #A9C5FF |
| On primary | #FFFFFF | #102C60 |
| Primary container | #DCE7FF | #244994 |
| Canvas | #F4F8FF | #071D49 |
| Surface | #FFFFFF | #071D49 |
| Container | #EAF1FC | #102C60 |
| Main ink | #10264B | #F1F6FF |
| Secondary ink | #486080 | #B7C9E7 |
| Camera/crop accent | #99DDEB on navy | #99DDEB on navy |

Identity panels use #102C60, #F4F8FF text and #B7C9E7 secondary text. Camera masks use #071D49. Card photographs retain their own colors. Native Android typography follows Material roles and user font scaling: headline 32/38 and 28/34 sp, title 22/28 and 17/24 sp, body 16/24 and 14/21 sp.

## Layout and states

- Phone gutter 24 dp; 8/12/16/20/24/32 dp spacing. Corners 8/12/16 dp; collection identity panel has a 24 dp bottom edge.
- Native 48 dp minimum controls. Material navigation bar on compact widths, rail at 600 dp, collection/detail split at 840 dp. Forms/settings cap at 720 dp; capture review at 640 dp.
- Collection photographs have a 190 dp preview stage, accessible retry and explicit two-side marker. Names and roles sit directly below their own image.
- Camera supports tap-to-focus, flash, framing guide, shutter acknowledgement, permission recovery and concise front/back context. The guide is not live edge-detection feedback.
- Boundary detection proposes a perspective-corrected crop. The crop page opens on the cropped preview when detection is confident; otherwise it exposes the full photo and adjustment controls. Corners support dragging and accessible nudge controls. Detection never silently commits a crop.
- Front crop leads to capture review: back camera/import or explicit continue-with-front. No recognition occurs before this decision. Both sides belong to one capture session.
- Scan progress follows real front OCR, back OCR and joint interpretation. The card flips to its actual reverse; joint interpretation shows both side thumbnails. No invented completion percentage.
- Review keeps the photograph beside grouped identity, phones, email, website, address and notes. Phone fields reflow on add/remove. Saving returns the same card to its collection/detail transition.
- Saved cards have a photo-management sheet with front/back recrop and replacement/add-back. Image changes preserve contact details until the user saves.
- Settings starts with an account panel and separates visual reading, correction history and privacy. History displays saved before/after changes separately from eligible learning labels.
- Auth shares the deep-blue identity panel, legible fields and animated registration name field. Errors, downloads, empty states, retries and destructive confirmation remain native actionable components.

## Motion grammar

`DockMotion` centralizes duration gating, cubic easing (0.2, 0, 0, 1), and purpose-specific physical springs. The owner's stronger motion brief is implemented as direct card manipulation rather than a longer sequence of canned transitions. OS animator scaling is respected; Remove animations removes the spatial turn and completes its state change immediately. No animation must finish before the next control can be pressed.

| Interaction | Behavior |
| --- | --- |
| Primary action / action row | 90 ms compression; spring release (stiffness 520–560, damping .76–.78); primary action haptic |
| Navigation | 320 ms directional arrival, 180 ms departure, faster opacity exit |
| Card open/save | Shared actual photo bounds, spring stiffness 420 / damping .92; side-specific identity prevents a back photo morphing into a front thumbnail |
| List/search mutation | 280 ms placement, short opacity; no stagger queue |
| Favorite | 220 ms icon rotation/scale and light haptic |
| Form rows / registration | 220 ms reflow/expand; inputs remain usable |
| Front/back | Direct finger tracking in either horizontal direction; 120 ms velocity projection chooses the adjacent face; release spring stiffness 290 / damping .82; grab interrupts and tap retargets from the current pose |
| Camera | Stream-based arrival, actual focus-result reticle, mechanical shutter pressure and capture-frame contraction |
| Reading | Bounded scan light during active OCR, actual side transition, explicit joint phase |
| Successful save | Shared image continuity, haptic, then actual saved-card thumbnail docks into a receipt with a drawn check; only after persistence succeeds |
| Sheets, switches, fields, snackbars | Role-specific pressure, focus, selection and arrival responses; native semantics and interruptible platform dismissal remain intact |

## Whole-app interaction sequences

Every interactive call site in `VaultScreen.kt` uses a category-specific motion primitive. This is the feedback layer; the surrounding state sequence is designed separately:

| Flow | Touch, state change, outcome and continuity |
| --- | --- |
| Navigation | Selected icon settles into its destination; collection/favorites move along their actual ordering; detail/edit return in the reverse direction; each collection retains its scroll position. |
| Search | Field takes focus, clear action responds immediately, actual on-device work reveals a thin activity line, result count changes, matching cards change position, and empty/results states replace each other without a false delay. |
| Open/favorite a card | Card pressure releases into the shared photo transition; favorite responds through its own icon change only when data updates, and removal from Favorites collapses that actual item. |
| Image delivery/retry | Existing cached photographs appear immediately. A missing image moves from retry to actual loading to its photograph; front/back swaps at the physical edge, without crossfading the faces together. |
| Add/remove phone | A new retained row unfolds, takes keyboard focus and comes into view. Removal keeps the departing row until its collapse finishes, moves following fields with it and removes its accessibility actions immediately. |
| Form save/validation | Save stays fixed above the keyboard; actual saving shows progress in that action. A rejected email/website expands its own explanation and brings the first invalid field into view. Correcting it removes stale validation; successful persistence owns the card receipt. |
| Auth mode | Identity panel stays anchored. Name/help fields reshape the same form; the heading changes direction with the mode; email survives. Submission dismisses the keyboard and shows progress tied to the real authentication operation. |
| Profile/settings | The saved profile name changes after acknowledgement. The learning switch changes its explanation with its state. Download controls become installed confirmation only after readiness changes. History opens as one dialog unit, displays actual pending work, then transitions between records and its empty state. |
| Sheets/dialogs | Sheet content enters as one group, with native drag, back, outside-tap and interruptible dismissal. Confirmation dialogs move as a complete surface, preserving native focus and action semantics. Destructive effects wait for the repository; failures retain the card and reveal recovery. |
| Empty/loading/error | Loading skeleton movement only exists during collection loading. Empty states lead to the relevant real action. Operation errors expand into layout, announce their message and collapse on dismissal rather than overlaying a button invisibly. |
| Disclosures | Recognized text expands from its own control, the directional chevron follows that state, and surrounding content moves with the expansion. |

Primary controls sink and recover depth, outlined controls tighten their boundary, text actions remain spatially steady with an underline response, icons compress around their center, toggles rock while the native thumb travels, and navigation lifts the selected icon inside a stationary target. These distinct roles avoid applying one spring bounce indiscriminately. Status labels have no fake click action. Photo shadows are disabled on API 26/27, which cannot tint platform shadows navy.

Keyboard/insets, system Back, native sheet dismissal and external apps retain their Android behavior; their surrounding VisiDock content is coordinated rather than replacing platform interaction. Physical-device frame pacing, dark/large-type layouts and actual account/download outcomes still require runtime evidence. No static coverage inventory proves a subjective quality grade.

## Performance and verification

The flip's pitch, slight lift and reflected light follow the actual angle. The photograph changes at the edge-on point, so text never mirrors. Short/cancelled drags settle to the nearest face; fast releases use measured velocity, capped at one adjacent face. The accessible turn button and custom accessibility action perform the same operation. There is no autonomous flip loop.

Decoded previews have a bounded 20 MiB account-and-image-path cache. Repository caching handles persistent bytes. New image paths invalidate prior renders; failed images remain retryable. Local previews decode at a bounded edge off the main thread. Bitmap producers are keyed by source identity. Frame-by-frame angles are read in graphics layers and scan-light positions in Canvas; composable content only changes when the visible face crosses its threshold. The scan light stops during joint model interpretation.

`AppPresentationTest` captures Android collection, front/back detail, photo actions, settings, editor and pre-read review into app-private `ui-review/`. `CardMotionTest` uses the Compose clock to check a partial finger-held turn, complete flip, cancelled short drag and interrupted/reversed tap, saving front/mid/back Android frames. `CardTurnPhysicsTest` checks release distance, velocity, direction and bounds. `CropPresentationTest` checks adjustment of the detected proposal and captures the crop screen. These are evidence only after successful execution. Screenshots cannot prove gesture smoothness, frame rate or physical-device inference speed. Verify dark theme, 1.3× type, compact height and reduced motion in the bounded release review.
