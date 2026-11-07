# STO-08 — C2C-3B Bounded PNG/JPEG Decoder Contract V0.1

**Status:** APPROVED C2C-3B DESIGN — both developers accepted the limits, Android API 26+ BitmapFactory direction, and conditional strict-rejection gate on 2026-11-07. **Implementation and test results are not yet approved or completed.**

**Starting integration baseline:** `48553ca` (`main`, C2C-3A approved writer contract, PR #122); local `main` and `origin/main` verified synchronized and clean. Reconfirm the remote baseline before creating the implementation branch.

**Issue:** STO-08 #31 (keep open).

**Upstream contracts:**
- `docs/architecture/sto_08_c2c2_verified_attachment_publication_protocol_v0_1.md` — approved publication and safety sequence (P1–P7).
- `docs/architecture/sto_08_c2c3a_insert_only_image_writer_contract_v0_1.md` — approved insert-only image writer direction (C3-D1–C3-D6).
- `ReadOnlyFinalizedAssetVerifier` and `AssetAcquisitionLeaseCoordinator` — merged C2C-2B/C primitives, not attachment authorization.

## 1. Purpose and permitted scope

Build and test an **internal, read-only, fully decoding inspector for registered finalized PNG/JPEG originals**. Its only successful output is bounded, verified metadata (such as asset ID, MIME, original pixel dimensions, encoded length and digest), **not a bitmap, file path, file descriptor, attachment token, or authorization**. The future C2C-3C writer must independently enforce its document/revision/ownership transaction rules.

C2C-3B does **not** insert/update/delete Room rows or owner edges, change the ownership-completeness gate, stage/finalize originals, relax `CanonicalContentValidator`, enable image tools, attach PDFs, alter note revisions, or reclaim/repair files. Genuine media decode is required; signature and hash alone are insufficient.

## 2. Jointly approved input and resource policy (B-D1)

| Constraint | Approved hard limit / policy |
|---|---|
| Accepted registered MIME | Exactly `image/png` or `image/jpeg` |
| Encoded original length | Positive and **at most 16 MiB** (16,777,216 bytes) |
| Intrinsic width | Integer **1–4,096** pixels |
| Intrinsic height | Integer **1–4,096** pixels |
| Total intrinsic pixels | **At most 8,388,608** pixels (8 MiPixels), with checked `Long` multiplication |
| Expected ARGB_8888 pixel budget | **At most 33,554,432 bytes** (32 MiB), checked before full decode |
| Actual decoded bitmap allocation | Must not exceed **33,554,432 bytes**; reject unexpected pixel config/dimensions |
| Full decode per inspection | Exactly one; no subsampling, thumbnail fallback, partial/image-region acceptance |
| Concurrent full decodes | At most one per inspection operation; no blanket claim of cross-process/global memory serialization |
| API compatibility | Android **API 26+** |

These limits are conservative project policy, **not** a guarantee about the total peak heap/native memory cost. Peak use also includes up to 16 MiB of encoded bytes, decoder workspace and runtime overhead. Do not assume a total-memory upper bound of 32 MiB or promise recovery from a system-level out-of-memory process kill. Use preflight bounds to avoid intentionally allocating excessive pixel buffers; profile before raising limits.

## 3. Filesystem and asset identity policy (B-D2)

- Input is a **registered `assetId` only**. No caller-supplied path, bytes, MIME type, hash, dimensions, crop, or unverified external URI is trusted.
- Resolve `AssetEntity` via `AssetDao` and require `state = finalized`, positive bounded `byteSize`, canonical lowercase SHA-256 and MIME-specific content-addressed relative path (`.png` or `.jpg`).
- Constrain the root and each asset shard directory to real app-private directories without symlink traversal. Require a regular, non-symlink final original; enforce `NOFOLLOW_LINKS` at file open, not only before it.
- Operate **under the existing `.asset-publication.lock` acquisition lease**. C2C-3B must not reacquire a non-reentrant lease if a caller already holds it, and must never call `ManagedAssetRegistrar.register` or `stageAndFinalize` inside the lease. For tests, a lease-aware orchestrator may wrap the internal decoder; no production owner writer is added here.
- Reuse the read-only C2C-2B verifier where appropriate, but **its `IntegritySnapshot` does not contain verified bytes or a stable handle**. A second open is not an immutable snapshot. For actual full decoding, read the file into one bounded encoded-byte buffer **while holding the same lease**, and recompute/check byte length, SHA-256, header/MIME, path and finalized metadata for **the exact bytes to be decoded**. Compare against persisted metadata and the verifier's result; reject mismatch. Do not decode from a fresh unchecked file open.
- Check for cancellation during bounded I/O and between verification, bounds decode and full decode. A non-cooperating process can mutate app-private files despite this lease; do not claim absolute filesystem immutability.

Implementation may factor shared read/verification logic to avoid duplicated verification, but such refactoring must preserve the merged verifier's read-only public contract and existing regression tests. No separate file rewrite or implicit repair is permitted.

## 4. Android decoding strategy (B-D3)

Use Android `BitmapFactory.decodeByteArray` on the same verified, bounded encoded-byte array:

1. **Bounds-only pass:** `BitmapFactory.Options(inJustDecodeBounds = true)`; require positive intrinsic `outWidth`, `outHeight`, non-null exact `outMimeType` matching the registered asset and expected PNG/JPEG type. Reject before pixel allocation if any dimensions, multiplication, or budget exceed approved limits.
2. **Full pixel decode:** Separate options, with `inJustDecodeBounds = false`, `inSampleSize = 1`, `inScaled = false`, `inPreferredConfig = Bitmap.Config.ARGB_8888`, `inMutable = false`, and no `inBitmap` reuse. Do not request hardware, region, sampled or reduced-resolution output. Use an I/O/background dispatcher rather than the UI thread.
3. Require a non-null decoded bitmap, actual `width`/`height` exactly matching the inspected intrinsic dimensions, expected ARGB_8888 config, and an `allocationByteCount` and `byteCount` within the approved budget. A config preference is not a promise; reject an unexpected result rather than silently accepting it.
4. Dispose of the decoded bitmap in `finally` (`recycle` as appropriate), including failures after allocation. Do not expose/cache the bitmap in the result; keep no mutable file path or encoded bytes in the result.

`BitmapFactory.Options.inJustDecodeBounds`, `outMimeType`, `inSampleSize`, and `inScaled` are supported on API 26. `ImageDecoder` is API 28+ and is not the sole implementation of the API 26 baseline. Android's `BitmapFactory` documentation **does not establish that every partial or malformed JPEG/PNG will be rejected**. The next section is therefore a hard acceptance gate, not a presumed property of this API.

## 5. Strict malformed/truncated-image acceptance gate (B-D4)

A successful BitmapFactory decode is **necessary but not sufficient** to declare a media original strictly validated. Before permitting any C2C-3C writer use, the implementation must demonstrate **fail-closed** outcomes for malformed/truncated PNG/JPEG bytes that may have a valid signature and even a matching registered digest.

- Use actual encoded fixtures for each format, and create adversarial copies whose metadata/digests are deliberately updated to match the corrupted bytes. This proves failures arise from media validation, not only SHA/signature rejection.
- Test header-valid but incomplete data, missing end markers, truncated compressed image body, altered segment/chunk lengths, and corrupt or inconsistent encoded structures (including PNG chunk CRCs where relevant).
- If BitmapFactory accepts any fixture that should be invalid, **do not label C2C-3B complete or permit the writer to rely on it**. Add and independently test a bounded structural validator (or a separately reviewed, stricter decoding option) for that exact failure class; rerun the entire matrix, including API 26. Do not claim that checking JPEG EOI or PNG IEND alone proves structural completeness.
- If a strict all-malformed-input guarantee cannot be supported with documented evidence and executable tests, document residual acceptance risk and **block production attachment activation pending a new joint decision**. The conditional design approval is not a waiver of this gate.

## 6. Cancellation, failures and resource cleanup (B-D5)

- Use cancellable `suspend` orchestration and bounded reads. Check cancellation before the expensive decode, immediately after the synchronous decode returns, and before reporting success.
- **BitmapFactory full decode is synchronous**; coroutine cancellation cannot be assumed to interrupt it mid-call. Do not claim hard timeout/cancellation guarantees during the native decode itself. Under cancellation, do not return success; release bitmap/buffers/file handles and the caller's lease by established `finally` logic.
- Treat a `null` decode, unexpected config/dimensions, failed checksum, bad MIME, unsupported format, I/O error or invalid metadata as a typed/fail-closed error. Do not swallow cancellation or convert it to success.
- Handle decoder allocation failure conservatively if the process survives; never guarantee recovery from process-level OOM. Avoid broad `Throwable` catches that hide cancellation, fatal VM failures or successful-but-partial decodes.
- Preserve files and metadata on all failures. No owner transaction, gate change, orphan cleanup or mutation is allowed.

## 7. Suggested internal interfaces (design direction, not final Kotlin API)

- An internal `VerifiedDecodedImageInfo` value object contains **only** `assetId`, registered MIME, verified SHA-256, encoded byte count, intrinsic width/height and pixel count.
- An internal decoder accepts a registered asset ID and the already-approved app-private dependencies; **lease ownership is explicit in the orchestration contract**. Do not expose unchecked raw-byte/BitmapFactory helpers as an application-facing authorization seam.
- Android-specific decode may use an internal platform adapter for deterministic JVM unit tests, but production success must depend on actual device decoding. Test fakes must never become an unchecked production code path.
- C2C-3C will later call the decoder **inside a previously acquired lease**, then open `RoomDocumentRepository`'s owner transaction, rechecking asset identity and revision, and release the lease only after commit/rollback. The decoder's output alone never grants insertion rights.

The exact Kotlin function signatures, code placement and error types may be settled during a small read-only implementation inspection; any change to resource limits or safety behavior requires revisiting this document before coding.

## 8. Required test matrix (B-D6)

| ID | Scenario | Required observation |
|---|---|---|
| B01 | Real, valid small PNG | Exact MIME/dimensions; actual full decode; metadata only returned |
| B02 | Real, valid small JPEG | Exact MIME/dimensions; actual full decode; metadata only returned |
| B03 | Valid encoded bytes at each permitted boundary | Accept only within all independent limits |
| B04 | Empty, missing, unregistered or non-finalized asset | Reject; no mutation |
| B05 | Invalid/unsupported MIME or MIME/signature/bounds mismatch | Reject |
| B06 | Noncanonical private path, linked directory or linked final file | Reject without following link or changing target |
| B07 | Wrong byte length, digest or changed file on bounded re-open | Reject without owner mutation |
| B08 | PNG signature with truncated/corrupt data and matching digest | Reject through decoder/structural checks |
| B09 | JPEG signature with truncated/corrupt data and matching digest | Reject through decoder/structural checks |
| B10 | Missing/inconsistent PNG end marker, chunk structure or CRC | Reject |
| B11 | Missing/inconsistent JPEG end marker/segments | Reject |
| B12 | Zero/negative/inconsistent bounds, MIME or null full decode | Reject |
| B13 | Width > 4,096; height > 4,096; pixel count > 8,388,608 | Reject before allocating full bitmap |
| B14 | Encoded bytes > 16 MiB; decoded allocation > 32 MiB | Reject, no oversized success |
| B15 | Bounds and full decode report different sizes or pixel config | Reject and dispose bitmap |
| B16 | Cancellation before read, while reading, between passes, or immediately after decode | Reject/no success, release all resources |
| B17 | File read, decoder exception, allocation failure | Fail closed, no partial success; clean up when process survives |
| B18 | Lease contention/cross-process publication compatibility | Do not decode unfinalized/contended content; no nested-lock deadlock |
| B19 | Approved disposable `STO07_S03B_Disposable` and API 26 compatibility | Fresh class-specific JUnit on approved disposable AVD; API 26 device coverage only on a separately authorized disposable AVD |
| B20 | Existing asset verifier, registrar, publisher, and lease regressions | All relevant tests still pass |

Use isolated app-private sandboxes, freshly registered assets, true PNG/JPEG fixtures, checksum-controlled mutations, and fresh per-class JUnit reports. Tests must not run on unknown or personal devices. Verify the approved AVD identity before instrumentation. JVM tests may cover policies/parsers; **Android instrumentation must prove actual BitmapFactory behavior**. Never run instrumentation on an unknown or personal device. If an API 26 disposable AVD is not separately authorized and available, record device coverage as outstanding rather than claiming it passed; API 26 compilation/API-surface review alone is not runtime coverage.

## 9. Completion criteria and downstream gate

C2C-3B implementation may be considered ready for a PR only when: (a) the numeric/resource limits and conditional strategy above are implemented and demonstrably enforced; (b) true full decoding and strict malformed/truncated-image rejection pass; (c) cancellation and file/bitmap/lease cleanup work under tested conditions; (d) code has no owner/Room writes or attachment/UI activation; and (e) JVM, lint, compilation, relevant Android test suites and collaborator review pass. Keep STO-08 #31 open.

**C2C-3B design approval does not authorize C2C-3C owner writes.** That writer requires separate exact API/revision/error-contract review and atomic rollback/end-to-end tests. The ownership gate remains `UNVERIFIED` or conservative `UNRESOLVED`, never silently promoted. No metadata or asset deletion, repair, or garbage collection is permitted.

## 10. Decision record — jointly accepted 2026-11-07

- **B-D1 — APPROVED:** 16 MiB encoded, 4,096 per dimension, 8,388,608 pixels, 32 MiB ARGB_8888 budget, PNG/JPEG only, one full decode per operation; limits may be revisited only after profiling and review.
- **B-D2 — APPROVED:** Registered finalized original under the acquisition lease, confined no-follow opens, verify the exact bounded bytes passed to the decoder; no arbitrary paths or bytes as caller authority.
- **B-D3 — APPROVED DIRECTION:** Android API 26+ `BitmapFactory` bounds pass then full-resolution byte-array decode with explicit result checks and cleanup; test on real Android execution targets.
- **B-D4 — CONDITIONALLY APPROVED:** Reject truncated/malformed files with matching digest/signature. BitmapFactory alone has no assumed strictness guarantee; structural validation or alternative must be added if tests show permissive decoding. No production writer activation until proven.
- **B-D5 — APPROVED:** Cancellable bounded I/O, explicit pre/post decode cancellation checks, finally cleanup; synchronous native decode is not promised to be interruptible.
- **B-D6 — APPROVED:** Genuine PNG/JPEG fixture matrix including adversarial matching-digest corruption, format/size limits, symlinks, process/lease behavior, API 26 coverage and existing regressions.

**Review outcome:** Design approved by both developers; documentation-only at this checkpoint. C2C-3B implementation remains separately test-gated; C2C-3C and all production media attachment features remain disabled.

### References

- [Android `BitmapFactory.Options`](https://developer.android.com/reference/android/graphics/BitmapFactory.Options) — bounds decode, intrinsic dimensions, reported MIME, sampling, scaling and preferred pixel configuration.
- [Android `ImageDecoder.OnPartialImageListener`](https://developer.android.com/reference/android/graphics/ImageDecoder.OnPartialImageListener) — API 28+ partial-image handling; not an API 26 substitute.
