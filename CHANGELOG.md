# Changelog

All notable changes to this project are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) — with one qualification that matters
more here than the version number does.

**Two guarantees, at different strengths, and neither waits for `1.0`.** The **on-disk format** is
declared and stable — a store written by an earlier release opens on every later one — and anything
affecting it is stated in [COMPATIBILITY.md](COMPATIBILITY.md) first and only summarised here. The
**Kotlin API** is tiered: a small stable core moves only under a deprecation cycle, and everything
else may change in any release. That claim lives in [STABILITY.md](STABILITY.md), on the same terms.

## [Unreleased]

### Added

- **`IndexCandidateOptions.maxDistinctFraction` — a ceiling on cardinality, unbounded by default.**
  `indexCandidates()` had `minDistinct` and no upper end, on the argument that a distinct value per
  document is the *best* equality index there is and that excluding it would confuse a bitmap's
  storage shape with an index's usefulness. That argument is still right, which is why the default is
  `Double.POSITIVE_INFINITY` and every existing caller gets the recommendations it got before.

  **What it missed is that the scorer is not the one who knows.** Over the transcript corpus the
  top-ranked candidate, at a score of 1.00, is `$.toolUseResult.structuredPatch[*].lines[*]` — every
  individual line of every diff Claude Code has ever shown. Present, perfectly type-stable, ~61 000
  distinct values: exactly what the ranking rewards, and an index over it would answer
  `line == "import app.oreshkov.rabosh.variant.Variant"` beautifully. It is just not a question
  anybody has. The missing input is **how many rows the caller expects back**, which the caller always
  has and a sketch can never derive, because an identifier and a category are the same shape. So
  `maxDistinctFraction = 1.0 / 50` says *a term should name a category*: at most one distinct value
  per fifty documents.

  Measured against `InferredSchema.documentCount` and against the *estimate* at the path, so a
  repeated path may exceed `1.0` legitimately — `$.tags[*]` can hold more distinct values than there
  are documents, the same asymmetry `InferredField.presence` has. Like `minDistinct` it gates the
  inverted index alone: a shredded column is read for the bytes it avoids, not for how many rows a
  lookup returns, so the tightest band that admits no term at all still admits the column.

  `IndexCandidateOptions` is stable core, and adding a constructor parameter *replaces* a JVM
  signature rather than adding one — so the six-argument form is retained at
  `DeprecationLevel.HIDDEN`, which is what [STABILITY.md](STABILITY.md)'s deprecation cycle reaches
  for at exactly this point: the symbol stays in the bytecode and leaves the source language.
  Already-compiled callers keep linking, and the committed ABI dump loses nothing.

  `:rabosh-samples:runTranscripts` was the caller filtering the recommendation by hand, which is the
  usual sign of a knob that belongs in the API, and it now hands the band to `indexCandidates`
  instead. That is not only tidier: a filter over the results is a filter over the *top sixteen*
  results, so a category ranked behind sixteen identifiers was one the sample could never reach. The
  sample's step 2 still prints the unbanded report, because what the scorer says without being told
  the expected answer size is the finding rather than a mistake to hide.

