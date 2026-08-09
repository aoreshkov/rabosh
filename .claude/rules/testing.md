---
description: Testing conventions for rabosh — the in-repo property harness, the three crash-safety instruments, golden stores, byte-identity suites, benchmark gating and the differential oracles.
paths:
  - "**/src/test/**"
  - "rabosh-testkit/**"
  - "rabosh-bench/**"
  - "build-logic/**"
---

The root `CLAUDE.md` indexes the cross-cutting design rules; each is argued in a path-scoped file
beside this one (`index-and-query.md`, `storage-durability.md`, `format-permanence.md`,
`index-sidecar-format.md`, `build-and-release.md`). Per-module conventions live in each module's
`CLAUDE.md`.

## Testing

- Property tests use the in-repo harness in `rabosh-testkit`, not an external framework:

  ```kotlin
  forAll(JsonGens.document()) { document -> /* invariant */ }
  forAll(Gen.int(0..100), Gen.string()) { count, name -> /* invariant */ }
  ```

  Edge cases run before random values. On failure the value is shrunk to a minimal
  counterexample and the report names the seed. **Pin that seed as a regression test** rather
  than only fixing the bug:

  ```kotlin
  // Regression: shrunk from a CI failure on 2026-07-25.
  forAll(gen, seed = -3246284733210625127L) { ... }
  ```

  Suite-wide overrides: `-Drabosh.property.seed=…`, `-Drabosh.property.iterations=…`.

- A custom `Gen` must make `shrink` **well-founded** — every candidate strictly simpler than its
  input — or the shrink loop only terminates by exhausting its budget, and reports arrive
  unminimised.
- `Gen.map` does not preserve shrinking (a `B` cannot be mapped back to its `A`). Where minimal
  counterexamples matter, implement `Gen` directly. `filter` does preserve it.
- `JsonGens` never emits lone surrogates or malformed number literals — those are
  malformed-input concerns and belong in targeted negative tests, not in roundtrip generators.
- Prefer differential testing against a reference: the JSON parser against
  `kotlinx-serialization-json`, the LSM against a `TreeMap`, the bitmap against
  `java.util.BitSet`, the query planner against a full scan.
