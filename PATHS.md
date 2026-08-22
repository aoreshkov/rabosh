# Paths

rabosh has **four path grammars**. This document is the one place they are compared, because the
divergences between them are the kind that fail silently: an expression that both a filter and an
extraction accept, and that means a different thing to each.

Its siblings cover the engine's other contracts — [COMPATIBILITY.md](COMPATIBILITY.md) for the on-disk
format, [STABILITY.md](STABILITY.md) for what the Kotlin API promises, [INTEGRATION.md](INTEGRATION.md)
for the runtime. This one covers what you type.

**Read it before you write a path in two places.** Writing one is fine; the traps here are all about
the same string being handed to two readers.

## Three questions, three types

The grammars are not variants of one language that drifted. They answer different questions, and the
type is the question:

| you are asking | the type | example | who evaluates it |
|---|---|---|---|
| **which documents** have a value here | `CatalogPath` | `$.items[*].sku`, `$..["@type"]` | the query planner, an index, a scan |
| **where in this document** a value is | `VariantPath` | `$.items[0].sku` | `Variant.select`, a projection, `VariantNode.location` |
| **which parts of this document** satisfy a condition | `JsonPathQuery` | `$.items[?@.qty > 5]` | `rabosh-jsonpath`, over a document you already hold |

A `CatalogPath` names a **set** of locations and has no indices — `$.items[*]` is one path whatever
the array length is, which is what makes an index over it possible. A `VariantPath` names **one**
location and has no wildcards, which is what makes a projection possible. Neither is convertible into
the other, and the split is deliberate: a filter that could say `[0]` would need an index per
position, and a projection that could say `[*]` would have to return a column of unknown width in a
row of fixed shape.

`JsonPathQuery` is the third and sits **beside** the engine rather than in it. Nothing in the storage
chain depends on it, so RFC 9535's comparison semantics can never decide a query. See the README's
*Where the walk needs a condition rather than a path*.

## The readers

Six entry points over those three types. What each accepts, verified by `PathGrammarTest` rather than
by this table being written carefully:

| entry point | `$.a` | `$["a"]` | `$['a']` | `\n` inside quotes | `[0]` | `[*]` | `[:]` | `..a` | `$..` | `[?…]` |
|---|---|---|---|---|---|---|---|---|---|---|
| `VariantPath.parse` | ✅ | ✅ | ❌ | **literal `n`** | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ |
| `VariantPath.parseNormalized` | ❌ | ❌ | ✅ | newline | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ |
| `VariantPath.parseJsonPathOrNull` | ✅ | ✅ | ✅ | newline | ✅ | `null` | `null` | `null` | `null` | `null` |
| `CatalogPath.parse` | ✅ | ✅ | ❌ | **literal `n`** | ❌ | ✅ | ❌ | ✅ | ✅ | ❌ |
| `CatalogPath.parseJsonPath` | ✅ | ✅ | ✅ | newline | refused | ✅ | ✅ | ✅ | ❌ | refused |
| `JsonPathQuery.compile` | ✅ | ✅ | ✅ | newline | ✅ | ✅ | ✅ | ✅ | ❌ | ✅ |

