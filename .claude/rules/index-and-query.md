---
description: The soundness rules an index, a column, a plan or the facade may never break — candidates versus certainties, coverage, bounds, type bracketing, negation, index-build lifecycle and the durability of derived data.
paths:
  - "rabosh-index/**"
  - "rabosh-query/**"
  - "rabosh-api/**"
  - "rabosh-catalog/**"
---

Cross-cutting rules are indexed in the root `CLAUDE.md`; each module's own conventions in its
`CLAUDE.md`; the byte layouts these rules read in `.claude/rules/index-sidecar-format.md`; the
differential suites that hold every claim here in `.claude/rules/testing.md`.

## What an index and a facade may change

- **A facade may change ergonomics, never answers.** `Rabosh` delegates every query to one
  `QueryEngine` and holds no evaluator of its own. The moment it starts rewriting a query, choosing a
  plan, or deciding what a path means, it has become a sixth module pretending to be a convenience —
  and the differential suite is what says so.
- **An index may change query speed, never query answers.** Every planner change is verified
  against a brute-force scan over the same data. In an LSM that takes three parts, and all three are
  load-bearing: an index yields **candidates**, each rechecked against the version the snapshot can
  actually see; everything the index cannot answer for is **scanned**; and the recheck runs the *same*
  walk that built the index, so "does this document match" is answered by the code that decided what to
  index rather than by a second definition of what a path means.
- **An index over a segment is sound at a snapshot if and only if the snapshot's sequence is at or
  above that segment's largest sequence.** An observation reports only the newest version of each key,
  and a segment holds older versions precisely when a snapshot pinned them — so a reader older than the
  segment is entitled to a version the index never recorded. Failing segments read as *stale* and are
  scanned, exactly as segments with no sidecar are. `IndexCoverage` is what keeps both visible. Do not
  weaken this into "the index is usually current": the failure is a silently missing document, and a
  differential test taken at the current sequence can never catch it.
- **Indexes are per-segment immutable sidecar files, never part of document data.** This is
  what makes retroactive indexing cheap; putting index state inside segments would force a
  rewrite on every `createIndex` and defeat the point of the project.
- **Compaction awareness is structural, and a posting-list merge would be worse.** Ordinals are
  positions within a segment, so a compaction renumbers all of them; remapping two input posting lists
  costs more than reading the term out of the document the compaction is already holding. There is no
  merge hook and there should not be one.

## Bounds, predicates and plans

- **A bound never narrows.** Every value at a path lies inside the segment bound and inside its block's
  — *including the residual values a column did not store*, because a residual value of a predicate's
  own family sitting outside the bound would make skipping unsound. Truncation of a text bound always
  widens: the minimum is a prefix, the maximum is a prefix with its last byte raised, and a value too
  long to bound leaves *no upper claim*, which means `+∞` and not an empty range. A bound that excludes
  a value it covers deletes documents from a result, silently.
- **A null slot holds the type's zero, and that zero must never reach a bound.** A filler leaking into
  a minimum widens it — correctness-safe, and therefore invisible to every differential test. The
  column simply stops pruning, quietly, forever. It has its own targeted assertion for that reason.
- **Type bracketing is part of the query contract, because skipping depends on it.** A numeric
  predicate matches numeric values only, a text predicate matches strings only, and anything else is
  not a match and not an error. Without it, a column whose numeric bound misses could not be skipped
  merely because the segment also holds strings at the path. `ColumnPredicate.matches` is the only
  definition, used by the column scan, the fallback document scan and every query leaf alike —
  including leaves over paths with no column, and including the strictness of `<` and `>`, which is
  why exclusive bounds live in `ColumnPredicate` rather than being bolted on by a caller.
- **A negated leaf is never a flipped operator.** `not($.a >= 10)` holds for a document whose `a` is
  a string and for one with no `a`; `$.a < 10` holds for neither. The normaliser keeps the negation on
  the leaf and applies it to the *document's* answer. The rewrite is the most natural-looking
  simplification in the whole layer and it deletes documents from a result, silently, on any corpus
  where a path holds more than one type — so it is pinned by a test over data, not over syntax.
