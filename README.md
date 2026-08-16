# rabosh

[![Maven Central](https://img.shields.io/maven-central/v/app.oreshkov/rabosh-api?color=blue)](https://central.sonatype.com/artifact/app.oreshkov/rabosh-api)
[![CI](https://github.com/aoreshkov/rabosh/actions/workflows/ci.yml/badge.svg)](https://github.com/aoreshkov/rabosh/actions/workflows/ci.yml)
[![API docs](https://img.shields.io/badge/API-Dokka-7F52FF)](https://aoreshkov.github.io/rabosh/)
[![JDK 25](https://img.shields.io/badge/JDK-25-437291)](#building)
[![Licence: Apache 2.0](https://img.shields.io/badge/Licence-Apache%202.0-blue)](LICENSE)
[![OpenSSF Scorecard](https://api.securityscorecards.dev/projects/github.com/aoreshkov/rabosh/badge)](https://scorecard.dev/viewer/?uri=github.com/aoreshkov/rabosh)

An embedded JSON storage engine for the JVM, written from scratch in Kotlin. It stores JSON of
unknown structure without a schema, works out what that structure actually is as a by-product of
normal storage maintenance, and lets you add indexes to data that is already on disk.

> **Status: early development.** The storage engine is complete end to end: the Variant codec —
> encoder, zero-copy reader, path navigation, JSON parser and shared dictionary — plus the whole
> LSM-tree. A checksummed write-ahead log, a sorted memtable, immutable sorted segments with block
> indexes and bloom filters, a manifest, levelled compaction, MVCC snapshots and range scans. On top
> of it, the catalog: per-segment path statistics collected during flush and compaction, schema
> inference, and index recommendations. And on top of that, retroactive indexing: a compressed
> bitmap, per-segment index sidecars, `createIndex` over data already on disk, inverted indexes for
> equality, `IN` and existence, and shredded typed columns for ranges. And on top of *that*, the
> query layer: a predicate AST, a planner that intersects indexes as bitmaps and prunes what it can,
> execution with projection and limits — every plan verified against a brute-force scan. Hardened
> with a fault-injecting filesystem, format-compatibility golden files and benchmark suites. And, on
> top of all of it, `Rabosh`: one object that owns the store, the model and the indexes, so none of
> the wiring below has to be remembered. Plus interop with the portable Roaring bitmap format, so a set
> of document positions can be handed to Lucene, Spark or pyroaring and read back, and index builds
> that run in the background — cancellable, resumable, and usable while they run. Nothing here is
> production-ready. The **on-disk format is declared and stable** — a store written by an earlier
> release opens on every later one ([COMPATIBILITY.md](COMPATIBILITY.md)) — and the **Kotlin API is
> tiered**: a small stable core moves only under a deprecation cycle, everything else may change in
> any release and says so with an opt-in marker ([STABILITY.md](STABILITY.md)). The runtime contract
> an embedding application has to obey is [INTEGRATION.md](INTEGRATION.md).

## Why

Ingesting JSON whose shape you do not know forces an unpleasant choice. Freeze a schema up front
and you reject or silently mangle the data that does not fit. Store the text opaquely and every
read pays a full parse, with no way to index anything.

Neither matches how this actually goes. The data arrives first. Understanding of it arrives
second. The need to query it quickly arrives third — usually after there is already a lot of it.

rabosh is built around that order:

1. **Write blind.** Any JSON is accepted and stored in a compact, path-navigable binary form.
   No schema, no rejection, no upfront modelling.
2. **Model later.** The engine derives which paths exist, how often, with what types and value
   distributions. This is computed during flush and compaction — passes the engine already
   makes — so it costs effectively nothing and is never stale.
3. **Index later.** Indexes and columnar projections are built *retroactively* against data
   already written, without rewriting or re-ingesting a single document.

The third point drives the design. Indexes live in per-segment immutable **sidecar files**,
never inside the document data. Creating an index on an existing collection is a sidecar build,
not a migration — and because a query uses sidecars where they exist and scans where they do not,
the index is usable while it is still being built — and the build itself can run in the background,
reporting progress by segment and stopping when you ask it to.

The governing invariant: **an index may change how fast a query runs, never what it returns.**

## Using it

All three of those steps, through one object:

```kotlin
Rabosh.open(Path.of("data")).use { db ->
    db.put(Key.of("user:1"), """{"name":"ada","team":"analytics"}""")   // write blind
    db.flush()

    println(db.schema().render())          // model later — nothing scanned the store to know this
    db.indexCandidates().forEach(::println)

    db.createIndex(IndexDefinition.inverted("$.team"))                  // index later

    db.query(Query.where(path("$.team") eq "analytics")).use { rows ->
        while (rows.next()) println(rows.key)
    }
}
```

`Rabosh` owns a `DocumentStore`, a `SchemaCatalog` and an `IndexCatalog`, and owning them is not
cosmetic: the catalogs have to be installed *before* the store opens, because a flush can begin the
moment it does; the index catalog has to be closed or its mappings stay live, which on Windows means
files that can never be deleted; and attaching both layers separately reads every unmodelled segment
twice. `open` and `close` are those three things, decided once. Attaching is where it is also
**faster** rather than merely tidier — one backfill pass through a composite observer instead of one
pass per layer.

Each layer stays public and unwrapped underneath, reached through `db.store`, `db.catalog` and
`db.indexCatalog`. The sections below are about those layers, and every one of them can be wired up
by hand; the facade is what stops you having to. Those three accessors are marked
`@RaboshExperimental` — not a warning-off, just the honest statement that the facade's own surface is
what carries a stability promise and the layers beneath it do not. See
[STABILITY.md](STABILITY.md).

**Before you build on it, read [INTEGRATION.md](INTEGRATION.md).** It is the runtime contract in one
page — the JDK floor, why no `--enable-native-access` flag is needed, one process and one writing
thread, what leaking each `AutoCloseable` costs, and the copy-before-`next()` rule that decides
whether a row you kept still means what you think. Three of those fail silently, which is the whole
reason the file exists rather than living in KDoc on classes you may never open.

That snippet is also a runnable program. `./gradlew :rabosh-samples:runThreeSteps` writes a few
thousand events of a shape nobody declared, prints the model derived from them and the indexes it
recommends, and then runs the same query **before and after** creating one — printing both results
and both sets of counters. The rows are identical; the work is not. See [Samples](#samples).

## Design

### Documents: Apache Open Variant

Documents are stored using the [Apache Parquet Variant binary encoding][variant] — a metadata
dictionary of field names plus offset-navigable value bytes. Reading `user.address.city` is a
binary search through field ids, not a parse of the document.

The specification separates metadata from values so that metadata can be shared, and rabosh
leans on this: **one dictionary per segment**, written once, with every document in it carrying
only value bytes. For the homogeneous-ish JSON that real systems produce, this is the single
largest space saving in the engine.

```kotlin
val variant = Variant.fromJson("""{"user":{"name":"ada","tags":["x","y"]}}""")
variant.select("$.user.tags[1]")?.stringValue()   // "y", without decoding the rest
```

JSON is parsed from UTF-8 bytes straight into the encoding: a string with no escapes is copied
across without being decoded, and malformed input is rejected with a byte offset, line and column
rather than repaired.

Promoted paths are extracted into typed columns following the [Variant shredding
specification][shredding], which also keeps the door open to exporting segments as
Parquet/Iceberg later.

### Storage: LSM

A write-ahead log with CRC32C-framed records, a sorted in-memory table, immutable sorted segments,
and levelled compaction. Single writer, many concurrent readers; snapshots pin a version so
readers never block. Segments are mapped through the JDK's FFM API (`MemorySegment`, not
`ByteBuffer`), so they are not bounded at 2 GB and unmap deterministically.

```kotlin
DocumentStore.open(Path.of("data")).use { store ->
    store.put(Key.of("user:1"), Variant.fromJson("""{"name":"ada","team":"analytics"}"""))
    store.get(Key.of("user:1"))?.select("$.team")?.stringValue()   // "analytics"
}
```

A snapshot is a view that does not move, however much is written after it is taken, and a cursor
walks a key range through one:

```kotlin
store.snapshot().use { snapshot ->
    store.scan(from = Key.of("user:"), to = Key.of("user:~"), snapshot = snapshot).use { cursor ->
        while (cursor.next()) {
            println("${cursor.key} -> ${cursor.document.select("$.team")?.stringValue()}")
        }
    }
}
```

Each key appears once, deleted keys do not appear at all, and documents are read straight out of the
mapped segment rather than copied. An open snapshot holds back the versions compaction would
otherwise drop — which is why it is `AutoCloseable` rather than something the collector tidies away.

**The guarantee: reopening yields exactly the acknowledged prefix.** Every commit whose call
returned is there, nothing that had not returned is there, and nothing in between is missing. By
default each commit is `fsync`ed, so that holds across power loss and not merely across process
death; `Durability.BUFFERED` plus an explicit `sync()` is available for bulk ingest.

Recovery distinguishes a *torn tail* — bytes an interrupted writer left behind, which nobody was
ever told about — from corruption that would lose an acknowledged commit. The first is truncated
away; the second is reported. Per-record checksums are only half of what that needs, so every record
also carries its sequence number: a checksum proves a record is intact, and only the sequence
numbers prove that none has gone missing between two intact ones.

Immutable segments are also what make the rest work: they give the modelling layer a natural
unit to attach statistics to, and the indexing layer a natural unit to build sidecars for.

### Model later

You never described the data, so the engine works out what it is. Statistics are collected on the
flush and compaction passes that walk every document anyway — nothing scans the store — and kept in
a small `.cat` sidecar beside each segment. The model is the fold of those, so a compaction that
merges two segments replaces two sketches with one and there is nothing to invalidate.

```kotlin
Rabosh.open(directory).use { db ->
    println(db.schema().render())
    db.indexCandidates().forEach(::println)
}
```

```
documents: 2000, paths: 11, coverage: 1/1 segments
  $.team
      presence 100.0%  types string=2000  distinct 9  avg 7.0 B  text team-0..team-8
  $.note
      presence 33.4%  types null=334, string=333  distinct ~328  avg 3.2 B  null 50.1%
  $.tags[*]
      presence 200.0%  types string=4000  distinct 5  avg 5.0 B  text common..t3
  …

$.team        -> INVERTED (0.89): present in 100% of documents, 100% string, 9 distinct values
$.profile.bio -> SHREDDED_COLUMN (0.49): carries 49% of the stored bytes as 100% string
```

Array indices collapse, so `$.items[0].sku` and `$.items[7].sku` are one path `$.items[*].sku` —
which is also the path an index over array elements wants. Presence above 100% is not a bug: a path
under an array really does occur three times in a document with three tags.

**Attaching is what makes "later" true.** It reads whatever sidecars exist and scans the segments that
have none, so a store that ran for a year without a catalog can be modelled without re-ingesting or
rewriting a document. `Rabosh.open` does it; `RaboshOptions(backfill = false)` skips the scan for a
store too large to wait on, and the model then reports itself as partial rather than pretending
otherwise. Cardinality is estimated with an in-repo HyperLogLog that is *exact* below a hundred
distinct values — which is where the recommendations are actually being made.

### Index later

An index is a set of document positions within a segment, so the structure everything else in the
indexing layer is built from is a compressed bitmap of those positions. It splits them into 65 536-value
blocks and stores each block in whichever of three encodings is smallest — a sorted array, a flat bitset,
or a list of runs — so a block holding three matching documents costs six bytes, and a block where every
document matches costs eight however many documents that is.

```kotlin
val matching = Bitmap()
matching.add(7)
matching.addAll(100..199)

val bytes = matching.encode()
val view = BitmapView.open(segment, offset, length, file = "0000000042.idx")
view.contains(150)          // read straight off the mapped file
view.and(other)             // one 8 KB block at a time, never the whole bitmap
```

`BitmapView` is where owning the format pays. It validates the structure once and then keeps nothing but
the mapping: `contains`, `rank`, `select` and iteration read the file where it lies, with no parse and no
heap copy of the data. `rank` and `select` are `O(log blocks)` because each directory entry carries the
cardinality of everything before it — a library's format would have made them walk. The two questions a
planner asks before reading anything, "do these overlap" and "by how much", answer without building
anything at all.

The encoding of a set of positions is **unique**: blocks are always in their smallest form, so two
bitmaps holding the same documents produce identical bytes. That is what lets a sidecar written during a
flush be compared with the same sidecar rebuilt by a rescan, without either side knowing how the other
was made — and the test suite compares those files byte for byte rather than comparing their contents.

The layout is rabosh's own, but a set of positions does not have to stay here. The portable Roaring
serialization format — what Lucene, Druid, Spark, CRoaring and pyroaring read — is an import and export
away:

```kotlin
val exported = RoaringPortable.encode(matching)   // hand to Lucene, Spark, pyroaring, …
val imported = RoaringPortable.decode(exported)   // and back, as an ordinary Bitmap
```

That the two formats agree is not taken on trust. The cross-implementation conformance files that
CRoaring, the Java library and the Go port all test against are committed in this repository, and the
export is checked against them **byte for byte** — which is possible at all only because both formats
pick each block's smallest encoding, so there is one shortest way to write a given set and both arrive
at it.

Those sidecars are what an index actually is. Creating one writes new files next to segments that are
already on disk; **no document is rewritten**, which is the whole point:

```kotlin
Rabosh.open(directory).use { db ->
    // Over ten million documents that were written before anybody thought about indexing them.
    val handle = db.createIndex(IndexDefinition.inverted("$.team"))

    // The index layer, reached directly, for what a query does not expose:
    db.snapshot().use { snapshot ->
        db.indexCatalog!!.read(db.store, handle, snapshot).use { reader ->
            reader.coverage                 // 42/42 covered, 0 stale
        }
    }
}
```

Over ten million documents that build takes a while, and it does not have to hold anybody up:

```kotlin
val build = db.createIndexInBackground(IndexDefinition.inverted("$.team"))

db.query(Query.where(path("$.team") eq "analytics")).use { rows -> … }  // right answer, right now
build.progress          // IndexBuildProgress(RUNNING, 12/40 segment(s), 12 built)

build.cancel()          // stops at the next segment; nothing is rolled back
build.await()
```

**Cancelling costs nothing and needs no rollback**, which is a consequence of the sidecar design rather
than a feature bolted onto it. A stopped build leaves an index that covers some segments and not
others — the same state a crash leaves, the same state a running build is in, and one every query
already handles by scanning the rest. Asking for the same index again picks up where it left off,
skipping what is covered without reading it. There is no resume verb because there is nothing to
resume *from*.

Two files per segment, split by how long they live. A `.idx` base holds the document positions' keys and
is written once, when the segment is; a `.pst` holds one index's `value → bitmap` posting lists, and is
what `createIndex` creates and `dropIndex` deletes. So defining an index never rewrites the base, and two
writers never touch the same file. A compaction replaces both alongside the segments it consumed, as a
consequence of the merge rather than as a step anybody has to remember.

A value matching a single document does not get a bitmap at all — it gets the bare position, stored in
the dictionary entry that names it, and the dictionary itself stores each term as the difference from
the one before it. That case is the whole cost of indexing something like an id, and measured over
200 000 documents the two together take such an index from 52 bytes per document to **19.7**.

**A query returns the same documents whether an index exists or not.** That is the invariant the whole
layer is built around, and in a log-structured store it takes some care: an index records the newest
version of each key *in one segment*, so a hit is a candidate that gets rechecked against the version the
snapshot can actually see, and anything the index cannot answer for is scanned. `IndexCoverage` says how
much that is — including segments an old snapshot is entitled to see a version of that the index never
recorded. An index that is still building is simply an index with low coverage, so it is usable
immediately, with no cutover.

The other half of the design is the **shredded typed column**: the values at one path lifted out of the
documents into a typed run, so a scan of that field never opens a document and a range predicate is two
integer comparisons. Reading a field is the same deal: where every projected path has a column, a query
fills its rows from the columns and opens no document at all.

```kotlin
val prices = db.createIndex(IndexDefinition.column("$.price"))

db.indexCatalog!!.readColumn(db.store, prices, snapshot).use { reader ->
    val scan = ColumnQuery.keysMatching(
        db.store, reader,
        ColumnPredicate.numericRange(BigDecimal("100"), BigDecimal("200")),
    )
    scan.keys              // the matching documents
    scan.documentsRead     // 0 — answered entirely from the column
    scan.blocksSkipped     // blocks ruled out by their min/max without reading a value
}
```

Numbers shred as fixed point: one scale for the whole column, values rescaled up to it exactly, and the
unscaled integers stored at the narrowest width that holds them. That is what makes the stored order the
*value* order — which an inverted index deliberately cannot offer, since its terms are sorted for lookup
and put `10` before `9`. Both orderings exist here on purpose, and a query picks by what it is asking.

Handing a value *back* is a stronger demand than testing one, and the column says which it can do. One
scale for the whole column means a segment holding `{"price":10}` beside `{"price":9.99}` would read
the first back as `10.00` — the same number, and not what the document says. So a column records
whether its values reconstruct exactly, and a projection that cannot be served from one reads the
document instead. An index may change where a value comes from; it may never change the value.

Values that do not fit the column's type — a string where the column is numeric, a number too wide for
64 bits — are not lost. They are marked *residual*, and the caller reads those documents; everything
else is answered from the column. That is the shredding specification's `value`/`typed_value` pair,
expressed as bitmaps, and it is what lets a column exist over a path whose type is merely usual rather
than guaranteed.

### Ask questions

On top of all of it, a query layer: a predicate AST, a planner that picks indexes and prunes segments,
and execution that returns rows in key order.

```kotlin
val query = Query.where(
    and(
        path("$.team") eq "analytics",
        path("$.price").between(BigDecimal("100"), BigDecimal("200")),
        not(path("$.retired").exists()),
    ),
).project("$.team", "$.price").limit(100)

db.query(query).use { rows ->
    while (rows.next()) println("${rows.key} ${rows.row["$.price"]}")
    rows.stats.documentsRead     // 0 — answered from an inverted index and a column
    rows.stats.blocksSkipped     // blocks the column's bounds ruled out
}
```

The two indexes are intersected **as bitmaps over the same segment's document positions**, so a
conjunction narrows before a single key is decoded, let alone a document read. Segments the plan cannot
answer for are read with a partial scan and merged in; the results come out in key order from a k-way
merge, so a `LIMIT` stops the work rather than truncating a finished answer.

Everything a plan cannot decide from an index is rechecked against the version the snapshot sees, by the
same walk that built the index. The recheck is skipped only where it is provably a no-op — where the
scan already holds the deciding version, or where one segment holds the key and its index decided
outright, which is a bisect over mapped key blocks rather than a document read.

```kotlin
println(db.explain(query).render())
// segments: 42 indexed, 0 scanned
// sources, cheapest first:
//   #2 SHREDDED_COLUMN $.price numeric in [100, 200] -> 1204 candidate(s), 1204 certain
//   #1 INVERTED $.team text:analytics -> 91022 candidate(s), 91022 certain
```

Over ten million documents that conjunction returns in **0.6 s** against **6.0 s** for the same query
with no index — identical results, no document opened, and the ordering of the intersection decided by
what each index actually admits rather than by a guess.

**Narrow with the index, then expand within the document.** A query answers *which documents* match.
It does not answer which `$.items[N]` inside one of them did, and it structurally cannot: an index
maps a value to a document and stops there. The second half is a walk of the one document you now
have, and it is a function rather than something to write by hand — `CatalogPath.forEachNodeIn` hands
back **nodes**, RFC 9535's word for a value together with its location.

```kotlin
val query = Query.where(path("$.items[*].sku") eq "ABC-123").project(Projection.DOCUMENT)
val items = CatalogPath.parse("$.items[*]")

db.query(query).use { rows ->
    while (rows.next()) items.forEachNodeIn(rows.row.document()) { node ->
        if (node.value.field("sku")?.stringValue() == "ABC-123") println(node.toJsonSummaryString())
        // $['items'][3] {"qty":2,"sku":"ABC-123"}
    }
}
```

Two details worth the line each. `Query.where` projects keys, which is what makes `documentsRead == 0`
reachable — so ask for `Projection.DOCUMENT` when you mean to read one. And a node's location is a
`VariantPath`, not a string: `document.select(node.location)` returns the value it was reported with,
and `node.location.toNormalizedPath()` writes it in RFC 9535 §2.7's form for anything outside the
engine to read.

**A conjunction over `[*]` is not correlated, and that is a defined semantics rather than an
oversight.** Each leaf is existential over the values at *its* path, independently:

```kotlin
// {"items":[{"sku":"A","qty":1},{"sku":"B","qty":5}]}
and(path("$.items[*].sku") eq "A", path("$.items[*].qty") eq 5)   // matches: sku from element 0, qty from element 1
```

The indexed and unindexed answers are identical, which is the invariant holding exactly.

**When you need the two to come from the same element, ask for that — `elemMatch`.** It is a
different question with a different spelling, so the conjunction above goes on meaning what it always
meant:

```kotlin
// matches only the document where one element has both
Query.where(elemMatch("$.items[*]", and(path("$.sku") eq "A", path("$.qty") eq 5)))
```

Paths inside are relative to the element: `$.sku` is the item's `sku`, not the document's. Negation is
the document's, as everywhere else — `not(elemMatch(…))` holds for a document where *no* element
satisfies it, including one with no items at all.

**What it costs without an index, and what an index does to it.** On its own this is a walk of each
element per document — the walk you were writing by hand. Declare a **composite index** and it becomes
one dictionary lookup:

```kotlin
db.createIndex(IndexDefinition.composite("$.items[*]", "$.sku", "$.qty"))
```

The index keys the *tuple* of an element's declared fields, so a match is exact and the plan opens no
document at all. What it needs is every declared field compared for equality — Postgres
`jsonb_path_ops`'s limit, and the reason it supplements your leaf indexes rather than replacing them.

**Asking for more than you declared is fine; asking for less is not.** A query that fixes the declared
fields and then adds a range, a negation, or a field the index never heard of is still narrowed by the
tuple — the extra conjunct is dropped, which only widens, and the element walk decides what survives.
A query that fixes *fewer* fields than the index declares gets nothing from it, and that is a
correctness limit rather than a missing feature: a term exists only for an element carrying every
declared field, so an element with a `sku` and no `qty` is keyed nowhere, and scanning the tuples would
quietly lose it. Index the field on its own if you query it on its own.

**What it cannot spell, your ordinary indexes narrow anyway.** An `elemMatch` over a range, over some
of the fields, or over a disjunction is rewritten into leaves over the concatenated paths — so an
index over `$.items[*].sku` you already had does the work before any element is walked. A single-leaf
`elemMatch` is not a correlated question at all, and is answered **exactly**:

```kotlin
// identical questions; the second is what the planner turns the first into
elemMatch("$.items[*]", path("$.sku") eq "A")
path("$.items[*].sku") eq "A"
```

A conjunction is the one shape that cannot be taken apart this way — `∃e(A∧B)` is not `∃eA ∧ ∃eB`,
which is the correlation gap itself — so it narrows and the walk decides. Nothing to configure.

It is opt-in on purpose, and the measurement says why. Over corpora whose element fields vary
independently, the uncorrelated conjunction returns **5-6×** the documents a caller keeps; over corpora
whose fields move together it returns exactly the right ones and this index earns nothing. Nothing in
a schema sketch can tell those apart, so the engine never recommends one — see
`./gradlew :rabosh-bench:runCorrelationCost`.

**Splitting the document is still the other answer, and still a good one.** One key per element —
`order:00123#item:00007` — makes each element a document, so the correlation is exact with no engine
feature at all. An ordered-key LSM is unusually good at it: a range scan reassembles the parent in one
contiguous read, and `$.items[*].sku` collapses to `$.sku`. Split when the elements are the things you
query; use `elemMatch` when the document is.

**Where the walk needs a condition rather than a path, there is a JSONPath query.** A `CatalogPath`
says *where*; a filter says *which*, and no sink or wrapper turns the first into the second. So
`rabosh-jsonpath` compiles RFC 9535 and applies it to a document you are already holding:

```kotlin
val correlated = JsonPathQuery.compile("$.items[?@.sku == 'ABC-123' && @.qty == 5]")

db.query(query).use { rows ->
    while (rows.next()) correlated.forEachNodeIn(rows.row.document()) { node ->
        println(node.toJsonSummaryString())
        // $['items'][3] {"qty":5,"sku":"ABC-123"}
    }
}
```

Two things it is, and one it is not. It is the recheck in the paragraph above made *expressible* —
the engine still narrows uncorrelated, and this is the per-document walk you were otherwise writing
by hand. It carries the descendant segment, `$..sku`, which is what a document whose nesting depth is
not known in advance needs. And it is deliberately **not** part of the query language: nothing in the
storage chain depends on this module, so RFC 9535's comparison rules and `Predicate`'s — which
genuinely disagree, on negation and on what an operand is — can never decide the same question.

The artefact is separate because that is the only way the claim can be scoped honestly.
`rabosh-jsonpath` implements RFC 9535 — **all 703** of the JSONPath Compliance Test Suite's cases run
and pass, with nothing excluded; `VariantPath.parse` and `CatalogPath.parse` remain the engine's own
grammar and are still not JSONPath.

That includes `match` and `search`, which are defined over [RFC 9485][rfc9485] I-Regexp and are
answered by a matcher written for this module rather than by `java.util.regex`. The reason is not
purity: a filter runs once per *document* over a corpus, RFC 9535 lets the pattern come from the
document too, and a backtracking engine turns `(a|aa)+b` into a complexity attack on a storage engine.
This one is a Thompson construction — it costs the pattern times the subject, never backtracks, and
the bound is asserted in transitions rather than on a clock.

## What it guarantees

These are the promises the engine makes, and next to each one what checks it. None of them is
aspirational: every line here has a test that fails when the promise breaks.

| Promise | How it is held to it |
|---|---|
| **Everything acknowledged survives.** A `put` that returned is in the store after a crash or an IO failure; one that threw may not be, and nothing that was never written appears. | A child JVM killed with `SIGKILL`/`TerminateProcess` mid-write, files truncated at *every* byte offset, and a fault-injecting filesystem that fails writes, short-writes and `force`. |
| **A torn tail is dropped; a lost commit is reported.** Corruption is never guessed past. | A checksum failure with a readable record behind it, a sealed log with an incomplete tail, and a gap in the sequence numbers are each their own check. |
| **A snapshot never changes.** What it sees at the moment it is taken is what it sees until it closes, whatever is written, flushed or compacted underneath. | A model-based comparison against a `TreeMap` after every operation of randomised scripts, and queries at snapshots the store has compacted past. |
| **An index changes how fast a query runs, never what it returns.** | Every plan shape compared against a brute-force scan, against two independent oracles, before an index exists, during a half-finished build, and after — and after *every step* of a write/flush/compact script. |
| **Derived data is never worth a document.** A sketch or a sidecar that cannot be written costs a rescan; the write it was riding on still lands. | Sidecar writes failed deliberately: the documents are all there, and the segment reads as *uncovered* rather than as covered and empty. |
| **On-disk formats are permanent.** A store written by an earlier build still opens. Specified in [COMPATIBILITY.md](COMPATIBILITY.md). | Five real stores committed as bytes and read by the current code, with their sidecars read rather than rebuilt — including three carrying index layouts this build can no longer *write*, and one written by a tagged release rather than a development build — plus the encodings pinned in hex where they are defined. |

### What it costs

Measured on one developer machine over 200 000 documents (33 MiB of JSON) with
`./gradlew :rabosh-bench:mainBenchmark`. They describe this engine on that machine — they are not a
comparison against anything else, and a number from a shared CI runner would be a measurement of the
runner.

| | |
|---|---|
| Writes, `SYNC` (the default) | 5 600/s single, **334 000/s** in batches of 100 |
| Writes, `BUFFERED` | 280 000/s |
| Point lookup, present / absent | **669 000/s** / 5 700 000/s |
| Parse JSON → Variant | 656 000/s (1.5 µs) |
| Read one field of an encoded document | 10 000 000/s (100 ns) |
| Indexed query against the same query scanned | **24×** equality, **45×** a two-index conjunction, 11× a column range |
| Store on disk | **0.73×** the JSON ingested; each index adds 1–2%, and a *unique-valued* index adds 11% |

The durability default is worth a sentence: `SYNC` forces the log on every commit, which costs 50×
against buffering — and a `WriteBatch` gets almost all of it back, because one commit is one append
and one force however many documents it carries.

### Modules

```
rabosh-api       Rabosh: one lifecycle over the layers below
  └── rabosh-query      predicate AST, planner, execution
        └── rabosh-index     bitmap (+ Roaring interop), inverted indexes, shredded columns
              └── rabosh-catalog    path sketches, schema inference
                    └── rabosh-core       LSM: WAL, memtable, SSTable, manifest, compaction
                          └── rabosh-variant    Open Variant encoding, path navigation

rabosh-jsonpath  RFC 9535 over one document — beside the chain, not in it
  └── rabosh-variant
```

`rabosh-jsonpath` depends on `rabosh-variant` and nothing else, and **nothing above depends on it**.
That is a mechanical guarantee rather than an intention: the filter selector cannot become a second
front end to the planner by accident, because the build would have to acquire the edge deliberately.

## Samples

Four runnable programs, in `rabosh-samples`. None is published and none depends on anything
but `rabosh-api`.

```sh
./gradlew :rabosh-samples:runThreeSteps   # write blind -> model later -> index later, narrated
./gradlew :rabosh-samples:runIndexLater   # a background build, queried while it is half finished
./gradlew :rabosh-samples:runDrain        # a staging buffer drained, checkpointed and retired
./gradlew :rabosh-samples:runTranscripts  # the three steps again, on JSON this repository did not write
```

`runThreeSteps` is the README's opening snippet with the evidence attached: it runs one query before
the index exists and again afterwards, and prints both. On the corpus it generates that reads
`documents read 4000 -> 0, segments scanned 8 -> 0` for the same 800 keys in the same order. It then
adds a shredded column and answers a range from it without opening a document — including the part
that surprises people, which is that the documents whose `latencyMs` arrived as a *string* are not
matched, because a numeric predicate matches numeric values only.

`runIndexLater` is about the state in the middle. It starts a background build over a 40-segment
store, **cancels it deliberately** so the half-built state is reached on purpose rather than by
timing, and queries from there: some segments answered from sidecars, the rest scanned, the same
4000 keys. Then it finishes the job by asking for the same index again — there is no resume verb,
because a cancelled build and a running one leave the same thing behind — and the second pass builds
exactly the segments the first did not.

`runDrain` is the one that is pure integration, and it exists because every mistake in it is silent.
A staging buffer holds events until something downstream has taken them, and the loop that hands them
over is five calls in one order: pin a snapshot, scan from the watermark, ship, *then* record the
watermark, then `deleteRange` what was shipped and `compact`. A watermark advanced before the ship
succeeds loses data; a scan without a snapshot can see a compaction land underneath it; a drain that
never compacts grows for ever while reporting that it deleted everything. It also takes a
`checkpoint` **while still writing**, opens the copy, and shows that it holds the prefix as of its
sequence and nothing after it. Deliberately not a `DrainCursor` — the value is the order, which a
wrapper would hide.

`runTranscripts` is the same three steps on a corpus this repository did not write and cannot
predict: Claude Code's own session transcripts under `~/.claude/projects`, which are JSONL produced
by a program none of us control, in a shape documented nowhere, that grows every time you use the
tool. Everything the other three arrange, it finds — a field that is an array in most documents and a
string in a few, a field that is explicitly `null` rather than absent, more distinct paths than the
model will hold — and it also has to cope with two things a generated corpus never does: the file
being appended to *while it reads*, so a last line that has not finished arriving is held back rather
than parsed, and a line the parser refuses, which is counted and reported rather than defaulted. Give
it a directory and the store survives, so the second run ingests only what a session added. It reads
your machine's transcripts and prints paths and counts from them, so it is a sample you run rather
than one CI does; the test runs it against a corpus it synthesises instead.

All four are executed by `SamplesTest` on every `./gradlew build`, and what it asserts is their
*output*: a sample that ran to completion and printed `0 rows` has failed at the only job it has.

## Dependencies

The engine has **no runtime dependencies at all** — not a small set, none. The JSON parser, the
compressed bitmap, the HyperLogLog cardinality estimator, the bloom filter and the property-test
harness are all written in-repo rather than pulled in, and everything below them is the JDK:
`FileChannel` for durable IO, the FFM API for mapped segments, `CRC32C` for checksums.

This is deliberate rather than incidental. The storage engine is the point of the project, and
owning the bitmap format in particular lets index sidecars be read straight off a mapped segment
with no deserialization step. Owning it does not mean being cut off from anything: interoperating
with the portable Roaring format needs no dependency either, and the conformance fixtures that prove
it are committed bytes rather than a library on the test classpath. The rest of the test and build
toolchain does use libraries — JUnit, `kotlinx-serialization-json` as a reference oracle for the
parser, Dokka, kotlinx-benchmark — and none of them ships.

## Building

Requires JDK 25.

```sh
./gradlew build      # compile, test, and check the public ABI against the committed dumps
./gradlew test       # tests only
./gradlew updateKotlinAbi   # after an intentional public API change
./gradlew checkApiTiers     # no unmarked signature exposes an experimental type (part of build)
./gradlew dokkaGenerate     # the aggregated API site, into build/dokka/html
```

Benchmarks are not part of `build` — they run once, briefly, in CI to prove they still work, and
properly on a quiet machine:

```sh
./gradlew :rabosh-bench:mainBenchmark      # the real suite
./gradlew :rabosh-bench:runAmplification   # bytes on disk per byte of JSON, by file kind
./gradlew :rabosh-bench:runReadCost        # where a point lookup's time goes, by block size
./gradlew :rabosh-bench:runQueryCost       # where an indexed query's per-row time goes
./gradlew :rabosh-bench:smokeBenchmark     # one short iteration of everything, as CI runs it
```

The samples are the other way round — part of `build`, because they are documentation and
documentation that nothing executes rots:

```sh
./gradlew :rabosh-samples:runThreeSteps    # the three steps, narrated, with the counters
./gradlew :rabosh-samples:runIndexLater    # a background index build, stopped and resumed
./gradlew :rabosh-samples:runDrain         # drain, checkpoint and retention, in the order they go
./gradlew :rabosh-samples:runTranscripts   # the three steps over your own Claude Code transcripts
```

A benchmark task **fails if it produced no results** — JMH can decline to start and still exit zero,
and a check that passes when its subject never ran is worth less than no check. What is asserted is
that every benchmark class the configuration selected has a result, never how fast it was: numbers
from a shared runner are not a regression gate.

The aggregated API documentation for the seven published modules is at
**[aoreshkov.github.io/rabosh](https://aoreshkov.github.io/rabosh/)**, generated by Dokka from `main`
on every push. It tracks the current API rather than the last release, deliberately: outside the
stable core the Kotlin API is free to move, so a versioned copy would document something you are
being told not to rely on. Each release also ships its own module documentation as the `javadoc`
classifier artefact, for anyone who needs to pin one.

Published to Maven Central under the group `app.oreshkov`; all code lives under the
`app.oreshkov.rabosh` package. `rabosh-api` brings the five modules of the storage chain with it:

```kotlin
dependencies {
    implementation("app.oreshkov:rabosh-api:0.3.0")
}
```

**`rabosh-jsonpath` is the seventh and is not one of them**, which is a design decision rather than an
oversight: it sits *beside* the chain, nothing depends on it, and that is exactly what keeps RFC 9535's
comparison rules from ever reaching the query planner. Ask for it by name if you want it:

```kotlin
dependencies {
    implementation("app.oreshkov:rabosh-jsonpath:0.3.0")   // optional; brings rabosh-variant only
}
```

Every jar carries a build provenance attestation, so what you resolved can be checked against the
workflow run that built it:

```sh
gh attestation verify rabosh-api-0.3.0.jar --repo aoreshkov/rabosh
```

Releases are cut by pushing a `v*` tag, which runs [`release.yml`](.github/workflows/release.yml):
it verifies on both platforms, signs and bundles every module, checks the bundle is a *complete*
release rather than merely a valid one, attests the jars' provenance, and publishes the GitHub
Release only after Central has accepted the deployment. `./gradlew publishToMavenLocal` produces the
same jars, sources and Dokka HTML locally.

## Contributing

Build requirements, the four rules that will fail a pull request, and what the testing conventions
expect are all in [CONTRIBUTING.md](CONTRIBUTING.md). Participation is under the
[Code of Conduct](CODE_OF_CONDUCT.md).

Security problems go through [private vulnerability reporting](https://github.com/aoreshkov/rabosh/security/advisories/new),
never a public issue — see [SECURITY.md](SECURITY.md), which also sets out what counts as a
vulnerability in an engine that opens no sockets and whose two inputs are both assumed hostile.

## Licence

[Apache License 2.0](LICENSE).

[variant]: https://github.com/apache/parquet-format/blob/master/VariantEncoding.md
[shredding]: https://parquet.apache.org/docs/file-format/types/variantshredding/
[rfc9485]: https://www.rfc-editor.org/info/rfc9485/