- Crash safety is tested with the out-of-process kill harness and with direct byte damage. After
  any crash point, reopening must yield exactly the acknowledged-commit prefix.

  The two instruments prove **different** things, and it matters that they are not confused:

  ```kotlin
  // Ordering: a JVM killed with SIGKILL / TerminateProcess mid-write. The page cache survives a
  // killed process, so this asks "was anything acknowledged that is not there?" — not "did fsync
  // work". The child prints a line per durable commit; a line the parent has read is a fact.
  // The fourth argument rotates every n commits, so the kill lands inside a flush or a compaction.
  ChildJvm.launch("app.oreshkov.rabosh.core.CrashWriterMain", listOf(dir, "SYNC", "1000000", "8"))
  ```

  ```kotlin
  // Power loss: damage the files directly. The sweep truncates the log — and the manifest — at
  // *every* offset and asserts that what recovers is a prefix, is correct, and is monotone in
  // surviving bytes. Monotonicity is the interesting half: recovery going backwards as more data
  // becomes available would mean its stopping condition depends on something other than the data.
  for (limit in 0..length) { truncateTo(log, limit); … }
  ```

  An in-process "crash" tests neither: it still runs `finally` blocks and still flushes.

  The **third instrument is the fault-injecting filesystem** in `rabosh-testkit` — a delegating
  `FileSystemProvider` over the real one, so the bytes and the durability are real until a `Fault`
  says otherwise. It answers what neither of the others can: the process survives and the *write*
  fails, so the question becomes whether the failure was noticed, what the files were left as, and
  whether what survives is still the acknowledged prefix.

  ```kotlin
  // The sharpest fault a storage engine can be handed: every byte written, and the barrier that
  // was to make them durable failed. Invisible to any harness that only fails writes.
  val fault = fs.arm(Fault.onSuffix(FaultOperation.FORCE, ".wal"))
  assertFailsWith<IOException> { store.put(key, document) }
  assertEquals(1, fault.fireCount)   // never omit: a fault that never fired proves nothing
  ```

  Three shapes, deliberately not one: a **failed write** loses the bytes, a **short write** writes
  some and then fails (the torn record recovery exists for), and a **failed `force`** leaves them all
  in the file. `Fault.after` puts the failure in the middle of a sequence rather than at the start,
  and `fireCount` is asserted every time — `IoFailureTest` first passed with a fault armed on `.log`
  when the log's suffix is `.wal`, and the counter is what caught it.

  One constraint decides the harness's design and must not be undone: `FileChannel.map(mode, offset,
  size, Arena)` is **concrete on `FileChannel` and throws**, so `FaultyFileChannel` delegates that
  overload explicitly. A wrapper that inherited it would fail every mapped read in this engine, which
  is all of them, and the harness would cover exactly the paths that do not matter.

- **Formats are pinned by files, not by round trips.** `rabosh-query/src/test/resources/golden/` holds
  stores written by earlier builds — segments, manifest, log, sketches, posting files, columns — and
  `FormatCompatibilityTest` opens each with `backfill = false` so a sidecar that will not decode
  cannot be quietly rebuilt into a pass. A round trip cannot replace this: renumber a type id or
  reorder a header and every write-then-read test still passes.

  **Regenerating a golden store means adding a new directory beside the old one**, never editing one —
  the same rule as adding a format id rather than changing it. `-Drabosh.golden.write=true` writes
  fresh ones into `build/golden/` for a human to inspect. `EncodingPinTest` in `rabosh-variant` does
  the same job in hex, where the format is defined.

  A new corpus is a new `GoldenCorpus` implementation and a new resource directory; the assertions are
  parameterised over all of them, so adding one — or dropping one — is a list entry rather than a
  copied test.

  **The directories are not instances of one test, and the asymmetry is the point.**
  `store-v1` was written by the *phase 9 build*, which is the only reason a golden file is worth
  anything: what it catches is a change that is self-consistent, and only code that no longer exists
  can catch that.

  **Phase 17 collected on that investment, and the rotation is the thing to understand.** Until then
  `store-v2` was a round trip wearing a golden file's clothes — written by the build that read it,
  pinning nothing `IndexByteIdentityTest` and `PostingEncodingTest` did not — and `CLAUDE.md` said so,
  along with the prediction that it would become load-bearing *at the next format change and not
  before*. `POSTING_VERSION = 2` was that change. Both older directories now hold a dictionary layout
  this build **cannot write**, so between them they are the only cover in the repository for
  `FlatTermDictionary` on bytes nobody regenerated.

  **Phase 18 did it again the same day, which turns the rotation from a mechanism into an
  observation.** `store-v3` was recorded as inheriting v2's role — this build's own output, evidence at
  the next format change and not before — and `BASE_VERSION = 2` was that change, hours later. All
  three older directories now carry a version-1 key block and are the only cover for
  `FixedWidthKeyBlockReader`; `store-v3` additionally is the only one pairing that with a version-2
  posting file, which nothing will produce again. `store-v4` takes the vacated role. Two consecutive
  format changes have each been read by the build that replaced them, so the prediction this section
  makes about a new directory is now something the repository has watched come true twice.

  So say which role each directory is in rather than implying all are evidence. A fixture that is an
  investment is worth keeping, pretending it is proof is how a suite acquires tests nobody can
  evaluate — and a fixture that has *become* proof should be recorded as having done so, because that
  is the only observation which justifies the rule.

  A corpus states its own format facts — `postingVersion`, `baseVersion`, `columnsClaimFidelity` —
  rather than a test hard-coding them. That is what lets one assertion run over every directory:
  reading a version-2 file with version-1 arithmetic lands inside the wrong field and can easily still
  find the value it was looking for. Each of those facts also has to be true of **more than one**
  corpus in each direction, or the assertion is satisfied by the feature not existing; three claiming
  base version 1 and one claiming 2 is what makes that one honest.

  **A golden store is retired when it stops discriminating, and not before.** Keep the newest, plus any
  older one that still exercises a decode path no later store does; drop one when every path it covers
  is covered by a later store — and, after 1.0, when its format falls outside the supported window.

  **The window is now defined, and before 1.0 it excludes nothing.** `COMPATIBILITY.md` declares that
  every format version ever released stays readable and that a version leaves the window only in a
  **major** release. So the second clause above is inert until 1.0 and cannot be reached by a minor
  release however tempting the tidy-up: a directory may be retired for being *redundant*, never for
  being *old*. The two reasons are worth keeping apart because only the first is checkable — redundancy
  is a claim about decode paths that the remaining stores either cover or do not, while age is a claim
  about nothing.
  Phase 12 is the rule's first live outing and it added **no** directory: both committed stores predate
  `SECTION_FIDELITY`, so between them they already cover the path that matters — an optional section
  absent, read as no claim, projection falling back to the document. A store written that day would
  have been a round trip, and the new section's bytes are pinned in `ColumnFileTest` where the format
  is defined, as `EncodingPinTest` and `IndexFormatTest` pin theirs.

  Phase 17 is the second outing and it **added** one, on the other side of the same rule: a front-coded
  dictionary is a layout no committed file held, and there is no way to have one written by this build
  except to write it now. It also found what phase 12's correct decision had left behind — with no
  store carrying `SECTION_FIDELITY`, the test asserting an *absent* section would have passed for an
  engine in which push-down never fired anywhere. The corpora now state whether they claim fidelity and
  the assertion runs both ways. Same rule as `documentsRead == 0` needing a second counter beside it,
  applied to a format flag: **an assertion about absence needs the presence case somewhere, or it is
  satisfied by the feature not existing.**

  Phase 18 is the third outing and it added one on the same side as phase 17's, for the same reason: a
  varint key block is a layout no committed file held. **Nothing was retired, and each of the three
  older directories has its own reason** — `store-v1`'s `BITMAP` singletons, `store-v2`'s version-1 term
  dictionary, `store-v3`'s otherwise unobtainable (posting v2, base v1) pair — plus the reason they
  acquired together that day, being the only committed cover for `FixedWidthKeyBlockReader`. Three
  outings is enough to state the shape: the rule adds a directory when a *layout* changes and adds
  nothing when an *optional section* does, because a reader that skips a section is already covered by
  the files that lack it.

  **The fourth outing is `store-v5`, and it is the case that shape got wrong.** Index kind 3 changed no
  layout and spent no section, so phase 22 read it as the second column and added nothing — recording
  the decision in `format-permanence.md` in as many words. The reasoning was about backward
  compatibility and holds: a kind byte an older build does not know stops it before the record
  continuation, so no committed file means anything different. But **a golden store is evidence for the
  builds that come after, not for the ones that came before**, and by that test a registry carrying
  `fieldCount` and the declared field paths was a shape with no committed bytes at all, covered only by
  a round trip through its own writer. So the third column is now stated: add a directory for a
  **layout** change, add nothing for an *optional section*, and add one for an **extension older
  readers cannot see** — a record continuation, a new discriminator value — precisely because their
  invisibility is what stops every existing directory from pinning them. Absence in the four older
  stores is a real assertion and it needs the presence case, which is the `SECTION_FIDELITY` lesson one
  level up from a flag.

  `store-v5` is also the first written by a **tagged release** rather than by a phase commit, which is
  the sentence `COMPATIBILITY.md` actually promises — a store written by an earlier *release* opens on
  every later one — evidenced rather than approximated. Regenerate from the tag, never from `main`: the
  point of the directory is the build that wrote it. And it grows the corpus, which the three before it
  deliberately did not: a composite term keys a tuple inside an array *element*, and the shared corpus
  has no array of objects, so holding it fixed would have pinned the feature not existing. That is
  `store-v2`'s case, not a break with `store-v3`'s — **hold the corpus fixed for a layout change, grow
  it for a shape the format could not previously express.**

  Without a rule the "add a directory" rule accumulates binary blobs one format change at a time.
  Under it `store-v1` stays for a concrete reason and not out of sentiment: regenerating it with the
  phase 11 build differs in exactly two `.pst` files, by 110 and 44 bytes — 5 × 22 and 2 × 22 — so it
  carries **seven posting lists of cardinality one stored as `BITMAP`**, a shape this build can no
  longer write and which nothing else in the repo covers. Delete it and the `BITMAP` branch of
  `postingAt` over a single-ordinal bitmap has no fixture at all.

  Being pre-1.0 does not decide this, and it decides it even less than it used to. The obligation comes
  from the claim, and the claim was once narrower than "the format is stable" — it was *ids are never
  renumbered, only added*, which the README's old disclaimer could sit beside. `COMPATIBILITY.md` has
  since declared the format itself, so the obligation is **stronger** rather than discharged: these
  directories are no longer evidence for a narrow internal rule, they are the evidence for a public
  guarantee that a store written by an earlier release opens on every later one. `store-v1` is the only
  thing standing behind the oldest end of it.

- **Interop is tested against bytes another implementation produced, and those bytes are committed
  rather than generated.** `rabosh-index/src/test/resources/roaring/` holds the two cross-implementation
  conformance fixtures from `RoaringBitmap/RoaringFormatSpec` — the files CRoaring, the Java library and
  the Go port all test against — verbatim, with their upstream commit and licence recorded beside them.
  Generating them by putting RoaringBitmap on the test classpath would make "compatible" mean
  "compatible with the version of the library our tests happened to resolve"; deriving them from the
  specification would make it mean "compatible with our reading of the specification".

  The value set is built in the test **from the specification's own recipe**, never from a remembered
  cardinality, so a fixture that changed fails against the recipe instead of redefining what is
  expected. And the export is asserted **byte for byte**, not as an equivalent bitmap — available only
  because both sides choose each container's encoding by size, which is the same canonicality property
  that lets a flush-written sidecar be compared with a backfill-rebuilt one.

  The pair is not two instances of one fixture, and neither may be dropped as redundant.
  `bitmapwithoutruns.bin` is what RoaringBitmap writes *without* `runOptimize()`; `RoaringPortable.encode`
  always writes the smallest encoding and therefore cannot produce it, so it is the only cover in the
  repository for that cookie and for the run-free header — an **import** fixture. `bitmapwithruns.bin` is
  both, and re-exporting the first must produce the second exactly.

- **Benchmarks smoke in CI and run fully by hand.** `:rabosh-bench:smokeBenchmark` runs every suite
  once with a 200 ms iteration, which proves they still compile, start and measure the thing they
  name; `:rabosh-bench:mainBenchmark` is the real run.

  **"Every suite" was false for a year of phases, and how it became false is the lesson.** The two
  suites that build a 200 000-document fixture in `@Setup` — `QueryBenchmark` and `ReadBenchmark` —
  were *excluded* from the smoke configuration, because at smoke size the setup is the run. Reasonable,
  commented, and it left neither of them ever starting in CI while the sentence above claimed
  otherwise. Phase 19 fixed it by shrinking the corpus instead: `documentCount` is a JMH `@Param` on
  both, the smoke configuration passes 2 000, and the flush cadence is derived from it so that the
  fixture's level structure is held fixed across sizes rather than collapsing to one segment. Anything
  added to `rabosh-bench` from here is included and parameterised, never excluded — a suite CI does
  not select is a suite nothing checks.

  **Phase 16's mechanism could not have caught this, and that is a property of the design rather than
  a gap in it.** `BenchmarkRunReport` derives its expectation from the configuration's own `include`
  patterns, which is what makes it impossible to drift; a class no pattern selects is therefore not
  *missing*, it is not in the universe. A check that derives its expectation from a configuration
  cannot notice that the configuration omits something, so what guards this is the rule above and not
  another assertion.

  **A benchmark that did not run fails the build, and that is what makes the sentence above true.**
  kotlinx-benchmark's runner catches a JMH failure, prints it and returns normally, so a stale
  `jmh.lock` or a fork that will not launch used to print `BUILD SUCCESSFUL` over a task that measured
  nothing — a check passing because its subject never ran, which is the defect `IoFailureTest`'s
  `fireCount` rule exists to prevent. `BenchmarkRunReport` (in `build-logic`, with its own tests)
  asserts the **artefact**: the results file the runner was told to write exists, is newer than the
  runner configuration rewritten at the start of that run, and holds at least one result for **every
  benchmark class the configuration selected** — the universe being JMH's own generated
  `META-INF/BenchmarkList`, filtered by the configuration's own include patterns. Never a remembered
  count of last run's rows, and never a string match on JMH's error output, which is not ours to
  depend on. Four things fail rather than being skipped, all for one reason — *a check that quietly
  becomes a no-op is the thing being fixed*: an unreadable runner configuration, an unreadable
  benchmark index, a report format this cannot read, and an `include` pattern that selects nothing,
  which would otherwise make the expectation empty and satisfied by an empty run.

  Three details there are decisions. The check attaches to the `JavaExec` tasks the plugin generates
  (`mainBenchmark`, `mainSmokeBenchmark`), derived from `benchmark.targets × benchmark.configurations`
  rather than typed — because `smokeBenchmark` is a *lifecycle* task that depends on the real one, so
  a `doFirst` there runs after the benchmark and a `doLast` there has no `args` at all. Freshness is
  evidenced by the runner configuration's own timestamp rather than a clock, because the plugin fixes
  the report's timestamped path when the task is *configured*, so a configuration-cache hit reuses it
  and one successful run would otherwise cover for every failed run after it. And **this gates that a
  benchmark ran, never how fast** — benchmark numbers from a shared runner are still not a regression
  gate.

  `:rabosh-bench:holdJmhLock` holds the lock so the failure can be arranged rather than reasoned
  about; the unit tests cover the decision, and that covers the wiring.

  Three diagnostics are `main`s rather than JMH
  suites, because each is one run of a *curve* and a harness built to repeat one operation has nowhere
  to put one: `runAmplification` (bytes on disk per byte ingested), `runReadCost` (point-get latency
  against block and segment size) and `runPageCache` (reads as a store grows past RAM — tens of
  gigabytes and a long time; nothing in CI goes near it). Benchmark numbers from a shared runner are
  **not** a regression gate — that would be a test of the runner.

- **A benchmark measures the engine it is run against, including that engine's defects.** Phase 12
  reported that projection push-down removed two thirds of a projected query's overhead. Hours later
  phase 13 deleted a redundant checksum from the document-read path, and the same three benchmarks
  showed the advantage gone: the thing push-down avoided had become cheap. Nothing about push-down
  changed. Before attributing a win to a mechanism, ask what the baseline is *also* paying for — and
  when a later phase moves a number an earlier phase claimed, correct the earlier record rather than
  leaving two documents that disagree.

- **A sweep that moves two costs in opposite directions beats a before/after.** `ReadCostMain` grows
  the block size, which shrinks the index block and grows the data block: if re-verification is the
  cost the curve has a minimum, and if it is not the curve is flat. That is falsifiable in a way "it
  got faster" is not, and it decomposed the two CRCs into ns-per-entry and ns-per-KiB that matched
  CRC32C's known hardware rate — a model that was arithmetic before it was a measurement. Prefer this
  shape whenever a cost can be attributed to two mechanisms; and hold the segment count and level
  structure fixed across the rows, or the sweep is measuring the fixture.

  **And when the sweep's axis is the fixture itself, it runs twice — over the favourable shape and the
  unfavourable one.** `QueryCostMain` varies segment *count*, which is exactly the thing `ReadCostMain`
  holds fixed, so the clause above does not apply and something has to replace it. `Corpus` writes
  ascending keys, so flushing every *n* documents gives segments with **disjoint** key ranges — the
  shape a key-range rejection answers in one comparison — and the first version of the sweep reported
  12.9× for a change worth 3.5× wherever segments overlap. The second fixture writes every *n*-th key
  into each segment so all of them span the whole key space and no range check can fire. Same rule as
  `ColumnFidelityTest`'s mixed-scale segment and the pruning fixtures' disjoint ranges: **the
  unfavourable case is arranged, never hoped for, and the honest headline is the smaller number.**

- **A read path's allocation profile is part of the read algorithm, and it is measured rather than
  reasoned about.** `KeyBlockReader.keyAt` walked up to `KEY_RESTART_INTERVAL` front-coded entries
  allocating *two* byte arrays per step, and `ordinalOf` reached each ordinal of its group through
  `keyAt` — restarting the walk every time, `O(interval²)` where the layout offers `O(interval)`. Both
  are on the per-row path of every indexed query, through `SegmentKeys` and `SegmentSelection.isUniqueKey`
  respectively, and both had been there since phase 7 with nothing measuring per-row cost. So the `Walk`
  is one object shared by both methods for the same reason the abstract container classes are one, and
  the buffer it owns is a **local**: a scratch buffer on the reader would be one fewer allocation and a
  public `IndexReader` that cannot be shared between threads, which is a contract change bought with a
  micro-optimisation.

  Its own failure mode is the one to keep tested: reconstructing each key over the last one means **a
  key shorter than its predecessor leaves that predecessor's tail in the buffer**, so every read is
  bounded by the decoded length and `KeyBlockTest` arranges the shape deliberately — neither generator
  produced it, because `ascending` pads to a fixed width. `ordinalOf` also gained a differential against
  a *linear reference*, because the existing one compares the two layouts and they now share the walk:
  a differential between two callers of one implementation agrees with itself.

- **The facade's acceptance is that the directory can be deleted.** `RaboshLifecycleTest` opens,
  writes, indexes, queries, closes, and then removes the directory — which fails immediately and
  deterministically on Windows if any mapping in the store or either catalog was left live. Same
  instrument as `ResourceLeakTest`, aimed at the one thing a lifecycle owner can get wrong. Its
  companion is `RaboshAttachTest`, which asserts **one backfill pass against the manual wiring's two**
  by counting `beginSegment` and `observe` calls with the manual side reproduced in the same test —
  counted, never timed, and never against a remembered number.

- **A documented example is a test.** The README's opening snippet runs in `RaboshLifecycleTest`. A
  snippet nothing executes is a snippet that rots, and this one is the first thing anybody types.

  `rabosh-samples` is that rule at the next size up, and it changes what has to be asserted. A
  snippet is short enough that "it compiled and threw nothing" is nearly the whole claim; a sample is
  not, and one that runs to completion printing `0 rows` has failed at its only job while passing any
  such check. So `SamplesTest` asserts **output**: that the rendered model names the paths the corpus
  writes, that the explained plan names the index it used, and — the load-bearing one —
  that `documentsRead` *fell* between the before-index and after-index runs of the same query. That
  last assertion is verified by breaking it: with the `createIndex` call removed the suite fails, so
  it is not passing on the sample merely having printed something.

  The work assertion does not stand alone, and it is arranged so that it structurally cannot. Each
  sample `check`s its own before/after row sets for equality **inside the program a reader is
  looking at** — which is both the phase-8 rule and the best line in either sample — so a counter
  assertion in the suite cannot pass while the answer is wrong. `IndexLaterMain` reaches the
  half-covered state by **cancelling the build deliberately** rather than by racing it, for the reason
  `backgroundSegmentHook` exists: a sample whose interesting state depends on timing demonstrates
  something different on every machine, and would quietly demonstrate nothing on a fast one.

  Two constraints on the module that are decisions rather than defaults. It depends on
  `:rabosh-api` and **nothing else** — not even `rabosh-testkit`, whose corpus generator is the
  tempting shortcut and which exports JUnit as `api`, and no argument parser or logging facade,
  because anything else on the classpath is something the reader has to discount. And everything a
  sample **prints** is ASCII: `System.out` encodes to the console codepage on Windows, so an em dash
  arrives as a question mark, and for these programs the output *is* the deliverable. Comments and
  KDoc are unaffected.

- **Resource leaks are asserted through the filesystem, not through a memory threshold.** After a
  compaction, every replaced file must be gone from the directory — and on Windows a mapped file
  cannot be deleted, so a leaked mapping fails `ResourceLeakTest` immediately and deterministically
  rather than as a drift somebody has to pick a bound for. CI runs Windows and Linux for this reason.
  The same test asserts the converse: an open `Snapshot` or `DocumentCursor` must *keep* a replaced
  file on disk, because a reader may be inside it. `CatalogLifecycleTest` makes the same assertion
  about `.cat` sidecars and `IndexLifecycleTest` about `.idx` and `.pst`: after a compaction the set of
  sidecars must equal the set of segments, exactly — and an open `IndexReader` must keep a retired one
  until it closes.

- **A query plan is verified against a brute-force scan, against two oracles, in every state.** The
  two are not redundant: one runs `DocumentMatcher` over a full scan, so it isolates *planning* from
  *meaning*; the other is a second implementation over the testkit's `JsonValue` model with type
  bracketing written out by hand, so it checks the meaning. A plan agreeing with the first and not the
  second is executing a predicate nobody asked for. Every shape — equality, `IN`, range, strict range,
  disjunction, negation, `EXISTS`, `IS NULL`, a repeated path, a leaf with no index — is compared in
  all three coverage states and after **every step** of a randomised write/flush/compact script.
- **A statistic is asserted against a plan, never against a clock.** `Explain` reports *measured*
  cardinalities — it reads the sources it would use — so "the selective conjunct is intersected first"
  is a fact about the plan. A timing assertion in its place would be a test of the machine.

  **A complexity guarantee is asserted the same way, in the unit the algorithm counts in.**
  `rabosh-jsonpath`'s I-Regexp matcher promises to be linear in the subject — which is the whole reason
  it exists rather than a `java.util.regex` translation, since a `match` pattern may come from the
  document — and `IRegexpTest` holds it to `transitions ≤ 2 × instructions × (code points + 1)` with a
  `TransitionCounter` the matcher increments. A wall-clock assertion would have been a test of the
  runner, and "it returned quickly" is what a *backtracking* engine also does until the input that
  makes it not. Two clauses stop the bound being vacuous, and both are the standing rule that an
  assertion about work never stands alone: the cost must **grow** with the subject, and doubling the
  subject must at most double it. Reach for this shape whenever the claim is a bound rather than a
  number — count the thing the proof counts, and assert against the formula.
- **An index is verified against a full scan in three states, not one.** *Before* it is built, *during*
  — with only some segments covered — and *after*. The middle state is the one worth arranging
  deliberately, because "usable while it is still building, with no cutover" is a claim rather than an
  observation, and the only way to test it is to reach a partially covered store on purpose.
  Comparison is after **every** step of a write/flush/compact script, for the same reason the bitmap is
  compared after every operation. A snapshot straddling an overwrite that compaction cannot drop is a
  separate test, and it asserts *both* that the answer is right and that `segmentsStale` is 1 — that
  the index declared itself unusable rather than being lucky.

- **Index sidecars are compared as bytes, not as contents.** A sidecar written by a flush and the same
  sidecar rebuilt by a backfill must be byte-identical. Four things make that hold and none is
  incidental: ordinals come from counting `observe` calls and both paths share `DistinctKeyFilter`,
  equal ordinals encode to identical bytes, `Variant.fieldName` is name-ordered so a document read
  through a memtable's dictionary and through a segment's enumerate identically, and — since phase 17 —
  the term dictionary front-codes against the **sorted** sequence with canonical varints, so neither
  arrival order nor a padded length can produce a second spelling. Do not weaken it into "the sidecars
  agree".

  Phase 18 put the key block under the same varint and needed **nothing** added to that list, which is
  worth noticing rather than passing over: keys already arrived in ascending order from a single pass,
  and `IndexWriter.writeVarint` already wrote the shortest form while `IndexBytes.varint` already
  refused a padded one. A canonicality rule that costs nothing when a second caller arrives is a rule
  that was stated at the right level; if a third one ever needs an exception, that is the signal the
  level was wrong.

  Both of the choices that could have broken it are pure functions of the sorted term list and must
  stay so: `cardinality == 1` picks the singleton encoding, and a restart every sixteen terms decides
  what shares a prefix with what. `PostingEncodingTest` and `TermDictionaryTest` each build the same
  terms in two insertion orders and compare **bytes** — for two different reasons, which is why both
  exist: one is a property of each posting *list*, the other of each term's *neighbour*. A builder that
  shared prefixes against the last term it happened to see would answer every query correctly and break
  byte identity silently, on some corpora only.

- **A two-level checksum is tested as two levels.** A flip in a sidecar's directory must be caught when
  the file is *opened*; a flip inside a posting list must be caught only when that posting is *read*.
  If either were caught by the other, one of the two checksums would be pointless — and opening a
  ten-million-key sidecar would stop being cheap, which is the whole reason it is mapped.

  **A `SINGLE` posting is the one thing on both sides of that line**, because its ordinal lives in the
  directory: covered by the header checksum *and* by its own entry's, so a flip in it is caught on
  open. A clause on the rule and not a hole in it — the deferred half exists so that opening a file
  does not cost its postings, and a file whose terms are all singletons has no posting region left to
  defer. The corruption fixture therefore has to hold **both** encodings, or each half of the rule is
  being tested against half the format.

  Phase 17 put one more structure on the *open* side and took a walk off it. A version-2 restart array
  is checked when the file is opened — offsets ascend, the first begins the region — because it decides
  *where a term is*; the front-coded records themselves are checked in `termAt`. And opening got
  **cheaper**, not merely no dearer: version 1 walks every term entry to derive where the terms end,
  while version 2 has no per-term offsets to walk, so once the header checksum verifies, `presenceOffset`
  is a fact and the extent is not something to derive. `O(restartCount)` against `O(termCount)`.

  **The key block's restart array is checked from `verify`, not from `open`, and the asymmetry with the
  paragraph above is the design.** A posting file's restart count is a sixteenth of its *term* count and
  the file is opened once; a key block's is a sixteenth of a *segment's document* count, and `keyAt`
  resolves one ordinal in constant time — so an open-time walk would put an `O(n)` cost on the read path
  of a ten-million-key sidecar to catch damage that makes a key *wrong* rather than a read *wild*, since
  every offset is bounds-checked either way. `BaseSidecar.verify` is where the `O(documentCount)`
  diagnostic already lives. `IndexCorruptionTest` pins the split by opening the damaged file
  **successfully** before asserting that `verify` reports it; do not "fix" that by moving the check into
  the reader's `init`.

  A column adds a failure class the inverted index does not have: **readable and wrong.** Every phase-7
  corruption makes a file unreadable, but a flipped byte in a column's `STATS` leaves a file that
  decodes perfectly and silently over-prunes. That is why statistics carry their own entry checksum and
  are verified before first use, and it is a new *reason* for the two-level scheme rather than another
  instance of it.

- **Assertions about *work* never stand alone.** `documentsRead == 0` passes trivially for a query that
  returned nothing, and `blocksSkipped > 0` proves nothing if the data happened to be sorted. Every such
  assertion sits in the same test as the differential equality against a full scan, and the pruning
  tests arrange disjoint ranges deliberately rather than hoping for them. `documentsRead == 0` needs a
  second counter beside it now that a projection can avoid a read as well as a filter:
  `rowsProjectedFromColumns == rowsReturned` is what says push-down actually fired rather than the
  query having asked for nothing.

- **A projected value is compared against the document, never against a literal in the test.** The
  first draft of `ColumnFidelityTest` expected `0.50` back from a document written as `0.50`, and the
  engine says `0.5` — `decideNumber` strips trailing zeros before anything is stored. Asserting against
  a remembered string tests the author's model of the encoder; asserting against the document tests the
  round trip, which is the only thing the phase claims. The mixed-scale segment is **arranged
  deliberately** for the same reason a pruning fixture is: hoping a generated corpus produces one would
  leave the guard untested on most runs. Block pruning is a *locality*
  property: a column whose values are uniformly interleaved with key order prunes nothing at all,
  however selective the predicate, so a fixture that cycles its values cannot be asserted to skip.

- **The tagged `scale` suite is off by default.** `IndexScaleTest` runs 200 000 documents in every
  build, because "identical results" is a correctness claim; the ten-million-document version runs
  under `-Drabosh.index.scale=true`.

  **It asserts no wall-clock ratio, and the one it used to assert is worth knowing about.** The check
  was `indexed * 4 < scanned`, defended as generous enough to catch only "the index is not being used
  at all" — the query being correct for the wrong reason. Two things were wrong with that. It was
  redundant: `documentsRead == 0` and `segmentsScanned == 0` are asserted in the same test and cannot
  both hold unless the index answered the query, so the thing it claimed to catch was already caught,
  by a fact about the plan rather than about the machine. And it was not generous enough anyway — the
  first time it ran on a two-vCPU CI runner it failed at 3× faster than the scan, which is a passing
  result reported as a defect. The timings are still printed, because they are informative; nothing
  reads them back. This is the same rule as "a statistic is asserted against a plan, never against a
  clock", and this was the last place in the suite that did not follow it.

- **A background build is compared against a full scan in the *cancelled* state, not only the finished
  one.** A build that can be stopped is worth nothing if stopping it can change an answer, so every
  scheduling assertion in `IndexBackgroundBuildTest` sits in the same test as a differential equality —
  the phase-8 rule applied to a phase whose whole subject is *when* work happens. Resumption is
  asserted as arithmetic rather than as a sentiment: cancel after *n* segments, resume, and the second
  build's `segmentsBuilt` must be exactly the remainder.

  **The cancelled state is arranged, never hoped for.** `IndexCatalog.backgroundSegmentHook` is
  `internal` and exists because the first version of that test could not fail: a cancel issued from the
  test thread landed after the build had already finished every time, so it asserted "cancelled *or*
  completed" and would have passed for years while never once running the path it named — the defect
  phase 16 exists to prevent, and the answer phase 16 reached (arrange the state, commit the
  arrangement, as `:rabosh-bench:holdJmhLock` does). The same seam is what makes `IndexConcurrencyTest`
  drop an index *provably* between an observation beginning and its sidecars being written.

  And that drop test was verified by breaking the fix: with both halves of `Collecting.complete`'s
  registry check disabled it fails with `a column file outlived its index`. A test for a race that has
  never been seen failing is worth very little.

- **Reclamation rules are tested against a killed process, not a closed one.** `IndexCrashTest` kills
  a JVM mid-`createIndex` **and mid-`createIndexInBackground`** — the same two tests, parameterised
  over both, with **every assertion identical**. That is the point rather than extra coverage: making a
  build non-blocking, cancellable and resumable must not cost a single durability guarantee, and the
  way to show it is to change nothing except where the kill lands. If an assertion ever has to be
  weakened for the background case, the design is wrong. It asserts the durability inversion in both
  directions: an index the child
  reported as created is still defined, one it reported as dropped has not returned, and nothing is
  half-registered. It also asserts the residue is *reclaimed* — no posting file for an undefined
  index, no sidecar for a departed segment, no `.tmp`. That second half is what found the sweep rule
  below, so do not weaken it to "the store still opens".

- **`sweep` uses the segment file's existence; `prune` uses the horizon.** They are not
  interchangeable. `prune` runs off `retain` with a possibly stale set and must not list the
  directory, so the horizon is right there. `sweep` runs once at attach — and a kill leaves sidecars
  for segments the manifest never named, necessarily numbered *above* the live maximum, which the
  horizon would protect forever. Whether the `.seg` exists is exact in both directions: a store
  deletes unreferenced segment files as it opens, and a segment being written has its file on disk
  before its sidecar, so a racing flush is not mistaken for an orphan.

- **The catalog's two approximations are demonstrated, never asserted away.** A key overwritten but
  not yet compacted is counted once per segment holding a live version, and a deleted document's
  contribution survives until compaction drops the superseded version. So `SchemaInferenceTest`
  asserts *exactness* only on a corpus where each key is written once and the store is compacted,
  and there is a separate test that shows the tombstone case resolving across a merge. Asserting
  exactness on a corpus with overwrites would be testing a claim the design does not make.

- **The bitmap has two claims, and the second one is the strong one.** It matches `java.util.BitSet` under
  randomised scripts — compared after *every* step, because a bitmap that goes wrong on the fourth
  operation and right again on the sixth is exactly the shape a container-transition bug has. And **equal
  ordinals encode to identical bytes**: each block is normalised to the smallest of the three encodings, so
  the encoding of a set of ordinals is unique and comparing files compares contents. `BitmapView.verify`
  enforces it on reading too, which is why a wastefully encoded block is reported rather than accepted. Do
  not weaken either into "the values match" — the byte form is what phase 7 will compare sidecars by.

- **A container threshold is tested *at* the value, not near it.** 4095, 4096 and 4097 scattered ordinals;
  a run of four values and a run of five. Each of those is a different answer from its neighbour, and a test
  that only checks "well below" and "well above" is not testing the threshold at all.

- **A cardinality estimate is not a count.** `HyperLogLog` is exact below its sparse limit and an
  estimate above it, and `PathSketch.distinctIsExact` says which. Assert equality below the limit
  and a three-standard-error bound above it — never equality above it, however tempting a round
  number looks.
- **Tests that reason about logs, segments or sealed memtables must set
  `backgroundMaintenance = false`.**
  Otherwise a flush writes the memtable out as a segment and deletes the log behind it — correct
  behaviour, and exactly what removes the fixture. `flush()` writes on the calling thread in both
  modes, so it is a real barrier; `compact()` rotates, flushes and then compacts, so it is a
  stronger one.

  The exception proves why: the *only* tests that turn maintenance back on are the ones about racing
  it, `IndexConcurrencyTest` and `CatalogBackfillRetainTest`. Phase 7 found a live bug that had been
  invisible precisely because every lifecycle test ran with it off — `DocumentStore.backfill` reported
  the segments live when its scan *started*, so a compaction landing mid-scan made `attach` delete the
  sidecar of a segment that was alive. Two things now prevent it and both must stay: `backfill` reads
  the live set after its loop, and no sidecar numbered **above the maximum of the retained live set**
  is ever deleted, because a number above it is not a departed segment but one the set is too old to
  mention.

  Phase 15 adds a second kind of concurrency and does **not** need maintenance on to reach it: a
  background build has a thread of its own, so `IndexBackgroundBuildTest` keeps
  `backgroundMaintenance = false` and still races two threads. The rule is unchanged and so is its
  reason — those tests reason about which segments exist, and the build must be the only thing moving.
