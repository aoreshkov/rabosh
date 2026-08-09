# JSONPath Compliance Test Suite

These files are **not written by rabosh**, and that is the entire reason they are here.
`rabosh-jsonpath` claims to implement RFC 9535. A test built from this project's own reading of the
specification could only confirm that the parser and the evaluator agree with each other — a
comparison table and an escaping table are exactly the kind of thing two implementations can each be
self-consistently wrong about. Bytes produced by somebody else are the only thing that can say
otherwise.

They come from the JSONPath Compliance Test Suite, the corpus the JSONPath implementations test
against — the same files, unmodified.

| File | Bytes | SHA-256 |
|---|---|---|
| `basic.json` | 16 079 | `d65f5a961f8d75658164d02a2ba72b8fa653199487e123af6bdb759c4d229448` |
| `filter.json` | 65 641 | `162fb135e2a4165113a41779fe1f5b65f1b3062bfa3aa43a5cd92f73ea37d41e` |
| `index_selector.json` | 4 000 | `d432778090551f7e80c97fcc74b657aecda763482182f8ee4bd2545e1b3ba93e` |
| `name_selector.json` | 25 440 | `9a1cf2ca428dab213460c91342d29198ea4719d806f9fb1a7e24c1538b503dc9` |
| `slice_selector.json` | 20 591 | `332c1744df285352c69e6091b37bfeb1403f2dd1b1b5287ca08e4bda66b5049f` |
| `functions/count.json` | 3 247 | `609116867165c21bebc08acf0279d934a3b0f160ad5c0b1aa53fd8cb568b1d40` |
| `functions/length.json` | 4 748 | `3e231657fbf2c016b23f2c4044f689ca0fbe141a6fb1ac5da237fec95a38978d` |
| `functions/match.json` | 7 936 | `b98be7545b491f70dc3ad2efb64040f83335d43a2c10e7169165ed384408afc6` |
| `functions/search.json` | 8 520 | `540717d642750f5827326ed5b613d2b7ddc223e54fcdfc868ab049e5d8ad7512` |
| `functions/value.json` | 1 385 | `c671f9de4a6a1521715a94d01dca9e26605e64502080d5b1fe827d354643e8fe` |
| `whitespace/filter.json` | 7 008 | `5c357008273fb43e8f95eb01116281c2821b29a4e429282c29b249a679b0057e` |
| `whitespace/functions.json` | 11 094 | `781173e76b4c05386eaba956308f1a67597f7e2428a7b046752f262784326470` |
| `whitespace/operators.json` | 32 041 | `06781f85417d2b3c706872bc2bde727bc1adcc9be9026d42024ab5d46f433216` |
| `whitespace/selectors.json` | 9 635 | `55dc306181c2f8c1bb914eb88d3cb8ce3b6bc7d498d33e8942bc86b4c5a92820` |
| `whitespace/slice.json` | 5 760 | `29ceddbf9ee0e5b30519dfebcbe4a00a4f8ce2969705c898a62ce8c91f16d6dc` |
| `LICENSE` | 1 478 | `0a76d5e15eeff92346a8783de64d5164c4d527a163f8599733e4e0ab941b59c0` |

## Provenance

- Upstream: <https://github.com/jsonpath-standard/jsonpath-compliance-test-suite>, path `tests/`
- Commit: `7be7c1fc28057c91e8eefaf197060fba7ed43acd` (2026-05-21), the tip when they were taken
- Licence: **BSD-2-Clause**, which is *not* this repository's licence

That last line is why `LICENSE` is committed beside them rather than mentioned. rabosh is Apache-2.0;
these files are not, and BSD-2-Clause's condition is that the notice travels with the bytes. It is
committed verbatim for the same reason the fixtures are.

Retrieved verbatim; nothing has been edited, reordered, filtered or reformatted. They are test
resources and not a dependency of anything: no rabosh artefact contains them, no build configuration
references them, and the engine still has zero runtime dependencies.

`.gitattributes` marks this directory `-text`, so no checkout normalises a line ending inside it —
`LICENSE` arrives with CRLF, and rewriting it would make the file shorter than the hash above.

The suite's own README recommends embedding it as a git submodule. This repository takes the other
option, for the reason recorded beside the Roaring fixtures in
`rabosh-index/src/test/resources/roaring/README.md`: what a test asserts must not be whatever a
submodule pointer happens to be at.

## Why two of these files also live under `rabosh-variant`

`name_selector.json` and `index_selector.json` are committed **twice**, byte for byte, here and in
`rabosh-variant/src/test/resources/jsonpath/`. That is deliberate. `rabosh-variant` implements
RFC 9535 §2.7 — the Normalized Path — and pins it against those two files; its test source set must
not acquire a dependency on a module above it, and 29 KB of duplicated test resources is cheaper than
a test-fixture edge between two modules. The hashes are identical and both READMEs record the same
commit, so a fixture updated in one place and not the other is a visible difference rather than a
silent one.

## What is asserted

`JsonPathComplianceTest` asserts the shape of the corpus **before any case runs** — 703 cases, 247 of
them invalid selectors, 667 Normalized Paths across 57 distinct spellings, 56 cases tagged for a
regular expression. A suite that quietly lost a fixture would otherwise pass over a smaller corpus,
and every other count in that file is derived from these four rather than remembered separately.

Then, over **all 703** cases — nothing is excluded:

1. **Every valid selector selects exactly the nodes the suite pairs with it** — the values against
   `result`, and the locations against `result_paths`, as `toNormalizedPath()` compared character for
   character and in order. The 9 cases whose answer depends on object member ordering carry
   `results`/`results_paths` and are matched against any one of the permitted nodelists, because
   RFC 9535 leaves that order to the implementation and this engine presents members in name order.
2. **Every invalid selector is rejected, with a position in the message.**
3. **Every `result_paths` entry round-trips character for character** through
   `VariantPath.parseNormalized` and `toNormalizedPath`, and selects the value it is paired with.

## The 56 that used to be excluded, and why the number is still asserted

`match` and `search` are defined over RFC 9485 I-Regexp. Until the matcher landed this build had none
— a filter runs once per document over a corpus and `java.util.regex` backtracks, so it had to be a
linear-time one, which was its own piece of work — and the 56 cases tagged `match` or `search` were
excluded **by tag**, counted, and each asserted to be *refused* rather than quietly answered.

They now run, and 50 of them (the other 6 are invalid selectors) are asserted to **compile** in a test
of their own, on top of being checked value-for-value by the general one. That test is the mirror of
the exclusion it replaced and is kept separate for the same reason the exclusion was counted: 56 is
the number that says which feature is being claimed, and folding it into the general assertions would
let a regression re-open the hole with every remaining count still passing.

A conformance suite that silently skips is a defect this repository names in two other places; an
exclusion that is not counted is the same defect with a comment on it. The number is 703, so the
README's claim says "RFC 9535" with nothing after it.