- **A sample that runs the three steps over Claude Code's own session transcripts, and a
  `SessionEnd` hook that feeds it.** `./gradlew :rabosh-samples:runTranscripts` ingests
  `~/.claude/projects/**/*.jsonl` — JSONL written by a program none of us control, in a shape
  documented nowhere — and then derives a model of it and indexes it. Nothing published, nothing
  added to the runtime, no new dependency: JSONL is lines of JSON and `Variant.fromJson` takes bytes.

  **`ThreeStepsMain` makes the argument on a corpus this repository generates, which is the honest
  way to make it reproducible and the dishonest way to make it convincing.** `SampleCorpus` is ragged
  because somebody chose its raggedness. This one is ragged because it is: on the corpus it was
  developed against, `$.message.content` arrived as an array 47 492 times and as a string 995 times,
  and `$.parentUuid` was explicitly `null` 258 times and absent otherwise — the two shapes
  `SampleCorpus` has to fabricate, occurring unprompted.

  Three things it does that no existing sample does, each because the corpus is live rather than
  generated. The reader is **resumable** and holds back a **torn tail** — the last line of the
  session being written right now has not finished arriving, and parsing it would turn a timing
  accident into a decode failure the next run would then skip past. A parse failure is **counted and
  reported**, which is `.claude/rules/format-permanence.md`'s rule about unknown data arriving from
  outside for once rather than being asserted about our own bytes. And the derived model comes back
  **truncated**: the corpus has roughly six times `CatalogOptions.maxPaths` distinct paths, and
  `InferredSchema.truncatedPathEstimate` is what turns that from a silent cap into a stated one.

  The `SessionEnd` hook (`rabosh-samples/hooks/session-end-queue.sh`) is deliberately not registered
  by this repository — a committed `.claude/settings.json` would switch it on for everyone who clones
  and write to their home directory — and deliberately does no ingest: `SessionEnd` hooks share a
  1.5-second budget, and a JVM start does not fit in it. It appends its stdin verbatim, so the queue
  is JSONL and the transcript reader reads it unchanged.

  `SamplesTest` runs the whole thing twice against a corpus it synthesises, never against the
  developer's own `~/.claude`, and asserts on the second run that the document count did **not**
  move. That assertion is there because a broken resume is invisible: keys are deterministic, so
  re-ingesting the whole corpus duplicates nothing, answers nothing wrongly and leaves the store
  correct. The count is the only symptom, and it was verified by breaking it.

### Changed

- **The walk's breadth budget is raised to 65 536, and the two copies of it are now one number.**
  `CatalogOptions.maxChildren` was 4096 and `IndexOptions.maxChildren` was 1024; both now default to
  `DEFAULT_MAX_CHILDREN` = 65 536. No format change, no ABI change, and nothing on disk means
  anything different — a sidecar written before this is read exactly as it was.

  **The reason is that this bound was the one budget in the engine that could cost a document
  silently.** Every other one either reports having fired or is declined symmetrically by the reader:
  `maxPaths` overflow is counted in `InferredSchema.truncatedPathEstimate`, `maxTermsPerSegment`
  drops the index for the segment so it reads as *not covered* and is scanned, `maxTermBytes` is
  applied to the same bytes by the planner so what the writer dropped is what the query declines, and
  a truncated bound widens. `maxChildren` does none of that: a container wider than the bound is
  walked to the bound, the segment still reads as covered, and — because the recheck runs the same
  `TermExtractor` — the fallback scan truncates identically, so both differential oracles agree with
  the shortfall. It is invisible to the suite by construction, which is why the number rather than the
  mechanism is what moved here.

  **The index's copy being *lower* than the catalog's was its own defect.** `TermExtractor` is
  `SegmentSketchBuilder`'s walk with a filter on it, and says so, because a differently-shaped
  traversal would make the estimator and the index disagree about what a path is. At 1024 against 4096
  the catalog counted a path's occurrences, recommended an index on the strength of them, and the
  index then recorded a quarter of them and reported itself covered.

  **Raising it is a mitigation and not a fix**, and it is written down as one: the fix is a coverage
  signal, which would let the bound come back down. A corpus with containers wider than 65 536 must
  still set the option — a measured protobuf-JSON dump holds 12 040 elements under one path, and a
  store that does not know its own widest array is trusting a number rather than checking one.

## [0.3.0] — 2026-08-13

**The release about everything around the answers.** 0.2.0 finished what the engine can *answer*;
this one is about what an application embedding it has to do — copy a store while it is being
written, retire what it has already drained, bound an expression it did not write, tell a second
launch who holds the directory, resolve by name on the module path — and about saying in writing
which parts of the Kotlin API are allowed to move. No format version moved, and the only new bytes on
disk are a diagnostic line in `LOCK` that nothing is required to read.

### Added

