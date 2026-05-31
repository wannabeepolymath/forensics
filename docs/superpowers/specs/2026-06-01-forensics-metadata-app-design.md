# Forensics — Android Metadata Inspector & In-Place Editor

**Date:** 2026-06-01
**Status:** Design approved, pending implementation plan

## Summary

An Android app that takes any file as input, shows **all** of its metadata
(structured per-format fields plus generic filesystem/raw views), and lets the
user **modify** that metadata **in place on the original file** — without ever
producing a user-visible new file.

Editing prioritizes **true same-length in-place byte patching** where safe, and
falls back to an explicitly-consented, verified rewrite otherwise. Preventing
corruption of the user's only copy is the central design constraint.

## Goals

- Inspect every extractable piece of metadata from any file, even unknown formats.
- Edit metadata directly on the original file the user picked; no second file
  appears in the user's storage.
- Make routine forensic edits (GPS, timestamps, camera serial, orientation) cheap
  and safe at any file size.
- Never corrupt the original file. Fail closed on any uncertainty.

## Non-Goals (v1)

- iOS support (sandbox makes in-place editing of the original impractical;
  possible later, more limited port).
- Rich structured editing for every format in the readme. v1 ships the
  architecture + a flagship JPEG/EXIF handler; other handlers are follow-on plans.
- Cloud sync, accounts, or sharing.

## Key Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Platform | **Android first** | SAF gives a real writable handle to the user's actual file; iOS works on security-scoped copies, defeating "edit the original in place." |
| Edit semantics | **Literal in-place byte editing** where possible | User's core requirement; honest "edited your file, no new file." |
| Edit-can't-be-in-place policy | **In-place when possible, warn-and-rewrite otherwise** | True byte-patching only works for same-length fixed-size fields; longer values / checksum-covered / signed fields can't be patched. |
| Format scope | **Broad multi-format vision via pluggable handlers** | "Broad" delivered as core + incremental handler modules, not a monolith. |
| Tech stack | **Native Kotlin + Jetpack Compose** | Best SAF access, strongest byte/IO handling, mature Java/Kotlin metadata libs. |
| Recovery copy | **Risk-tiered, size-aware** | No temp for same-length in-place patches (any size); internal temp only for structural rewrites (intrinsic to doing them safely). |

## Why true in-place editing is limited (design rationale)

In-place = change bytes without shifting anything after them; file length stays
the same. It is only safe when writing a value of **exactly the same byte length**
into a **fixed-size field** with **no checksum/signature** over it. It breaks when:

- **Values are variable-length** — a longer/shorter replacement shifts all
  following bytes (no longer in-place).
- **Formats use internal offset pointers** — PDF `xref`/`startxref`, MP4/MOV
  `stco`/`co64`, ZIP central directory. Shifting bytes invalidates these.
- **Checksums/signatures cover the bytes** — PNG chunk CRC32; digital signatures
  we may be unable to recompute.

Safely-patchable fields include EXIF fixed-size types (Orientation, ISO, GPS
rationals, exposure), fixed-format EXIF date/time, and same-length-or-shorter
ASCII strings — which happen to cover most forensically interesting fields.

## Architecture

Five independently-testable layers.

### 1. File acquisition
SAF document picker → `takePersistableUriPermission(read|write)` → persisted
read/write URI. Lazily open a read stream for parsing and, only when an edit
occurs, a `"rw"` `ParcelFileDescriptor` → `FileChannel` (random access: seek to
offset, overwrite N bytes). The URI never changes across the session — reads,
in-place patches, and rewrites all target the same file.

