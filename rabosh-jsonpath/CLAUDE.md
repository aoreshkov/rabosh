# rabosh-jsonpath — module conventions

The root `CLAUDE.md` indexes the cross-cutting design rules; testing conventions are in
`.claude/rules/testing.md`. Note which rules do **not** reach here: this module writes nothing to
disk, so `format-permanence.md` and `index-sidecar-format.md` have no claim on it, and it holds no
plan, so `index-and-query.md`'s planner rules do not either.

`rabosh-jsonpath` implements RFC 9535 over a `Variant` the caller is already holding: compile a
query, expand it to `VariantNode`s. It is **beside the dependency chain, not in it** — it depends on
`:rabosh-variant` and nothing else, and nothing in the chain depends on it. Since the I-Regexp
matcher landed it also implements RFC 9485, which is a second grammar in the same module and is
deliberately not a second published surface.

## The six things a change here must not break

**The module stays beside the chain.** An edge from `rabosh-core`, `rabosh-catalog`,
`rabosh-index`, `rabosh-query` or `rabosh-api` onto this module is the change that makes everything
below unsafe, and it is not a refactoring — it is a decision, and the argument against it is the next
paragraph. A *test*-only edge in the other direction is fine and this source set has one:
`:rabosh-catalog` is on the test classpath, which is what lets the node differential, the reader
differential and `PathGrammarTest` compare grammars nothing in `main` may put in one place.

**Two definitions of comparison are allowed here precisely because they cannot meet.** RFC 9535's
rules and `ColumnPredicate.matches` disagree in ways neither can be implemented in terms of the
other — a `Predicate` leaf is existential over every value at a path while `@.a` here *is* the array;
`Not` there is the complement of the *document* while `!` here is the complement of the *node*;
`Nothing == Nothing` is true here and has no counterpart there. Two comparison semantics in one
repository are a defect exactly when both can decide the same question. Nothing here decides which
documents a `Query` returns, so they never do — and a facade method delegating to this module would
be the first time they could.

**The claim is scoped by the artefact, and its size is asserted rather than described.**
`rabosh-jsonpath` implements RFC 9535; `VariantPath.parse` and `CatalogPath.parse` remain the
engine's own grammar and are still not JSONPath. The compliance gate runs **703 of 703** cases and
excludes nothing. The **56** tagged `match` or `search` are still counted, in a test that now asserts
they are *answered* — that assertion is the mirror of the one it replaced and exists for the same
reason: 56 is the number that says which feature is being claimed, so a regression could otherwise
re-open the hole with every remaining count still passing. Do not fold it into the two general tests.

**The matcher is linear and that is a security property, not a performance one.** A filter runs once
per document over a corpus, and RFC 9535 lets the pattern be a `ValueType` — so a backtracking engine
would put `(a|aa)+b` behind a *document* as well as behind a query. `IRegexpProgram` is a Thompson
construction: each instruction is visited at most once per input position, and `IRegexpTest` asserts
that in **transitions** rather than on a clock. Two bounds hold up the other half — at most 10 000
instructions and 64 levels of group nesting — and both refuse a pattern outright rather than
truncating one, which RFC 9485 §7 asks for by name. A change that makes the matcher backtrack, or
that removes either bound, is the change this paragraph exists to stop.

**A pattern that is not an I-Regexp is an *answer*, and this is where the module's strictness stops.**
`compile` rejects 247 invalid selectors with a position, and `$[?match(@.a, '[')]` is not one of them:
RFC 9535 §2.4.6 rules that a second argument which does not conform makes the *result* `LogicalFalse`.
So `IRegexp.compileOrNull` answers `null` — for a syntax error, and equally for a pattern too large to
run — and nothing surfaces the reason. A literal pattern is still compiled while the query is, so
applying a compiled query touches no grammar at all.

**The walk carries no budget that *truncates*, and three that *refuse*.** The original rule said the
walk carried no budget at all, and the reasoning behind it is unchanged and still governs: a bound
that stopped the walk and returned what it had would be a wrong answer with nothing to say so, and a
caller cannot tell a truncated nodelist from a small document. What phase 24 added is the opposite
mechanism — `JsonPathLimits` counts the work and **throws** `JsonPathLimitExceededException`, so the
caller gets no nodelist rather than a short one. Keep the two apart in any change here: a `NodeSink`
answering `false` has *learned* the answer and is declining more of it; a budget being met means the
answer is unknown. Making the budget return `false` would produce exactly the truncation the first
sentence forbids, and it would look like a simplification.

