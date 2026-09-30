# VisiDock design system

## Direction

A person checks a new connection on their phone while moving between conversations in a bright conference venue. Readable white surfaces, deep green actions and quiet neutral rows make the person and card easy to recognize. A matching near-black scheme supports evening use. The palette is provisional; the owner requested modern, polished motion without prescribing a color.

## Color and type

Material roles are centralized in `VisiDockTheme.kt`. Primary light: #245B40; primary container: #D7EFDF; foreground: #1B2420; secondary text: #536158; background: white. Dark primary: #A4D7B5; background: #111412; text: #E4EAE5. Green seed corresponds approximately to OKLCH hue 145; native rendering uses sRGB Material roles.

System sans-serif supports familiar Android rendering and scaling. Headline 32/38 sp, title 22/28 and 17/24 sp, body 16/24 and 14/21 sp. Use semantic Material type roles throughout. Fields retain visible labels. Card names can wrap; supporting list context truncates, with full text on detail.

## Layout and components

- 24 dp phone content gutter. Main spacing rhythm: 8, 12, 16, 20, 24, 32 dp.
- Compact collection: top identity, heading, search, flat contact rows, one add-card FAB, three-destination navigation bar.
- At 600 dp: navigation rail. At 840 dp: collection/detail split. Editor and settings remain independent screens, with a 720 dp content-width limit.
- Touch targets use native Material 48 dp behavior. Rounded rectangles: 8/12/16 dp; avatars are circular.
- Action hierarchy: filled primary, outlined secondary, text tertiary/destructive.
- Add-card sheet offers camera, import and manual entry. Review always precedes save.
- Details prioritize identity, photo, explicit contact export, contact fields and contextual notes.
- Loading, image retry, empty collection, empty search, validation, duplicate warning and operation errors each have visible UI.

## Motion contract

| Unit | Behavior | Timing / purpose |
| --- | --- | --- |
| Primary button | Press to 0.97 scale; interruption-safe return | 120 ms; tactile acknowledgement |
| Navigation screen | Shared-direction slide plus fade | 260 ms enter, 180 ms exit, 110 ms exit fade |
| Person identity | Shared avatar bounds from row to detail | 300 ms; maintains visual continuity |
| List mutation | Placement adjustment plus item fade | 220 ms placement, 160/100 ms opacity |
| Bottom sheet | Native Material drag and dismiss | Material defaults; follows the finger |
| Fields, tabs, favorites | Material focus/ripple/selection feedback | Native state semantics |
| Busy status | Progress and short fade | 120/90 ms; explains pending work |
| Expanded text | Native animated visibility | Reveals secondary information |
| Snackbar | Native Material transition | Confirms completed action |

Compose animation duration scaling follows the OS animator-duration setting; custom button pressure explicitly checks whether animations are enabled. Do not delay access to content for decorative entrances. Avoid background loops when no work is happening.

## Verification standard

Check compact phone, smaller width, landscape/tablet split, dark mode, large font and removed animations. At shorter heights or larger fonts, the collection heading becomes compact and the subtitle is omitted; search placeholders stay on one line. Test actual card creation/edit/delete and model inference. A static screenshot proves layout only; it does not prove animation smoothness or accessibility. Keep remaining results and limitations in `docs/Verification.md`.