- **`checkApiTiers` — the stability tiers are now held by a gate rather than by a script.** The
  module-wide opt-in that keeps the engine from needing several hundred `@OptIn`s also blinds the
  compiler to a public signature that *names* an experimental type without carrying the marker, and
  an ABI dump writes signatures and never annotations — so between them nothing was checking that
  `STABILITY.md`'s claim was still true. Run by hand it had already found four such leaks.

  `ApiTierAudit` reads the marker set from the **sources** and the surface from the **committed
  dumps**, both derived and neither listed: a hand-maintained list of experimental types would
  disagree with the annotations exactly once, silently, in the direction of not reporting a leak. It
  is a root task, because a type marked in one module leaks through another module's dump, and it
  hangs off `check`, so `./gradlew build` runs it.

  Promoting it found a bug the hand-written version never had to have: attributing a marked member to
  the *most recent* type declaration rather than the enclosing one put `IndexCatalog.read` inside a
  `private class` two hundred lines above it. Nesting now follows indentation, and the sibling case is
  a test.

- **`Rabosh.checkpoint(target)` — a consistent copy, taken while you are writing.** The recipe it
  replaces was *stop writing and copy the directory*, which a desktop application cannot do because it
  is the writer. The database is flushed, a snapshot is pinned, and the copy is of what that snapshot
  sees, so it holds exactly the acknowledged prefix as of `CheckpointInfo.sequence`. Segments are
  hard-linked where the filesystem allows it, so it costs a directory entry per file rather than its
  bytes — which also means it is a consistent *view* rather than an off-site backup.

  Sidecars travel with their segments and are **read** by the copy rather than rebuilt, verified by
  opening the checkpoint with backfilling off. The `INDEXES` registry travels too, because an index
  definition is an instruction somebody gave rather than derived data. The fault-injecting filesystem
  fails the copy at four steps and the **source is unharmed in every case**.

- **`Rabosh.deleteRange(from, to)` — retention by key range**, both bounds inclusive and both
  optional. The loop a caller would otherwise write, which to write correctly means knowing four
  invariants of the storage layer. Deliberately point deletes rather than an LSM range tombstone: no
  new operation id, no format change, no change to compaction. Follow it with `compact()`, which is
  what turns tombstones into reclaimed space.

- **`JsonPathLimits` — bounded evaluation for untrusted JSONPath.** `rabosh-jsonpath`'s chosen use
  case is expressions you did not write, and until now nothing bounded what a *small, valid* query
  cost against a *large* document: `$..*..nope` is fourteen characters, is quadratic in the document's
  node count, and returns the empty nodelist — so nothing measured on the answer can see it coming.

  **The bound refuses; it never truncates.** Exceeding it raises `JsonPathLimitExceededException` and
  delivers nothing, because a short nodelist cannot be told from a small document. Counted in steps
  and never on a clock, for the reason the I-Regexp bound is. All 703 compliance cases and the
  module's 20 000-deep and 5 000-wide fixtures pass under the shipped defaults, which are a backstop
  rather than a policy — a deployment serving hostile expressions should set its own, far lower.

- **`explain()` says when a predicate cannot match the data's types.** A numeric comparison against a
  path where a third of the values arrive as strings now carries a note on the plan. A *diagnostic,
  never a coercion*: type bracketing is unchanged, `ColumnPredicate.matches` is still the only
  definition, and nothing here makes a numeric predicate match a string. Reported for leaves with no
  index too, which is where a caller has no other signal at all.

- **`Variant.detached()` and `InferredSchema.shreddingAdvice()` — the lakehouse hand-off**, with no
  Parquet dependency taken. A document read from a segment carries *that segment's* shared dictionary,
  so handing `(metadata, value)` to something expecting a self-contained Variant is a trap that
  sometimes works — `detached()` rebuilds it with a dictionary of its own. The advice renders what the
  catalog already computes for a Parquet **shredding schema**, including the decision a hand-written
  one gets wrong: whether `variant_value` can be dropped.

