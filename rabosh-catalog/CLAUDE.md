# rabosh-catalog — module conventions

The root `CLAUDE.md` indexes the cross-cutting design rules. The ones that govern this module are in
`.claude/rules/index-and-query.md` and `.claude/rules/format-permanence.md`; testing conventions in
`.claude/rules/testing.md`.

`ValueSignature` and `ValueBoundsBuilder` live in `rabosh-catalog` and are **shared, never duplicated**.
The catalog counts distinct values and bounds a path with them to decide whether it is worth an index;
`rabosh-index` keys its term dictionary and bounds its columns with the same two. Both failures are silent
rather than loud, which is the test for whether something belongs in one place: an estimator that disagreed
with the index it recommended would advise against indexing a low-cardinality column, or build a dictionary
a query could never spell, and a bound computed two ways would delete documents from a result.

The bound **codec** is the deliberate exception and the reasoning is written into `ColumnBounds`: the bytes
are duplicated between `SketchFormat` and `ColumnFormat` because publishing a permanent on-disk shape as
public API to save eighty lines would make every private-format change an ABI event, and because a codec
that disagreed fails loudly, on decode, in the module that wrote it. Semantics shared, bytes not.

**A budget here reports and does not stand anything down, which is the opposite of what the same
budget does in `rabosh-index`.** `maxPaths` has always counted its overflow into
`InferredSchema.truncatedPathEstimate`; `maxChildren` and `maxDepth` now record a
`TruncatedWalkException` in `SchemaCatalog.problems`, and the segment stays covered with its partial
model written. The asymmetry is the severity: an under-counted path is an `IndexCandidate` that ranks
low, while an under-recorded index is a document that goes missing. Do not "make them consistent" by
dropping a truncated sketch — that trades an understated count for no count at all.

The counter is **not** in the sidecar, and that is a decision with a price. `.sk` is a flat payload
whose reader rejects trailing bytes, so persisting it costs `SketchFormat` a version; a version bump
buying a report is not a trade this engine takes. The consequence to keep true: silence from a model
assembled out of sidecars means *not observed in this process*, never *did not happen*, and nothing
may default that to zero anywhere a caller would read it as a claim.

**`CatalogStep` holds a collapse and a pattern, and only one of them can come from data.**
`AnyElement` is emitted by `SegmentSketchBuilder` because a document *has* positions this type will
not distinguish; `AnyDescendant` is emitted by nothing, because `..` is what a caller wrote. That
asymmetry is the whole argument for one type rather than two — the precedent said `VariantPath`
should not gain a wildcard, and the reason it should not is that every existing caller relies on
`select` returning one value, which no sketch key does. So the invariant is a test rather than a
comment: *no sketch, over any corpus, ever emits a path containing a descendant*. If a change makes
that false, the type should be split instead of the test relaxed.
