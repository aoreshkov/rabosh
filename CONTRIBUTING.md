# Contributing to rabosh

Thanks for looking. This is a single-maintainer project with strong opinions about a few specific
things; everything here is either a build requirement or one of those opinions, written down so you
do not have to discover it in review.

If you are reporting a security problem, do not open an issue —
see [SECURITY.md](SECURITY.md).

## Before a large change, open an issue

Small fixes — a bug, a test, a documentation error, a clearer name — are welcome as pull requests
directly. For anything that adds a public API, changes a file format, or adds a dependency, open an
issue first. Not as a formality: three of the rules below can make an otherwise good change
unmergeable, and finding that out after the work is done is nobody's idea of a good time.

## Building

You need **JDK 25**. This is not a preference. The engine maps every segment through the FFM API
(`Arena`, `MemorySegment`, `FileChannel.map(..., Arena)`), which is final from JDK 22, and there is
no fallback path on an older JDK. You do **not** need `--enable-native-access`: nothing in the engine
calls a restricted method, the test JVMs are given no grant, and `INTEGRATION.md` says why.

```sh
./gradlew build                # compile, test, and check the public ABI against the committed dumps
./gradlew test                 # tests only
./gradlew -p build-logic check # the build logic's own tests — not part of the root `build`
```

Both of those run in CI, on Linux **and** Windows. The Windows leg is not a formality: a mapped file
cannot be deleted on Windows, so the resource-leak assertions in `ResourceLeakTest`,
`IndexLifecycleTest` and `RaboshLifecycleTest` only actually assert anything there. A change that
holds a segment open too long passes on Linux and fails on Windows, which is the entire reason the
matrix exists.

Benchmarks are not part of `build`. CI smokes them to prove they still start and measure what they
name; the real numbers come from a quiet machine:

```sh
./gradlew :rabosh-bench:mainBenchmark
```

## Four rules that will fail your pull request

**1. A public API change needs its ABI dump updated.** Every published module commits
`api/<module>.api`, and `checkKotlinAbi` — which `build` depends on — fails when the code and the
dump disagree. After an intentional change:

```sh
./gradlew updateKotlinAbi
```

and commit the result. The tasks are `checkKotlinAbi` and `updateKotlinAbi`; the `…LegacyAbi` pair
that Kotlin still registers are deprecated shims and using them is a build that breaks on the next
upgrade for no benefit today.

**A dump says nothing about stability tiers**, so a second check runs beside it. `checkApiTiers` —
also part of `build` — fails when a public signature exposes a `@RaboshExperimental` type without
carrying the marker itself. If it names your declaration, either mark it or move the type into the
stable core and say so in [STABILITY.md](STABILITY.md); the one thing not to do is leave a consumer
holding an experimental type that nothing asked them to opt in to. It reads the marker set from the
sources, so adding a marker is all that is needed to teach it.

**2. A new dependency needs agreement first, and the runtime scope is closed.** "No runtime
dependencies at all" is a claim the README makes, so it has to stay true — the JSON parser, the
compressed bitmap, the HyperLogLog, the bloom filter and the property-test harness are all in-repo
rather than pulled in, and that is deliberate. Owning the bitmap format is precisely what lets an
index sidecar be read straight off a mapped segment with no deserialization step.

JetBrains and Kotlin libraries are pre-approved for test and build scopes. Anything else, ask in an
issue before writing it into `gradle/libs.versions.toml`, and expect the trade-off against writing
the thing by hand to be taken seriously — RoaringBitmap, simdjson-java and Kotest were all offered
and declined. Anything proposed for the *runtime* scope needs an argument against the JDK, not just
agreement.

Versions live in `gradle/libs.versions.toml` and are never inlined in a build script. Latest stable,
no pre-releases.

**3. On-disk constants are permanent — add, never renumber.** Format magics, type ids, section
kinds, encoding bytes, operation ids and the hash functions behind the bloom filter and the
HyperLogLog are all written to files that exist on other people's disks. Changing what one of them
means silently reinterprets every file ever written. Extension points exist for this: a segment
block's `blockType`, a bitmap container's `kind`, and a sidecar section's `kind` are all bytes whose
whole purpose is that a new encoding is a new id rather than a new file version.