- **`toJsonSummaryString(limit, depth)` — a summary that reaches past its own top level.** The
  one-level form elides every child below the root however small it is, so a document whose
  interesting field is one level down summarised to `{"id":42,"order":{…3},…9 more}`. The new overload
  is the same walk carried further: `depth = 1` *is* the old function, pinned equal to it by property
  over every document and every limit rather than left to be read off the code, and each level applies
  `limit` independently. Both elisions stay distinguishable — `{…4}` is a container the walk stopped
  *at*, `…2 more` is what a level had left over.

  **`depth` has no default and will not be given one.** At most `limit + limit² + … + limit^depth`
  values are shown, so the parameter is exponential in the cost and the number a caller writes is the
  price they are agreeing to. What does not change is the property the summary exists for: what is
  read and written is a function of `limit` and `depth` alone, with no term in it for the document's
  size, and the recursion is bounded by `depth` rather than by the value's own nesting — checked
  against a 20 000-deep built document.

- **`StoreLockedException` says who holds the directory.** It carries `directory` and a `LockHolder`
  with the pid and start time the `LOCK` file records, so a desktop application's second launch can
  focus the existing window instead of matching on a message. **The start time is not decoration** —
  operating systems reuse pids, and `isRunning` checks both, so a user is never told to kill a
  stranger's process. No lock stealing, no timeout, no force-open.

- **`:rabosh-samples:runDrain`** — a staging buffer drained: snapshot, ship, record the watermark,
  retire, compact, in that order. It composes `checkpoint` and `deleteRange`, which makes it their
  acceptance test in the only way that matters, a caller's program. Also **`Key.successor()`**,
  promoted to public because writing that loop found it was the one thing the inclusive bounds cannot
  say.

- **`:rabosh-bench:runTextBoundCost` — what a wider text bound buys, and what it costs.** 160 000
  documents over values carrying a 40-byte shared prefix, run over a clustered corpus *and its own
  permutation*, because block pruning is a locality property and the unfavourable case is arranged
  rather than hoped for.

  **The pruning curve is a step and the step is a proof.** At or below the shared prefix every bound
  collapses to that prefix, the maximum is the prefix incremented, and the half-open range covers
  every value the prefix can start — so the skip rate is exactly zero. One byte past it, 0.444. Four
  bytes past it, 0.950, which is 19 of 20 blocks and the maximum available, since the block holding
  the value cannot be skipped. **The cost curve is flat**, and it was arithmetic before it was a
  measurement: a bound is paid for twice per block plus once per segment, so widening 8 bytes to 46
  predicts 1 596 bytes and measured 1 596, or 0.02% of an 8 MB column. That also refuses an adaptive
  bound — one curve is a step and the other is flat, so there is no trade-off to adapt to. The default
  stays 64, which is above this corpus's prefix; the shape that defeats it needs a 64-byte prefix, and
  raising it is a one-line change that now has a measurement behind it.

- **[`INTEGRATION.md`](INTEGRATION.md) — the runtime contract, in public.** The rules an embedding
  application has to obey were discoverable only by reading KDoc on classes a caller may never open,
  and three of them fail *silently*: a row is valid only until the next `next()`, a leaked `Snapshot`
  pins disk indefinitely, and a second writing thread gets contention rather than an error. One page,
  every claim naming the type, option or test that enforces it.

- **[`STABILITY.md`](STABILITY.md) and `@RaboshExperimental` — which parts of the Kotlin API are
  allowed to move.** "Major version zero, any signature may change" was honest and unactionable: a
  consumer could not tell whether `Key.of` was as volatile as `IndexCatalog.readColumn`, so the only
  rational responses were to wrap all of the API or none of it. There are now two tiers — a small
  stable core that moves only under a deprecation cycle, and everything else, marked with an opt-in
  requirement. It is deliberately **not** a promise of 1.0.

  What is marked is the way *in* rather than every member: `Rabosh.store`/`catalog`/`indexCatalog`,
  `DocumentStore.open`, the `SchemaCatalog`/`IndexCatalog`/`QueryEngine` constructors,
  `IndexCatalog.read`/`readColumn`, and the bitmap, column and sketch types themselves. Holding one
  of those objects means you already opted in, so its own methods carry nothing.

  `rabosh-samples` is what holds the claim, and it holds it by *not* opting in: it depends on
  `:rabosh-api` alone, compiles with `allWarningsAsErrors`, and is part of `build`, so it is a real
  consumer compiling against the stable core. The ABI dumps cannot do this job — the JVM dump format
  writes signatures and never annotations, so a tier change is invisible to `checkKotlinAbi`.