Three bounds, all counted in steps and never on a clock, for the reason the regex bound is — a
wall-clock budget makes the failure depend on the machine. `maxNodesVisited` counts node *touches*
rather than distinct nodes, because it bounds work and `$..*..*` buys the same node once per stage;
counting distinct nodes needs a set of every node visited, which is the memory the attacker wanted
you to spend. The counters live on `Evaluation`, created per call, never on the query — a counter on
the query would break the "any number of threads at once" promise silently, by having two documents
share a budget.

**The defaults are a backstop, not a policy, and the fixtures that say so must keep passing.**
`JsonPathQueryTest`'s 20 000-deep document and 5 000-wide array run under the shipped defaults
untouched, and so do all 703 compliance cases — a limit that changed a compliant answer would have
broken the module's only claim. A deployment actually serving hostile expressions sets its own, far
lower. `JsonPathLimitsTest` pins the numbers so that lowering one is a visible decision.

The attack fixture is worth understanding before it is changed: `$..*..nope` names a field the
document does not have, so its answer is the **empty nodelist** and it is by its result
indistinguishable from `$.absent` — nothing measured on the answer can see it coming, which is why
the bound has to be on the work. `$..*..*` is quadratic too and is caught by `maxNodesProduced`
first, which is correct and would have left `maxNodesVisited` untested by its own case.

The two *query* bounds are unchanged and are a different kind of thing — 1024 selectors and 64 levels
of nesting, checked while parsing, bounding what the caller wrote rather than what it costs. And the
descendant walk is still iterative over an explicit stack: a `Variant` built through `VariantBuilder`
is never re-checked against `DEFAULT_MAX_JSON_DEPTH`, so a recursive walk would be a stack overflow
reachable from data.

## Three findings worth keeping

**`^` and `$` are anchors, and RFC 9485's own ABNF says they are ordinary characters.** This is the
one place in the module where the implementation knowingly departs from a specification's text, so it
is recorded rather than left to be rediscovered as a bug. `NormalChar` admits %x5E and %x24, which
makes `^ab` a pattern for strings beginning with a caret. But §5.3 and §5.4 — the *same document's*
recipe for realising I-Regexp on ECMAScript, PCRE, RE2 and Ruby — escape neither and prescribe
wrapping the pattern in `^(?:` and `)$`, so every implementation built that way reads both as anchors.
The compliance suite pins that reading in `explicit caret` and `explicit dollar`, and **it was
verified by breaking**: with `^` read as a literal the suite fails on exactly that case. The syntax
rule and the mapping rule contradict each other, one of the two is what the corpus tests, and
interoperability is the whole purpose of I-Regexp — so the mapping wins. Inside a character class,
where the mapping has nothing to say and `^` already means negation in first position, both are
literal.

Two consequences worth stating: the category table is written out rather than derived from
`Character`'s constants for a related reason — `\p{Cs}` is a Unicode general category and is *not* an
I-Regexp one, so a generated table would have accepted it and quietly widened `\p{C}` — and
`IRegexpTest` runs a differential against `java.util.regex`, the engine this module *declined*, over
the sub-language both can spell, with `^` and `$` deliberately left out of it because Java's `$` also
matches before a final line terminator.


**`CatalogPath.toString()` is not a JSONPath query, and that is now checked.** `$.items[*]` parses
under both grammars and means different things: `AnyElement` selects array elements, RFC 9535's `*`
selects every child of an object *or* an array. The RFC 9535 selector that does mean `AnyElement` is
the slice `[:]`, which is what `CatalogPath.toJsonPath` emits and what `NodeWalkDifferentialTest`
compares two evaluators over — which is why that differential can be an *equality* rather than an
approximation. Phase 20 left this as a documentation question; it is answered, in the direction of
not claiming the rendering.

The consumer-facing half of that answer is `PATHS.md`, the repository's one comparison of the four
grammars, and **its tables are `PathGrammarTest` in this source set** rather than prose somebody has
to keep true by hand. The division to preserve: a reader's own suite owns its behaviour in depth,
that test owns only the *relationships between* readers, and the document owns neither — it renders
what the test asserts. Deepen a reader here and nothing above needs touching; change what one reader
does relative to another and all three move together.

**The errata are about PEG ordering and change nothing here.** All five against RFC 9535 — 8343,
8352, 8353, 8354 and 8779 — concern prioritised choice in the ABNF, which is a failure mode a
recursive-descent parser with explicit lookahead does not have. Record it when the list grows;
do not read a sixth erratum as evidence the grammar is unsettled without checking whether it is
another one of these.
