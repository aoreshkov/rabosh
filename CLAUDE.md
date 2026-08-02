# rabosh — working conventions

An embedded JSON storage engine for the JVM, written from scratch in Kotlin. Store JSON of
unknown structure, derive a model of it later, add indexes retroactively.

"Phase *N*" throughout this file, the module notes and the source comments refers to a numbered
development iteration of the engine. The numbers are chronology — they say when a decision was taken
and what it replaced — and they are not released versions.

## Dependency policy

**JetBrains and Kotlin libraries are pre-approved. Every other third-party library requires
explicit confirmation from the user before it is added.**

Ask before writing the dependency into `gradle/libs.versions.toml`, not after. Present the
trade-off against writing the thing by hand — that is a real option here and has been chosen
before: RoaringBitmap, simdjson-java and Kotest were all offered and declined, so the
compressed bitmap, the JSON parser and the property-test harness are all in-repo. Phase 5 added
another without needing to ask, because nothing was worth asking about: the HyperLogLog in
`rabosh-catalog` is ~200 lines, and owning it is what lets a sketch be serialised in a format the
engine controls rather than one a library version can change underneath it. Phase 6 collected on the
RoaringBitmap decision: because the bitmap format is ours, a `BitmapView` reads a mapped index sidecar
**with no deserialization step at all**, which a library bitmap could not do at any price.

**Zero runtime dependencies is a claim the README makes, so keep it true.** `kotlinx-io` was
declared as the sole runtime dependency and removed in phase 4 once it was clear nothing referenced
it: the codec reads `MemorySegment` directly, the log needs `FileChannel.force` (kotlinx-io 0.9.1
cannot express `fsync`), and segments are mapped through the FFM API. Anything proposed for the
runtime scope from here needs an argument against the JDK, not only the user's approval.

ABI validation uses Kotlin's built-in `abiValidation()` (2.4+) rather than the standalone
`binary-compatibility-validator` plugin, whose bundled ASM cannot read Java 25 bytecode.

**The tasks are `checkKotlinAbi` and `updateKotlinAbi`, and the `…LegacyAbi` pair is not a synonym.**
`checkLegacyAbi` and `updateLegacyAbi` are still registered by KGP 2.4, but only as shims: each
depends on its replacement and logs `Task … is deprecated, use …` from a `doFirst`. KGP's own comment
states the cycle — warn, then throw, then remove — so a script or a habit that uses one is a build
that breaks on an upgrade for no benefit today. The names are the *only* thing that moved: the
reference dumps stay at `<module>/api/<module>.api`, and `./gradlew updateKotlinAbi` was run across
all six published modules on 2026-08-02 and rewrote none of them, byte for byte, which is what says
the rename cost nothing.

## Toolchain

Versions are centralised in `gradle/libs.versions.toml`; do not inline them in build scripts.
Keep them at the latest stable release; do not adopt pre-releases (e.g. Kotlin `-Beta`) without
asking.

- JDK 25 is not incidental: the engine maps segments through the FFM API
  (`Arena`, `MemorySegment`, `FileChannel.map(..., Arena)`), which is final from JDK 22.
  Tests run with `--enable-native-access=ALL-UNNAMED`.
- Build conventions live in `build-logic/`, an included build. One thing there is a decision rather
  than a default:
  - `BenchmarkRunReport` — plain Kotlin, no Gradle types, so the decision that fails a benchmark task
    has unit tests. An included build's tests are **not** part of the root `build`, so CI runs
    `./gradlew -p build-logic check` as its own step; a test nothing runs is the defect that class
    exists to remove.

Publication lives in `rabosh.kotlin-library` too: `maven-publish` with a full POM, a sources jar and
Dokka's HTML under the `javadoc` classifier, checked by `publishToMavenLocal` in CI so a broken POM
fails where it is introduced rather than at a release. Two things there are decisions rather than
defaults. The POM carries **no email address** — a POM is published forever, and an address in one is
permanent. And `gradle.properties` stays `0.1.0-SNAPSHOT` for a *mechanical* reason rather than a
claim: `release.yml` derives the release version from the git tag and nowhere else, and
`CentralBundleReport` rejects a `-SNAPSHOT` through that path, so the checked-in version is the
development one by construction. Do not "fix" it to a release number — that would put the version in
two places and make the tag advisory.

**The format claim lives in `COMPATIBILITY.md` and nowhere else.** It used to live in the README's
status blockquote, coupled to the version here, and the coupling was what kept it: each artefact cited
another and none cited the format. The two guarantees are now separate and stated at the strength of
their own evidence — the **on-disk format is declared and stable**, held by the golden stores; the
**Kotlin API is major-version zero** and free to move, held by nothing, which is exactly why it is not
claimed. A change to either belongs in `COMPATIBILITY.md` first; the README links it rather than
restating it, so the two cannot drift.

