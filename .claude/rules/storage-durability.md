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

- **A checkpoint obeys the ordering rule in the *target* directory, not only in the source.** *Log,
  then memtable, then segment, then manifest, then delete* governs `writeCheckpoint` exactly as it
  governs a flush: every data file is linked or copied **and forced** before the manifest naming it is
  written, and `CURRENT` is written last of all. A checkpoint that forced its manifest first is the
  same bug in a new place and fails the same way — a directory that opens and then cannot find a
  segment. Two consequences that are decisions rather than details. The **snapshot is held open across
  the copy**, which is what stops a compaction reclaiming a segment out from under it; that is why the
  snapshot is in the design rather than being a way to pick a sequence. And **no log is copied**,
  which is correct only because `checkpoint` flushes first — a copied log would replay into sequence
  numbers the checkpoint's own manifest has already issued.

  What core copies is *every file numbered after a live segment*, never a list of suffixes:
  `rabosh-core` does not know what a `.cat`, `.idx`, `.pst` or `.col` is and must not learn, so a
  sidecar kind added later travels with no change here. The one file that is **named** rather than
  numbered is `INDEXES`, which is `IndexCatalog.copyRegistryTo`'s job and is why `Rabosh.checkpoint`
  exists rather than the facade delegating and stopping. Losing it would lose an *instruction* rather
  than derived data — the inversion `index-and-query.md` states — and would leave the posting files as
  orphans for the next sweep.

  A failed checkpoint is not unwound, deliberately: the target is not a store until `CURRENT` names
  its manifest, so a partial one is a directory to throw away rather than a state to repair. What is
  asserted instead is that the **source is unharmed**, at every step, by the fault-injecting
  filesystem. Note which step the fault is armed on: the segments are *hard-linked*, so no byte is
  written for one and a `WRITE` fault never fires — `FORCE` is the step that happens either way, and
  it is the one the ordering rule is about.

- **`deleteRange` is point deletes, and staying that way is the decision.** One snapshot scopes the
  whole loop, keys are collected a batch at a time rather than all at once, and the next scan resumes
  at `Key.successor()` of the last key handled — an inclusive bound restarted at that key would rescan
  a range whose head is now a tombstone. No new operation id, no format change, no change to what a
  merge emits or what `EntryCursor` collapses, and above all no change to the tombstone-drop rule,
  which is on the short list of invariants that fail by returning a deleted document to a reader. A
  real range tombstone is the other design and the format has room for it; it needs the
  bytes-written-per-byte-retired measurement first, and that question belongs in the open-work index.

- **The `LOCK` file's byte zero is the lock and everything after it is a diagnostic.** `tryLock()`
  with no arguments takes `[0, Long.MAX_VALUE)`, and a Windows file lock is *mandatory* — so a second
  process could not read a holder record written inside it, which is exactly when it wants to. Locking
  one byte and writing `pid=… startedAt=…` after it leaves the record readable on every platform, and
  the two regions overlap at byte zero so a build using either scheme still excludes one using the
  other. The record is **not** forced and is not part of any ordering rule: losing it costs a better
  error message and never a document. An empty record reads as *holder unknown*, which is what a store
  last opened by an older release looks like, and `LockHolder.isRunning` checks the start time as well
  as the pid because pids are reused.
