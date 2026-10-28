# Portable `.notebook` validation — FMT-02 (working draft)

Scope and source: this validator implements the V0.2 document model's *proposed* `schemaVersion: 1`. The JSON Schema in `../schemas/notebook_schema_v1_draft.json` has been reconstructed from the approved V0.2 specification because the original companion draft was not present in the inspected repository path. It is a **new implementation proposal**, not a recovered production schema. Both developers should review the strictness decisions below before merging.

## Running locally

From the repository root:

```powershell
py -3 -m pip install -r shared/validators/requirements.txt
py -3 -m unittest discover -s shared/validators/tests -v
py -3 shared/validators/validate_notebook.py path/to/example.notebook
```

Run only on synthetic/test packages until full semantic and asset validation is implemented. An exit status of 0 currently means **preflight + draft JSON Schema checks pass**, not that an imported archive is safe to register in the app database.

## Checkpoint 1 (existing)

- Archive structure, paths, duplicate ZIP entries, symlinks, encryption and provisional size caps
- Manifest presence, schema version, duplicate page references and missing page files

## Checkpoint 2 (this change)

- Draft 2020-12 schema for the manifest, pages, four editable element envelopes and backgrounds
- UTC-compatible timestamp syntax (ordering not yet checked)
- Page schema validation, page-ID alignment with manifest references, finite floating-point parsing, duplicate JSON-key rejection
- Command-line exit codes and synthetic positive/negative schema tests

## Deferred semantic validations (next checkpoints)

- Verify that every asset exists, its MIME/file signature matches, SHA-256 and byte length match, and paths agree with the hash and extension
- Check duplicate note-wide element/asset IDs and image/PDF asset references
- Verify invertible transforms, chronological timestamps, crop containment and time ordering
- Validate PDF page index with a safe PDF parser; enforce operational limits and unknown-entry policy
- Produce separately reviewed golden and malformed fixtures in FMT-03 and integrate CI in FND-07A

## Strictness choices pending final cross-platform review

- `additionalProperties: false` on known v1 structures avoids silently ignoring unknown fields.
- All shown envelope fields and the manifest's `assets` array are required, even when empty.
- `pdfPage.displayBox` currently accepts `crop` only, as shown in V0.2; alternative boxes need an explicit contract revision.
- Brush/text enums mirror V0.2. Any future editing or rendering extensions require coordinated schema and fixture updates.

None of these decisions freeze production format compatibility or commit the project to a handwriting engine.
