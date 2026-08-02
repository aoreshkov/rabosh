---
description: rabosh-core's write ordering, crash recovery, tombstone and compaction rules, file reclamation and block verification — the invariants whose failure mode is a lost or resurrected document.
paths:
  - "rabosh-core/**"
---

Cross-cutting rules are indexed in the root `CLAUDE.md`; module conventions in
`rabosh-core/CLAUDE.md`; testing conventions in `.claude/rules/testing.md`. On-disk shapes obey
`.claude/rules/format-permanence.md`.

- **A block is verified once where there *is* a once, and on every read where there is not.** A
  segment has three metadata blocks — index, bloom, dictionary — and `SegmentTable.open` checks all
  three when it maps the file. It has thousands of data blocks and a lookup touches one, so those are
  checked as they are read. The two rules are not interchangeable, and the index block quietly obeyed
  the wrong one until phase 13: re-verifying it per lookup cost a CRC32C over one entry per data block
  in the segment, **3.0 µs of a 4.15 µs get** at the defaults, growing linearly with segment size.
  `readBlockCheckedAtOpen` is the reader that states the once-check as a precondition; use it for the
  three and never for a data block. What it gives up is a flip *after* the mapping, which the bloom
  and the dictionary never caught either — checksums here catch a bad write and a bad disk, both of
  which happen before a reader maps the file.
- **The log is appended before the memtable is touched, always.** A memtable holding an entry the
  log does not is the one ordering that survives a crash as an unacknowledged write that is
  nevertheless present. `DocumentStore.write` is the single place this order is decided; `put` and
  `delete` route through it rather than having fast paths of their own.
- **A torn tail may be dropped; anything that would lose an acknowledged commit is reported.** The
  checks that separate the two are in `LogReader` and none of them is a heuristic: a checksum failure
  with a readable record behind it, a sealed log with an incomplete tail, an unreadable header in any
  log but the newest, and a gap in the sequence numbers are all corruption. Do not "simplify" one of
  them away — each catches a case the others cannot see.
- **A tombstone may only be dropped at the bottom-most level that can contain the key, and only
  below the oldest live snapshot.** Dropping one while a deeper level still holds what it hides
  deletes the deletion, and the document comes back. Keeping a tombstone too long costs space;
  dropping one too early costs correctness, so the asymmetry is deliberate. The superseded-version
  rule is stated separately in `runCompaction` because it fails differently.
- **A file is deleted only after its last reference drops and its arena closes**, and only if it
  *departed* the tree. Order first: on Windows a mapped file cannot be deleted at all, so the
  deterministic unmap is what makes reclamation possible. Departure second: hanging deletion off
  "the last reference went" alone would make `close()` delete the live segments of a store that is
  merely shutting down.
- **Ordering everything: log, then memtable, then segment, then manifest, then delete.** A segment
  is forced before the manifest names it, and logs are deleted only after that record is forced. A
  crash between any two steps leaves an unreferenced file, which the next open sweeps — never a
  manifest naming a file that is not there.
- **`DocumentStore.write` publishes the read bound last.** A batch is atomic to one view because
  `visibleSequence` moves only once every operation is in the memtable. The unit is the view, not
  the call.