- **`Automatic-Module-Name` in every published jar**, derived from the module name:
  `app.oreshkov.rabosh.{variant,core,catalog,index,query,api,jsonpath}`. On the module path the jars
  previously resolved under names derived from their filenames, which is unstable by construction and
  is where a `jlink`/`jpackage` build stopped. Held by a new
  `:rabosh-samples:runThreeStepsOnModulePath`, which asks the JVM for `app.oreshkov.rabosh.api` **by
  name** — so a missing attribute fails at startup rather than silently resolving something else.

### Changed

- **No `--enable-native-access` flag is required, by any module**, and `INTEGRATION.md` now says so.
  The engine maps segments through `FileChannel.map(mode, offset, size, Arena)`, which is *not* a
  restricted method — it carries no `@Restricted` and declares no `IllegalCallerException` — and
  nothing here calls one that is. The new module-path task runs the full cycle under
  `--illegal-native-access=deny` with no grant of any kind, so the claim is checked rather than
  asserted, and a future release that acquires a restricted call fails it.

### Fixed

- **`CatalogOptions.textBoundBytes` documented itself as the dial that decides pruning, and it is not
  that dial.** Its KDoc said a truncated sketch bound was what lets the planner skip on it; nothing in
  `rabosh-query` reads a sketch bound at all. There are two dials, both defaulting to 64, which is how
  they came to be argued about as one. `CatalogOptions.textBoundBytes` truncates the bounds in a `.cat`
  sketch, and those are **descriptive** — rendered by `InferredSchema.render`, readable through
  `InferredField.bounds`, written to the sidecar. `IndexOptions.columnTextBoundBytes` truncates a
  shredded column's segment bounds and its per-block statistics, and *that* is what `ColumnReader`
  skips on. Widening the catalog dial buys legibility; widening the index dial buys pruning. A
  correction to a published module's public API documentation rather than a change of behaviour: no
  default moved and no dump was rewritten.

### Considered and refused

Two proposed features were specified, checked, and found not to be well-formed — a different outcome
from "not yet", and each is now pinned by a test so a later attempt meets the argument rather than
rediscovering it.

- **A JSONPath filter as a query predicate.** The only bridge from a filter selector to a `Predicate`
  is "the document matches when the nodelist is non-empty", and it does not work: on
  `{"tags":["a","b"]}` the filter `$.tags[?@ == 'b']` selects the `b` and `$.tags[?!(@ == 'b')]`
  selects the `a`, both non-empty, so `where(f)` and `where(not(f))` would return the same document. A
  filter's `!` negates a test about one node while the selector stays existential over the rest; a
  `Predicate`'s `not` negates the answer for the document, after the existential is folded. Both
  quantify correctly, over different things, which is why neither can adopt the other — and `elemMatch`
  is already that feature under a name that says which way it quantifies. Pinned by
  `FilterIsNotAPredicateTest`, in `rabosh-jsonpath` alone: the differential that would compare the two
  semantics directly cannot be written anywhere here without acquiring the module edge the layout
  forbids.

- **A `QueryCursor` that hands back the locations that matched a row.** "The locations that matched" is
  a partial question. A positive leaf has witnesses; a negated leaf has none; `not(exists())` is
  satisfied by the absence itself — so an empty witness set would mean both *nothing matched* and *the
  match was an absence*, with one spelling. The positive half is no easier: a leaf is existential and
  settled on the first satisfying value, so by the time a row exists the engine holds one bit per leaf
  and no locations, and reporting all of them means abandoning that short-circuit for every query
  including the ones that never ask. What already answers it is a `CatalogPath` walked per row, which
  asks where a *path* matched rather than where a *predicate* did — and every match of a path has a
  location by construction. `MatchWitnessTest` drives every case through the engine's own evaluator.

### Compatibility