- **A plan's candidates are a superset and its certainties a subset, and the gap is what gets read.**
  An `Or` node needs every operand present at a segment, because a branch of a union nobody looks for
  is a missing answer; an `And` node may drop operands, because dropping a conjunct only widens — but
  a drop costs the node its *certainty*, and a leaf with no index anywhere is a drop like any other.
  Forgetting to record one is a residual predicate silently ignored, which is exactly the bug the
  differential suite caught during phase 8.

## The lifecycle of an index build

- **Stopping an index build is safe because coverage is honest, and that is why cancellation has no
  rollback.** A build cancelled by `IndexBuild.cancel`, one a crash interrupted, one still running and
  one whose segment overflowed its term budget all leave the same thing: an index that is *defined and
  partly covered*. `IndexCoverage` reports it and a query scans what is not covered, so there is
  nothing to undo and no compensating write to get wrong. Resumption is the same fact read forwards —
  `createIndexInBackground` called again skips every covered segment without reading it, which is why
  it deliberately does **not** return early the way `createIndex` does when the definition exists.
  There is no resume verb because there is no separate state to resume from; do not add one, and do not
  add a "build is incomplete" flag, which would be a second, weaker copy of `IndexCoverage`.

  Two mechanics under that. **Cancellation is a wrapping `SegmentObserver`, never a change to
  `rabosh-core`** — `DocumentStore.backfillSegment` already skips a segment whose `beginSegment`
  answers `null` without opening a cursor, so "stop" costs a map lookup per remaining segment and
  scheduling policy stays out of the storage core. And **a cancelled pass finishes the segment it is
  in rather than abandoning it**: an observation writes nothing until it completes, so abandoning would
  throw away a nearly finished scan and leave exactly the segment a resumed build must redo first.

- **A worker thread is shut down, never interrupted.** `IndexCatalog.stopBackgroundBuilds` cancels
  every build by flag and then calls `ExecutorService.shutdown`. `shutdownNow` interrupts, and an
  interrupt inside a `FileChannel` operation *closes the channel* — for every thread, not just the
  interrupted one — which in an engine that maps every segment through one is a way to lose a segment
  the store is still using in order to stop a build a few milliseconds sooner. The wait is bounded and
  overrunning it is *reported*, not thrown: an abandoned build costs a rescan and nothing else.

- **A background build is stopped before anything it is scanning is closed.** `IndexCatalog.close`
  calls `stopBackgroundBuilds` first, and `Rabosh.close` calls it before `store.close()` — in front of
  the store-then-catalog ordering rather than disturbing it. Otherwise a build is failed by a
  `checkOpen` it did not deserve and, worse, a thread is left writing sidecars into a directory the
  caller is about to delete. Cancelled rather than waited out, for the asymmetry this layer runs on: a
  flush holds data the log would otherwise have to keep, and an index build holds nothing.

## Durability of derived data

- **Sketches and indexes are derived; documents are not.** That asymmetry sets the durability rules
  for `rabosh-catalog` and `rabosh-index`, and it is the only place in the engine where a relaxation
  is allowed. A `.cat`, `.idx` or `.pst` sidecar is not forced before the manifest names its segment,
  because losing one costs a rescan rather than data. What must stay true: a missing sidecar reads as
  **not collected**, never as *collected and empty* — `CatalogCoverage` and `IndexCoverage` are what
  keep that visible. And an observer that throws costs its own segment's derived data and nothing
  else; the write carries on.
- **An index *definition* is not derived data, and its durability rule inverts the one above.**
  Losing a `.pst` costs a rescan; losing `INDEXES` means the store silently stops having an index an
  operator created, with nothing anywhere to say so — a lost instruction. So the registry gets
  `CURRENT`'s treatment: whole file, temporary name, `force`, `ATOMIC_MOVE`, directory forced after.
  And `createIndex` makes the definition durable **before** any posting file exists, so a crash leaves
  an index that is defined and uncovered — a state every query already handles — rather than posting
  files nothing knows about. Index ids are never reused after a drop, for the same class of reason: a
  stale `.pst` left by a crash must not become readable as a later index's postings.
