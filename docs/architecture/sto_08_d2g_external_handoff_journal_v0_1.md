# STO-08 D2G — Append-only external handoff journal v0.1

**Implementation checkpoint:** Android Room v7 diagnostic only, stacked on D2F. It is **not** an external destination completion receipt, a hold-release protocol, a persisted URI-grant policy, an ownership certification or a GC implementation.

## 1. Purpose and trust boundary

D2F can independently reread the user-created SAF document after writing a v6-bound `.notebook` archive. But power loss, provider upload delay, cloud inconsistency, process death, data-sync limitations and non-atomic provider visibility cannot be ruled out by a matching readback. The app previously retained no durable record that a provider write was attempted, so after restart a caller could inadvertently repeat a non-idempotent write.

D2G adds a narrow, fail-closed distinction:

- **No attempt observed (untrusted):** Room has no journal row. This is not proof that a provider has no bytes, particularly for pre-v7 operations.
- **Attempt pending (untrusted):** a one-shot intent committed before any external write; actual provider outcome is unknown. This is terminally ambiguous and may never be retried automatically for this operation.
- **Readback observed (untrusted):** after a provider write/sync/close/readback sequence and source revalidation, an immutable record says matching bytes were once returned. No provider-independent publication or power-loss guarantee follows.
- **Incomplete:** invalid or inconsistent Room operation, binding, pin set or immutable journal observation.

Only the user-facing explicit `ACTION_CREATE_DOCUMENT` callback can pass a freshly created URI into the SAF seam. Its SHA-256 identity is recorded locally, **not** the raw URI or document contents. Raw URI shape cannot establish freshness; no URI is persisted for later replay.

## 2. Additive Room v6 → v7 migration

Tables:

1. `export_external_handoff_attempts`: `operation_id` PK/FK to immutable `export_operations`, destination identity SHA-256, expected retained archive byte count/digest, exact v6 source snapshot and asset-set digests, attempt timestamp, version 1.
2. `export_external_readback_observations`: `operation_id` PK/FK to the attempt, provider returned digest and byte count, observation timestamp, version 1.

SQLite triggers make **both tables strictly append-only**, validate the v6 source binding for attempt insertion, and reject a readback that differs from its immutable attempt. The existing export operation, source binding and asset holds retain their delete/update prohibition and `asset_reference_index_state` remains `UNVERIFIED`/fail-closed. No historical attempt or receipt is backfilled; older v6 rows remain untouched.

## 3. Choreography / crash windows

1. Caller checks complete Room-bound private stage, draft and separate archive, under the existing asset-publication lease.
2. Before opening any provider write descriptor, the journal transaction validates the v6 operation/binding/complete bounded hold set and inserts exactly one immutable attempt. A second attempt for the same operation is rejected **before provider I/O**, even if the first ended in failure, cancellation or process death.
3. Outside the OS lease, D2F copies only the private archive; the original managed asset paths are not accessed. Provider sync/close and independent same-document readback must match.
4. After revalidating the private archive against Room, a second Room transaction inserts one exact matching **untrusted** readback observation. A failure to commit this observation leaves a pending attempt, never a completion.
5. On restart, a read-only Room transaction classifies the immutable evidence; it never opens the provider, retries an operation, deletes partial provider bytes, updates a hold, or promotes `VERIFIED`.

| Interruption | Durable Room evidence | Safe interpretation |
|---|---|---|
| Before attempt transaction | No attempt observed | Cannot prove external bytes absent |
| After attempt, before provider open | Attempt only | Unknown / no retry |
| During partial provider write | Attempt only | Unknown / no retry |
| Provider sync refused | Attempt only | Unknown / no retry |
| After matching provider readback, before Room observation | Attempt only | Unknown / no retry |
| After Room observation commits, provider later loses bytes | Attempt + readback | Historical matching readback, **not** durability receipt |
| Corrupt/missing v6 binding, holds or evidence | Incomplete | Retain all originals and pins |

## 4. Acceptance and next separate decisions

- CI compile, JVM fault-injection and lint; genuine v6→v7 migration using the exact committed Room `6.json`, plus API 26/37 instrumentation on disposable test devices.
- Verify the real generated `7.json` is committed separately without hand-authored schema fabrication.
- Test actual user-created disposable SAF documents, crash/reopen behavior and provider-specific durability/visibility semantics **before user-facing activation**.
- A provider-independent SAF readback cannot justify a protected completion receipt. A later trusted completion design must select a destination class with adequate independent durability guarantees or remain explicitly nonreleasing. Any hold release/GC requires a separate concurrency/ownership audit and approval.

**Never interpret this journal's positive state as permission for original-file deletion, source reallocation, export-hold release, automated stage cleanup, ownership gate `VERIFIED` or GC.**
