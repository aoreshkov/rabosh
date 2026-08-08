# rabosh-jsonpath — module conventions

The root `CLAUDE.md` indexes the cross-cutting design rules; testing conventions are in
`.claude/rules/testing.md`. Note which rules do **not** reach here: this module writes nothing to
disk, so `format-permanence.md` and `index-sidecar-format.md` have no claim on it, and it holds no
plan, so `index-and-query.md`'s planner rules do not either.

`rabosh-jsonpath` implements RFC 9535 over a `Variant` the caller is already holding: compile a
query, expand it to `VariantNode`s. It is **beside the dependency chain, not in it** — it depends on
`:rabosh-variant` and nothing else, and nothing in the chain depends on it.

## The four things a change here must not break

**The module stays beside the chain.** An edge from `rabosh-core`, `rabosh-catalog`,
`rabosh-index`, `rabosh-query` or `rabosh-api` onto this module is the change that makes everything
below unsafe, and it is not a refactoring — it is a decision, and the argument against it is the next
paragraph. A *test*-only edge in the other direction is fine and there is one: the differential
depends on `:rabosh-catalog`.

**Two definitions of comparison are allowed here precisely because they cannot meet.** RFC 9535's
rules and `ColumnPredicate.matches` disagree in ways neither can be implemented in terms of the
other — a `Predicate` leaf is existential over every value at a path while `@.a` here *is* the array;
`Not` there is the complement of the *document* while `!` here is the complement of the *node*;
`Nothing == Nothing` is true here and has no counterpart there. Two comparison semantics in one
repository are a defect exactly when both can decide the same question. Nothing here decides which
documents a `Query` returns, so they never do — and a facade method delegating to this module would
be the first time they could.

**The claim is scoped by the artefact, and its size is asserted rather than described.**
`rabosh-jsonpath` implements RFC 9535 less `match` and `search`; `VariantPath.parse` and
`CatalogPath.parse` remain the engine's own grammar and are still not JSONPath. The compliance gate
runs **647 of 703** cases, excludes exactly **56** by tag, and asserts that each excluded case is
*refused* by `compile` rather than answered. Do not widen the sentence in `JsonPathQuery`'s KDoc
until the number is 703; do not narrow the exclusion into a skip.

**The walk carries no budget and the query carries two.** A bound on the walk truncates a nodelist,
which is a wrong answer with nothing to say so; a bound on what the caller wrote costs no answer at
all. So the limits are 1024 selectors and 64 levels of nesting, both checked while parsing, and the
descendant walk is iterative over an explicit stack — a `Variant` built through `VariantBuilder` is
never re-checked against `DEFAULT_MAX_JSON_DEPTH`, so a recursive walk would be a stack overflow
reachable from data. `JsonPathQueryTest` builds a 20 000-deep document to say so.

## Two findings worth keeping

**`CatalogPath.toString()` is not a JSONPath query, and that is now checked.** `$.items[*]` parses
under both grammars and means different things: `AnyElement` selects array elements, RFC 9535's `*`
selects every child of an object *or* an array. The RFC 9535 selector that does mean `AnyElement` is
the slice `[:]`, which is what `NodeWalkDifferentialTest` renders — and why that differential can be
an *equality* rather than an approximation. Phase 20 left this as a documentation question; it is
answered, in the direction of not claiming the rendering.

**The errata are about PEG ordering and change nothing here.** All five against RFC 9535 — 8343,
8352, 8353, 8354 and 8779 — concern prioritised choice in the ABNF, which is a failure mode a
recursive-descent parser with explicit lookahead does not have. Record it when the list grows;
do not read a sixth erratum as evidence the grammar is unsettled without checking whether it is
another one of these.