## Module layout

Dependencies flow strictly downward. Do not introduce an upward or sideways edge.

Each module's own conventions live beside it and load when you work under that directory:
`rabosh-index/CLAUDE.md`, `rabosh-query/CLAUDE.md`, `rabosh-api/CLAUDE.md`,
`rabosh-catalog/CLAUDE.md`, `rabosh-core/CLAUDE.md`. Read the one for the module you are
changing before changing it — each states what that module owns and, more importantly, which of its
shapes exist once on purpose.

## Design rules that must not be quietly broken

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
- **Sidecars are split by lifetime, and a sidecar is written once.** The `.idx` base carries the key
  block, the present bitmap and the segment's statistics; a `.pst` carries one index's postings. Neither
  is ever rewritten. Folding them into one file per segment would make `createIndex` and a concurrent
  compaction two `ATOMIC_MOVE` writers of one path — last writer wins, so a build could fail to
  converge forever — and read-modify-write instead would mean re-emitting sections a newer build wrote
  and this one cannot read.
- **Compaction awareness is structural, and a posting-list merge would be worse.** Ordinals are
  positions within a segment, so a compaction renumbers all of them; remapping two input posting lists
  costs more than reading the term out of the document the compaction is already holding. There is no
  merge hook and there should not be one.
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
- **The magics are spelled `JKDB-`, and that is not a typo to correct.** The project was called
  `jsonkdb` when the format was written, and the prefix is a *retained historical* one: a magic is a
  discriminator that says which kind of file this is, never branding. Renaming it would invalidate all
  four golden stores — including the three carrying layouts this build can no longer write — and buy
  nothing a hex dump cares about. `PK` outlived PKZIP and a Java class file still opens `CAFEBABE`. A
  ninth magic uses `JKDB-` too, because one consistent stale prefix is a footnote and two prefixes are
  a defect.

- **On-disk type ids and format constants are permanent.** `VariantPrimitiveType` ids come from
  the Apache specification; the WAL magic (`JKDB-WAL`), the segment magic (`JKDB-SEG`), the
  manifest magic (`JKDB-MAN`), the sketch-sidecar magic (`JKDB-CAT`), the index-registry magic
  (`JKDB-IXR`), the base-sidecar magic (`JKDB-IDX`), the posting-file magic (`JKDB-PST`) and the
  column magic (`JKDB-COL`), their
  format versions, the
  shared record frame in `Frames`, the operation ids (`PUT` = 1, `DELETE` = 2), the segment footer
  layout and block entry layout, the key tag's 56-bit sequence and 8-bit operation split, the
  manifest edit tags (1..5), the sidecar's bound tags and `SketchFormat.typeId` ids, `BitmapFormat`'s
  version and its container kind ids (`ARRAY` = 1, `BITSET` = 2, `RUN` = 3) together with its header
  and directory layout, `IndexFormat`'s section kinds (`META` = 1, `KEYS` = 2, `PRESENT` = 3,
  `COLUMN` = 4 reserved), its index kinds (`INVERTED` = 1, `SHREDDED_COLUMN` = 2), its posting
  encodings (`BITMAP` = 1, `SINGLE` = 2), its `KEY_RESTART_INTERVAL` of 16, its
  `KEY_V1_ENTRY_HEADER_BYTES` of 8 and all three of its header and directory
  layouts, `ColumnFormat`'s own section kinds (`META` = 1 … `STATS` = 7, `FIDELITY` = 8) and its
  `FIDELITY_EXACT_VALUES` = bit 0, its column types
  (`INT64` = 1, `DECIMAL32` = 2, `DECIMAL64` = 3, `DECIMAL128` = 4 reserved, `BOOLEAN` = 5,
  `STRING` = 6, `DOUBLE` = 7 reserved), its statistics encodings (`TYPED` = 1, `PREFIX` = 2), its
  bound tags and its `COLUMN_BLOCK_SHIFT` of 13,
  and the hash functions behind the bloom filter and the HyperLogLog are all
  written to disk. Never renumber — **add**. The `blockType` byte in a segment's block trailer exists
  precisely so that compression or a denser entry encoding is a new id rather than a new segment
  version; `BitmapFormat`'s `kind` byte is the same extension point for a denser container; and
  `IndexFormat`'s section-`kind` and posting-`encoding` bytes are the same again, which is what made a
  shredded column and — in phase 11 — a singleton posting list new ids rather than new file versions.

