# Stability

This document is rabosh's declared public API for its **Kotlin surface**. Its sibling
[COMPATIBILITY.md](COMPATIBILITY.md) does the same job for the on-disk format, and the two are
deliberately separate because they move at different speeds and rest on different evidence.

**This is not a promise of 1.0, and it is not a step towards one.** It is a smaller and truer claim:
*these* declarations move under a deprecation cycle, and the rest may move in any release. "Major
version zero, any signature may change" was honest and unactionable — a consumer could not tell
whether `Key.of` was as volatile as `IndexCatalog.readColumn`, so the only rational responses were to
wrap all of the API or none of it. Two tiers cost nothing and say what the evidence supports.

| | Guarantee |
|---|---|
| **Stable core** | Removed or changed incompatibly only after a release deprecating it. Listed below. |
| **Everything else** | May change or be removed in any release. Marked `@RaboshExperimental` where the compiler can enforce it. |

## The stable core

Small on purpose. It is the surface the README's examples call, the surface the two samples call, and
it has not moved in two releases.

**`rabosh-api`** — `Rabosh` (`open`, `close`, `put`, `get`, `delete`, `deleteRange`, `write`, `scan`,
`snapshot`, `query`, `keys`, `explain`, `createIndex`, `createIndexInBackground`,
`buildIndexesInBackground`, `dropIndex`, `indexes`, `schema`, `indexCandidates`, `attach`,
`checkpoint`, `flush`, `sync`, `rotate`, `compact`, `stats`, `directory`, `options`) and
`RaboshOptions`.

**`rabosh-core`** — `Key` (including `successor`), `WriteBatch`, `Durability`, `Snapshot`,
`DocumentCursor`, `StoreOptions`, `StoreStats`, `LogRecoveryMode`, `SegmentObserver`,
`SegmentObservation`, `SegmentSummary`, `CheckpointInfo`, `LockHolder`, and the whole
`StoreException` hierarchy.

**`rabosh-query`** — `Query`, `Predicate` and its cases, the predicate DSL (`path`, `and`, `or`,
`not`, `eq`, `anyOf`, `exists`, `isNull`, `elemMatch`, the comparison operators), `PathRef`,
`Comparison`, `QueryValue`, `Projection`, `Row`, `QueryCursor`, `QueryStats`, `Explain`,
`ExplainSource`, `ExplainTypeNote`, `IndexUse`.

**`rabosh-index`** — `IndexDefinition`, `IndexHandle`, `IndexBuild`, `IndexBuildProgress`,
`IndexBuildState`, `IndexCoverage`, `IndexOptions`, `DamagedIndexPolicy`, `CompositeSegmentObserver`,
and the whole `IndexException` hierarchy.

**`rabosh-catalog`** — `CatalogPath`, `CatalogStep` and the node walk, `InferredSchema`,
`InferredField` (except its `sketch`), `CatalogCoverage`, `IndexCandidate`, `IndexCandidateOptions`,
`IndexKind`, `ValueBounds`, `NumericRange`, `TextRange`, `CatalogOptions`, `DamagedSketchPolicy`,
`ShreddingAdvice` and `InferredSchema.shreddingAdvice`, and the whole `CatalogException` hierarchy.

**`rabosh-variant`** — `Variant` and its readers including `detached`, `VariantNode`, `VariantPath`,
`VariantPathStep`,
`VariantKind`, `VariantBasicType`, `VariantPrimitiveType`, `VariantBuilder`, `VariantMetadata`,
`DuplicateFieldPolicy`, `toJsonString` / `toJsonSummaryString`, and the whole `VariantException`
hierarchy.

**`rabosh-jsonpath`** — `JsonPathQuery`, `JsonPathLimits`, `JsonPathLimit`,
`JsonPathLimitExceededException`. The limits are stable core rather than experimental because the
module's chosen use case is evaluating expressions you did not write, and a bound a caller cannot
rely on is not a bound.

### Two entries that are in the list for a reason worth knowing

**`SegmentObserver` is stable.** It is the seam every layer above `rabosh-core` is built on, and
`RaboshOptions` takes one, so it is part of the supported way to compose the engine rather than an
internal that leaked. It could not have been marked experimental even if that had been wanted:
`RaboshOptions`' own constructor names it, and opt-in propagates through signatures, so marking the
interface would have made constructing `RaboshOptions` require opt-in — the stable core's own options
object, inside the experimental tier.

**`InferredField` is stable except for one property.** Everything on it is a named reading against
the document count; `sketch` is the serialised estimator those readings are derived from, and its
registers, hash and sparse limit belong to the sidecar format.