### 2. Generic core (works on any file)
Guarantees "show all metadata" even for unknown/unparseable formats:
- Filesystem metadata: name, size, MIME, timestamps, URI/path.
- Hashes: MD5/SHA-256, streamed.
- Magic-byte format detection (trusted over the resolver's MIME hint).
- Raw **hex dump** (paged/virtualized, jump-to-offset) and **strings** (filterable).

### 3. Format handlers (pluggable)
Interface each format implements:
```
interface FormatHandler {
    fun canHandle(magic: ByteArray, mime: String?): Boolean
    fun parse(reader: FileReader): List<MetadataField>
    fun validateEdit(field: MetadataField, newValue: Value): EditPlan
}
```
`MetadataField`: `key, value, byteOffset, byteLength, type (fixed|variable),
editable, group`.
New formats = new handler modules; no core changes. v1 handler: **JPEG/EXIF**.
Follow-on: PNG, MP4/MOV, MP3/ID3, PDF (read + limited), ZIP (read).

### 4. Edit engine
Compiles every edit into a validated `EditPlan` **in memory before opening the
file for write**:
- `InPlace(offset, originalBytes, newBytes, checksumPatches[])` — requires
  `newBytes.size == field.byteLength`.
- `RequiresRewrite(reason, rebuiltBytes)` — full new content, built & parsed in
  memory/temp first.
- `Rejected(reason)` — out of range, or covered by a signature we can't re-sign.

### 5. UI (Jetpack Compose)
Pick → Summary → grouped, collapsible metadata sections with per-field
editability badge and tap-to-edit. See UI/UX section.

## Data flow

1. **Acquire** — SAF URI → persistable r/w permission; open read stream now,
   `"rw"` descriptor only on first edit.
2. **Identify** — sniff magic bytes → select handler (or generic-only).
3. **Parse (read)** — generic core + matched handler run independently; results
   merge into ordered field groups (Filesystem, format groups, Raw/Hex, Strings).
   A handler that throws is caught and dropped; generic view still renders.
   Reads never mutate the file.
4. **Render** — grouped sections; editability badge derived without writing.
5. **Edit (write)** — only on user change + confirm:
   `validateEdit` → `InPlace` (seek + same-length write) | `RequiresRewrite`
   (consent → rebuild → overwrite same URI) | `Rejected` (inline error).
6. **Verify** — re-open and re-parse the affected region; UI refreshes from the
   re-parse, not the in-memory edit. In-place asserts file size unchanged.

## Corruption safety (central constraint)

**Fail closed: the default is don't write.** The `"rw"` descriptor opens only
after a complete, validated plan exists.

1. **Validate before touching disk** — parsing, offset/length math, new-byte
   construction, checksum recompute all in memory first.
2. **Strict in-place invariant** — `newBytes.size == originalBytes.size` and
   `offset + size <= fileSize`, else reclassify as rewrite or reject.
3. **Read-back undo buffer** — read original region → write → `force(true)` fsync
   → re-read & verify; on any failure restore from undo buffer (deterministic
   because same-length, contiguous, bounded).
4. **Structural re-parse** — after writing, re-run the whole handler `parse()`;
   confirm the container still parses (offsets intact, checksums valid, not
   truncated). On failure → restore (in-place) and report.
5. **Concurrent-modification guard** — capture `(fileSize, header-hash)` at parse;
   re-check right before write; abort + re-parse if changed.
6. **Checksum/signature handling** — recompute & patch recomputable checksums
   (e.g. PNG CRC32) within the same plan and verify; reject edits covered by a
   signature we cannot re-sign.
7. **Residual risk (acknowledged)** — a pure same-length patch has a tiny
   non-atomic window (one fsync'd write); process kill mid-write could leave the
   region half-written. One small contiguous write — minimal exposure, far less
   than the rewrite path.

### Risk-tiered, size-aware policy

| Situation | Temp copy? | Cost |
|---|---|---|
| In-place, same length (most edits) | **No** | ~edited-field size (bytes–KB), file-size-independent |
| Zero-checksum fixed field | **No** | undo buffer only |
| Rewrite (length change / structural) | **Yes — app-private internal temp on disk** | ~file size, disk-bounded, streamed, never user-visible, auto-deleted |

- **Tier 0 (in-place same-length):** no temp at any file size; only the
  field-sized undo buffer is held. A 4 GB video costs the same as a 2 MB photo.
- **Tier 1 (rewrite):** staging is intrinsic — a length-changing edit shifts all
  following bytes, so the new content must be assembled before the original is
  replaced (can't transform a file onto itself). Stage to app-private internal
  temp on **disk** (not RAM, so large files don't OOM), full parse-verify, then
  stream over the original, then delete. User never sees a new file.
- **Read path** — hashing, hex, strings stream/page; whole file never loaded.

## UI / UX (Jetpack Compose)

- **Pick** — empty state with "Open file" → SAF; recent files by persisted URI.
- **Summary** — thumbnail (images), filename, detected format (MIME secondary),
  size, hashes (background, progress for large files), badge row
  (e.g. "EXIF ✓ · GPS present · 14 editable fields").
- **Metadata** — grouped collapsible sections: Filesystem; format groups
  (EXIF, GPS, Thumbnail, MakerNotes…); Raw → Hex (paged, jump-to-offset) +
  Strings (filterable). Each field row: label, value, group, **editability badge**
  (✓ green in-place · ⚠ amber rewrite · 🔒 grey read-only). Tapping a field's
  byte offset scrolls the Hex view to that region.
- **Edit flow** — type-aware editor (text, ranged number, GPS picker, enum
  dropdown e.g. Orientation 1–8, date/time). On save: in-place applies
  immediately and refreshes from re-parse with "patched N bytes in place ✓";
  rewrite shows a blocking consent dialog explaining the full rebuild; rejected
  shows an inline reason.
- **Safety affordances** — persistent banner "Edits modify your original file
  directly." Session-only undo for the last in-place patch (undo buffer retained).

## Testing strategy

Built around **byte-level round-trip guarantees**.

- **Fixtures** — tiny real sample files per format (incl. EXIF+GPS, PNG w/ CRC,
  HEIC, MP4/MOV, MP3 ID3v2, PDF, ZIP) plus malformed ones (truncated, bad magic,
  corrupt offsets, broken CRC), each with an expected-fields snapshot.
- **Per-handler unit tests** — parse correctness vs snapshot; offset/length
  accuracy (bytes at `[offset, offset+length)` equal the reported value); edit
  classification (`InPlace`/`RequiresRewrite`/`Rejected`).
- **Edit-engine tests (highest value)** — in-place size invariant (file size
  unchanged); round-trip (patch → re-parse equals new value; patch back →
  bit-identical hash); checksum recompute verified independently; structural
  re-parse gate detects fault-injected bad writes and restores to original hash;
  rewrite staging (original overwritten only after temp parse-verifies; temp
  deleted; kill-after-stage recoverable); concurrent-modification guard aborts
  and leaves file untouched.
- **Failure-path / fuzz** — every malformed fixture degrades to generic view,
  never crashes, never writes; random valid-length patches re-parse cleanly or
  are rejected — never a false-OK (reported valid but actually broken).
- **Instrumented** — on-device SAF acquire → parse → in-place edit → verify
  (confirms `"rw"` descriptor + `FileChannel.force()`); large-file streaming
  without OOM; Tier-0 edit with field-sized memory only.
- **CI gate** — round-trip and structural-re-parse tests are **blocking**; a
  failure means a potential corruption bug and fails the build.

## Phasing

1. **v1 (first implementation plan):** generic core (fs metadata, hashes, hex,
   strings, format detection) + JPEG/EXIF handler end-to-end + edit engine with
   Tier 0/Tier 1 + UI + the corruption-safety test suite.
2. **Follow-on plans (one per handler):** PNG, MP4/MOV, MP3/ID3, PDF (read +
   limited edit), ZIP (read), each reusing the `FormatHandler` interface.

## Residual risks

- Same-length in-place patch has a tiny non-atomic write window (see safety #7).
- iOS not addressed; would require a different, more limited acquisition model.
- Some formats will remain inspect-only where in-place editing is infeasible
  (PDF/MP4/ZIP structural fields); surfaced to the user via the 🔒/⚠ badges.