❌ is `IllegalArgumentException`; *refused* is `PathNotRepresentableException`, which is different and
is [why](#three-ways-a-path-can-be-rejected-and-only-one-is-your-typo). `null` is an answer rather
than a failure — see [Is this expression one location?](#is-this-expression-one-location)

Three cells need a footnote. `parseNormalized` reads RFC 9535 §2.7, which has **exactly one spelling
per name** — that is the whole point of a normalized path, since it makes two of them comparable as
text — so its form of `$.a[0]` is `$['a'][0]` and the shorthand is not a matter of taste to it.
`CatalogPath.parseJsonPath` accepts `.*` as well as `[*]`, both meaning `AnyElement`, which is the
leniency the trap below is about. And the **bare `$..`** — every node including the root — is the one
expression the engine's grammar holds and RFC 9535's does not: a descendant segment there must carry
a selector, and the nearest query, `$..*`, is every node *below* the root rather than every node. It
is a catalog path and not a JSONPath query, both directions, which is why `toJsonPath` refuses to
render one rather than approximating it.

And what each writer emits:

| writer | `$.items[*].sku` | a name needing quotes |
|---|---|---|
| `CatalogPath.toString` | `$.items[*].sku` | `$["@type"]` |
| `CatalogPath.toJsonPath` | `$['items'][:]['sku']` | `$['@type']` |
| `VariantPath.toString` | *(no wildcards)* `$.items[0].sku` | `$["@type"]` |
| `VariantPath.toNormalizedPath` | *(no wildcards)* `$['items'][0]['sku']` | `$['@type']` |

The first row of each pair is **the engine's own spelling** — what `parse` reads back, what a log
line shows, and, for a `CatalogPath`, the exact bytes an index registry and a sketch sidecar store.
The second is **the interchange spelling**, which is what you hand to something outside the engine.
They are not interchangeable, and the next three sections are why.

## The trap: `[*]` means two different things

This is the one that costs you documents rather than an exception.

```
$.items[*].sku
```

- To a **`CatalogPath`**, `[*]` selects **array elements**.
- To **RFC 9535**, `*` selects **every child** — of an object as well as an array.

The same six characters, two meanings, no diagnostic. They agree on every array and diverge on every
object:

```
{"items": {"a": {"sku": "x"}}}          items is an object, not an array

CatalogPath.parse("$.items[*].sku")     matches nothing — no array, no elements
JsonPathQuery.compile("$.items[*].sku") selects "x"    — a is a child of items
```

On a protobuf-JSON corpus, where a repeated field is an array and a map field is an object, both
shapes are routine. `NodeWalkDifferentialTest` asserts the divergence rather than merely noting it.

**The RFC 9535 selector that means what `AnyElement` means is the slice `[:]`.** §2.3.4.2.2 is
explicit that a slice *"selects no nodes from a node that is not an array"*. So `CatalogPath.toJsonPath`
emits `[:]` and never `[*]`, and `CatalogPath.parseJsonPath` **accepts either** — lenient on the way
in because `[*]` is what people type, strict on the way out because a rendering that is wrong over
objects is worse than one that is unfamiliar.

If you are writing one expression for both a filter and an extraction, and it walks an array, write
`[:]`. Both readers take it and they agree about what it means.

## The trap: a backslash means opposite things

Inside the **engine's** grammar a backslash escapes the next character *literally*:

```
$["a\nb"]      CatalogPath.parse / VariantPath.parse  →  the 3-character name  anb
$["a\nb"]      RFC 9535                               →  the 3-character name  a⏎b
```

Two spellings that look interchangeable name **different fields**. The engine's rule exists so that
`toString` has exactly one escape to define and `parse` exactly one to undo; the RFC's exists because
a JSON string literal is what it is.

There is no way to widen either reader out of this. Making `CatalogPath.parse` accept single quotes
was considered and refused for precisely this reason: it would put two escaping rules behind one
quote-agnostic reader, so `$["a\nb"]` and `$['a\nb']` would name different fields **in the same
grammar**. The divergence stays at a named boundary — `parseJsonPath` — instead of moving inside a
parser.

**In practice:** if your field names contain no backslashes, this cannot reach you. If they do, pick
one grammar per string and never let a string cross.

## The trap: quote style is a portability problem

`CatalogPath.parse` and `VariantPath.parse` accept `"` only. RFC 9535 accepts both.

That is not only an aesthetic difference. A protobuf-JSON corpus needs the bracket form on every
message — `$["@type"]`, since `$.@type` does not parse — and on Windows a shell consumes an
argument's inner double quotes before they reach the JVM unless the caller escapes them. So the
portable spelling of a name selector is the single-quoted one, which is exactly the one the engine's
own readers reject.

`CatalogPath.parseJsonPath` and `VariantPath.parseJsonPathOrNull` accept both quotings. Reach for
them for anything that came from a command line, a configuration file, or a user.

## One expression, both a filter and an extraction

The recipe this document exists for. **Write it in RFC 9535 and read it with the two boundary
readers**, never with `parse`:

```kotlin
val expression = """$['response']['body']['@type']"""     // from a CLI, a config file, a user

// which documents have a value there
val shape = CatalogPath.parseJsonPath(expression)         // $.response.body["@type"]
db.query(Query.where(path(shape) eq "TypeA"))

// where it is in a document you are holding
val location = VariantPath.parseJsonPathOrNull(expression)  // $.response.body["@type"]
```

Both take single quotes, both take double quotes, both take the `.name` shorthand, both apply
RFC 9535's escapes. One string, two questions, one meaning — as long as it stays inside the
sub-language both can represent, which is: name selectors, and (for `CatalogPath`) `[*]` or `[:]`.

## Is this expression one location?

`VariantPath.parseJsonPathOrNull` answers *does this name exactly one location*, returning `null`
when it does not. A wildcard, a slice, a descendant, a filter and a negative index each name
something other than one location — and so does a typo. Both get `null`, because the caller's
response to both is the same.

**Do not hand-roll this as a string comparison.** The check people reach for is:

```kotlin
val path = VariantPath.parse(expression)
if (path.toString() != expression) return null              // ✗ fails closed, silently
```

It is a stringly-typed test of a semantic property, and it rejects perfectly good expressions:
`$["response"]["body"]` round-trips to `$.response.body`, misses the equality, and costs you column
projection. The query still answers correctly and just reads whole documents instead of columns —
observable only through `Explain.projectsFromColumns` — which is to say, only if you already suspect
it.

Nor should you wrap the throwing reader:

```kotlin
runCatching { VariantPath.parse(expression) }.getOrNull()   // ✗ catches Throwable
```

`runCatching` catches `Throwable`, so it swallows `CancellationException` — turning a cancelled
coroutine into a silent `null` — along with `StackOverflowError`. That is why the nullable entry
point exists: an expected failure should be a value, not a caught error.

## Three ways a path can be rejected, and only one is your typo

| what happened | what you get | what to do |
|---|---|---|
| malformed — a stray bracket, an unterminated quote | `IllegalArgumentException`, naming the position | fix the expression |
| well-formed, but this grammar has no step for it | `PathNotRepresentableException`, carrying a `PathConstruct` | ask the other type — or answer this one per document with `JsonPathQuery` |
| well-formed, but too expensive to evaluate | `JsonPathLimitExceededException`, carrying a `JsonPathLimit` | raise the limit, or refuse the input |

The middle row is the one worth wiring up. `$.items[0]` handed to `CatalogPath.parseJsonPath` is not
a typo — it is a question a *shape* cannot ask, and the answer is to ask a `VariantPath` instead.
`PathConstruct` says which construct it was: `INDEX`, `SLICE`, `FILTER`, `MULTIPLE_SELECTORS` — and
`DESCENDANT`, which is **no longer raised**. It was, until `..` became a step; the entry stays because
removing a value from an enum a caller may `when` over breaks a build and buys nothing. Separating
them by message is not separating them.

`PathNotRepresentableException` is a subclass of `IllegalArgumentException`, so an existing
`catch (IllegalArgumentException)` around a path parse keeps working unchanged; catch the narrower
type first if you want the distinction.

## What a filter cannot say, and which of those will change

| construct | in a filter | why |
|---|---|---|
| `[0]`, `[-1]`, `[1:5]` | never | a `CatalogPath` collapses positions on purpose — that is what makes one index serve an array of any length |
| `[?…]` filter selectors | **never** | refused on *meaning*, not on cost — see below |
| `..` descendant | **yes** | a step of its own — an ordinary filter and an ordinary index; see below |
| `[*]` over an object | never | `[*]` is array elements here; there is no step for "every member" |

### The descendant, and what it costs

`..` is a step of `CatalogPath`, so it needs no special form: an ordinary predicate leaf, an ordinary
inverted index, an ordinary posting file.

```kotlin
db.createIndex(IndexDefinition.inverted("""$..["@type"]"""))
db.query(Query.where(path("""$..["@type"]""") eq "type.googleapis.com/CityDTO"))

// correlated to one node rather than to the document:
Query.where(
    elemMatch(
        path("$.."),
        and(path("""$["@type"]""") eq "type.googleapis.com/CityDTO", path("$.name") eq "Sofia"),
    ),
)
```

It means RFC 9535's descendant segment exactly — **zero levels counts**, so `$..a` matches the root's
own `a` as well as one twenty levels down — and it exists because the alternative does not work.
Enumerating the shapes instead was measured on a real corpus and refused: 72% of tagged elements
belonged to a type occupying more than one shape, one type occupied 49 of them, and four months of
drift added 29 new ones. A shape missing from that list is a **document missing from a result**, with
nothing to report it.

Two costs, and both are charged only to a store that spells one:

- **The walk stops pruning.** Every other path narrows on the way down, so a subtree no index reaches
  is never walked; a descendant candidate never narrows away, and flush and compaction walk each
  document whole for that index.
- **Nothing recommends one.** `db.schema()` describes what documents *are*, in shapes, and no model
  over any corpus emits a `..` — `indexCandidates` will offer `$.payload.rewards[*]["@type"]`. Asking
  the question shape-agnostically is a decision you make, not one the model makes for you.

`$..` on its own is every node in the document, the root included, which is the shape an `elemMatch`
over a subtree of unknown depth needs. Two `..` in a row are refused: the second selects nothing the
first does not, and there would be no expression for it to round-trip through.

**Filter selectors are permanently refused, and the reason is not effort.** RFC 9535's `!` is the
complement of the *candidate node*; `Predicate.Not` is the complement of the *document*. Under the
two readings `where(f)` and `where(not(f))` can return the same document, so the two negations cannot
be implemented in terms of each other. Both are right for their own question. Anything proposing that
the query language grow filter expressions is proposing this, and it is closed.

Where you need one, `JsonPathQuery` is the answer, applied per document to rows the engine already
narrowed. That is the pattern in the README, and it is deliberately a second pass rather than a
richer filter.

## Stability

Every entry point named here is in the **stable core** and moves only under a deprecation cycle:
`CatalogPath` (including `toJsonPath` and `parseJsonPath`), `VariantPath` (including
`parseJsonPathOrNull` and `toNormalizedPath`), `PathNotRepresentableException` and `PathConstruct`,
and `rabosh-jsonpath`'s `JsonPathQuery` and its limits. The list and its terms are in
[STABILITY.md](STABILITY.md).

None of this is on-disk format. A `CatalogPath` is persisted as `toString` in an index registry, and
that spelling is covered by [COMPATIBILITY.md](COMPATIBILITY.md) — which is the reason `parse` cannot
be widened even where it would be convenient.
