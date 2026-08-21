# rabosh — working conventions

An embedded JSON storage engine for the JVM, written from scratch in Kotlin. Store JSON of
unknown structure, derive a model of it later, add indexes retroactively.

"Phase *N*" throughout the instruction files, the module notes and the source comments refers to a
numbered development iteration of the engine. The numbers are chronology — they say when a decision
was taken and what it replaced — and they are not released versions.

## Where the conventions live

This file holds what every session needs: the rules that fire in conversation rather than at a file,
and a one-line index of the invariants. **The argument behind each invariant lives in a path-scoped
rule that loads when you open the code it governs.** Read the one covering what you are about to
change, and do not copy its content back here — a rule stated twice is a rule that can disagree with
itself.

| File | Loads when you touch | Holds |
| --- | --- | --- |
| `.claude/rules/build-and-release.md` | build scripts, `gradle/`, `build-logic/`, CI workflows, `COMPATIBILITY.md`, `README.md` | the dependency history, ABI validation, toolchain, publication, the format claim |
| `.claude/rules/format-permanence.md` | `rabosh-core`, `rabosh-index`, `rabosh-catalog`, `rabosh-variant`, `COMPATIBILITY.md` | the magics, permanent ids, when a version bump is allowed, unknown data |
| `.claude/rules/index-sidecar-format.md` | `rabosh-index` | one spelling per varint, the key block, front-coding, posting encodings, the exchange format |
| `.claude/rules/index-and-query.md` | `rabosh-index`, `rabosh-query`, `rabosh-api`, `rabosh-catalog` | index soundness, bounds, type bracketing, planning, build lifecycle, derived-data durability |
| `.claude/rules/storage-durability.md` | `rabosh-core` | write ordering, recovery, tombstones, file reclamation, block verification |
| `.claude/rules/testing.md` | any test source root, `rabosh-testkit`, `rabosh-bench`, `build-logic` | the property harness, the three crash-safety instruments, golden stores, byte identity, benchmark gating |
| `<module>/CLAUDE.md` | that module | what the module owns, and which of its shapes exist once on purpose |

`rabosh-jsonpath` is the one module none of the format or planner rules govern — it writes nothing to
disk and holds no plan. Its own `CLAUDE.md` is the whole of its conventions, and the first of them is
why it must stay beside the dependency chain rather than in it.

A path-scoped rule arrives when a matching file is **read**, so it can still be absent while you are
planning. Before designing a change to an on-disk shape, an index, a plan or the write path, open the
rule that covers it rather than working from the summaries below.

## Dependency policy

**JetBrains and Kotlin libraries are pre-approved. Every other third-party library requires
explicit confirmation from the user before it is added.** Ask before writing the dependency into
`gradle/libs.versions.toml`, not after, and present the trade-off against writing the thing by hand —
that is a real option here and has been chosen before.

**Zero runtime dependencies is a claim the README makes, so keep it true.** Anything proposed for the
runtime scope needs an argument against the JDK, not only the user's approval. The declines this rule
already survived, and the one addition that did not need asking, are in
`.claude/rules/build-and-release.md`.

## Toolchain

Versions are centralised in `gradle/libs.versions.toml`; do not inline them in build scripts. Keep
them at the latest stable release; do not adopt pre-releases (e.g. Kotlin `-Beta`) without asking.

- **JDK 25 is not incidental**: the engine maps segments through the FFM API, which is final from
  JDK 22. Tests run with `--enable-native-access=ALL-UNNAMED`.
- **The ABI tasks are `checkKotlinAbi` and `updateKotlinAbi`, and the `…LegacyAbi` pair is not a
  synonym** — the legacy names are deprecated shims that will throw and then be removed. Reference
  dumps live at `<module>/api/<module>.api`.
- **`build-logic/` is an included build, and its tests are not part of the root `build`** — CI runs
  `./gradlew -p build-logic check` as its own step.
- **`checkApiTiers` is the gate `checkKotlinAbi` cannot be**: a dump carries signatures and never
  annotations, and module-wide opt-in blinds the compiler, so a public signature exposing a
  `@RaboshExperimental` type without carrying the marker is invisible to both. It is a root task and
  runs under `build`.
- **`gradle.properties` stays `0.1.0-SNAPSHOT`**: `release.yml` derives the release version from the
  git tag and nowhere else. Do not "fix" it to a release number.
- **The format claim lives in `COMPATIBILITY.md` and the API claim in `STABILITY.md`, each in one
  place**, and the README links both rather than restating either. The on-disk format is declared and
  stable; the Kotlin API is tiered, with a stable core and an explicit `@RaboshExperimental`.
- **The runtime contract lives in `INTEGRATION.md`** — JDK floor, the native-access question, one
  writer, the `AutoCloseable`s, copy-before-`next()`. A sentence about bytes on disk belongs in
  `COMPATIBILITY.md` and is linked, never moved.