- **The composite index's on-disk shape is now pinned by committed bytes**, in a fifth golden store
  written by the `v0.2.0` tree. 0.2.0 added index kind 3 without a version bump or a section kind, and
  recorded that no golden store was needed — true of every file written *before* it, and the wrong
  test: a store defining a composite index carries a registry record no committed file held, and the
  only thing standing behind it was a round trip through the same writer. `golden/store-v5` holds the
  kind-3 registry continuation, the composite kind byte and a tuple dictionary, and is read by the
  compatibility suite alongside the four older stores. Nothing in the engine changed.

- **No format version moved**, and the ten encodings stand at the versions
  [COMPATIBILITY.md](COMPATIBILITY.md) tabulates. A store written by 0.1.0 or 0.2.0 opens on this
  release unchanged, its sidecars read rather than rebuilt.

- **`LOCK` now carries one line of ASCII** — `pid=… startedAt=…` — where it held nothing at all. It is
  a **diagnostic and not a format**: no magic, no version, nothing reads it but a process that has just
  failed to take the lock, and an empty one reads as *holder unknown*, which is exactly what an older
  release's `LOCK` reads as and what `StoreLockedException.holder` reports for it. The lock is still on
  byte zero and the line begins after it, so a build that locks the whole file and a build that locks
  byte zero go on excluding each other. Releases stay mixable on one directory.

### Upgrading

```kotlin
dependencies {
    implementation("app.oreshkov:rabosh-api:0.3.0")
    implementation("app.oreshkov:rabosh-jsonpath:0.3.0")   // optional, and deliberately not transitive
}
```

**One source-level break, and it is the point rather than a side effect.** `@RaboshExperimental` is an
opt-in **error**, so code reaching past the stable core — `Rabosh.store`, `Rabosh.catalog`,
`Rabosh.indexCatalog`, `DocumentStore.open`, the `SchemaCatalog` / `IndexCatalog` / `QueryEngine`
constructors, `IndexCatalog.read` / `readColumn`, `SchemaCatalog.sketchOf`, `InferredField.sketch`, and
the bitmap, column and sketch types — compiled on 0.2.0 and now has to say so:

```kotlin
@OptIn(RaboshExperimental::class)
fun dumpPostings(db: Rabosh) { … }

// or, for a module that lives down there:
kotlin { compilerOptions { optIn.add("app.oreshkov.rabosh.RaboshExperimental") } }
```

Nothing moved and nothing was removed — the marker states what "major version zero" already said, in
the one place a compiler can carry it. **Binary compatibility is unaffected**: the annotation has
`BINARY` retention and nothing reads it at run time, so an already-compiled 0.2.0 consumer keeps
linking. Code using only the stable core listed in [STABILITY.md](STABILITY.md) needs no change at
all, which is the claim `rabosh-samples` holds by depending on `:rabosh-api` and opting in to nothing.

**You can drop `--enable-native-access` if you added it for rabosh.** No module requires it, and
`:rabosh-samples:runThreeStepsOnModulePath` now runs the full cycle under
`--illegal-native-access=deny` with no grant, so the claim fails a build rather than being asserted in
a comment. The flag stays harmless if you keep it.

## [0.2.0] — 2026-08-09

**A document's interior became addressable.** 0.1.0 could tell you *which documents* matched and
structurally could not tell you which `$.items[3]` inside one of them did. This release closes that
from three directions — a JSONPath query over a document you already hold, a correlated `elemMatch`
with an index behind it, and the ordinary indexes put in front of the element walk — and adds a
seventh published module to carry the first.

### Added

