# rabosh-core — module conventions

The root `CLAUDE.md` indexes the cross-cutting design rules. The ones that govern this module are in
`.claude/rules/storage-durability.md` and `.claude/rules/format-permanence.md`; testing conventions in
`.claude/rules/testing.md`.

`rabosh-core` must not depend on `rabosh-catalog`. Sketches are collected during flush and
compaction through **`SegmentObserver`**, which `core` declares and `catalog` implements. It hooks
into `SegmentWriter.add`, the single funnel every document passes through on both flush and
compaction; `SegmentWriter` opens and closes the observation itself, so a flush and a compaction get
it without either of them knowing there is an observer. `StoreOptions.segmentObserver` installs one
and `DocumentStore.backfill` replays existing segments through the same contract — the index builds use
exactly that, so do not write a second one.

That slot holds **one** observer, and it must stay one. `CompositeSegmentObserver` in `rabosh-index` is how
a catalog and an index catalog share it. Making the option a list would move failure-isolation policy into
core, where `ObservationGuard` deliberately keeps it per observer — and the composite has to catch per child
for the same reason, or one layer's throw costs the other its segment.
