---
description: What on disk may never change — the JKDB- magics, permanent type and section ids, when a version bump is allowed instead, and how unknown data must be reported rather than defaulted.
paths:
  - "rabosh-core/**"
  - "rabosh-index/**"
  - "rabosh-catalog/**"
  - "rabosh-variant/**"
  - "COMPATIBILITY.md"
---

Cross-cutting rules are indexed in the root `CLAUDE.md`; testing conventions, including the golden
stores that hold this file's claims, in `.claude/rules/testing.md`. The `.pst`/`.idx` layouts these
ids live inside are in `.claude/rules/index-sidecar-format.md`.

- **The magics are spelled `JKDB-`, and that is not a typo to correct.** The project was called
  `jsonkdb` when the format was written, and the prefix is a *retained historical* one: a magic is a
  discriminator that says which kind of file this is, never branding. Renaming it would invalidate all
  five golden stores — including the three carrying layouts this build can no longer write — and buy
  nothing a hex dump cares about. `PK` outlived PKZIP and a Java class file still opens `CAFEBABE`. A
  ninth magic uses `JKDB-` too, because one consistent stale prefix is a footnote and two prefixes are
  a defect.

- **On-disk type ids and format constants are permanent.** `VariantPrimitiveType` ids come from
  the Apache specification; the WAL magic (`JKDB-WAL`), the segment magic (`JKDB-SEG`), the
  manifest magic (`JKDB-MAN`), the sketch-sidecar magic (`JKDB-CAT`), the index-registry magic
  (`JKDB-IXR`), the base-sidecar magic (`JKDB-IDX`), the posting-file magic (`JKDB-PST`) and the
  column magic (`JKDB-COL`), their
  format versions, the
  shared record frame in `Frames`, the operation ids (`PUT` = 1, `DELETE` = 2), the segment footer
  layout and block entry layout, the key tag's 56-bit sequence and 8-bit operation split, the
  manifest edit tags (1..5), the sidecar's bound tags and `SketchFormat.typeId` ids, `BitmapFormat`'s
  version and its container kind ids (`ARRAY` = 1, `BITSET` = 2, `RUN` = 3) together with its header
  and directory layout, `IndexFormat`'s section kinds (`META` = 1, `KEYS` = 2, `PRESENT` = 3,
  `COLUMN` = 4 reserved), its index kinds (`INVERTED` = 1, `SHREDDED_COLUMN` = 2,
  `COMPOSITE_TERM` = 3), its posting
  encodings (`BITMAP` = 1, `SINGLE` = 2), its `KEY_RESTART_INTERVAL` of 16, its
  `KEY_V1_ENTRY_HEADER_BYTES` of 8 and all three of its header and directory
  layouts, `ColumnFormat`'s own section kinds (`META` = 1 … `STATS` = 7, `FIDELITY` = 8) and its
  `FIDELITY_EXACT_VALUES` = bit 0, its column types
  (`INT64` = 1, `DECIMAL32` = 2, `DECIMAL64` = 3, `DECIMAL128` = 4 reserved, `BOOLEAN` = 5,
  `STRING` = 6, `DOUBLE` = 7 reserved), its statistics encodings (`TYPED` = 1, `PREFIX` = 2), its
  bound tags and its `COLUMN_BLOCK_SHIFT` of 13,
  and the hash functions behind the bloom filter and the HyperLogLog are all
  written to disk. Never renumber — **add**. The `blockType` byte in a segment's block trailer exists
  precisely so that compression or a denser entry encoding is a new id rather than a new segment
  version; `BitmapFormat`'s `kind` byte is the same extension point for a denser container; and
  `IndexFormat`'s section-`kind` and posting-`encoding` bytes are the same again, which is what made a
  shredded column and — in phase 11 — a singleton posting list new ids rather than new file versions.

  **`INDEX_KIND_COMPOSITE_TERM = 3` is the strongest demonstration of that so far, because it
  extended a *record* and not just a meaning.** A composite index needs its declared fields in the
  registry, and the registry's per-index record had nowhere to put them. The kind byte was the answer:
  the record **continues** for kind 3 alone, and an older build never reaches those bytes because
  `indexKindOfId` answers `null` at the kind and reports the file as written by a newer build. So a
  new *field* arrived with no version bump and no section kind spent — and it
  is worth noticing why that was safe here and would not have been in the `.pst`: the registry
  validates the discriminator before it reads anything positioned after it, which is exactly what an
  extension point has to do to be one. A record whose unknown discriminator is read *after* the fields
  it governs cannot be extended this way at all.

  **It also said "and no golden store added", and that clause was retracted after 0.2.0 shipped.** The
  reasoning behind it was about *backward* compatibility and was correct as far as it went: no file an
  earlier build can write means anything different, and the four committed directories already cover
  the case an older reader takes. What it missed is that a golden store is not evidence for the build
  that wrote it. A record continuation nothing has committed is unpinned in the *forward* direction
  the moment it ships — the only cover was a round trip through its own writer — so `golden/store-v5`
  was written from the `v0.2.0` tree and holds the kind-3 record, the composite kind byte and a tuple
  dictionary. **The general rule this sharpens:** an extension that is invisible to older readers is
  exactly the kind that no existing golden store can pin, so "additive" is an argument for adding a
  directory rather than against it. Ask which build the fixture is evidence *for*, never which builds
  it leaves undisturbed.