- **`rabosh-jsonpath`** — a seventh published module: [RFC 9535](https://www.rfc-editor.org/info/rfc9535/)
  JSONPath over a document you are already holding. `JsonPathQuery.compile(…)` and then
  `forEachNodeIn` / `nodesIn`, answering with the `VariantNode`s `rabosh-variant` already defines.

  It expresses the two things a `CatalogPath` cannot: **a condition** — `$.items[?@.sku == 'A' && @.qty == 5]`
  selects the element where *both* hold, which is the recheck callers were writing by hand — and **a
  descendant segment**, `$..sku`, for documents whose nesting depth is not known in advance.

  **All 703** of the JSONPath Compliance Test Suite's cases run and pass, with nothing excluded. The
  module depends on `rabosh-variant` and nothing else, and nothing in the storage chain depends on it
  — which is what keeps RFC 9535's comparison rules and the query language's, which genuinely
  disagree, from ever deciding the same question.

- **`match` and `search`, over an [RFC 9485](https://www.rfc-editor.org/info/rfc9485/) I-Regexp
  matcher written for the job.** The two function extensions were declared and refused while the
  matcher was missing; they are now answered, which is what took the compliance claim from 647 cases
  to 703 and let it stop saying "less `match` and `search`".

  ```kotlin
  JsonPathQuery.compile("$.items[?match(@.sku, 'ABC-[0-9]{3}')]")
  ```

  **Not `java.util.regex`, and the reason is a security one rather than a preference.** A filter runs
  once per *document* over a corpus, and RFC 9535 lets the pattern itself come from the document — so
  a backtracking engine would put `(a|aa)+b` behind somebody's data. This is a Thompson construction:
  each instruction is visited at most once per input position, so a match costs the pattern times the
  subject and never more, and the bound is asserted in **transitions** rather than on a clock. The
  answers are additionally checked against `java.util.regex` over the sub-language both can spell.

  Two behaviours worth knowing. A pattern that RFC 9485 does not admit — including `\d`, `\s`, `\w`,
  lookaround, backreferences and Unicode blocks, all of which it removed from XSD deliberately — makes
  the *result* `LogicalFalse` rather than failing to compile, which is what §2.4.6 requires. And a
  pattern is refused outright above 10 000 compiled instructions or 64 levels of group nesting, which
  is the resource bound RFC 9485 §7 asks for; a pattern from a hostile document costs a comparison
  rather than a hang.

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

  **And a query asking for *more* than the index declares now uses it too.** `elemMatch($.items[*],
  and($.sku eq "A", $.qty eq 5, $.note eq "x"))` against an index over `(sku, qty)` is narrowed by the
  tuple — correlatedly, which is what no ordinary index can do — and the extra conjunct is settled by
  the element walk over what survives. Dropping a conjunct inside the existential only widens it, so
  this needed no new mechanism and no format change; the exact case, where the tuple accounts for the
  whole conjunction, still opens zero documents.

  Asking for **fewer** fields than the index declares still gets nothing from it, and that is a
  correctness limit rather than a gap: a term exists only for an element carrying every declared field,
  so an element with a `sku` and no `qty` is keyed nowhere. Index the field on its own if you query it
  on its own.

### Compatibility

No format change in either sense that matters. The JSONPath module writes nothing to disk. The
composite index is **additive**: a new permanent index-kind id, a `.pst` that is a posting file in
every byte but one header field, and the declared fields carried in the registry through the kind byte
rather than through a version bump — no version was bumped, no section kind was spent, and no golden
store was added. A store written by an earlier release opens unchanged; an earlier release meeting a
store that defines a composite index reports it as written by a newer build, which is what an unknown
id has always meant here. No existing `.api` dump lost an entry; one was added, for the new module.

### Upgrading

`rabosh-api` brings the storage chain with it and **does not bring `rabosh-jsonpath`**, which sits
beside the chain rather than in it — nothing depends on it, which is what keeps RFC 9535's comparison
rules away from the query planner. Add it explicitly if you want it:

```kotlin
dependencies {
    implementation("app.oreshkov:rabosh-api:0.2.0")
    implementation("app.oreshkov:rabosh-jsonpath:0.2.0")   // optional, and deliberately not transitive
}
```

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

[Unreleased]: https://github.com/aoreshkov/rabosh/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/aoreshkov/rabosh/releases/tag/v0.3.0
[0.2.0]: https://github.com/aoreshkov/rabosh/releases/tag/v0.2.0
[0.1.0]: https://github.com/aoreshkov/rabosh/releases/tag/v0.1.0
