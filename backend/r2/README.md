# VisiDock private card images

Cloudflare Worker for a private R2 bucket. Firebase Authentication and Firestore remain in project `visidock-thotapalli`. No Firebase admin credential or R2 API secret is embedded in Android. This directory is an independently deployable backend; it is deployed at `https://visidock-private-images.aswin-ec3.workers.dev` with the private `visidock-card-images` bucket.

## Contract

`GET`, `PUT`, `DELETE /v1/cards/{cardId}/{original|preview.jpg}` require `Authorization: Bearer <Firebase ID token>`. Keys always derive from the verified UID. Firestore's matching owner document must reference the exact object path. GET requires `ready`; PUT requires `uploading`; DELETE requires `deleting`. Successful mutations return 204. Errors are JSON with a safe `error` string. Reads never expose a public bucket URL and use `private, no-store`.

Preview: JPEG, up to 6 MiB. Original: JPEG, PNG or WebP, up to 20 MiB. The Worker bounds actual body bytes, checks MIME and file signatures, and rejects empty data. Signatures are a format sanity check, not a complete image decoder.

## Local verification and eventual deployment

Run `npm ci`, `npm test`, and `npm run check` here. `check` bundles with Wrangler without publishing. For deployment, authenticate Cloudflare, create the private `visidock-card-images` bucket, verify the project/bucket values in `wrangler.jsonc`, then run `npm run deploy`. Keep R2 public access and custom public bucket domains disabled. Configure Android with the resulting HTTPS Worker origin. Never put Firebase ID tokens in URLs or logs.

Firebase ID tokens are checked with Google's remote JWKS, RS256, issuer, audience, expiry, issued time, authentication time and a path-safe subject. Every request then reads the owner's Firestore document using the same bearer token, so Firestore rules remain authoritative. No CORS access is added; Android does not need browser CORS.

## Operational limits

Both front and back support immutable revisions: `original-r<32 lowercase hex>`, `preview-r<32 lowercase hex>.jpg`, and the corresponding `back-` names. Legacy names remain readable. Replacing a side preserves the other side and stores the exact acknowledged old record as `previousRecord` while the new revision uploads. Old images remain readable during upload; PUT cannot overwrite a retained image. After the new record becomes ready, obsolete revisions can be deleted and the previous manifest removed. Stale recovery transactionally claims `rollingBack`, which blocks upload completion, before deleting only new revisions and restoring the exact old record. Deletion transactionally claims the current record before removing referenced revisions.

Deploy both `firestore.rules` and this Worker before distributing a client that writes revisions. Local preview caches are private, bounded, account-scoped and keyed by immutable revision; sign-out clears them. Previously cropped originals cannot recover pixels discarded by an earlier crop. The current local Worker suite includes 16 passing tests, including revision retention, rollback access control and ambiguous upload response handling.

JWT validation does not independently check Firebase token revocation; short-lived token validity and Firestore's current authorization apply. R2 and Firestore cannot share an atomic transaction. Manifest checks before and after the R2 write catch deletion races. A confirmed lifecycle change that no longer retains the image permits compensation; an unavailable or malformed post-write manifest preserves the uploaded revision for retry and durable cleanup, because another retry may already have committed it. A revision retained by a newer replacement is also preserved. If compensating deletion fails, an orphan can remain. The client should serialize operations per card and keep failed cleanup manifests; a future reconciler can remove stale orphan objects. Per-user quotas and rate limiting should be configured before broad public release. Do not log bearer tokens, request bodies, or contact details.

Verified locally: 11 tests pass, including actual RSA JWT signing/verification, owner-path isolation, manifest lifecycle gates, byte limits (including false Content-Length), file signatures, post-write cleanup and safe errors. `wrangler deploy --dry-run` also succeeds. These checks do not constitute a live Cloudflare/Firebase integration test.

References: [Firebase ID token verification](https://firebase.google.com/docs/auth/admin/verify-id-tokens), [R2 Worker API](https://developers.cloudflare.com/r2/api/workers/workers-api-reference/).

## Explicit live smoke test

On 30 September 2026, the extended live harness passed 92 checks against the deployed revision backend with zero failures. It verified two temporary accounts, both sides, front replacement with old-image read access and overwrite protection, unchanged back images, byte-identical revision reads, isolation, removal of both revisions and final account cleanup. No credentials were logged.

`test/live-smoke.mjs` is excluded from `npm test`. After deployment, set `IMAGE_API_URL` to the HTTPS Worker origin and `FIREBASE_WEB_API_KEY` to the Android project's Firebase web API key, then run `node test/live-smoke.mjs` explicitly. This creates two temporary password accounts with unique `example.invalid` addresses (no verification emails), tests original/preview upload, byte-identical reads, user isolation and deletion, then attempts document/object/account cleanup in `finally`. Output contains only operation labels and statuses, never credentials. Cleanup failures cause a nonzero exit; unsuccessful object cleanup retains its deleting manifest for administrator recovery. Account deletion is still attempted, so an administrator must recover residual data when cleanup fails. The harness has not been run live.

Live API verification passed 36 checks with two temporary Firebase accounts on 20 September 2026 (India time), including upload/read/delete, cross-user denial, missing-token rejection, byte preservation and final account cleanup. See 	est/live-smoke.mjs; it only runs explicitly with IMAGE_API_URL and FIREBASE_WEB_API_KEY environment variables. Do not log either account tokens or passwords.