- **A version bump is not a renumbering, and this engine has taken exactly two.** The rule above is
  about *ids*, because an id is read by a build that never heard of the value that replaced it and has
  nothing in the file to warn it. A version field is the opposite: it is the one number a reader checks
  *before* it believes anything else. So `POSTING_VERSION` is 2 and `POSTING_VERSION_FLAT` is 1,
  `BASE_VERSION` is 2 and `BASE_VERSION_FLAT` is 1, and **all four are read**, while
  `POSTING_ENCODING_SINGLE` is still 2 and `SECTION_KIND_COLUMN` is still reserved and unused. What a
  version permits is replacing a *layout*; what it still forbids is changing what any existing byte
  means.

  The test for reaching for it is written into `IndexFormat`: an encoding byte reinterprets a field, a
  version replaces a layout, and taking the second when the first would do is how a format acquires
  versions nobody can drop. Phase 17 qualified because neither half of it was expressible as an id —
  the term entry lost two fields every existing file has, and front-coding changes what the bytes
  between two entries mean. Phase 18 qualified for the first of those reasons alone: a key entry
  narrowing two `u32`s to two varints replaces a record every existing file has, and there is no byte
  in that record to gate a reinterpretation on.

  **The alternative that has to be argued down is a new section kind, and it is worse than it looks.**
  An unknown section kind is *skipped* rather than reported, so `SECTION_KIND_KEYS_V2` would appear
  additive — and an older build would then find no `KEYS` section at all and call the sidecar damaged.
  The same failure with a worse message, at the price of a permanent id, and with two key sections able
  to sit in one file, which is a second definition of the segment's key space. Skipping is safe for a
  section a reader does not *need*; do not reach for it when the reader needs the section.

  **Reading the old version is not politeness, it is forced.** `SegmentIndex.open` *throws* when a
  `.pst` or an `.idx` that exists will not decode: a sidecar may be **missing**, which reads as "not
  covered", but never unintelligible. So refusing version 1 would fail `IndexCatalog.attach` on every
  older store under the default `REPORT` policy rather than quietly rebuilding it — and would make
  `FormatCompatibilityTest`, which opens the golden stores with `backfill = false` precisely so a
  sidecar that will not decode cannot be rebuilt into a pass, unable to do its job.

  **Both bumps were taken before the format was declared, and that discount has expired.** They were
  argued for on the rule that a format change is free before a freeze and permanent after one, which
  was true and is no longer available: `COMPATIBILITY.md` declares the format, so a third bump is
  permanent from the moment it is written. What the two leave behind is a **pattern rather than a spent
  budget** — a new version whose reader keeps its predecessor is exactly the mechanism the declaration
  promises, and it has been demonstrated twice rather than asserted. So a third is allowed and is not
  cheap: its price is a third layout maintained forever, a golden store that can never be retired while
  it is the only cover for the layout below, and a `BASE_VERSIONS`-style array that only ever grows.
  Take one when a change is not expressible as an id and a measurement demands it — the test phases 17
  and 18 both passed — and not because the two above make a third look routine.

- **Three ways to break this fail *silently* rather than loudly and deserve naming.**
  `SketchFormat.typeId` and `IndexFormat.indexKindId` must never use `VariantKind.ordinal` or
  `IndexKind.ordinal` — inserting a value would change what every existing file means. Changing
  `SketchHash` or the tags on `ValueSignature` rewrites the meaning of every HyperLogLog register
  *and* every term in every posting file ever written. All are exhaustive `when`s with no `else` so
  the compiler forces a choice when a value is added, and `IndexFormatTest` pins the existing numbers,
  because a `when` cannot stop somebody changing one.

- **An unknown section kind is skipped rather than reported, and a manifest edit tag is not.** That is
  safe for the section for precisely the reason it is not safe for the tag: the section directory is
  fixed-width and carries each section's extent, so a reader can find its way past one it does not
  understand. A manifest cannot, which is why `Manifest` calls an unknown tag corruption. Do not make
  either behave like the other.

- **An optional section states its claim positively, and that is what makes it additive both ways.**
  `FIDELITY` is the first one: a `.col` written before phase 12 does not carry it, an older build
  skips it, and a reader finding none reads every flag as clear. Spelling it the other way round —
  "this column may be lossy" — would have made every column ever written silently claim a property
  nobody checked. So a flag added here must be a *capability*, never a *defect*, and absence must be
  the conservative answer.

- **`.idx` and `.col` share the framing and not the vocabulary.** `SectionDirectory` is one
  implementation, because two copies of "where does this section begin" that disagreed would make a file
  readable by one reader and damaged to the other. The section *kinds* are separate namespaces, both
  starting at 1: a section of `.idx` is a fact about a segment and a section of `.col` is a fact about
  one path within one index over one segment, the file disambiguates them exactly as it does
  `BitmapFormat`'s container kinds, and sharing would make every future column encoding burn a globally
  scarce id. `IndexFormat.SECTION_KIND_COLUMN = 4` stays reserved and unused; do not repurpose it.

  **They do not share a version field either, and phase 18 is where that stopped being theoretical.**
  `SectionDirectory.open` takes the *set* of versions its caller reads and reports back which one the
  file carries, so `.idx` offers two and `.col` offers one. Widening the framing is not the same as
  widening a file: a second entry in `.col`'s list would be a claim about bytes no build has ever
  written, and the one place that decides what a version means stays the file's own `open`.

- **Unknown data decodes to a signalled failure, not to a default.** An unrecognised type id
  means the file is unreadable by this version, not that the value is absent. A directory holding
  store files but no `CURRENT` is the same kind of thing: reported, never guessed at.
