# Roaring portable-format conformance fixtures

These two files are **not written by rabosh**, and that is the entire reason they are here.
`RoaringPortable` claims to read and write the RoaringBitmap portable serialization format; a test
built from this project's own reading of the specification could only ever confirm that the reader and
the writer agree with each other. Bytes produced by another implementation are the only thing that can
say otherwise.

They are the cross-implementation conformance fixtures that CRoaring, the Java library and the Go port
all test against — the same two files, unmodified.

| File | Bytes | SHA-256 |
|---|---|---|
| `bitmapwithoutruns.bin` | 72 616 | `d719ae2e0150a362ef7cf51c361527585891f01460b1a92bcfb6a7257282a442` |
| `bitmapwithruns.bin` | 48 056 | `1f1909bfdd354fa2f0694fe88b8076833ca5383ad9fc3f68f2709c84a2ab70e3` |

## Provenance

- Upstream: <https://github.com/RoaringBitmap/RoaringFormatSpec>, path `testdata/`
- Commit: `f7192321e1ef2cfc5d2e1a59749f4ec0201f9659` (2016-12-14), the last to touch that directory
- Licence: Apache License 2.0, the same licence this repository is under

Retrieved verbatim; neither file has been edited, truncated or re-serialized. They are test resources
and not a dependency of anything: no rabosh artefact contains them, and the engine still has zero
runtime dependencies.

## What they hold

Both encode the same set of 200 100 values, which the specification's own test case builds as:

```java
for (int k = 0;      k < 100_000; k += 1000) rb.add(k);
for (int k = 100_000; k < 200_000; ++k)      rb.add(3 * k);
for (int k = 700_000; k < 800_000; ++k)      rb.add(k);
```

`RoaringConformanceTest` builds that set from the recipe rather than remembering a cardinality, so a
fixture that changed would fail against the recipe instead of quietly redefining what is expected.

The pair is deliberate, because the two files exercise different halves of the format:

- **`bitmapwithoutruns.bin`** is what RoaringBitmap writes *without* `runOptimize()`: the
  `SERIAL_COOKIE_NO_RUNCONTAINER` header, no run bitmap, and eleven array and bitset containers.
  `RoaringPortable.encode` never produces this form — it always writes the smallest encoding — so this
  file is an **import** fixture, and it is the only thing in the repository that covers that cookie.
- **`bitmapwithruns.bin`** is the same set after `runOptimize()`: the `SERIAL_COOKIE` header with the
  container count in its high half, a two-byte run bitmap marking the last three containers, and an
  offset header (eleven containers is above the format's threshold of four). This one is an **export**
  fixture as well: encoding the recipe set must reproduce it byte for byte.
