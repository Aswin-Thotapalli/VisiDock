# Local security verification

19 September 2026. Firestore and Storage emulators only, project `demo-visidock`.

The expanded suite in `tests/rules.test.mjs` passed 12 tests with zero failures. Captured command output: `.tools/rules-expanded.log`.

Verified rules reject another user's document reads, writes, deletion and collection queries; anonymous access; malformed and overlong fields; future creation times; unexpected fields; owner-path changes; creation-date mutation; document deletion without a deleting manifest; resurrection during deletion; invalid upload age; foreign/anonymous image access; unexpected object names/MIME types; and oversized uploads. Valid owner CRUD and image operations pass.

The app uses a durable upload/deletion manifest so image cleanup can resume. Stale uploads are identified separately from card creation time, and a deleting card cannot be set ready by an old client. Cleanup is client-driven: abandoned accounts can retain unfinished manifests/images until that user returns. A production janitor is a possible future operational addition.

Project visidock-thotapalli has been provisioned with email/password auth and Standard Firestore in asia-south1. Firestore rules deployment succeeded; live user isolation is not yet tested. Firebase Storage has been replaced by private Cloudflare R2. These tests are not a penetration test, account-recovery audit or production security certification. Authentication provider configuration, abuse controls, billing limits and live account isolation still need validation in the owner's project.


## R2 migration

Cloudflare R2 replaces Firebase Storage. The Firebase Storage tests above are historical and do not validate the new backend. Eleven Worker tests pass, covering cryptographic Firebase token verification, caller-derived paths, lifecycle authorization, bounded image bodies, format signatures and concurrent deletion cleanup. Wrangler deployment dry-run succeeds. R2 and the Worker are now deployed. Live API verification passed 36 checks across two temporary accounts, including unauthorized/cross-user rejection, image lifecycle and cleanup. Public r2.dev access is disabled and no public bucket domain is attached.
