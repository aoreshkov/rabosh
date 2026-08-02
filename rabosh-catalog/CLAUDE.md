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
