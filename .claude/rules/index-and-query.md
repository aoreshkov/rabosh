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
- **A conjunction is uncorrelated and `elemMatch` is correlated, and neither may drift into the
  other.** `and($.items[*].sku eq "A", $.items[*].qty eq 5)` settles each leaf from any value at its
  own path, so it matches a document whose two values came from different elements;
  `elemMatch($.items[*], and($.sku eq "A", $.qty eq 5))` requires one element to satisfy the whole
  operand, and its inner paths are read **from the element** rather than from the document. Two
  spellings because they are two questions. A refactor making the first correlated would look like a
  fix, would change answers, and would be caught by neither differential oracle — both evaluate leaves
  the same way — which is why `CorrelationSemanticsTest` and `ElemMatchTest` pin the two directions
  against each other rather than leaving either to a comment.

  What the second is worth is measured rather than assumed: `CorrelationCost` puts the uncorrelated
  conjunction at **5-6x** the documents a caller keeps on corpora whose element fields vary
  independently, and at **exactly the right ones** where the fields move together. That spread is why
  a composite index is asked for by name and never recommended — a sketch cannot see which shape a
  corpus is.

- **A composite term is exact, and that is a property of storing the tuple rather than hashing it.**
  A term exists only for an element carrying *every* declared field, so an ordinal in the posting list
  is a document with a satisfying element rather than a candidate for one — the plan may decide the
  node and open nothing. Had the tuple been hashed, as `jsonb_path_ops` does, every answer would have
  been candidates-only and the selectivity would depend on a hash quality nobody measured. The cost
  taken instead is visible and bounded: a tuple above `maxTermBytes` is not keyed, and the planner
  applies the same bound to the same bytes so that what the writer dropped is what the query declines.

- **A composite index needs every declared field fixed by equality, and does not care what else the
  query asks.** The two halves of that sentence are not symmetrical and the asymmetry is the whole
  rule.

  **Fewer is unsound.** A query fixing a *subset* of the declared fields gets **no** source from the
  tuple — not even candidates — because a term exists only for an element carrying every declared
  field. An element with `sku = "A"` and no `qty` satisfies `elemMatch(p, sku eq "A")` and contributes
  no term, so any scan of the tuples is a **subset** of the answer and a subset cannot be rescued by a
  recheck. This is the `jsonb_path_ops` limit, inherited deliberately, and it is the reason this kind
  *supplements* the leaf indexes rather than replacing them.

  **More is fine, and is taken.** Extra conjuncts — a range, a negation, a field the index never heard
  of, a second leaf over a path already fixed — are **dropped**, because dropping a conjunct inside
  the existential only widens it: `∃e(A ∧ B ∧ C) ⊆ ∃e(A ∧ B)`. So the tuple narrows *correlatedly*,
  `CompositeChoice.exact` is false, and the element walk decides what survives. Nothing new was needed
  for it — the mark is `OrdinalExpression.All(complete = false)`, the same one a dropped conjunct
  already uses — and the decomposition is intersected on top rather than skipped, so a caller who also
  has ordinary indexes is never worse off than before the tuple could be used at all.

  Getting `exact` wrong is the way this returns wrong answers, and it is checkable: forcing it true
  makes `ElemMatchTest` report a document whose element matched the tuple and failed the extra
  conjunct. Do not derive it from the field count alone — a conjunction can name as many conjuncts as
  the index has fields and still leave one unconsumed.

- **A composite term cannot be scanned by prefix, and the reason is the exactness argument from
  behind.** A tuple's fields are written in declaration order, so a query fixing a prefix of them
  looks answerable by a range scan over the sorted dictionary — no new kind, no id, no version. Both
  premises that were named for it hold: a sub-tuple *is* a byte prefix of the tuple extending it, and
  the run sharing that prefix is contiguous and reachable by a bisect rather than a walk.
  `CompositeTermPrefixTest` pins both, and then pins the third nobody named: **the tuples are not a
  complete record of the sub-tuple.** The property that makes a full lookup exact is the same property
  that makes a partial one lossy, and the presence bitmap cannot repair it because it too means *a
  complete tuple*. Refused on that, not on a measurement — the feature is unsound before it is slow.
  A second, independent refusal is on record for the range half: a numeric signature is decimal text,
  so `10` sorts before `9` and the "range on the next field" was never available for numbers.

- **What the tuple cannot spell, the ordinary indexes still narrow — and whether that is exact turns
  on one quantifier identity.** An element node decomposes into leaves over concatenated paths,
  because the values at `p + r` are exactly the union over the elements at `p` of the values at `r`
  within each. From there:

  | inside the `elemMatch` | decomposition | exact? |
  |---|---|---|
  | one leaf | `leaf(p + r)` | **yes** — there is nothing to correlate |
  | a disjunction | the disjunction of the parts | **yes** — `∃e(A∨B) ⟺ ∃eA ∨ ∃eB` |
  | a conjunction | the conjunction of the parts | **no** — `∃e(A∧B) ⊆ ∃eA ∧ ∃eB` |
  | a nested `elemMatch` | `elemMatch(p + q, …)` | yes at this level |
  | anything negated | declined | — |

  The third row **is** the correlation gap, seen from the other side: it is why a composite term
  exists, and it is why a decomposed conjunction is marked `complete = false` so the element walk
  decides. Getting that mark wrong is the one way this construction returns wrong answers, and it is
  the reason the exactness travels with the rewrite rather than being inferred later.

  This is what §10.6's gate measured as the real gap and it cost **no format change, no index kind and
  no id** — the ordinary indexes a caller already has, put in front of a walk that measured at ~400 ns
  per element. An element ordinal space was refused against it; the numbers are in the open-work
  index's Tier 2 record.

- **`explain` may report a type mismatch and may never repair one.** `ExplainTypeNote` says when a
  leaf's family disagrees with the types the catalog observed at its path — a numeric comparison where
  a fifth of the values are strings — because in a third-party archive that is the normal state of the
  world and the symptom is a query returning fewer rows with nothing to say why. It changes no answer,
  no plan and no bound, which is exactly why `explain` is the right place for it: nothing there can be
  got wrong in a way that costs a document. **Anything that made a numeric predicate match a string
  would be a second definition of `ColumnPredicate.matches` and would break skipping.** The family
  travels on `Normal.Leaf` from the lowering, because `ColumnPredicate.kind` is `internal` to
  `rabosh-index` and re-deriving it in the query layer would be that second definition arriving by the
  back door. A leaf that brackets to nothing — `EXISTS`, `IS NULL`, a mixed `IN` — reports nothing,
  since there is no family for the data to disagree with, and the note is over **every** leaf rather
  than only indexed ones, because a path with no index is where a caller has no other signal at all.

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
