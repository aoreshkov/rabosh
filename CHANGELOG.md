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

Nothing yet.

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
