# Cloudflare R2 image storage

VisiDock uses Firebase Authentication and Firestore for accounts/card records, and a private Cloudflare R2 Standard bucket for card originals and JPEG previews. OCR and semantic inference remain on-device.

## Request flow

The Android app obtains its Firebase ID token and sends it over HTTPS to the image Worker. The Worker verifies the token against Google's signing keys and the VisiDock project audience/issuer, then checks the caller's Firestore card manifest using that same token. It derives the R2 path from the verified UID; the app cannot select another owner's prefix. R2 credentials are never embedded in the APK. Public bucket access must remain disabled.

Routes: `/v1/cards/{cardId}/original` and `/v1/cards/{cardId}/preview.jpg`, with GET, PUT and DELETE. Uploads require an uploading manifest, reads require a ready manifest, and deletion requires a deleting manifest. The original upload limit is 20 MiB; previews are limited to 6 MiB. No object-list endpoint is provided.

## Provision and deploy

From `backend/r2`, install locked dependencies with `npm ci`, run `npm test`, and authenticate using Wrangler. Create the configured bucket with Standard storage and an `apac` location hint. Keep public access disabled. Deploy the Worker only after tests pass. Copy the resulting HTTPS Worker origin into the Android build:

```powershell
.\scripts\Build.ps1 -Cloud -ImageApiUrl 'https://YOUR-WORKER.YOUR-SUBDOMAIN.workers.dev'
```

The public endpoint can alternatively be set using Gradle property `visidock.imageApiUrl`. It is configuration, not a secret. Firebase client configuration remains `app/google-services.json`. A missing endpoint fails image operations clearly; manual text cards can still use Firestore.

R2 activation may require completing the account's subscription checkout. The owner controls payment information. Firebase Blaze is not required for this design. Do not deploy `storage.rules` as part of this architecture; those rules and their tests are retained as historical Firebase Storage checks only.

## Location and usage

Firestore records remain in Mumbai (`asia-south1`). R2's `apac` hint is best-effort placement, not a guarantee of India-only image storage. See https://developers.cloudflare.com/r2/reference/data-location/.

R2 Standard includes 10 GB-month storage, 1 million Class A operations and 10 million Class B operations per month. Internet egress is free. Excess usage is billable; these are account allowances, not per-user allocations. The image API also consumes Workers requests/CPU and Firestore reads. Worker Free has its own limits, so R2 allowances are not the app's only quotas. See https://developers.cloudflare.com/r2/pricing/ and https://developers.cloudflare.com/workers/platform/pricing/.

## Verification and operational limits

Local Worker tests and Android build checks must pass before live deployment. Live verification requires an authenticated Cloudflare account with R2 activated, then two synthetic Firebase users to test upload/read/delete and cross-user denial. Do not use private contacts for initial verification.

R2 and Firestore do not share an atomic transaction. Durable uploading/deleting manifests support retries, but cleanup depends on the user reopening the app. Abandoned accounts can leave orphan objects; a scheduled reconciliation job is future operational work. Original files are preserved, so their sizes dominate storage consumption.

## Current deployment — 20 September 2026 (India)

- Account: ec35c180c9004b49bf8a28e0146c45f9.
- Bucket: visidock-card-images; Standard class, apac location hint.
- Public r2.dev access disabled; no custom bucket domains.
- Worker: https://visidock-private-images.aswin-ec3.workers.dev
- Version: 7566fbcb-2e84-455c-8842-d9d05ed860f9.
- Android endpoint persisted in gradle.properties.
- Live API test: 36 passed, zero failures, test accounts/images/documents cleaned up. Output: .tools/r2-live-smoke.log.
