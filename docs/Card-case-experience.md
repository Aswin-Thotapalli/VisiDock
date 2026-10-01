# VisiDock: card-case experience

## Material construction

A wide upright file of real cards sits inside a deep-blue case. The rear shell, recessed lining, individual card bodies, and front lip are separate layers. The lip occludes lower card edges. It must also intercept touches on the concealed area. The shell uses a fine matte grain, controlled edge highlights and a recessed accent groove. The interior uses a denser woven sample. Card bodies have thin paper edges; actual card photographs remain untouched. Texture samples are seeded, seamless, cached bitmap shaders: they never regenerate or shimmer during motion.

Deep-blue contact shadows anchor the case. The app background uses a much quieter paper grain beneath content; the shell and lining have stronger, distinct texture. Actual card photographs retain their original artwork. A static surface should look finished before any animation starts.

## Connected journeys

- Browse: drag the lazy card file; neighbouring cards change angle, height and scale continuously with the gesture. Accessible previous/next and open actions provide the same destinations. Preserve focused card identity across returning, favorite updates and list changes.
- Open and return: shared photograph bounds move along a lifted path between the case and detail. The card stays identifiable. The collection remains in its prior position on return.
- Save: only a persistence acknowledgement initiates the trip back into the case. Clear search, focus the saved card and show its actual photograph in its receiving slot. Failure keeps the editor intact. Multi-person review must keep the next draft available.
- Search and favorites: retain the same case construction while changing its contents; provide List mode for dense browsing. Do not decode an entire collection to render the case.
- Capture: detected corners open as the proposed straightened crop, then the front/back capture decision. Crop modifications affect the saved photo.
- Read: one front image under an optical scanning sweep, one physical flip, then one back image under the sweep. Combined interpretation retains the last side without fabricated OCR activity.
- Edit: the shared photo establishes the source; grouped editable panels unfold below it. No claims of flying from exact OCR coordinates until source-to-field coordinates are actually available. Fields remain interactive throughout arrival.

## Motion and material rules

Every transition must preserve object identity, origin, occlusion and interruption behavior. Press states support this larger choreography; they are not the definition of the system. Paper, matte shell and soft lining have distinct reflection and grain. Material detail is cached; per-frame changes belong to drawing or graphics layers. Geometry must work at narrow widths, large fonts and tablet sizes. System reduced-motion settings replace travel/turns with direct state changes.

## Acceptance gates

Runtime inspection must include the case at rest, midway through a swipe, opening/returning, save acknowledgement, search/favorite changes, large text, and reduced motion. Verify no hidden-card hit targets, duplicate photo transitions, lost focus, delayed save actions or extra full-collection decoding. Detector tests must include uneven/textured backgrounds and incomplete edges. Email tests must compare repeated differing model responses against identical OCR evidence.

This document specifies intended acceptance; implemented behavior and passing evidence are recorded separately in Verification.md. It is not a claim that every gate already passes.