- **No module needs `--enable-native-access`, and that is checked**:
  `:rabosh-samples:runThreeStepsOnModulePath` runs under `--illegal-native-access=deny` with no grant.
  `FileChannel::map` is not a restricted method; do not add the flag back on the assumption that it is.

## Module layout

Dependencies flow strictly downward. Do not introduce an upward or sideways edge.

Each module's own conventions live beside it and load when you work under that directory:
`rabosh-index/CLAUDE.md`, `rabosh-query/CLAUDE.md`, `rabosh-api/CLAUDE.md`,
`rabosh-catalog/CLAUDE.md`, `rabosh-core/CLAUDE.md`, `rabosh-jsonpath/CLAUDE.md`. Read the one for
the module you are changing before changing it — each states what that module owns and, more
importantly, which of its shapes exist once on purpose.

**`rabosh-jsonpath` sits *beside* the chain and is the exception to the sentence above the table.**
It depends on `rabosh-variant` and nothing else, and nothing in the chain depends on it. That is not
tidiness: it is what keeps RFC 9535's comparison semantics unable to reach the planner, and adding an
edge onto it from any other module is a decision rather than a refactoring.

## Design rules that must not be quietly broken

An index of claims, not the arguments. Every line is stated in full, with the failure it prevents and
the test that holds it, in the rule named at the end of its group. Each one is here because breaking
it fails **silently** — a document missing from a result, a file that stops meaning what it said.

### Answers, indexes and plans — `.claude/rules/index-and-query.md`

- A facade may change ergonomics, never answers.
- An index may change query speed, never query answers.
- A walk budget bounds what the engine writes and never what a query answers, and that takes a
  stand-down *and* a reader with no budget — either alone leaves the shortfall.
- An index over a segment is sound at a snapshot if and only if the snapshot's sequence is at or
  above that segment's largest sequence.
- Indexes are per-segment immutable sidecar files, never part of document data.
- Compaction awareness is structural, and a posting-list merge would be worse.
- A bound never narrows.
- A null slot holds the type's zero, and that zero must never reach a bound.
- Type bracketing is part of the query contract, because skipping depends on it.
- A negated leaf is never a flipped operator.
- `explain` may report a type mismatch and may never repair one.
- A plan's candidates are a superset and its certainties a subset, and the gap is what gets read.
- A composite index needs every declared field fixed by equality and does not care what else the query
  asks: **more** conjuncts are dropped and cost it only its certainty, **fewer** are unsound.
- A composite term cannot be scanned by prefix, and the reason is the exactness argument from behind.
- Stopping an index build is safe because coverage is honest, and that is why cancellation has no
  rollback.
- A worker thread is shut down, never interrupted.
- A background build is stopped before anything it is scanning is closed.
- Sketches and indexes are derived; documents are not.
- An index *definition* is not derived data, and its durability rule inverts the one above.

### The write path — `.claude/rules/storage-durability.md`

- A checkpoint obeys the ordering rule in the target directory, not only in the source.
- `deleteRange` is point deletes, and staying that way is the decision.
- The `LOCK` file's byte zero is the lock and everything after it is a diagnostic.

- The log is appended before the memtable is touched, always.
- A torn tail may be dropped; anything that would lose an acknowledged commit is reported.
- A tombstone may only be dropped at the bottom-most level that can contain the key, and only below
  the oldest live snapshot.
- A file is deleted only after its last reference drops and its arena closes, and only if it
  *departed* the tree.
- Ordering everything: log, then memtable, then segment, then manifest, then delete.
- `DocumentStore.write` publishes the read bound last.
- A block is verified once where there *is* a once, and on every read where there is not.

### What is written to disk — `.claude/rules/format-permanence.md`

- The magics are spelled `JKDB-`, and that is not a typo to correct.
- On-disk type ids and format constants are permanent. Never renumber — **add**.
- A version bump is not a renumbering, and this engine has taken exactly two. A third is allowed,
  permanent, and not cheap.
- `SketchFormat.typeId` and `IndexFormat.indexKindId` must never use an enum `ordinal`, and
  `SketchHash` and `ValueSignature`'s tags must not change — these three fail silently.
- An unknown section kind is skipped rather than reported, and a manifest edit tag is not.
- An optional section states its claim positively: a capability, never a defect.
- `.idx` and `.col` share the framing and not the vocabulary, and not the version field either.
- Unknown data decodes to a signalled failure, not to a default.

### Sidecar bytes — `.claude/rules/index-sidecar-format.md`

- Sidecars are split by lifetime, and a sidecar is written once.
- The engine's variable-width fields are two varint regions, and each has exactly one spelling.
- Variable width only where a sequential walk was happening anyway; fixed width everywhere a position
  is computed.
- The key block's saving has no crossover, and the term region's does — do not describe them alike.
- Front-coding pays on long terms and costs on short ones, and the entry narrowing pays always.
- `POSTING_ENCODING_SINGLE` reinterprets a field, and that is what an encoding byte is for.
- An exchange format is not the storage form, and its constants obey the opposite rule.
