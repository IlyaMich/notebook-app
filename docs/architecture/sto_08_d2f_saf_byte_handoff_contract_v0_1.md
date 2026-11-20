# STO-08 D2F — User-created SAF document byte handoff v0.1

**Status:** guarded production-code seam, NOT enabled from the UI. No durable export receipt, release protocol, `VERIFIED` promotion or GC authorization. Built after D2E's v6 Room-bound independent private archive.

## Provenance / preconditions

1. A caller explicitly requests an Android `ACTION_CREATE_DOCUMENT` destination. The result must be **freshly selected/created by the user** for this attempt; do not accept an arbitrary persisted URI. Android's URI shape alone cannot prove the system picker just returned it.
2. `ExportSafCreatedDocumentDestination` rejects raw `file:` and non-DocumentsContract URIs. It consumes a single write opportunity and cannot be reused to retry a failed write. The adapter does not choose a filename, launch a picker, grant URI permissions, or modify app documents on its own.
3. The coordinator rechecks the v6 source binding and *every* required private stage/draft/archive byte before touching the destination. Unbound legacy v5 operations and incomplete/corrupt private copies cannot enter publication.
4. The external provider is called **outside** the shared asset-publication lease, to avoid keeping a process-wide asset lock while an arbitrary remote/cloud provider responds. Persistent Room v6 holds remain unchanged before, during and after provider I/O.
5. The byte writer confines its source to the app-private `retained-private-export-archives/<canonical UUID>.notebook`, requires a positive bounded length and SHA-256, uses no-follow file channels, and independently checks the source before and after transfer. It never opens managed asset originals.
6. On an explicit fresh destination, the writer streams exactly the expected bytes, requires the provider fd's data sync to succeed, closes it, reopens the same URI, rereads with strict byte budget and matching SHA-256, and rechecks private Room-bound provenance afterward. No in-memory writer flag proves a destination match.

## Intentionally unproven / unsafe to assume

- `ExternalBytesObservedUntrusted` means **only** that a provider returned matching bytes on a readback. It does not prove atomic creation/visibility, power-loss durability, account sync, remote-cloud persistence, secure custody, or portable import at a later time.
- Generic `DocumentsProvider` implementations can return pipe-like descriptors on which `sync` fails, defer remote uploads, retain partial bytes after a failure, or ignore expected overwrite semantics. The writer must fail closed when sync/readback fails; it must not issue a receipt even when both return normally.
- `ACTION_CREATE_DOCUMENT` generally creates a new document, but accepting a historical URI cannot prove that behavior. Future UI integration must bind a one-time activity-result callback to the operation, and must not call the raw-URI seam from arbitrary restored state.
- No external destination is automatically deleted/replaced/retried after failure, cancellation, or process death. A partially created user document may require explicit user/provider handling.
- The Room operation state and immutable `export_asset_holds` remain unchanged. A positive observation **never** releases pins, retires originals, changes the ownership gate, or authorizes physical GC.
- In-app private copies remain within the app sandbox and are **not** considered a user-retained independent backup after uninstall.

## Test and release gates

- JVM: successful exact handoff and negative provider write, fsync, truncated/malformed/oversized readback, source mutation and cancellation.
- Android: on-device Room v6 → private stage/draft/retention → synthetic provider roundtrip, and negative URI validation; assert holds and originals remain intact.
- Before activation: API 26 and API 37 real user-created `DocumentsProvider` acceptance using disposable locations, interrupted writes/process death, provider retry/collision semantics, URI permissions and reopen after restart. Confirm archive import conformance.
- Before any hold release: separate immutable, restart-verifiable, independently durable destination-completion protocol **with provider-specific guarantees**. A generic matching SAF readback alone is explicitly insufficient.
- GC, legacy backfill, `VERIFIED` ownership promotion and managed-source deletion remain prohibited.
