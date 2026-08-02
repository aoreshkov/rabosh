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

## The format claim

**The format claim lives in `COMPATIBILITY.md` and nowhere else.** It used to live in the README's
status blockquote, coupled to the version in `gradle.properties`, and the coupling was what kept it:
each artefact cited another and none cited the format. The two guarantees are now separate and stated
at the strength of their own evidence — the **on-disk format is declared and stable**, held by the
golden stores; the **Kotlin API is major-version zero** and free to move, held by nothing, which is
exactly why it is not claimed. A change to either belongs in `COMPATIBILITY.md` first; the README
links it rather than restating it, so the two cannot drift.
