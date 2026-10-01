# Approved collection and physical-interface expansion

User approval: 2026-10-01. This is an implementation and acceptance ledger, not a completion claim.

Follow-up reminders are explicitly excluded. My Card supports scanning/importing an existing physical card and manual creation, with selective sharing.

## Required acceptance areas

- [ ] Source-grounded assignment, field-local uncertainty, reconciliation and protected user edits
- [ ] Shared visible control geometry, invisible accessible touch targets
- [ ] Cohesive material, light, depth, texture and interruptible motion across all routes
- [ ] Continuous case, search, camera, crop, scan, review and save journeys
- [ ] Repeated phone, email and website fields; multiple-person ownership
- [ ] Explicit duplicate review and merge, version history, undo and recovery bin
- [ ] Account-isolated offline records, photos, durable outbox and conflict resolution
- [ ] Durable draft recovery
- [ ] Encrypted portable backup and validated restore
- [ ] Selective vCard, CSV and image export
- [ ] Biometric lock, protected recents and notification privacy
- [ ] Meeting context, dated notes, tags, collections and bulk organization
- [ ] Batch capture and paired-side review queue
- [ ] QR contact reading and explicit URL preview
- [ ] Android Share image/PDF import and document region selection
- [ ] Separate crops for multiple physical cards in one image
- [ ] Mixed-script recognition, preserved originals and optional transliteration
- [ ] Organization relationships preserving person and branch distinctions
- [ ] Selective sharing without private notes by default
- [ ] Android shortcuts and privacy-aware widget
- [ ] My Card scan/import/create and contact/photo/QR sharing
- [ ] Recognition regression corpus and release gate
- [ ] Local-learning evaluation and rollback safeguards
- [ ] Adaptive processing and private opt-in diagnostics

Each item requires a usable entry point, persistence where applicable, failure/recovery handling and appropriate verification. A helper class or specification alone does not complete an item. Tests do not replace visual inspection or representative phone performance measurements.

## Integration evidence — October 1

- Local regression suite: 193 tests passed in both demo and cloud release, including omitted-channel ownership, compact scalar reconstruction and shared-line distinct-name safeguards; lint, bundle validation, native alignment and persistent upload-certificate checks passed. Model acceptance remains outstanding, so the bundle is not a final delivery artifact.
- Firestore/storage rule suite: 19 tests passed, including maximum metadata, all 24 notes, malformed entries, timestamp overflow and control separators. Updated Firestore rules compiled and deployed successfully.
- A full Android regression passed all 66 tests on virtual ARM API 35, with zero skipped tests (matrix-8a6qqx7j14fya). Coverage includes delete/restore, offline photo conflicts, draft recovery, repeated fields, QR review, small-print OCR and forward/reverse case browsing. This run predates the newest omitted-channel safeguard and a text-encoding-only fix.
- Screenshot review found a smaller rear-sheet image-window pop after the primary layer exchange was fixed. Distant sheets now move behind leather before entering/leaving the three-image window. The corrected test derives drag distance and probes from measured geometry; it passed in the full Android run. Inspected crossing frames no longer show the rear image-window pop.
- My Card now requires explicit ownership selection for a joint physical card, preserves that choice through draft recovery, and cancels a new own-card camera session cleanly. Local and Android regressions passed, including restart before and after ownership selection and saving the two people separately.
- Positioned OCR contact channels omitted by the model now request an ownership review instead of being silently attached to the first person. Six regressions passed. This safeguard does not detect channels explicitly assigned to the wrong person, and has not yet passed the physical model corpus.
- The corrected constrained-output schema compiles using llguidance 1.3.0, matching the SDK pin. A negative control reproduces the unsupported propertyNames error from the earlier physical run. Syntax validity does not establish extraction accuracy.
- Production CPU interpretation on physical Pixel 9 passed four of five fixtures. The two-column card returned one person instead of two. Total scan times were 49.56s single, 27.08s two-column (failed), 67.50s two-sided, 97.37s three phones/postal address, and 121.26s multiple channels. Native OCR took 0.35–1.19s; interpretation and assignment review dominate latency. The correctness and 15-second performance gates both fail.
- The Pixel 9 GPU/speculative-decoding experiment failed all five fixtures (matrix-1wf4tvaxh5lxz): four native decoding-mask errors and one invalid JSON response. No usable proposal was produced. First initialization took about 39 seconds and later cached initialization about 3–4 seconds. Decoder errors no longer trigger an irrelevant CPU image-encoder retry. GPU remains test-only; speculation is now explicitly opt-in in tests.
- A compact-output instruction now requests source IDs rather than duplicate scalar text when exact, undisputed OCR regions already supply the value. Corrections and partial regions still require literal values; channels remain explicit. Parser regressions do not establish whether this improves model accuracy or speed.
- Follow-up screenshot inspection found a hollow handoff shadow, stale ready-phase copy and a lagging browse caption. These were corrected and their native assertions passed in matrix-3nqainylffpta. That full run passed 65 of 66 tests; AppPresentationTest still waited for the intentionally removed duplicate editor heading. Its selector now uses the editor title and checks the name field is visible. The rerun (matrix-c5yxzbd39nb5a) was rejected with TEST_QUOTA_EXCEEDED before execution; the corrected assertion is unverified. Latest handoff screenshots were inspected.
- Shared-line distinct names are retained only for explicit, uniquely occurring, non-overlapping, word-bounded literal spans. Identical, ambiguous, overlapping and ungrounded assignments remain flagged. Six local regressions passed; person count and channel ownership are not inferred from text splitting.
- The physical Galaxy S26 API 36 probe (matrix-1ubzluyyfvf8v) timed out after 600 seconds while downloading the public model, before any fixture or inference began. It provides no recognition-speed or correctness evidence. Test-only checksum-verified model staging is now compiled to avoid that repeated public download; its remote execution remains unverified.
- A direct physical-phone runner is available at scripts/Verify-ConnectedAndroid.py. It verifies demo APK identities, never launches an emulator or changes device settings, rejects skipped/failed/empty results, and saves private evidence. Ten combined host-tool regressions passed. No authorized physical phone was attached during preflight.

## Connected feature entry points

- Card review: source regions, repeated channels, optional QR comparison with explicit same-person/field choices, organization and meeting details.
- Card details: multiple contact actions, selective sharing, history, notes, related company contacts and explicit duplicate merge.
- Settings compartments: My Card, batch/import queue, export, encrypted backup/restore, recovery, sync conflicts, device lock, script selection, learning and private diagnostics.
- Android entry points: image/PDF share import, capture/My Card shortcuts and a widget without contact details.

Follow-up reminders remain excluded. Automatic detection of several separate physical cards in one photograph is not claimed: the import queue supports separate user-selected crops. Recognition and full visual acceptance are release gates, not optional follow-up polish.
