# Account verification and image reliability — 30 September 2026

## Confirmed live evidence

Firebase project `visidock-thotapalli` uses the default `https://visidock-thotapalli.firebaseapp.com/__/auth/action` callback. Its default verification email template contains the generated `%LINK%` directly. No incorrect mode or template encoding was found.

The authenticated `backend/auth/verify-live.cjs` probe created a disposable account and generated links with `returnOobLink: true`, which does **not send email**. Generated verification links include `mode=verifyEmail`, an API key and an action code. Firebase SDK `checkActionCode` accepted the verification link, applying the code marked the synthetic account verified, and used/malformed codes were rejected. A generated password-reset action also passed inspection and reset the synthetic password. The account was deleted afterward. Real users and their links were not inspected or changed. A genuinely expired code was not generated; expired-error presentation is covered locally.

The reported “selected page mode invalid” error was **not reproduced**. Its actual cause remains unknown without the affected link's non-secret structure or browser error. Do not claim it was caused by Firebase configuration or claim it is fixed.

Follow-up inspection of the project's live `/__/auth/action.js` identifies exactly when that message is displayed: the Firebase handler lacks a recognized action handler or a nonempty `mode`, `apiKey`, or `oobCode` query parameter. This happens before action-code validation. It is therefore an incomplete/unsupported-link condition, not that handler's expired-code condition. The affected user's original link has not been inspected, so which parameter was lost remains unknown. A fresh generated link's API key was compared in memory with the Android key and matched; the live SDK probe now uses the email link's actual key.

## Branded handler and provider restriction

A deep-blue (`#071D49`) and lime handler is deployed at [VisiDock account actions](https://visidock-thotapalli.web.app/auth). It supports email verification, password reset and email recovery, validates Firebase's action type, explains incomplete/expired/used links, and removes the code from browser history. No third-party fonts, analytics, automatic redirects or action-code logging are included. CSP and no-referrer/no-store headers are deployed. A missing mode can be recovered from Firebase's verified action type; unsupported/mismatched modes fail closed.

Hosted HTML/JS/CSS each return HTTP 200 with correct content types. Three action-handler test groups pass, covering supported operations, missing/malformed/duplicate modes/codes, mismatch rejection and safe errors. Actual SDK verification/reset flows pass against the live backend. Email recovery is covered by local operation dispatch tests but not a live recovery email, because generating one would require an email-address change notification. Browser rendering could not be inspected: no browser surface was available through the computer-use tool.

Firebase rejected the targeted callback update with **HTTP 400 `EMAIL_TEMPLATE_UPDATE_NOT_ALLOWED`**. Consequently, the default callback remains active. The custom handler is deployed but is **not yet the destination of newly sent email links**. Existing default links remain supported. This restriction requires resolution through Firebase project configuration/support before switching the action URL; no security settings were weakened to work around it.

The custom page now also provides an independently usable recovery path: open `/auth`, paste the full link from the latest email, and confirm the Firebase-validated action. It repairs copied HTML ampersand entities and derives a missing mode from Firebase's verified action type. Only this project's two Firebase Hosting domains and recognized action paths are accepted. It never follows an arbitrary pasted URL, uses the fixed project configuration rather than a pasted API key, clears pasted content immediately, and does not write the link to browser history/storage/logs. A synthetic escaped verification link passed this recovery parser, Firebase `checkActionCode`, and verification. Four local handler test groups pass. This route does not repair missing action codes or deliver unsent email.

Recovery deployment verification: hosted `/auth` HTML and `/app.js` each return 200, exactly match local SHA-256 hashes, and carry `Referrer-Policy: no-referrer`. All **eight** parser/DOM tests pass, including paste-to-verification, mismatched/reset passwords, expired links and explicit email-recovery confirmation. The DOM tests use real parsed markup with a mocked Firebase SDK; live SDK verification/reset tests are separate. `createBrowserTab('iab', ...)` explicitly returned `Browser is not available: iab`, so no rendered-browser claim is made.

An actionable Firebase support draft is in `backend/auth/Support-request.md`; it has not been sent. The project reports subtype `IDENTITY_PLATFORM` and email provider `DEFAULT`. The provider restriction is distinct from the malformed-link message and the user's unknown resend exception.

## Resend behavior

`CloudRepository.verifyEmail` now reloads the user and refreshes the ID token before resending. Already-verified accounts return without another message. Network, throttling and stale/disabled-account failures now receive specific messages, with the original exception retained as the cause. Unknown Firebase auth failures include their safe error code for diagnosis. The user's actual resend exception has not yet been obtained; forced-refresh handling does not prove that specific failure is fixed. No real verification email was sent during investigation.

## Storage/retry verification

The backwards-compatible front/back image Worker and Firestore rules are deployed. Worker version: `67d90abd-d950-4fb6-b164-26aa603f843d`. Twelve Worker tests pass. Thirteen emulator rule tests pass using temporary alternate ports because the configured port was occupied. The enhanced live smoke harness passed **60 checks**, including all four image objects, byte-identical reads, cross-account denial, anonymous denial, deleting-state enforcement, manifest deletion and account cleanup. No synthetic data was left by successful runs.

Repository save retries now consult server state, recognize an already-committed ready write from the same scan, resume matching uploading manifests, and preserve uncertain uploads instead of deleting possibly saved cards. Deletion uses paths from the server manifest so back images are not lost from cleanup. Kotlin build/device verification is handled separately by the main task.

The latest rule deployment also permits a bounded company-only contact without inventing a person's name, while rejecting empty name and company together. All **14** emulator rules tests pass, including the company-only creation/edit validation.

Sources: [Firebase custom handlers](https://firebase.google.com/docs/auth/custom-email-handler), [Identity Platform action-link API](https://docs.cloud.google.com/identity-platform/docs/reference/rest/v1/accounts/sendOobCode), [Identity Platform configuration](https://docs.cloud.google.com/identity-platform/docs/reference/rest/v2/Config).