- **A version bump is not a renumbering, and this engine has taken exactly two.** The rule above is
  about *ids*, because an id is read by a build that never heard of the value that replaced it and has
  nothing in the file to warn it. A version field is the opposite: it is the one number a reader checks
  *before* it believes anything else. So `POSTING_VERSION` is 2 and `POSTING_VERSION_FLAT` is 1,
  `BASE_VERSION` is 2 and `BASE_VERSION_FLAT` is 1, and **all four are read**, while
  `POSTING_ENCODING_SINGLE` is still 2 and `SECTION_KIND_COLUMN` is still reserved and unused. What a
  version permits is replacing a *layout*; what it still forbids is changing what any existing byte
  means.

  The test for reaching for it is written into `IndexFormat`: an encoding byte reinterprets a field, a
  version replaces a layout, and taking the second when the first would do is how a format acquires
  versions nobody can drop. Phase 17 qualified because neither half of it was expressible as an id —
  the term entry lost two fields every existing file has, and front-coding changes what the bytes
  between two entries mean. Phase 18 qualified for the first of those reasons alone: a key entry
  narrowing two `u32`s to two varints replaces a record every existing file has, and there is no byte
  in that record to gate a reinterpretation on.

  **The alternative that has to be argued down is a new section kind, and it is worse than it looks.**
  An unknown section kind is *skipped* rather than reported, so `SECTION_KIND_KEYS_V2` would appear
  additive — and an older build would then find no `KEYS` section at all and call the sidecar damaged.
  The same failure with a worse message, at the price of a permanent id, and with two key sections able
  to sit in one file, which is a second definition of the segment's key space. Skipping is safe for a
  section a reader does not *need*; do not reach for it when the reader needs the section.

  **Reading the old version is not politeness, it is forced.** `SegmentIndex.open` *throws* when a
  `.pst` or an `.idx` that exists will not decode: a sidecar may be **missing**, which reads as "not
  covered", but never unintelligible. So refusing version 1 would fail `IndexCatalog.attach` on every
  older store under the default `REPORT` policy rather than quietly rebuilding it — and would make
  `FormatCompatibilityTest`, which opens the golden stores with `backfill = false` precisely so a
  sidecar that will not decode cannot be rebuilt into a pass, unable to do its job.

  **Both bumps were taken before the format was declared, and that discount has expired.** They were
  argued for on the rule that a format change is free before a freeze and permanent after one, which
  was true and is no longer available: `COMPATIBILITY.md` declares the format, so a third bump is
  permanent from the moment it is written. What the two leave behind is a **pattern rather than a spent
  budget** — a new version whose reader keeps its predecessor is exactly the mechanism the declaration
  promises, and it has been demonstrated twice rather than asserted. So a third is allowed and is not
  cheap: its price is a third layout maintained forever, a golden store that can never be retired while
  it is the only cover for the layout below, and a `BASE_VERSIONS`-style array that only ever grows.
  Take one when a change is not expressible as an id and a measurement demands it — the test phases 17
  and 18 both passed — and not because the two above make a third look routine.

- **The engine's variable-width fields are two varint regions, and each has exactly one spelling.**
  LEB128 admits `0x80 0x00` for zero alongside `0x00`; `IndexBytes.varint` calls the padded form
  corruption. Two spellings of one value would let one dictionary or one key block encode to two
  different files, and the byte identity between a flush-written sidecar and a backfill-rebuilt one is
  what lets the suites compare sidecars as *files*. Same rule as `BitmapView.verify` reporting a
  wastefully encoded block rather than accepting it: a format with no canonical form quietly
  invalidates every argument that rests on one. One rule, two callers, and both need a reproduction —
  `IndexCorruptionTest` has a padded varint in a `.pst` term record and one in an `.idx` key entry.

  Both are confined to a region that is **walked and never indexed into** — a posting file's term
  region since phase 17, a base sidecar's key entries since phase 18. That is the line to hold:
  variable width only where a sequential walk was happening anyway, fixed width everywhere a position
  is computed. The posting directory stayed fixed-width and got *narrower* (24 → 16 bytes), so
  `postingAt` is still arithmetic and its stride shrank; both restart arrays stayed `u32`, because a
  restart offset is reached by multiplication.

- **The key block's saving has no crossover, and the term region's does — do not describe them
  alike.** A varint *header* is two bytes where a `u32` pair is eight below 128 bytes and four below
  16 KiB, so a version-2 key block is never larger than the version-1 block of the same keys, for any
  key this engine can hold. Front-coding is the opposite (see below). `KeyBlockTest` pins the
  inequality **with the six-byte figure beside it**, because an inequality alone is satisfied by a
  change that saves nothing — the same rule that puts a second counter beside `documentsRead == 0`.

