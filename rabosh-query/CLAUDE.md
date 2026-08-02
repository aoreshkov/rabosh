# rabosh-query — module conventions

Cross-cutting design rules live in the root `CLAUDE.md`; testing conventions in `.claude/rules/testing.md`.

**A query's universe is the snapshot's own version, never the store's live set.** `IndexCatalog.pin`
reads `DocumentStore.liveSegmentNumbers`; a `Snapshot` reads the `Version` it pinned; a compaction makes
the two disjoint. A plan partitions `Snapshot.segmentNumbers`, and a segment is an ordinal source only if
it is in that set **and** in the reader's `usableSegments` — everything else in the version is scanned
through `DocumentStore.scanSegments`. Partitioning the live set instead scans files the snapshot cannot
see and skips every one it can, which is a missing document and not a slow query.

**Filtering speaks `CatalogPath`; projection speaks `VariantPath`.** A catalog path describes a *set* of
locations, which is what "does any value here match" needs and what "what is the value here" has no
answer for. `$.tags[*]` is therefore rejected as a projection, exactly as `$.items[0]` is rejected as a
filter. Two types because they are two questions, and neither may be converted into the other. That
split is also what settled the repeated-path question phase 12 was expected to answer: a projection
cannot spell a wildcard, so a column over one can never be asked to serve a row, and no projected path
has more than one value. `ProjectionColumns` binds a field only where the `VariantPath` is a chain of
field steps naming a column's `CatalogPath` exactly.

**Projection push-down may change where a value is read from, never what the value is.** Deciding a
predicate and returning a value are different demands on a column, and the difference is a trap worth
naming: the numeric family is stored at **one common scale per segment**, so a segment holding
`{"price":10}` beside `{"price":9.99}` reads the first back as `10.00`. Equal as a number, and not
what the document says. `ColumnFormat.SECTION_FIDELITY` is what stops that reaching a caller, and two
rules keep it sound. The flag is decided **where the document's value is in hand**, in `ColumnBuilder`,
because a reader has only unscaled integers and a scale — exactly the information that cannot answer
it. And the value is rebuilt through `VariantBuilder.appendNumberLiteral`, which is the *parser's own*
decision about what a JSON number encodes to: `decideNumber` strips trailing zeros, so a parsed
document holds the canonical form, and canonicalising once more on the way out returns precisely what
was there. Choosing integer-or-decimal here instead would be a second definition of that rule, and the
two would only have to disagree once. Push-down is **all-or-nothing per row** for the same class of
reason — a document read serves every field at once, so mixing sources doubles the ways one value can
be wrong while saving nothing.
