# Changelog

All notable changes to this project are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) — with one qualification that matters
more here than the version number does.

**Two guarantees, at different strengths.** The Kotlin API is major-version zero: any signature may
change in any release, and `0.x` gives you no compatibility promise at all. The **on-disk format** is
declared and stable — a store written by an earlier release opens on every later one — and that
promise does not wait for `1.0`. Anything affecting it is stated in
[COMPATIBILITY.md](COMPATIBILITY.md) first and only summarised here.

## [Unreleased]

### Added

- **`rabosh-jsonpath`** — a seventh published module: [RFC 9535](https://www.rfc-editor.org/info/rfc9535/)
  JSONPath over a document you are already holding. `JsonPathQuery.compile(…)` and then
  `forEachNodeIn` / `nodesIn`, answering with the `VariantNode`s `rabosh-variant` already defines.

  It expresses the two things a `CatalogPath` cannot: **a condition** — `$.items[?@.sku == 'A' && @.qty == 5]`
  selects the element where *both* hold, which is the recheck callers were writing by hand — and **a
  descendant segment**, `$..sku`, for documents whose nesting depth is not known in advance.

  Scoped deliberately. `match` and `search` are defined over RFC 9485 I-Regexp and are **refused at
  compile time** rather than half-answered, so the claim is "RFC 9535 less `match` and `search`": 647
  of the JSONPath Compliance Test Suite's 703 cases run and pass, and the 56 excluded are excluded by
  tag with the count asserted. The module depends on `rabosh-variant` and nothing else, and nothing
  in the storage chain depends on it — which is what keeps RFC 9535's comparison rules and the query
  language's, which genuinely disagree, from ever deciding the same question.

- **Correlated queries over one array element** — `elemMatch`, and a composite index to answer it.

  ```kotlin
  // only the document where ONE item has both
  Query.where(elemMatch("$.items[*]", and(path("$.sku") eq "A", path("$.qty") eq 5)))
  db.createIndex(IndexDefinition.composite("$.items[*]", "$.sku", "$.qty"))
  ```

  A plain conjunction over an array path is existential in each leaf independently and goes on meaning
  exactly what it did — `elemMatch` is a different question with its own spelling, and its inner paths
  are read from the element rather than from the document. The new `IndexKind.COMPOSITE_TERM` keys the
  *tuple* of an element's declared fields, so the answer is **exact**: the plan decides it and opens no
  document. It answers a fully known equality conjunction and nothing else — Postgres `jsonb_path_ops`'
  limit — and anything it cannot spell falls back to the walk with the same answer.

  It is opt-in and never recommended, because the measurement behind it says the benefit is a property
  of the data: the uncorrelated conjunction returns **5-6x** the documents a caller keeps where element
  fields vary independently, and exactly the right ones where they move together
  (`./gradlew :rabosh-bench:runCorrelationCost`).

  **What the composite index cannot spell, your ordinary indexes now narrow.** An `elemMatch` over a
  range, over some of an index's fields, or over a disjunction is rewritten into leaves over the
  concatenated paths, so an existing index over `$.items[*].sku` does the work before the element walk
  runs. A single-leaf `elemMatch` is not correlated at all and is answered **exactly** — zero documents
  opened — while a decomposed conjunction narrows and the walk decides. No new index kind, and nothing
  to configure.

### Compatibility

No format change in either sense that matters. The JSONPath module writes nothing to disk. The
composite index is **additive**: a new permanent index-kind id, a `.pst` that is a posting file in
every byte but one header field, and the declared fields carried in the registry through the kind byte
rather than through a version bump — no version was bumped, no section kind was spent, and no golden
store was added. A store written by an earlier release opens unchanged; an earlier release meeting a
store that defines a composite index reports it as written by a newer build, which is what an unknown
id has always meant here. No existing `.api` dump lost an entry; one was added, for the new module.

## [0.1.0] — 2026-08-02

First published release. Six modules under the Maven group `app.oreshkov`, with **no runtime
dependencies** beyond the Kotlin standard library.

```kotlin
dependencies {
    implementation("app.oreshkov:rabosh-api:0.1.0")
}
```

### Added

- **`rabosh-variant`** — the Apache Open Variant binary encoding: codec, zero-copy path navigation
  and a streaming JSON builder. A document is stored as typed binary, not as text.
- **`rabosh-core`** — the LSM storage core: write-ahead log, memtable, SSTable segments, manifest,
  levelled compaction and MVCC snapshots. Single writer, many concurrent readers; segments are
  mapped through the FFM API rather than copied.
- **`rabosh-catalog`** — schema inference from what was already written: per-segment path sketches,
  HyperLogLog cardinality estimation and a merged collection model. Deriving the model scans no
  documents.
- **`rabosh-index`** — retroactive index sidecars: an in-repo compressed bitmap, inverted path
  indexes and shredded typed columns. Indexes are per-segment immutable sidecar files, buildable
  after the data is written, in the background, cancellable and resumable, and usable while they
  build.
- **`rabosh-query`** — a predicate AST, a planner that intersects indexes as bitmaps and prunes
  segments and blocks it can rule out, and execution with projection and limits.
- **`rabosh-api`** — `Rabosh`, one object owning the store, the schema catalog and the index
  catalog, so the wiring below it does not have to be remembered.

Also shipped: interoperability with the portable Roaring bitmap format, so a set of document
positions can be exchanged with Lucene, Spark or pyroaring; and `Explain`, which reports measured
cardinalities rather than estimates.

### Compatibility

The **on-disk format is declared and stable** as of this release, at the versions tabulated in
[COMPATIBILITY.md](COMPATIBILITY.md) — a store written by 0.1.0 opens on every later release. The
**Kotlin API is major-version zero** and carries no such promise: any signature may change in any
release.

### Verification

Every jar carries a [build provenance attestation](https://docs.github.com/actions/security-guides/using-artifact-attestations-to-establish-provenance-for-builds):

```sh
gh attestation verify rabosh-api-0.1.0.jar --repo aoreshkov/rabosh
```

[Unreleased]: https://github.com/aoreshkov/rabosh/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/aoreshkov/rabosh/releases/tag/v0.1.0
