# Compatibility

This document is rabosh's declared public API for its **on-disk format**. Semantic Versioning
requires that a project declare a public API, and permits that declaration to live in documentation
rather than in code; this is that declaration, and it is deliberately separate from the Kotlin API.

**The two move at different speeds, and the split is the point.**

| | Guarantee |
|---|---|
| **On-disk format** | Stable. Governed by this document. |
| **Kotlin API** | Not stable. Major version zero: any signature may change in any release. |

A store is data somebody owns; an API is a call somebody can rewrite. Freezing the first and not the
second says exactly what the evidence supports, and no more.

## What is covered

Ten independently versioned encodings. Eight carry an eight-byte magic, legible in a hex dump; two
are embedded in a larger file and carry a version without one.

| Encoding | File | Magic | Version |
|---|---|---|---|
| Write-ahead log | `%010d.wal` | `JKDB-WAL` | 1 |
| Segment | `%010d.seg` | `JKDB-SEG` | 1 |
| Manifest | `MANIFEST-%010d` | `JKDB-MAN` | 1 |
| Sketch sidecar | `%010d.cat` | `JKDB-CAT` | 1 |
| Index registry | `INDEXES` | `JKDB-IXR` | 1 |
| Base index sidecar | `%010d.idx` | `JKDB-IDX` | 2, and 1 is still read |
| Posting file | `%010d.%04d.pst` | `JKDB-PST` | 2, and 1 is still read |
| Shredded column | `%010d.%04d.col` | `JKDB-COL` | 1 |
| Bitmap block | *embedded* | — | 1 |
| Variant metadata | *embedded* | — | 1 |

`CURRENT` and `LOCK` carry no version. `CURRENT` holds one manifest name and nothing else; `LOCK` holds
nothing at all.

The magics are spelled `JKDB-` because the project was called `jsonkdb` when the format was written.
A magic is a discriminator saying which kind of file this is, never branding, so the prefix is
retained rather than corrected — `PK` outlived PKZIP, and a Java class file still opens `CAFEBABE`.

## What is guaranteed

None of these is aspirational. Every row has a test that fails when the promise breaks.

| Promise | How it is held to it |
|---|---|
| **A store written by an earlier release opens on every later one.** Its sidecars are *read*, not silently rebuilt — an index answers from the bytes on disk, at the speed it was built for. | Five complete stores committed as bytes and opened by the current code with rebuilding disabled, so a sidecar that would not decode cannot be repaired into a pass. Three of them carry index layouts this build can no longer *write*, and the newest was written by a tagged release rather than by a development build. |
| **A file from a newer release is reported, never guessed at.** An unknown format version raises `UnsupportedFormatException`, `UnsupportedIndexFormatException`, `UnsupportedSketchFormatException` or `UnsupportedBitmapFormatException` — separately from corruption, so nobody goes looking for a failing disk. | Each format has a test that writes a version one higher than this build knows and asserts the failure is *unsupported* rather than *damaged*. |
| **An unknown identifier is never read as a default.** An unrecognised block type, posting encoding, column type, index kind or sketch type id means "this file needs a newer build", not "the value is absent". | Exhaustive `when`s with no `else`, so adding a value forces a decision at compile time, plus assertions pinning every existing number against renumbering. |
| **Identifiers are permanent; layouts may take a version.** A type id, section kind or encoding byte is never renumbered — only added. A *layout* may be replaced, and when it is, the version that replaced it keeps reading its predecessor. | Two version bumps taken so far, both still reading the version below them, with committed stores in the older layouts to prove it. |
| **Every format version ever released stays readable.** A version leaves the supported window only in a major release. | The golden stores are retained under a rule that forbids retiring one while it is the only cover for a decode path. |

## What is not guaranteed

Stated as loudly as the promises, because a compatibility document is read for both.

- **Forward compatibility.** An older build cannot read a newer store, and does not pretend to. It
  fails on the first file it does not understand, naming the file, the version it found and the
  version it reads. This is a designed behaviour rather than a gap: the alternative is a reader that
  guesses at bytes it has never seen.
- **`RoaringPortable`.** The portable Roaring bitmap format is an *exchange* format belonging to a
  specification this project does not own. No sidecar reads it and no on-disk identifier is spent on
  it. If the specification moves, `RoaringPortable` follows it — this document does not cover it.
- **Byte identity between builds.** That a sidecar rebuilt by a backfill is byte-identical to one
  written by a flush is an internal property the test suites rest on, not a promise to a caller. Two
  releases may write different bytes for the same documents; both will be readable by every later
  release, which is the guarantee that matters.
- **Performance characteristics.** A later release may change block sizes, encodings or index
  layouts. The old files stay readable; how fast they are read is not part of this contract.

## One behaviour worth stating plainly

`DamagedIndexPolicy.REBUILD` and `DamagedSketchPolicy.REBUILD` do not distinguish a *damaged* sidecar
from one written by a **newer** build. Under either policy, an older build meeting a newer sidecar
deletes it and rebuilds it in the format that build writes, recording the failure in
`IndexCatalog.problems` or `SchemaCatalog.problems`.

This is intended. Sidecars are derived data — losing one costs a rescan and never a document — so
repairing by rebuilding is the right default for a damaged file, and a newer file is repaired the same
way. But it means **a store opened alternately by two builds will have its sidecars rewritten
downward**, silently apart from the entry in `problems`.

If that matters, use the default `REPORT` policy, under which both cases are raised rather than
repaired. Documents are never affected either way: the log, the segments and the manifest are not
derived, and no policy deletes them.

## How the format changes from here

An identifier is **added, never renumbered**. A new block type, posting encoding, column type or
section kind is a new number on a format that already exists, and an older build meets it as
"unsupported" rather than misreading it.

A **layout** may be replaced, and that takes a version bump. When it does, the reader for the previous
version stays — that is what "every version ever released stays readable" costs, and it is paid rather
than promised: the base sidecar and the posting file each read two layouts today, and the older layout
of each is exercised by committed stores rather than by a comment.

The two are not interchangeable. An identifier is read by a build that never heard of the value that
replaced it and has nothing in the file to warn it; a version is the one number a reader checks
*before* it believes anything else. Reaching for a version bump where an identifier would do is how a
format acquires versions nobody can ever drop.

## Reporting a compatibility failure

A store that will not open on a later release is a bug in that release, not in the store. Please
[open an issue](https://github.com/aoreshkov/rabosh/issues) with the exception message, which names
the file, the version found and the version expected.
