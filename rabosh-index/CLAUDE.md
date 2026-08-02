# rabosh-index — module conventions

Cross-cutting design rules live in the root `CLAUDE.md`; testing conventions in `.claude/rules/testing.md`.

`rabosh-index` holds the compressed bitmap — `Bitmap` (mutable, heap), `BitmapView` (read-only, straight
off a `MemorySegment`), the `ReadableBitmap` surface they share and, since phase 14, `RoaringPortable`,
which exchanges one with the outside world — and, since phase 7, the indexing layer
built on it, plus, since phase 15, `IndexBuild`/`IndexBuildProgress`/`IndexBuildState` and the worker
behind them: the `INDEXES` registry, the per-segment `.idx` base sidecar, the per-(segment, index) `.pst`
posting file and `.col` shredded column, `IndexCatalog` (a `SegmentObserver` and `AutoCloseable`),
`IndexReader`/`ColumnReader`, `IndexQuery`/`ColumnQuery` and `CompositeSegmentObserver`. Since phase 17
the `.pst` term dictionary has two layouts behind the sealed `TermDictionary` — `FlatTermDictionary`
for version 1, `FrontCodedTermDictionary` for version 2 — and **`PostingFile.open` is the only place
that knows which**. Nothing below it branches on the version, including `postingAt`, which takes the
posting fields' offset within an entry from the dictionary: a reader with two notions of what a posting
list *is* would be a second definition of one, which is the rule `POSTING_ENCODING_SINGLE` already
states a level down.

Since phase 18 the `.idx` key block has two layouts the same way — `FixedWidthKeyBlockReader` for
version 1, `VarintKeyBlockReader` for version 2, sealed under `KeyBlockReader`, with **`BaseSidecar.open`
the only place that knows which**. The sharing goes *further* here than in the dictionary and that is
deliberate rather than inconsistent: both key-block versions front-code, both restart every
`KEY_RESTART_INTERVAL`, both are walked, so the walk, the ordinal arithmetic and the bisect live once in
the base class and a subclass supplies nothing but `lengthAt` — which answers in the packing
`IndexBytes.varint` already defines rather than a second one. Two implementations of "the *n*-th key"
that drifted would resolve one posting list to different documents depending on when its sidecar was
written; do not duplicate that walk to make the two versions look symmetrical with the dictionary's.
Since phase 19 that walk is one object, `Walk`, so `keyAt` and `ordinalOf` share it too rather than the
second calling the first per ordinal — see the allocation-profile rule below, and note that the block's
`range` is decoded **lazily**, because a damaged sidecar must still open.

Its readers answer in **ordinals** as well as in keys — `candidateOrdinals`, `presentOrdinals`,
`absentOrdinals`, `documentOrdinals`, `ColumnReader.evaluate(segment, predicate)` — because both indexes
over a segment hang off that segment's one base sidecar and therefore share one ordinal space. That is
what lets a planner intersect two indexes before decoding a single key. Every one of them **throws** for a
segment outside `usableSegments` and must never answer emptily: an empty bitmap cannot be told apart from
"no matches here", which is an index changing an answer rather than a speed. `TermExtractor` is public for
the same class of reason — the recheck has to *be* the code that built the index, not agree with it.

**The two index kinds answer different questions and must not be confused.** An inverted index answers
equality, `IN` and existence; its terms are sorted for *lookup*, so `NUMERIC || "10"` precedes
`NUMERIC || "9"` and it cannot answer `<` at all. A shredded column answers ranges and answers them
without opening a document, because at a common scale its unscaled integers are ordered *by value*. Both
orderings live in this module deliberately; a planner reaching for the wrong one is a wrong answer. It has its own sealed `IndexException` hierarchy — `CorruptBitmapException`,
`UnsupportedBitmapFormatException`, `CorruptIndexException`, `UnsupportedIndexFormatException` and
`IndexStateException` — for the reason `CatalogException` is separate from `StoreException`: an index that
will not decode has not cost anybody a document, and the repair is to rebuild it from the segment.

Two rules there that a change must not quietly break. **The read algorithms of a container live once**, in
the abstract `ArrayBlock`/`BitsetBlock`/`RunBlock`, and both the heap container and the mapped one extend
them — a heap `select` and a mapped `select` that disagreed would make a query return different documents
depending on whether the sidecar it read had been flushed yet. And **a constructive operation never mutates
an operand**: `materialise()` hands back the receiver itself for a heap block, so an implementation that
wrote into it would corrupt the bitmap it was reading while still returning the right answer.