## `@RaboshExperimental`

Everything not listed above may change or be removed in any release, with no deprecation cycle. Where
that can be stated to the compiler, it is:

```kotlin
@OptIn(RaboshExperimental::class)
fun dumpPostings(db: Rabosh) { … }
```

Marked today: `Bitmap`, `BitmapView`, `BitmapCursor`, `ReadableBitmap`, `RoaringPortable`,
`ColumnReader`, `ColumnQuery`, `ColumnScan`, `ColumnMatch`, `ColumnPredicate`, `IndexReader`,
`IndexQuery`, `KeyCursor`, `CompositeTerm`, `TermExtractor`, `ElementExtractor`, `HyperLogLog`,
`SegmentSketch`, `ValueSignature`, `ValueBoundsBuilder`; the `Rabosh.store`, `Rabosh.catalog` and
`Rabosh.indexCatalog` accessors; `DocumentStore.open`; the `SchemaCatalog`, `IndexCatalog` and
`QueryEngine` constructors; `IndexCatalog.read` and `IndexCatalog.readColumn`;
`SchemaCatalog.sketchOf`; and `InferredField.sketch`.

### What is marked is the way *in*, not every member

A `ColumnReader` can only be reached through `Rabosh.indexCatalog` or `IndexCatalog.readColumn`, and
both of those are marked — so once you hold one, its methods carry no further annotation. The same
goes for a handful of types that carry no marker of their own and are reachable only through one:
`PathSketch`, `IndexTerm` and the rest of the sidecar vocabulary. They are outside the stable core by
this list, which is the claim; the annotation is the enforcement, applied at the entrances.

Marking every member instead would take some hundred and fifty annotations and, worse, would force
every stable signature naming an experimental *type* to be marked too — a cascade that ends with the
stable core inside the experimental tier. The `SegmentObserver` note above is that cascade caught at
one step.

## The deprecation cycle

A stable-core declaration is never removed in the release that stops recommending it. It first ships
with `@Deprecated(DeprecationLevel.WARNING)` carrying a `ReplaceWith` wherever a mechanical
replacement exists; a later release moves it to `DeprecationLevel.HIDDEN`, which keeps the symbol in
the bytecode so already-compiled callers keep linking; only after that may it go. A declaration in the
experimental tier gets none of this, which is the whole difference between the tiers.

Moving a declaration *between* tiers is a change like any other: into the stable core is additive and
may happen in any release; out of it goes through the cycle above.

## How this is held to

Two mechanisms, and it is worth being precise about which does what, because the obvious one does
less than it looks.

**`checkKotlinAbi` holds the signatures, not the tiers.** The committed dumps at `<module>/api/*.api`
fail the build on any binary-incompatible change to any published declaration, stable or not. What
they do **not** carry is the markers: the JVM dump format writes signature lines only and never
annotations, and the synthetic method Kotlin emits for an annotated property is filtered out as
synthetic. A declaration changing tier is invisible to it.

**`rabosh-samples` is what holds the stable core.** It depends on `:rabosh-api` and nothing else, it
compiles with `allWarningsAsErrors`, it is part of `./gradlew build`, and — unlike every other module
in the repository — it deliberately does **not** opt in to `@RaboshExperimental`. It is therefore a
real consumer compiling against the stable core with no opt-in. A stable declaration that silently
acquires the marker fails there, and so does a sample that reaches past the facade. That asymmetry is
load-bearing and should not be tidied away by giving every module the same build configuration.

**`checkApiTiers` holds the other direction**, which a sample cannot: that no unmarked public
signature *exposes* an experimental type. Module-wide opt-in means the compiler permits exactly that
inside the library, so a consumer could be handed a `ColumnReader` by a method carrying no marker at
all — the tier statement above quietly ceasing to be true. The audit reads the marker set from the
sources and the surface from the committed dumps, both derived rather than listed, and runs at the
root because the leak is cross-module. It found four leaks the first time it was run by hand.

Verified by breaking them, which is this repository's standing rule for a check nobody has watched
fail: adding `db.store.flush()` to a sample fails `./gradlew build` with the opt-in error naming the
marker; commenting out the opt-in in `rabosh.kotlin-library` fails the published modules; and removing
the marker from `IndexCatalog.read` fails `checkApiTiers` naming the method, the type it exposes and
the module.

## Reporting

A stable-core declaration that changed without a deprecation cycle is a bug. Please
[open an issue](https://github.com/aoreshkov/rabosh/issues) naming the declaration and the two
releases.
