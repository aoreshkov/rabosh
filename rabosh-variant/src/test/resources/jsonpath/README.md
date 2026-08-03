# JSONPath compliance-suite fixtures for RFC 9535 §2.7

These files are **not written by rabosh**, and that is the entire reason they are here.
`VariantPath.toNormalizedPath` claims to render RFC 9535 §2.7's Normalized Path form and
`VariantPath.parseNormalized` claims to read it. A test built from this project's own reading of the
specification could only ever confirm that the renderer and the parser agree with each other; the
escaping table is exactly the kind of thing two implementations can each be self-consistently wrong
about. Bytes produced by somebody else are the only thing that can say otherwise.

They come from the JSONPath Compliance Test Suite, the corpus the JSONPath implementations test
against — the same files, unmodified.

| File | Bytes | SHA-256 |
|---|---|---|
| `name_selector.json` | 25 440 | `9a1cf2ca428dab213460c91342d29198ea4719d806f9fb1a7e24c1538b503dc9` |
| `index_selector.json` | 4 000 | `d432778090551f7e80c97fcc74b657aecda763482182f8ee4bd2545e1b3ba93e` |
| `LICENSE` | 1 478 | `0a76d5e15eeff92346a8783de64d5164c4d527a163f8599733e4e0ab941b59c0` |

## Provenance

- Upstream: <https://github.com/jsonpath-standard/jsonpath-compliance-test-suite>, path `tests/`
- Commit: `7be7c1fc28057c91e8eefaf197060fba7ed43acd` (2026-05-21), the tip when they were taken
- Licence: **BSD-2-Clause**, which is *not* this repository's licence

That last line is why `LICENSE` is committed beside them rather than mentioned. rabosh is Apache-2.0;
these three files are not, and BSD-2-Clause's condition is that the notice travels with the bytes. It
is committed verbatim for the same reason the fixtures are.

Retrieved verbatim; nothing has been edited, reordered, filtered or reformatted. They are test
resources and not a dependency of anything: no rabosh artefact contains them, no build configuration
references them, and the engine still has zero runtime dependencies.

The suite's own README recommends embedding it as a git submodule. This repository takes the other
option, for the reason recorded beside the Roaring fixtures in
`rabosh-index/src/test/resources/roaring/README.md`: what a test asserts must not be whatever a
submodule pointer happens to be at.

## What is asserted, and what is not

Each case carries a `selector`, a `document`, a `result` and — the field this is here for —
`result_paths`, the Normalized Paths of the nodes the selector matched. `JsonPathCtsTest` asserts two
things per case and nothing else:

1. **Every `result_paths` entry round-trips character for character.**
   `parseNormalized(p).toNormalizedPath()` must equal `p`. It is the strongest form the claim can
   take: `p` was written by another implementation, so the assertion is about agreement rather than
   about self-consistency, and it is an equality on *characters* rather than on parsed paths, which
   is what pins the escaping table rather than merely the grammar.
2. **Every `result_paths` entry selects the value the suite says it does.** `Variant.select` on the
   parsed path must yield `result[i]`. That is the node contract — a location and a value travelling
   together — pinned against a pairing this repository did not choose.

**The selectors are deliberately not asserted.** Both files hold selector cases, including invalid
ones, and this engine implements §2.7 and nothing else: `$..[?@.a == 1]` is a feature phase 20
declined to build, and asserting against fixtures for it would claim a compliance the README is
careful not to claim. The cases are present because the file is verbatim, not because they are used.

## What they hold, and why both files

Forty Normalized Paths between them, twenty-one of them distinct, covering what the escaping table
can get wrong:

- **`name_selector.json`** — all seven of §2.7's named escapes (`\'`, `\\`, `\b`, `\f`, `\n`, `\r`,
  `\t`), each as its own case; a double quote and a solidus written **raw**, which is the pair most
  likely to be over-escaped by an implementation that reused a JSON string writer; the empty member
  name; `U+D7FF` and `U+E000` either side of the surrogate block, which is where
  `normal-unescaped`'s range is defined; and two astral code points as surrogate pairs.
- **`index_selector.json`** — the index selector, which is the other half of `normal-selector` and is
  in neither of the two forms above. Small, and the only committed cover for an index step in the
  normalized grammar.

One gap is worth recording, because it decides where the rest of the coverage lives: **no
`result_paths` entry uses the `\u00xx` form.** A raw control character in a *selector* is invalid, so
every case that would have produced one is an `invalid_selector` case with no result at all. The
control-escape table — which C0 characters take a named escape, which take `\u00xx`, and that the hex
digits are lowercase — is therefore pinned in `NormalizedPathTest` against the RFC's own ABNF, and
these files cannot cover it. Do not read the round-trip passing here as covering that.