- **Front-coding pays on long terms and costs on short ones, and the entry narrowing pays always.** A
  front-coded record is `2 + (length - shared)` against `length`, so the term region only shrinks once
  the average shared prefix exceeds two bytes. Phase 17's measured 30.4 → 19.7 B/doc is mostly the
  eight bytes of entry, not the front-coding — `PostingEncodingTest` pins **both** directions with the
  crossover named, because the tempting response is an adaptive dictionary and phase 11 settled that: a
  layout chosen by anything but the sorted term list breaks byte identity. Do not attribute the saving
  to the mechanism that is easier to describe; that is the error phase 13 caught phase 12 making.

  **`POSTING_ENCODING_SINGLE` reinterprets a field, and that is what an encoding byte is for.** For it
  the term entry's `postingOffset` *is* the ordinal and `postingLength` is zero, so a term matching one
  document costs no posting bytes; a reader must therefore branch on the encoding **before** it treats
  either field as an offset, and bound the ordinal by `documentCount` rather than by the file length.
  Two consequences to keep true. The choice is a **pure function of the posting list** — `cardinality
  == 1`, nothing adaptive, nothing order-dependent — because a flush-written and a backfill-rebuilt
  sidecar are byte-identical and an order-dependent choice would break that while still returning every
  right answer. And the ordinal is inside the region the header checksum covers, so a flip in it is
  caught on *open*; see the two-level-checksum rule under Testing.

  Three of these fail *silently* rather than loudly and deserve naming. `SketchFormat.typeId` and
  `IndexFormat.indexKindId` must never use `VariantKind.ordinal` or `IndexKind.ordinal` — inserting a
  value would change what every existing file means. Changing `SketchHash` or the tags on
  `ValueSignature` rewrites the meaning of every HyperLogLog register *and* every term in every posting
  file ever written. All are exhaustive `when`s with no `else` so the compiler forces a choice when a
  value is added, and `IndexFormatTest` pins the existing numbers, because a `when` cannot stop somebody
  changing one.

  An unknown **section** kind is skipped rather than reported, and that is safe for precisely the reason
  it is not safe for a manifest edit tag: the section directory is fixed-width and carries each section's
  extent, so a reader can find its way past one it does not understand. A manifest cannot, which is why
  `Manifest` calls an unknown tag corruption. Do not make either behave like the other.

  **An optional section states its claim positively, and that is what makes it additive both ways.**
  `FIDELITY` is the first one: a `.col` written before phase 12 does not carry it, an older build
  skips it, and a reader finding none reads every flag as clear. Spelling it the other way round —
  "this column may be lossy" — would have made every column ever written silently claim a property
  nobody checked. So a flag added here must be a *capability*, never a *defect*, and absence must be
  the conservative answer.

  **`.idx` and `.col` share the framing and not the vocabulary.** `SectionDirectory` is one
  implementation, because two copies of "where does this section begin" that disagreed would make a file
  readable by one reader and damaged to the other. The section *kinds* are separate namespaces, both
  starting at 1: a section of `.idx` is a fact about a segment and a section of `.col` is a fact about
  one path within one index over one segment, the file disambiguates them exactly as it does
  `BitmapFormat`'s container kinds, and sharing would make every future column encoding burn a globally
  scarce id. `IndexFormat.SECTION_KIND_COLUMN = 4` stays reserved and unused; do not repurpose it.

  **They do not share a version field either, and phase 18 is where that stopped being theoretical.**
  `SectionDirectory.open` takes the *set* of versions its caller reads and reports back which one the
  file carries, so `.idx` offers two and `.col` offers one. Widening the framing is not the same as
  widening a file: a second entry in `.col`'s list would be a claim about bytes no build has ever
  written, and the one place that decides what a version means stays the file's own `open`.

