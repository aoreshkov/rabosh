# Integrating rabosh

The rules an embedding application has to obey. This is a **contract, not a tutorial** — the
[samples](README.md#samples) are the tutorial and the [README](README.md) is the argument. Everything
here is a rule that, if broken, either fails silently or does not fail until production.

Three of the four in "Lifetimes" are the silent ones. If you read nothing else, read those.

Related: [COMPATIBILITY.md](COMPATIBILITY.md) for the on-disk format, [STABILITY.md](STABILITY.md)
for which Kotlin declarations are allowed to move.

## The runtime

**JDK 25 or later.** Not a floor picked for tidiness: the engine maps every segment through
`FileChannel.map(mode, offset, size, Arena)` and reads it as a `MemorySegment`, so segments are
unmapped deterministically when their arena closes rather than whenever a garbage collector gets
round to a `ByteBuffer`. That API is final from JDK 22; 25 is the LTS.

**No `--enable-native-access` flag is required, by any module.** This is worth stating explicitly
because it is easy to assume otherwise from the fact that the engine uses the Foreign Function &
Memory API, and because adding the flag "to be safe" propagates into launcher scripts, Dockerfiles
and IDE run configurations that then outlive the reason for them.

The flag governs **restricted** methods — `MemorySegment::reinterpret`, `Linker::downcallHandle`,
`SymbolLookup::libraryLookup`, `System::loadLibrary` and their neighbours. rabosh calls none of them.
`FileChannel::map` is not among them: in JDK 25 it carries no `@Restricted` annotation and declares no
`IllegalCallerException`, and neither does `Arena.ofShared`, `Arena.allocate` or
`MemorySegment.ofArray`.

This is checked rather than asserted. `./gradlew :rabosh-samples:runThreeStepsOnModulePath` runs a
full write/model/index/query cycle under `--illegal-native-access=deny` with **no** grant of any kind,
on the module path, where the engine's code sits in a named module that `ALL-UNNAMED` would not cover
even if it were passed. If a future release acquires a restricted call, that task fails.

> If you are running an older JDK than 25 you may see a warning; that is JDK 22-24 behaviour and not
> a supported configuration. If you ever do need the grant — because a future release takes a
> restricted call — the spelling on the module path is `--enable-native-access=app.oreshkov.rabosh.core`,
> not `ALL-UNNAMED`.

**On the module path**, each published jar declares its own name via `Automatic-Module-Name`, so
`jlink` and `jpackage` builds resolve them stably rather than under a name derived from the filename:

| Artefact | Module |
|---|---|
| `rabosh-api` | `app.oreshkov.rabosh.api` |
| `rabosh-query` | `app.oreshkov.rabosh.query` |
| `rabosh-index` | `app.oreshkov.rabosh.index` |
| `rabosh-catalog` | `app.oreshkov.rabosh.catalog` |
| `rabosh-core` | `app.oreshkov.rabosh.core` |
| `rabosh-variant` | `app.oreshkov.rabosh.variant` |
| `rabosh-jsonpath` | `app.oreshkov.rabosh.jsonpath` |

These are automatic modules — there is no `module-info.java` — so they read every other module on the
path and export every package. `kotlin-stdlib` ships a real module descriptor, so name it explicitly
if nothing else already requires it.

## One process, one writer

**One process may have the directory open, and within it one `Rabosh` (or one `DocumentStore`).** The
lock file is what makes that a guarantee rather than a convention: two writers over one LSM directory
do not produce a merge conflict, they produce two interleaved logs and an unrecoverable sequence
space.

A second attempt raises `StoreLockedException`, distinguishably in each direction — `… is already
open in this process` when it is your own code, `… is locked by another process` when it is not. Held
by `DocumentStoreTest`'s *a second store cannot open the same directory*.

**For a desktop or CLI application this is the normal second-launch case, not a fault.** Catch
`StoreLockedException` specifically — it is a distinct subtype of the sealed `StoreException` — and
focus the existing window, or exit with a message. Do not catch `Exception` and treat it as
corruption, and do not delete the lock file: it is a real advisory lock held by an open channel, and
removing it produces the two-writer state it exists to prevent. There is no force-open and there will
not be one.

**Within the process, one writing thread.** Any number of threads may read concurrently — snapshots,
scans and queries are all safe — and `Rabosh` guards the cached planner statistics behind `query`
itself. What is not safe is two threads calling `put`, `delete` or `write` at once; that is
contention rather than an exception, so it fails as corruption of your own ordering rather than
loudly. Use a single writer thread, or your own lock around the writes.

## Lifetimes

Four `AutoCloseable`s, and leaking any of them costs something specific. Three of the rules here fail
silently.

| Type | Leaking it costs |
|---|---|
| `Rabosh` | the directory lock, every mapping, and any background index build |
| `Snapshot` | disk: it holds back the versions compaction would otherwise drop, indefinitely |
| `DocumentCursor` | the segments it is reading, which cannot be reclaimed |
| `QueryCursor` | the same, plus its own snapshot if it took one |

Behind the opt-in marker, `IndexReader` and `ColumnReader` are the same story: each pins every sidecar
it may consult.

**On Windows a mapped file cannot be deleted at all**, so a leak there is not a slow drift in memory —
it is a compaction that can never reclaim its inputs. This is why `RaboshLifecycleTest` and
`ResourceLeakTest` assert by *deleting the directory* rather than by measuring anything.

### A row is a view, not a copy — copy before `next()`

This is the rule most likely to be discovered in production.

```kotlin
// WRONG: every element ends up reading whatever the cursor last landed on.
val found = mutableListOf<Variant>()
db.query(query.project(Projection.DOCUMENT)).use { rows ->
    while (rows.next()) found += rows.row.document()
}

// RIGHT: take a copy at the point you decide to keep it.
val found = mutableListOf<String>()
db.query(query.project(Projection.DOCUMENT)).use { rows ->
    while (rows.next()) found += rows.row.toJsonString()      // or Variant.toByteArray()
}
```

Every `Variant` in a row reads straight out of a mapped segment. `QueryCursor.row` and
`DocumentCursor.key` / `.document` are valid **until the next `next()`**, and the underlying bytes are
valid only while the snapshot behind the read is open. That is the trade that makes reads cheap; the
copy is available wherever it is actually wanted, via `Row.toJsonString()` or `Variant.toByteArray()`.

### `Query.where` projects keys only

`Row.document()` throws `IllegalStateException` under `Projection.KEY` — the default — and also for a
row that was filled from shredded columns, because in neither case was a document opened. That is not
a defect: `documentsRead == 0` is reachable *because* of it, and it is the largest single win an index
buys. Ask for `Projection.DOCUMENT` when you are going to read one.

## Durability

The default is `Durability.SYNC`: every commit is `fsync`ed before the call returns, so an
acknowledged write survives power loss and not merely process death. A `WriteBatch` is one commit —
one append and one force however many documents it carries — which is what makes durable bulk writing
fast.

`Durability.BUFFERED` writes to the operating system without forcing. Commits survive `kill -9`,
because a killed process does not discard the page cache, and are lost only if the machine stops. The
pattern it exists for is bulk load: write, call `sync()`, and only then report success to whoever
asked for the load. Do not report success before the `sync()`.

## Taking a copy of a store

**There is no `checkpoint` yet.** Until there is, the only defined way to copy a store is:

1. Stop writing. Not "pause the ingest thread" — no `put`, `delete` or `write` may be in flight.
2. `db.flush()`, which returns when the memtable is on the platter and the manifest names it.
3. Copy the whole directory, including `CURRENT`, `MANIFEST-*`, every `.wal`, `.seg`, `.cat`, `.idx`,
   `.pst` and `.col`. Not a subset: the manifest names the files it expects.
4. Resume writing.

**A directory copied while a writer is running is not defined to be recoverable**, and a copy that
skips the log or the manifest is not a store. Neither failure is loud — the copy usually opens and is
usually missing something.

`LOCK` may be copied or not; it holds nothing.

## Version pinning

The on-disk format is declared and stable, and a store written by an earlier release opens on every
later one — see [COMPATIBILITY.md](COMPATIBILITY.md). The **Kotlin API** is a different claim with a
different strength: [STABILITY.md](STABILITY.md) names a small stable core that moves only under a
deprecation cycle, and everything else may change in any release and is marked `@RaboshExperimental`
where the compiler can say so.

Practically: pin an exact version, stay inside the stable core, and treat an opt-in error as the
library telling you that you have left it. Note that `rabosh-api` brings the five modules of the
storage chain with it, but **`rabosh-jsonpath` is not one of them** — ask for it by name.

One behaviour worth knowing before you open the same directory with two different builds:
`DamagedIndexPolicy.REBUILD` and `DamagedSketchPolicy.REBUILD` do not distinguish a damaged sidecar
from one written by a *newer* build, so alternating builds rewrites sidecars downward. Documents are
never affected. [COMPATIBILITY.md](COMPATIBILITY.md#one-behaviour-worth-stating-plainly) has the
detail and the `REPORT` alternative.

## What rabosh is not

Stated here so that it is stated somewhere a reader looks before building on an assumption.

- **Not a server.** It opens no sockets. It is a library in your process.
- **No replication, no clustering, no multi-process access.** One directory, one owner.
- **No encryption at rest.** Use filesystem-level or volume-level encryption; the engine writes plain
  bytes and does not pretend otherwise.
- **No authentication or authorisation.** There is no principal to authenticate; access control is
  the file permissions on the directory.
- **No `ORDER BY` on a value, no aggregation, no joins, no string expression language.** Results come
  back in key order. These are declined rather than pending.

Both of the engine's inputs — the JSON you write and the files on disk — are treated as hostile;
[SECURITY.md](SECURITY.md) sets out what that means and what counts as a vulnerability in a library
with no network surface.
