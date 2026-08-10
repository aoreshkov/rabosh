---
description: Why rabosh has no runtime dependencies, how ABI validation and the toolchain are pinned, how publication is set up, and where the format claim is allowed to live.
paths:
  - "**/*.gradle.kts"
  - "gradle/**"
  - "gradle.properties"
  - "build-logic/**"
  - ".github/workflows/**"
  - "COMPATIBILITY.md"
  - "README.md"
---

The rules every session needs — ask before adding a dependency, keep versions in
`gradle/libs.versions.toml`, the ABI tasks are `checkKotlinAbi` and `updateKotlinAbi` — are stated in
the root `CLAUDE.md`. This file is the argument behind them.

## Dependency policy

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

**And the JDK is not automatically the answer either, which the I-Regexp matcher is the first case of.**
`java.util.regex` costs no dependency and was still declined: it backtracks, RFC 9535 lets a `match`
pattern come from the *document*, and a filter runs once per document over a corpus — so
`rabosh-jsonpath` carries a hand-written Thompson construction whose cost is linear in the subject and
asserted in transitions. Every earlier "library or by hand" here turned on **owning the bytes**; this
one turns on **owning the worst case**, and it is worth keeping apart from the others because the
usual argument — the JDK is free, take it — points the wrong way. The rejected translation survives as
the test oracle, which is the shape to reach for whenever an alternative is declined on a property
rather than on its answers.

## ABI validation

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

## Publication

Publication lives in `rabosh.kotlin-library` too: `maven-publish` with a full POM, a sources jar and
Dokka's HTML under the `javadoc` classifier, checked by `publishToMavenLocal` in CI so a broken POM
fails where it is introduced rather than at a release. Two things there are decisions rather than
defaults. The POM carries **no email address** — a POM is published forever, and an address in one is
permanent. And `gradle.properties` stays `0.1.0-SNAPSHOT` for a *mechanical* reason rather than a
claim: `release.yml` derives the release version from the git tag and nowhere else, and
`CentralBundleReport` rejects a `-SNAPSHOT` through that path, so the checked-in version is the
development one by construction. Do not "fix" it to a release number — that would put the version in
two places and make the tag advisory.

## The format claim, the API claim and the runtime contract

**Each lives in exactly one file, and the README links all three rather than restating any.**
`COMPATIBILITY.md` holds the on-disk format, `STABILITY.md` the Kotlin API, `INTEGRATION.md` the
runtime contract. The format claim used to live in the README's status blockquote, coupled to the
version in `gradle.properties`, and the coupling was what kept it: each artefact cited another and
none cited the format.

The two guarantees are stated at the strength of their own evidence. The **on-disk format is declared
and stable**, held by the golden stores. The **Kotlin API is tiered** — a stable core that moves only
under a deprecation cycle, and an explicit `@RaboshExperimental` for the rest. It used to be
"major-version zero, held by nothing", which was honest and unactionable: a consumer could not tell
whether `Key.of` was as volatile as `IndexCatalog.readColumn`, so the rational response was to wrap
all of the API or none of it. Phase 23 replaced it with the smaller, truer claim, and **that is a
substitute for 1.0 rather than a step towards one** — say so wherever it is described.

Three things about the marker that a change must not quietly undo.

**It lives in `rabosh-variant`, package `app.oreshkov.rabosh`, and it has to.** Everything marked is
below `rabosh-api` in the chain, so a marker declared there could not be applied in `rabosh-index`
without the upward edge this project does not have. The package is the project's rather than the
codec's for the same reason.

**What is marked is the way *in*, not every member.** `Rabosh.store`/`catalog`/`indexCatalog`,
`DocumentStore.open`, the `SchemaCatalog`/`IndexCatalog`/`QueryEngine` constructors,
`IndexCatalog.read`/`readColumn`, `SchemaCatalog.sketchOf`, `InferredField.sketch`, and the bitmap,
column and sketch *types*. Marking every member instead forces every stable signature naming an
experimental type to be marked too, and that cascade ends with the stable core inside the experimental
tier. `SegmentObserver` is that cascade caught at one step: `RaboshOptions`' constructor names it, so
marking the interface would have put `RaboshOptions(...)` behind an opt-in. It is stable, deliberately.

**The gate is `rabosh-samples` not opting in, and the ABI dumps are not the gate.** The JVM dump
format writes signature lines and never annotations — verified in the dumper, and confirmed by the
markers changing the committed dumps by exactly one entry, the annotation class itself — so a
declaration changing tier is invisible to `checkKotlinAbi`. What catches it is the samples module:
`:rabosh-api` and nothing else, `allWarningsAsErrors`, part of `build`, and the one module the
opt-in is deliberately withheld from. Do not "tidy" that asymmetry by giving every module the same
compiler options; `rabosh.kotlin-library`, `rabosh-testkit` and `rabosh-bench` opt in, samples do not.

## Native access: the flag nobody needs

**No module requires `--enable-native-access`, and the reason is not that the engine avoids the FFM
API.** It maps every segment through `FileChannel.map(mode, offset, size, Arena)` — which is simply
**not a restricted method**: it carries no `@Restricted` and declares no `IllegalCallerException` in
JDK 25, and neither do `Arena.ofShared`, `Arena.allocate` or `MemorySegment.ofArray`. The restricted
set is `MemorySegment::reinterpret`, the `Linker` and `SymbolLookup` entry points and the
`load`/`loadLibrary` family, and nothing here calls one.

This was believed otherwise for several phases and written into a build comment as fact, so it is
worth stating how it was settled: not by reading the JEP, but by running the engine under
`--illegal-native-access=deny` with no grant and watching it pass, and then confirming that the same
flag *does* fail a two-line program that calls `MemorySegment.reinterpret`. A check that has not been
seen fail proves nothing, and that applies to a check on the JVM's behaviour as much as to one in the
suite.

`:rabosh-samples:runThreeStepsOnModulePath` is where the claim now lives, and the module path is the
only place it can: `ALL-UNNAMED`, which the other two samples pass, would cover a restricted call from
the classpath and hide the answer. The `--enable-native-access=ALL-UNNAMED` on `Test` tasks and on the
two classpath samples is retained as harmless future-proofing; the *reasoning* attached to it is not
load-bearing and should not be repeated as though it were.
