---
description: The byte-level layout rules of rabosh-index's sidecars — one spelling per varint, the key block, front-coding, the singleton posting encoding, why a sidecar is written once, and why the portable Roaring format obeys the opposite rules.
paths:
  - "rabosh-index/**"
---

Which numbers may never change is in `.claude/rules/format-permanence.md`; what an index may and may
not do to an answer is in `.claude/rules/index-and-query.md`; the byte-identity suites that hold these
claims are in `.claude/rules/testing.md`.

- **Sidecars are split by lifetime, and a sidecar is written once.** The `.idx` base carries the key
  block, the present bitmap and the segment's statistics; a `.pst` carries one index's postings. Neither
  is ever rewritten. Folding them into one file per segment would make `createIndex` and a concurrent
  compaction two `ATOMIC_MOVE` writers of one path — last writer wins, so a build could fail to
  converge forever — and read-modify-write instead would mean re-emitting sections a newer build wrote
  and this one cannot read.

- **The engine's variable-width fields are two varint regions, and each has exactly one spelling.**
  LEB128 admits `0x80 0x00` for zero alongside `0x00`; `IndexBytes.varint` calls the padded form
  corruption. Two spellings of one value would let one dictionary or one key block encode to two
  different files, and the byte identity between a flush-written sidecar and a backfill-rebuilt one is
  what lets the suites compare sidecars as *files*. Same rule as `BitmapView.verify` reporting a
  wastefully encoded block rather than accepting it: a format with no canonical form quietly
  invalidates every argument that rests on one. One rule, two callers, and both need a reproduction —
  `IndexCorruptionTest` has a padded varint in a `.pst` term record and one in an `.idx` key entry.

  Both are confined to a region that is **walked and never indexed into** — a posting file's term
  region since phase 17, a base sidecar's key entries since phase 18. That is the line to hold:
  variable width only where a sequential walk was happening anyway, fixed width everywhere a position
  is computed. The posting directory stayed fixed-width and got *narrower* (24 → 16 bytes), so
  `postingAt` is still arithmetic and its stride shrank; both restart arrays stayed `u32`, because a
  restart offset is reached by multiplication.

- **The key block's saving has no crossover, and the term region's does — do not describe them
  alike.** A varint *header* is two bytes where a `u32` pair is eight below 128 bytes and four below
  16 KiB, so a version-2 key block is never larger than the version-1 block of the same keys, for any
  key this engine can hold. Front-coding is the opposite (see below). `KeyBlockTest` pins the
  inequality **with the six-byte figure beside it**, because an inequality alone is satisfied by a
  change that saves nothing — the same rule that puts a second counter beside `documentsRead == 0`.

- **Front-coding pays on long terms and costs on short ones, and the entry narrowing pays always.** A
  front-coded record is `2 + (length - shared)` against `length`, so the term region only shrinks once
  the average shared prefix exceeds two bytes. Phase 17's measured 30.4 → 19.7 B/doc is mostly the
  eight bytes of entry, not the front-coding — `PostingEncodingTest` pins **both** directions with the
  crossover named, because the tempting response is an adaptive dictionary and phase 11 settled that: a
  layout chosen by anything but the sorted term list breaks byte identity. Do not attribute the saving
  to the mechanism that is easier to describe; that is the error phase 13 caught phase 12 making.

- **`POSTING_ENCODING_SINGLE` reinterprets a field, and that is what an encoding byte is for.** For it
  the term entry's `postingOffset` *is* the ordinal and `postingLength` is zero, so a term matching one
  document costs no posting bytes; a reader must therefore branch on the encoding **before** it treats
  either field as an offset, and bound the ordinal by `documentCount` rather than by the file length.
  Two consequences to keep true. The choice is a **pure function of the posting list** — `cardinality
  == 1`, nothing adaptive, nothing order-dependent — because a flush-written and a backfill-rebuilt
  sidecar are byte-identical and an order-dependent choice would break that while still returning every
  right answer. And the ordinal is inside the region the header checksum covers, so a flip in it is
  caught on *open*; see the two-level-checksum rule in `.claude/rules/testing.md`.

- **An exchange format is not the storage form, and its constants obey the opposite rule.**
  `RoaringPortableFormat`'s cookies (`12346`, `12347`), its offset threshold of 4 and its derivation of
  a container's type from the run bit and the cardinality are **not** on the permanent-ids list in
  `.claude/rules/format-permanence.md` and must never
  be added to it. They belong to a specification this project does not own; if it grows a cookie, that
  file changes to follow it. That list exists because files rabosh has already written must keep their
  meaning, and nothing rabosh writes is in this format — no sidecar reads it, no id was spent on it,
  and `IndexByteIdentityTest`, `PostingEncodingTest` and the golden stores passing unchanged is the
  assertion that says so.

  Two consequences a change must not quietly undo. **The two encoding-selection rules are separate
  copies on purpose**: a run costs `2 + 4n` bytes there and `4 + 4n` here, which moves the array/run
  boundary at exactly four consecutive values, and generalising `BitmapFormat.smallestKind` over the
  run overhead would put a foreign specification one parameter away from the encoding every sidecar's
  byte identity rests on — the `ColumnBounds` precedent exactly, semantics shared and bytes not. And
  **there is no `MemorySegment` decode and there must not be one**: this format's offset header is
  *conditional*, which is one of the five reasons §9.6 declined the layout as a storage form, so
  reading it in place would adopt the cost the decision was taken to avoid. A stream is decoded onto
  the heap, always.

  The decoder is also the one place in the engine that reads bytes with no checksum in front of them
  and no guarantee they came from a compatible domain, so two of its rules invert `BitmapView`'s.
  It **validates every container as it builds it** — ascent, population count, run separation, every
  cardinality — because the values are being copied to the heap regardless. And it **bounds-checks
  rather than requiring an exact tiling**, because a foreign writer's offset header may legitimately
  point anywhere inside its payload. A well-formed bitmap holding a value above
  `BitmapFormat.MAX_ORDINAL` is `UnsupportedBitmapFormatException` and not corruption: the bytes are
  intact, and the boundary is one *value* wide — key 32767 remainder 65534 is fine and 65535 is not.