The magics are spelled `JKDB-`. The project was called `jsonkdb` when the format was written, and
that prefix is retained history, not a typo — renaming it would invalidate the golden stores and buy
nothing.

Any change to the format claim belongs in [COMPATIBILITY.md](COMPATIBILITY.md) first. It is stated
in one place on purpose, so the README and the release notes cannot drift from it.

**4. An index may change query speed, never query answers.** Every planner change is verified against
a brute-force scan over the same data. If you touch the planner, the index sidecars or the column
statistics, the differential suite is what says you were right — and a test that passes at the
current sequence can miss a whole class of index-staleness bug, so read the reasoning before
weakening one.

## Testing

The conventions are in [`.claude/rules/testing.md`](.claude/rules/testing.md) — the property
harness, the three crash-safety instruments, the golden-store rotation, the byte-identity rules and
how benchmarks are gated. It is long because the interesting failures in a storage engine are the
ones a naive test cannot see. Read the part covering what you are changing.

Two habits worth stating up front:

- **Assert on the output, not on the run.** A benchmark that produced no results and exited zero, a
  sample that printed `0 rows`, a check whose subject never executed — these are the failures this
  project has actually had, and several classes exist only to make them loud.
- **A test over data beats a test over syntax** for anything about query semantics. Type bracketing
  and negated leaves are both pinned by corpora rather than by assertions about the AST, because
  the tempting simplification in each case is correct-looking and deletes documents from a result.

## Style

`allWarningsAsErrors` is on. `explicitApi()` is on for every published module, so public
declarations need explicit visibility and return types.

Comments explain *why*, especially where the obvious thing is wrong. Much of this codebase's comment
volume is a record of an alternative that was tried or considered and rejected; if you remove one,
you are removing the reason somebody will otherwise re-introduce the bug. Match the density of the
file you are in.

Commit messages are prose in the imperative — "Name the ABI tasks that are not on a removal path",
not "fix: abi". Say what changed and why it changed.

## What is not here, and why

**CodeQL.** There is no code-scanning workflow, and the reason is now one release wide rather than
several. CodeQL's `java-kotlin` extractor is version-locked to the Kotlin compiler it traces, and the
CodeQL CLI *bundle* that `github/codeql-action` ships is what decides which versions that is. Support
for Kotlin **2.4.20** — this build's version — merged on 2026-09-08 (github/codeql#22404) and missed
the cut for CLI **2.27.0**, released the next day and still the newest bundle. Run it against this
build today and the extractor stops before reading a line:

```
Kotlin version 2.4.20 is too recent. CodeQL currently supports versions below 2.4.20
```

The workflow is written and was briefly on this branch — recover it with
`git log --diff-filter=D -- .github/workflows/codeql.yml` — so putting it back is a file rather than a
design. The load-bearing part of it is `build-mode: manual`: CodeQL extracts Kotlin by tracing the
compiler, so the build under it runs with `--no-daemon`, `--no-build-cache`,
`--no-configuration-cache` and `-Dkotlin.compiler.execution.strategy=in-process`, each of them there
to keep compilation inside a process the tracer started. Getting one wrong does not fail — it
extracts less, which is the failure mode this repository is most careful about elsewhere.

**Check the ceiling against the bundle, not against the documentation.** CodeQL's supported-languages
page said "Kotlin 1.8.0 to 2.4.20" while the shipped extractor rejected 2.4.20, for two reasons at
once: the upper bound is exclusive, and the page describes the CLI being developed rather than the
one a runner downloads. The artefact that answers is `java/kotlin-extractor/versions.bzl` at the
`codeql-cli/vX.Y.Z` tag the pinned action resolves to — a list of compiler versions, with this
build's `kotlin` from `gradle/libs.versions.toml` either in it or not. That is the same rule the
dependency claim in `README.md` is verified under: **check a claim against the artefact you receive,
not against the source that was meant to produce it.**

It goes in when a bundle later than 2.27.0 ships, and nothing else has to change for it to.

## Licence

By contributing you agree that your contribution is licensed under the
[Apache License 2.0](LICENSE), the same as the rest of the project.
