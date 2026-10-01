# Recognition evidence and release checks

`tests/vision/recognition-gate.json` defines the current synthetic corpus. The release check separates three kinds of evidence:

1. JVM contracts exercise parsing, field ownership, source recovery, repeated channels and personal-learning safeguards. They do not execute OCR or Gemma.
2. `SmallPrintEvidenceTest` executes native OCR on a generated small-print photograph and verifies punctuation, the postal line and source coordinates.
3. `VisualModelDeviceTest` with `visualModelTest=true` executes native OCR and the actual installed model. Suite 2 now passes OCR evidence into the model, exercises source assignment and adds a multiple-email/multiple-website fixture. Old suite results do not validate this path.

Use fresh test output from the build being assessed:

```powershell
python scripts/Check-RecognitionGate.py --version <build-version> --unit-results <fresh-junit-directory> --android-results <fresh-device-junit-directory> --model-log <fresh-instrumentation-log> --output <private-report-path>
```

The check reports correctness separately from the 15-second end-to-end synthetic scan target. It returns nonzero when any required execution is absent, skipped, failing or slower than that target. A passing parser suite cannot turn an unexecuted model fixture green. This gate does not trigger a model download or device run.

All fixture identities are fictional. Public reports need only fixture IDs, pass/fail, timing, build version and device metadata. Do not attach real contacts or photographs to diagnostic reports. Production timing collection is explicitly opt-in, local, bounded, excluded from backup and contains no recognized text, image paths or account IDs.

## Host grammar preflight

Before paying the time cost of a device/model run, export the **current** `VisualSchema.json` to a UTF-8 JSON file. Use a fresh JVM build, or independently compile the actual `VisualSchema.kt` with Kotlin and the test `org.json` dependency; a small runner in the same package can print `VisualSchema.json`. Do not validate a hand-copied or stale schema.

In an isolated Python environment, run:

```powershell
python -m pip install llguidance==1.3.0
python scripts/Check-VisualSchema.py <exported-schema.json> --output <private-report.json>
```

The version matches [LiteRT-LM 0.17.1's Cargo.lock](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.17.1/Cargo.lock). The script compiles the grammar and verifies that a negative control using unsupported `propertyNames` is rejected. It reports a schema hash and exits nonzero for compilation, negative-control or environment failures. No production dependency is added.

The host wheel lacks LiteRT-specific patches and the Android tokenizer. This is a grammar preflight, **not** proof of Android execution, extraction accuracy or speed; the native/model release gates remain required. See the [compiler's JSON Schema support](https://github.com/guidance-ai/llguidance/blob/main/docs/json_schema.md) when changing keywords.

## Connected physical-phone validation

When cloud quota is exhausted, connect an authorized physical Android phone over USB and run:

```powershell
python scripts/Verify-ConnectedAndroid.py
python scripts/Verify-ConnectedAndroid.py --serial <authorized-device-serial> --run
```

The default command is read-only preflight. It excludes emulator serials and devices reporting `ro.kernel.qemu`, requires an explicit serial when several phones are connected, and verifies the current demo APK identities. Build `assembleDemoDebug` and `assembleDemoDebugAndroidTest` first. `--run` installs those two APKs with `-r` and executes an explicit non-model regression class list. The production cloud app is not targeted. Demo tests use synthetic fixtures and may replace their own demo records; keep real contacts in the production app.

For the separate real OCR/model gate, add `--visual-model --run`; this explicitly allows the existing test to download the pinned 2.6 GB public model if absent. Allow sufficient phone storage and download time. The helper never launches an emulator, uninstalls an app, clears data/logcat, or changes device settings. It saves instrumentation output, build hashes, test-generated screenshots/reports and a result summary under an ignored `.tools/connected-android-<timestamp>` directory. It does not collect general device logs or screenshots. Failures, skipped tests, missing test execution and command timeouts fail validation. On a command timeout, the helper attempts a bounded force-stop of only the demo app and its test package, preserves their data, and records cleanup success or failure. Inspect that result before another run; unsuccessful cleanup can leave native work running.

## Remaining limits

- These synthetic cases are regression protection, not a population-level accuracy percentage. Real typography, glare, rotated or curved print, unusual multi-person ownership and tiny low-contrast printing need a larger consenting evaluation corpus.
- Bundled OCR supports Latin, Devanagari, Chinese, Japanese and Korean scripts. Automatic selection uses the device language; select a script explicitly for a differently written card. Other scripts are not claimed as supported OCR.
- The source-assignment repair is conditional and runs at most once. It can add latency and may fail its context budget; the first usable proposal remains available with unresolved review issues.
- Personal learning trains a separate classifier, not Gemma's base weights. Stable physical-scan holdouts, conflicting-label exclusion and immediate disabling of a confidently contradicted adapter limit regressions. This is not a guarantee that every future prediction improves.
- A source reference locates the supporting text. It does not prove that AI classified the region or associated it with the right person.

## Portable backup format

New backups use `VISIDOCK-BACKUP-2`. PBKDF2-HMAC-SHA256 derives a password key using a fresh salt. The compressed ZIP is encrypted in independently authenticated 64 KiB AES-GCM records. Record position and length are authenticated; nonces combine a random prefix and monotonic position. An authenticated zero-length terminator is mandatory. Reordering, truncation and trailing bytes are rejected before any contacts are imported.

Both encryption and decryption enforce stream byte quotas. ZIP names, duplicate entries, entry count, per-entry expansion and total expansion are bounded. Extraction uses a fresh private staging directory and failed restores remove it. The experimental whole-archive version 1 is not accepted; it was not a delivered backup feature and could require archive-sized decryption buffers.