- **An exchange format is not the storage form, and its constants obey the opposite rule.**
  `RoaringPortableFormat`'s cookies (`12346`, `12347`), its offset threshold of 4 and its derivation of
  a container's type from the run bit and the cardinality are **not** on the list above and must never
  be added to it. They belong to a specification this project does not own; if it grows a cookie, that
  file changes to follow it. The list exists because files rabosh has already written must keep their
  meaning, and nothing rabosh writes is in this format — no sidecar reads it, no id was spent on it,
  and `IndexByteIdentityTest`, `PostingEncodingTest` and the golden stores passing unchanged is the
  assertion that says so.

  Two consequences a change must not quietly undo. **The two encoding-selection rules are separate
  copies on purpose**: a run costs `2 + 4n` bytes there and `4 + 4n` here, which moves the array/run
  boundary at exactly four consecutive values, and generalising `BitmapFormat.smallestKind` over the
  run overhead would put a foreign specification one parameter away from the encoding every sidecar's
  byte identity rests on — the `ColumnBounds` precedent exactly, semantics shared and bytes not. And
  **there is no `MemorySegment` decode and there must not be one**: this format's offset header is
  *conditional*, which is one of the five reasons §9.6 declined the layout as a storage form, so
  reading it in place would adopt the cost the decision was taken to avoid. A stream is decoded onto
  the heap, always.

  The decoder is also the one place in the engine that reads bytes with no checksum in front of them
  and no guarantee they came from a compatible domain, so two of its rules invert `BitmapView`'s.
  It **validates every container as it builds it** — ascent, population count, run separation, every
  cardinality — because the values are being copied to the heap regardless. And it **bounds-checks
  rather than requiring an exact tiling**, because a foreign writer's offset header may legitimately
  point anywhere inside its payload. A well-formed bitmap holding a value above
  `BitmapFormat.MAX_ORDINAL` is `UnsupportedBitmapFormatException` and not corruption: the bytes are
  intact, and the boundary is one *value* wide — key 32767 remainder 65534 is fine and 65535 is not.

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
- **Unknown data decodes to a signalled failure, not to a default.** An unrecognised type id
  means the file is unreadable by this version, not that the value is absent. A directory holding
  store files but no `CURRENT` is the same kind of thing: reported, never guessed at.
- **A block is verified once where there *is* a once, and on every read where there is not.** A
  segment has three metadata blocks — index, bloom, dictionary — and `SegmentTable.open` checks all
  three when it maps the file. It has thousands of data blocks and a lookup touches one, so those are
  checked as they are read. The two rules are not interchangeable, and the index block quietly obeyed
  the wrong one until phase 13: re-verifying it per lookup cost a CRC32C over one entry per data block
  in the segment, **3.0 µs of a 4.15 µs get** at the defaults, growing linearly with segment size.
  `readBlockCheckedAtOpen` is the reader that states the once-check as a precondition; use it for the
  three and never for a data block. What it gives up is a flip *after* the mapping, which the bloom
  and the dictionary never caught either — checksums here catch a bad write and a bad disk, both of
  which happen before a reader maps the file.
- **The log is appended before the memtable is touched, always.** A memtable holding an entry the
  log does not is the one ordering that survives a crash as an unacknowledged write that is
  nevertheless present. `DocumentStore.write` is the single place this order is decided; `put` and
  `delete` route through it rather than having fast paths of their own.
- **A torn tail may be dropped; anything that would lose an acknowledged commit is reported.** The
  checks that separate the two are in `LogReader` and none of them is a heuristic: a checksum failure
  with a readable record behind it, a sealed log with an incomplete tail, an unreadable header in any
  log but the newest, and a gap in the sequence numbers are all corruption. Do not "simplify" one of
  them away — each catches a case the others cannot see.
- **A tombstone may only be dropped at the bottom-most level that can contain the key, and only
  below the oldest live snapshot.** Dropping one while a deeper level still holds what it hides
  deletes the deletion, and the document comes back. Keeping a tombstone too long costs space;
  dropping one too early costs correctness, so the asymmetry is deliberate. The superseded-version
  rule is stated separately in `runCompaction` because it fails differently.
- **A file is deleted only after its last reference drops and its arena closes**, and only if it
  *departed* the tree. Order first: on Windows a mapped file cannot be deleted at all, so the
  deterministic unmap is what makes reclamation possible. Departure second: hanging deletion off
  "the last reference went" alone would make `close()` delete the live segments of a store that is
  merely shutting down.
- **Ordering everything: log, then memtable, then segment, then manifest, then delete.** A segment
  is forced before the manifest names it, and logs are deleted only after that record is forced. A
  crash between any two steps leaves an unreferenced file, which the next open sweeps — never a
  manifest naming a file that is not there.
- **`DocumentStore.write` publishes the read bound last.** A batch is atomic to one view because
  `visibleSequence` moves only once every operation is in the memtable. The unit is the view, not
  the call.

## Testing

The testing conventions live in `.claude/rules/testing.md`, which loads automatically whenever you
work with a test source root, `rabosh-testkit`, `rabosh-bench` or `build-logic`. Read it before
adding or changing a test: it is where the property harness, the three crash-safety instruments, the
golden-store rotation, the byte-identity rules and the benchmark gating are stated, and several rules
elsewhere in this file refer to it by name.
