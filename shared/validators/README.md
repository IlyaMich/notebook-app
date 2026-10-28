# Portable `.notebook` validator — FMT-02 (working draft)

This validator implements preflight, draft JSON Schema 2020-12, and the **Checkpoint 3 semantic checks** based on the jointly reviewed V0.2 document model (`schemaVersion: 1`, still provisional). The schema was reconstructed from V0.2 during Checkpoint 2; it is **not** the original missing companion artifact or an immutable production contract.

## Run from the repository root (Windows PowerShell)

```powershell
py -3 -m pip install -r shared/validators/requirements.txt
py -3 -m unittest discover -s shared/validators/tests -v
py -3 shared/validators/validate_notebook.py path/to/synthetic.notebook
```

A successful exit status means all **implemented** checks pass, not that untrusted content is safe to decode or register in a database. The unit tests build disposable synthetic ZIPs in memory; they are **not** the golden documents that belong to FMT-03 (#13).

## Checkpoint 1 — preflight

- Safe ZIP paths, unique entries (including case-insensitive collisions), no symlinks/encryption, provisional file-count/compressed/uncompressed size caps.
- Manifest required; draft version 1 only; page references resolve to existing page entries.

## Checkpoint 2 — draft schema

- Manifest and page JSON Schema checks; supported editable elements (stroke, shape, text, image) and backgrounds.
- Invalid timestamps and nonfinite numbers rejected; duplicate JSON keys rejected; referenced page IDs match their pages.
- RFC 3339 format checking requires both `jsonschema` and `rfc3339-validator` from `requirements.txt`.

## Checkpoint 3 — semantic validation

- UTC timestamps and `createdAt <= updatedAt`.
- Asset ID/path uniqueness; presence, content-derived filenames, byte lengths, SHA-256 and basic MIME signatures (PNG/JPEG/PDF).
- Each image refers to an actual registered image; each PDF background to an actual registered PDF.
- Element IDs unique across the **entire note**, including separate pages.
- Invertible 2×2 affine transform; nondecreasing stroke `tMs`; image crop containment.
- Page filenames match page IDs and undeclared archive files are rejected.
- Provisional element/point count caps; assets streamed in chunks to bound RAM.

## Remaining limitations and future work

- PDF is only checked by its `%PDF-` prefix. **PDF page count, valid `pageIndex`, malformed content, rendering and embedded structure are NOT checked**. Use a bounded, sandbox-conscious PDF parser before import acceptance.
- PNG/JPEG signatures are *not* full file-format decoding or protection against crafted image payloads. Additional decoder and pixel/dimension limits are needed before production import.
- Validation is separate from the **transactional staging/commit, asset GC, and crash recovery** protocols described in the storage plan.
- More adversarial ZIP cases (CRC corruption, decompression resource caps) and golden round-trip fixtures remain for FMT-03, integration/CI and hardening.
- Resource caps are provisional and require empirical review. Unknown fields are rejected by the draft JSON Schema; this policy and undeclared-file rejection should be reviewed jointly before treating v1 as frozen.
- The validator does not select or implement a handwriting engine. All assets and test records must be synthetic.

## Checkpoint 4 — GitHub Actions gate

`.github/workflows/shared-format-ci.yml` runs on pull requests, pushes to `main`,
and manual dispatch. It installs the declared Python dependencies and executes
all the validator unit tests. The new CLI tests verify correct success/failure
exit codes for valid archives and synthetic invalid versions, hashes, and paths.

The Android build remains in `.github/workflows/android-ci.yml` and is not
modified by this checkpoint. CI's test packages are generated in temporary
directories. FMT-03 (#13) will add committed golden `.notebook` fixtures and
separate conformance checks; this workflow does not claim to test those yet.
