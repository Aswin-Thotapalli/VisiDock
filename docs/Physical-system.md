# VisiDock physical interface

## Intent and acceptance

VisiDock should feel like handling a personal card case: material has thickness, cards retain their identity, and a gesture causes a comprehensible movement. This requires visual construction, continuous motion, and state transitions together. A textured background or animated button alone does not satisfy that requirement.

The implementation described here is a designed physical metaphor. It is not a rigid-body simulation, a photorealistic renderer, or a claim that every atomic part of the app is complete. Completion requires inspecting the integrated journeys on a device with animations enabled, including interruptions and accessibility settings.

## Common construction rules

- Light originates above and to the left. Raised upper-left edges catch it; cast shadows move down and right. All shadow colors are navy, never native black elevation.
- A contact shadow locates an object against its supporting surface. A broader cast shadow conveys height. Pressing reduces the air gap, offset and spread.
- Leather compresses more than paper or metal. The glyphs of the personal inscription are pressed into the leather: a dark upper cavity, reflected lower edge, and textured depressed face. They are not bright printed text.
- The case has a painted layered edge, a rolled opening, a recessed lining, and thread in a stitched channel. These elements share the light direction and scale.
- Actual card photographs are not covered by leather grain or altered for OCR. Material treatment belongs to their edge and supporting case.
- Neighboring cards stay parallel. Their small depth offsets and scale changes describe a stack; they do not turn away like carousel panels.

## Shared code and parameters

`PhysicalScene.kt` exposes:

```kotlin
Modifier.physicalSurface(
    depthDp = 4f,
    pressedFraction = { pressure.value },
    shape = RoundedCornerShape(16.dp),
    material = PhysicalMaterial.Leather,
)
```

There is also a Float pressure overload for static callers. The provider is read during drawing, so an animated pressure value does not itself require recomposition or rebuilding the shape outline. Shape outlines and lighting brushes are cached. Pressure-dependent drawing uses cached native paths and Paint shadow layers on API 28+, with the object interior excluded from shadow drawing. API 26/27 use a dense low-alpha contour fallback. The earlier five-contour approximation showed visible rings in remote screenshots and was replaced. Hardware support follows [Android drawing-operation documentation](https://developer.android.com/develop/ui/views/graphics/hardware-accel). This is a designed penumbra, not ray tracing.

The toolkit does not change input semantics, callbacks, hit targets, or accessibility. `PhysicalMotion.surface` also returns a compression scale for an owning interaction layer to apply; `physicalSurface` itself draws the shadow and bevel rather than scaling content.

| Material | Normalized mass | Stiffness | Damping ratio | Maximum compression |
| --- | ---: | ---: | ---: | ---: |
| Leather | 1.25 | 360 | 0.88 | 3.5% |
| Paper | 0.72 | 430 | 0.82 | 1.2% |
| Metal | 1.80 | 720 | 0.94 | 0.4% |

These are art-directed relative values, not measured kilograms or laboratory material constants. `PhysicalMotion.settle` supplies Compose's unit-mass spring with `stiffness / normalizedMass`, preserving the intended natural frequency proportional to `sqrt(k/m)`. Damping controls overshoot independently. Gesture input remains direct; a spring is used for release or state settling, not to make the object lag behind the finger.

For pressure `p` clamped to 0–1 and nonnegative resting depth `d`:

- height = `d * (1 - 0.82 * p)`;
- shadow offset = `0.65 * height`;
- shadow spread = `0.8 + 0.72 * height`;
- content scale = `1 - material.compression * p`.

For rotation, coordinate systems matter. A modifier inside a rotating graphics layer rotates its shadow as well. That is inappropriate for a cast shadow on a stationary supporting plane. Both overloads accept `castShadow` and `drawBevel` flags, defaulting to true. Flipping-card scenes use `drawBevel = false` on a stationary outer layer and `castShadow = false` on the rotating card layer. Parallel cards and controls can use the combined default. The integration still owns positioning and silhouette of the supporting-plane shadow.

## Texture and inscription

`MaterialTexture.kt` caches deterministic, periodic bitmap shaders. The shell is a Voronoi pebble height field with narrow valleys and small pore variation. Local height derivatives produce upper-left illumination; it is not independent random brightness per pixel. The lining and paper use separate samples. Shader samples are generated once, not per animation frame.

`CardCase` and `EmptyCardCase` accept `ownerName`. An empty value yields “VisiDock”; a name yields a possessive inscription such as “Aswin’s VisiDock”. Native glyph outlines are measured to fit and cached with their bounds. Visible lettering never shrinks below 12sp; long names use a first-name fallback and then ellipsis, while accessibility retains the complete inscription. Three relief passes and the leather shader create the recessed lettering. The complete inscription remains available to accessibility services.

## Card browsing and identity

The case retains the tested pager's stable card keys, settled selection, explicit external-focus reconciliation, and supersession of an older browse request. Only the focused card and its immediate neighbors invoke the image composable, bounding photo decoding to three.

Logical page spacing remains zero. Foundation 1.7.6 counts trailing page spacing when calculating its maximum scroll offset; negative spacing made the final card unreachable. Visual overlap is therefore achieved through translation of parallel card layers, not negative pager spacing. Case-shell hit testing prevents taps from passing through the opaque lip into hidden cards.

## Coverage and remaining work

| Area | Source implementation | Remaining acceptance |
| --- | --- | --- |
| Case shell, lining, rim, seam and thread | Dimensional material construction and cached relief texture | Device inspection at light/dark theme and multiple widths |
| Personal inscription | Recessed glyph construction and accessible full name | Legibility of long names and restrained contrast in actual render |
| Card stack | Parallel depth offsets, stable selection, bounded image decoding | Finger tracking, overlap ordering and end-of-stack gestures on device |
| Physical surface primitive | Pressure-aware contact/cast shadow and bevel, draw-phase provider | Shared controls, card rows and reading support use the primitive; native field editing remains intact. Field-container-only depth is still required |
| Material response | Mass-adjusted spring presets and bounded pressure model | Consistent use by each interaction owner; presets alone do not animate the app |
| Scanning | Four-second sweep per side, ordered front-to-back turn, actual OCR regions, projected turn shadow and review handoff | Integrated cast-shadow placement, handoff and animation-enabled visual review |
| Save/open/return journeys | Existing shared-image and collection work is a separate integration | Confirm object continuity, actual save acknowledgement, interruption and reversal |
| Forms, menus, dialogs, account/settings and navigation | Existing motion wrappers provide a foundation | Full visual/material/motion audit; not declared complete by this document |

Static content need not move continuously. Every actionable or changing unit does need an intentional material treatment, response, and relationship to the scene. Filling a checklist of wrappers is not evidence that all those experiences work together.

## Verification

`PhysicalSceneTest` checks bounded, monotonic pressure behavior, relative compliance, symmetric stack depth, and possessive-name formatting. `CardCasePresentationTest` retains selection/favorite/deletion/return and narrow-layout/image-budget checks and asserts the personalized inscription's accessibility semantics.

These checks establish behavioral contracts, not visual quality. This document records source coverage; it does not claim a fresh device run passed. The integration owner must record build and device outcomes in the release verification record after running them. Reduced motion should retain meaning and direct manipulation while suppressing decorative movement; ordinary motion must also be inspected with Android animations enabled.

Final image review removed the physical shadow from the outer text-field wrapper because that wrapper includes helper/error text. Native label/focus/error animation remains. A future field depth treatment must belong to the actual decoration container. Control bevels around minimum touch bounds were also removed; cards and the case retain material bevels.
