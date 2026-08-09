package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.catalog.IndexKind
import java.util.Locale
import java.util.zip.CRC32C

/**
 * The on-disk layout of the index registry and the two sidecar kinds.
 *
 * ```
 * INDEXES   := magic["JKDB-IXR"] version:u32 payloadLength:u32 crc32c:u32        (20 bytes)
 *              nextIndexId:u32 indexCount:u32 index*
 * index     := indexId:u32 kind:u8 reserved:u8[3]
 *              pathLength:u32 path                the canonical `$.items[*].sku`, UTF-8
 *              createdAtSequence:u64
 *              [fieldCount:u32 (fieldLength:u32 field)*]   COMPOSITE_TERM only, kind 3
 *
 * %010d.idx := header entry[sectionCount] section*                              version 2
 * header    := magic["JKDB-IDX"] version:u32 sectionCount:u32 crc32c:u32         (20 bytes)
 * entry     := kind:u8 reserved:u8[3] offset:u64 length:u32 crc32c:u32           (20 bytes)
 * section   := META    segmentNumber:u64 documentCount:u32 tombstoneCount:u32
 *                      minSequence:u64 maxSequence:u64                           kind 1
 *            | KEYS    entry* restartOffset:u32[restartCount] restartCount:u32   kind 2
 *            | PRESENT bitmap                                                    kind 3
 * KEYS entry := shared:varint unshared:varint unsharedKey[unshared]
 *
 * version 1 differs in the KEYS entry alone, and is still read:
 * KEYS entry := sharedLength:u32 unsharedLength:u32 unsharedKey[unsharedLength]
 *
 * %010d.%04d.pst := header path directory restarts terms presence posting*      version 2
 * header    := magic["JKDB-PST"] version:u32 kind:u8 reserved:u8[3]              (16)
 *              segmentNumber:u64 maxSequence:u64                                 (16)
 *              indexId:u32 documentCount:u32 termCount:u32 directoryOffset:u32   (16)
 *              presenceOffset:u32 presenceLength:u32 presenceCrc32c:u32          (12)
 *              crc32c:u32                                                        (4)
 * path      := pathLength:u32 utf8[]
 * directory := term-entry[termCount]                       at directoryOffset
 * term-entry:= postingOffset:u32 postingLength:u32
 *              encoding:u8 reserved:u8[3] crc32c:u32                             (16 bytes)
 * restarts  := restartOffset:u32[restartCount]             relative to the term region
 * terms     := term-record*, ascending by unsigned byte order, front-coded
 * term-rec  := shared:varint unshared:varint unsharedBytes[unshared]
 * term      := signatureTag:u8 payload[]                   exactly ValueSignature
 * presence  := a BitmapFormat bitmap: ordinals carrying any value at the path
 * posting   := BITMAP a BitmapFormat bitmap at postingOffset                     encoding 1
 *            | SINGLE postingOffset *is* the ordinal, postingLength = 0          encoding 2
 *
 * version 1 differs in the dictionary alone, and is still read:
 * directory := term-entry[termCount]                       no restart array
 * term-entry:= termOffset:u32 termLength:u32
 *              postingOffset:u32 postingLength:u32
 *              encoding:u8 reserved:u8[3] crc32c:u32                             (24 bytes)
 * terms     := term*, ascending by unsigned byte order, stored whole
 * ```
 *
 * Little-endian throughout, matching the log, the manifest, the segment, the sketch sidecar, the
 * bitmap and the Variant encoding. **These constants are permanent**: add, never renumber.
 *
 * **A version bump is not a renumbering.** The permanence rule is about *ids* — a section kind, an
 * encoding byte, a type tag — because an id is read by a build that has never heard of the value that
 * replaced it, and there is nothing in the file to warn it. A version field is the opposite: it is the
 * one number a reader checks *before* it believes anything else, so a new one means "written by a
 * build you may not understand" rather than "reinterpret what you already read". What that permits is
 * a change to a layout; what it still forbids is changing what any existing byte means. So
 * [POSTING_ENCODING_SINGLE] stays 2 and [SECTION_KIND_COLUMN] stays reserved, and both older versions
 * keep being read rather than being declared obsolete — see the notes on the two dictionaries and the
 * two key blocks below.
 *
 * Two files here have taken one: [POSTING_VERSION] in phase 17 and [BASE_VERSION] in phase 18, and
 * both for the same reason rather than by habit. **Neither change was expressible as an id.** A term
 * entry losing two fields and a key entry narrowing two `u32`s to two varints are both replacements of
 * a record every existing file already has, and there is no byte in either record to gate a
 * reinterpretation on. The test is written down because the tempting alternative is a *new section
 * kind*, which looks additive and is not: an unknown section kind is skipped, so an older build
 * meeting a version-2 key block would find no `KEYS` section at all and report the sidecar as damaged
 * — the same failure with a worse message, at the price of a permanent id, and with two key sections
 * able to sit in one file, which is a second definition of the segment's key space.
 *
 * Six choices in that layout carry weight.
 *
 * **Two levels of checksum, and the split is the whole reason a sidecar can be mapped.** A header's
 * crc covers everything that decides *where a byte is* — the version fields, the path, the directory,
 * the term bytes — and is checked once on open. Each directory entry's crc covers one section's or
 * one posting's bytes together with its own kind or encoding byte, and is checked when that section
 * or posting is first read, never before. A [POSTING_ENCODING_SINGLE] posting is the one thing on
 * both sides of that line: its ordinal lives *in* the directory, so it is covered by the header crc
 * as well as by its own entry's, and a flip in it is therefore caught on open rather than deferred.
 * That is a clause on the rule and not a hole in it — the deferred half is what keeps opening a file
 * independent of its postings, and a file whose terms are all singletons has no postings to defer.
 * That is the division `BitmapView.open` and
 * `BitmapView.verify` already make, expressed physically: §9.6 left a bitmap block with no magic and
 * no checksum on the stated grounds that the sidecar carrying it would do for it what
 * `SegmentBytes.verifyBlock` does for a segment's data block, and this is where that debt is paid. A
 * single whole-file checksum would have to be re-read on every open, which would defeat mapping
 * entirely.
 *
 * **Both directories are fixed-width and always present, and phase 17 made the posting one narrower
 * rather than variable.** Section entry *i* begins at `20 + 20i` and term entry *i* at
 * `directoryOffset + 16i`, so finding either is still arithmetic. The same argument as the bitmap's
 * directory in `BitmapFormat`, and the same reason: a branch on a variable-width record would sit on
 * the hottest read in the query layer.
 *
 * What phase 17 changed is the *other* half of that sentence — a term is no longer one indirection
 * from its entry — and the claim has to be re-argued rather than quietly dropped, because the whole
 * case for a fixed-width directory was lookup cost. It survives, and in the direction that matters:
 *
 * ```
 *                              version 1                     version 2
 * postingAt(i), the hot path   directoryOffset + 24i         directoryOffset + 16i
 * probes in a bisect           log2(n)                       log2(n / 16), four fewer
 * one probe costs              8-byte read + a random        one entry walk from a restart
 *                              read into the term region
 * final step                   —                             <= 15 sequential entries
 * ```
 *
 * The bisect makes four fewer probes and touches a sixteenth of the term footprint, and pays for it
 * with a bounded walk inside one or two cache lines. [POSTING_V2_TERM_ENTRY_BYTES] is two thirds of
 * [POSTING_V1_TERM_ENTRY_BYTES], so the directory a probe walks is a third smaller as well — and
 * `postingAt`, the only thing left on the query path once a term is located, is strictly cheaper
 * because its stride shrank. The variable-width record is confined to the term region, which is
 * walked and never indexed into, and that is the line: variable width where a walk was happening
 * anyway, fixed width everywhere a position is computed.
 *
 * **A varint has exactly one spelling, and the reader enforces it.** LEB128 admits `0x80 0x00` for
 * zero alongside `0x00`; `IndexBytes.varint` calls the padded form corruption. Otherwise one
 * dictionary could encode to two different files, and the byte identity between a flush-written
 * sidecar and a backfill-rebuilt one — which is what lets the suites compare sidecars as *files* —
 * would hold only by luck. `BitmapView.verify` rejects a wastefully encoded block for the same
 * reason, and it is the same rule: a format that permits two spellings of one value has no canonical
 * form, and every argument resting on one quietly stops being true.
 *
 * **Two regions are variable-width now, and the line between them and everything else is the same
 * one.** Phase 17 put varints in a posting file's term region; phase 18 put them in a base sidecar's
 * key entries. Both are *walked* from a restart point and never indexed into, so a variable width
 * costs nothing that was not already a sequential read. Every position that is **computed** stayed
 * fixed-width, and that is not a coincidence to be tidied away later: the term directory, the section
 * directory and both restart arrays are arithmetic, and a varint in any of them would turn one
 * subtraction into a scan.
 *
 * **The key entry's saving has no crossover, which is where it differs from the term region's.**
 * Front-coding a term costs `2 + (length - shared)` against `length`, so it loses on short terms and
 * the loss had to be bounded and pinned. A varint *header* is two bytes where [KEY_V1_ENTRY_HEADER_BYTES]
 * is eight, for every key shorter than 128 bytes, and four bytes for every key shorter than 16 KiB —
 * so a version-2 key block is never larger than the version-1 block of the same keys, for any key this
 * engine can hold. Six bytes per key, unconditionally, and `KeyBlockTest` asserts the inequality
 * rather than trusting this paragraph.
 *
 * **Two dictionary layouts, one notion of a posting list.** A version-1 file is still read, so there
 * are two implementations of "the *i*-th term" and "where is this term" — and exactly one place that
 * knows which: the version check in `PostingFile.open`. Nothing below it branches, for the reason
 * [POSTING_ENCODING_SINGLE] gives about encodings: a reader with two notions of what a posting list
 * *is* would be a second definition of one, and the two would only have to disagree once. The
 * dictionaries differ in how a term is found and in nothing else — same order, same bytes, same
 * `-(insertionPoint + 1)` convention on a miss — which is a claim a differential test between them
 * has to make rather than a comment.
 *
 * **Two key blocks, one notion of an ordinal, and the sharing goes further than it does above.** A
 * version-1 `.idx` is still read for exactly the reason a version-1 `.pst` is, and the version is
 * checked in one place again: `BaseSidecar.open`. But the two key-block layouts differ *only* in how
 * one entry's two lengths are read — both front-code, both restart every [KEY_RESTART_INTERVAL], both
 * walk — so the walk, the ordinal arithmetic and the bisect live once in `KeyBlockReader` and the
 * subclasses supply nothing but an entry header. That is phase 6's rule about a container's read
 * algorithm, not a weaker version of the dictionary's: two implementations of "the *n*-th key" that
 * drifted would make a posting list resolve to different documents depending on when its sidecar was
 * written.
 *
 * **[INDEX_KIND_SHREDDED_COLUMN] was reserved and is now implemented; [SECTION_KIND_COLUMN] was
 * reserved and deliberately never will be.** Both ids were fixed before shredded typed columns
 * existed, so that when they arrived they would arrive as *new ids* on formats that already existed —
 * which is exactly what the `blockType` byte in the segment format and the `kind` byte in the bitmap
 * format are for, and the reason neither has ever needed a version bump. Phase 7b collected on that:
 * the index kind is written into every registry naming a column index, with no version bump anywhere.
 *
 * The section kind is the interesting half, because the reservation turned out to be the *wrong
 * shape*. A column is a separate `.col` file with its own section-kind namespace, not a section of a
 * segment's `.idx` — a section of `.idx` is a fact about a segment, a section of `.col` is a fact
 * about one path within one index over one segment, and sharing a namespace would make every future
 * column encoding burn a globally scarce id. So [SECTION_KIND_COLUMN] stays reserved and unused, and
 * it must not be repurposed: an id that was published as meaning one thing cannot quietly come to mean
 * another, whether or not anything ever wrote it.
 *
 * **The encoding byte is the extension point, and phase 11 spent it.** A path with a distinct value
 * per document produces one posting list per document, and a `BitmapFormat` bitmap costs 22 bytes to
 * hold a single 4-byte ordinal. Measured over 200 000 documents, a unique-valued index cost **52.4
 * bytes per document** against 2.5 for a low-cardinality one, so [POSTING_ENCODING_SINGLE] stores a
 * posting list of cardinality one as a bare ordinal — and stores it *in the directory entry*, which
 * is why it costs nothing at all rather than four bytes. A new id and not a new file version, so a
 * reader meeting an encoding it does not know says "written by a newer build" instead of "damaged".
 *
 * What that removed was the bitmap and only the bitmap: the 24-byte entry and the term itself were the
 * remaining 30.4 bytes, and a denser *dictionary* was a different change on a different field. The
 * arithmetic is written down here because the estimate it corrects — "roughly nine tenths" — came
 * from counting the bitmap as though it were the whole cost.
 *
 * **Phase 17 is that different change, and it is why this file has a version 2.** The dictionary was
 * by then the *whole* of a unique-valued index's cost, and neither half of it could be reached by
 * spending another id: dropping `termOffset`/`termLength` narrows a record every existing file has,
 * and front-coding the terms changes what the bytes between two entries mean. Nothing in the phase-11
 * shape could express either, which is the honest test for when a version bump is the right
 * instrument rather than the lazy one — an encoding byte reinterprets a field, a version replaces a
 * layout, and reaching for the second when the first would do is how a format acquires versions
 * nobody can drop.
 *
 * **Of those two halves the entry is the one that always pays, and the front-coding is not.** A
 * front-coded record costs `2 + (length - shared)` bytes — two varints version 1 never wrote, because
 * it kept the length in a directory entry it was spending 24 bytes on regardless — so the term region
 * only shrinks once the average shared prefix exceeds two bytes, and for a path whose values are
 * two-byte terms it *grows* by about a third of a byte each. The file is smaller either way, because
 * eight bytes of entry went and at most two came back. This is written down because the tempting
 * response to it is an adaptive dictionary, and phase 11 settled that: a layout chosen by anything but
 * the sorted term list would break the byte identity between a flush-written sidecar and a
 * backfill-rebuilt one, which is what lets the suites compare sidecars as files. `PostingEncodingTest`
 * pins both directions, so the unfavourable one cannot quietly get worse.
 *
 * **The presence bitmap is deliberately not part of this.** Its `(offset, length, crc32c)` triple at
 * [POSTING_PRESENCE_OFFSET] carries no encoding byte, so there is nothing to gate a reinterpretation
 * on; giving it one would be a header change to save 18 bytes per *file* rather than per term. It is
 * always a bitmap, and a reader may rely on that.
 *
 * **Every file repeats its own identity and it is checked on open.** A `.pst` carries the segment
 * number, the index id, the path and the maximum sequence its base sidecar reported; all four are
 * checked against the filename, against the registry and against the base. `SketchFile` set that
 * precedent for the same reason: a sidecar copied or renamed into place must never be folded in as if
 * it described a segment it has never seen.
 */
internal object IndexFormat {
    // --- the registry ------------------------------------------------------------------------

    /** `JKDB-IXR` in ASCII, distinct from every other magic in the engine. */
    val REGISTRY_MAGIC: ByteArray = "JKDB-IXR".encodeToByteArray()

    /** `JKDB-IDX` in ASCII: the per-segment base sidecar. */
    val BASE_MAGIC: ByteArray = "JKDB-IDX".encodeToByteArray()

    /** `JKDB-PST` in ASCII: one index's posting lists over one segment. */
    val POSTING_MAGIC: ByteArray = "JKDB-PST".encodeToByteArray()

    const val MAGIC_BYTES: Int = 8

    /** The only version of the registry this build writes, and the only one it reads. */
    const val REGISTRY_VERSION: Int = 1

    /**
     * The base-sidecar version this build **writes**. Phase 18's varint key entries.
     *
     * [BASE_VERSION_FLAT] is still read, and for the same forced reason [POSTING_VERSION_FLAT] is:
     * `SegmentIndex.open` throws when an `.idx` that exists will not decode — a sidecar is only
     * allowed to be *missing*, never unintelligible — so refusing version 1 would fail
     * `IndexCatalog.attach` on every store written before this build under the default `REPORT`
     * policy, rather than quietly rebuilding it.
     */
    const val BASE_VERSION: Int = 2

    /** The pre-phase-18 base sidecar: `u32/u32` key entry headers. Read, never written. */
    const val BASE_VERSION_FLAT: Int = 1

    /** Base-sidecar versions this build reads, newest first. */
    val BASE_VERSIONS: IntArray = intArrayOf(BASE_VERSION, BASE_VERSION_FLAT)

    /**
     * The posting-file version this build **writes**. Phase 17's front-coded dictionary.
     *
     * [POSTING_VERSION_FLAT] is still read. That is not politeness: `SegmentIndex.open` throws when a
     * `.pst` that exists will not decode — a sidecar is only allowed to be *missing*, never
     * unintelligible — so refusing version 1 would fail `IndexCatalog.attach` on every store written
     * before this build, under the default `REPORT` policy, rather than quietly rebuilding it.
     */
    const val POSTING_VERSION: Int = 2

    /** The pre-phase-17 posting file: whole terms behind per-entry offsets. Read, never written. */
    const val POSTING_VERSION_FLAT: Int = 1

    const val REGISTRY_HEADER_BYTES: Int = 20

    /** Ceiling on the registry, so a corrupt length is rejected rather than allocated. */
    const val MAX_REGISTRY_BYTES: Int = 1 shl 24

    /** Ceiling on the number of indexes one store may define. */
    const val MAX_INDEXES: Int = 1 shl 12

    // --- the base sidecar --------------------------------------------------------------------

    const val BASE_HEADER_BYTES: Int = 20
    const val BASE_ENTRY_BYTES: Int = 20

    /** A section kind this build knows. Permanent. */
    const val SECTION_KIND_META: Int = 1
    const val SECTION_KIND_KEYS: Int = 2
    const val SECTION_KIND_PRESENT: Int = 3

    /**
     * Reserved for a shredded typed column, and permanently unused. Never written, never read.
     *
     * Kept rather than reclaimed. Phase 7b put columns in their own `.col` file with its own
     * section-kind namespace, so nothing will ever occupy this slot — but the id was published as
     * meaning "a column section of a base sidecar", and reusing it for something else would make a
     * future reader of a future file wrong about a past decision. `ColumnFormat` has the reasoning.
     */
    const val SECTION_KIND_COLUMN: Int = 4

    /** `segmentNumber:u64 documentCount:u32 tombstoneCount:u32 minSequence:u64 maxSequence:u64`. */
    const val META_BYTES: Int = 32

    /**
     * Ordinals between restart points in the key block.
     *
     * Fixed, and that is what makes ordinal-to-key **arithmetic** rather than a search: restart *i*
     * covers ordinals `[16i, 16i + 16)`, so resolving an ordinal is one array read plus at most
     * fifteen entry steps. This is the one place the block layout deliberately departs from the
     * segment's `Block`, whose restarts have to be bisected by key because its entries are not
     * positionally uniform. Changing it would change where every key in every sidecar lives, so it
     * is as permanent as anything else here.
     */
    const val KEY_RESTART_INTERVAL: Int = 16

    /**
     * `sharedLength:u32 unsharedLength:u32` — the **version-1** key entry header.
     *
     * Version 2 has no constant to put here, which is the whole of what phase 18 did: two varints are
     * two bytes at every key length this engine sees, against these eight. Named for its version the
     * way [POSTING_V1_TERM_ENTRY_BYTES] is, so that a reader meeting it cannot mistake it for the
     * width of a record this build writes.
     */
    const val KEY_V1_ENTRY_HEADER_BYTES: Int = 8

    // --- the posting file --------------------------------------------------------------------

    const val POSTING_HEADER_BYTES: Int = 64

    /** `termOffset:u32 termLength:u32 postingOffset:u32 postingLength:u32 encoding:u8[4] crc32c:u32`. */
    const val POSTING_V1_TERM_ENTRY_BYTES: Int = 24

    /** `postingOffset:u32 postingLength:u32 encoding:u8[4] crc32c:u32` — the term is front-coded. */
    const val POSTING_V2_TERM_ENTRY_BYTES: Int = 16

    /**
     * Terms between restart points in a version-2 dictionary.
     *
     * The same number as [KEY_RESTART_INTERVAL] and deliberately a **second constant**, not a reuse.
     * They govern different files under different version fields, and they answer different questions:
     * a key block is entered by ordinal, so its interval bounds a *walk from a known group*, while a
     * term dictionary is entered by value, so this one trades bisect probes against that walk. Sharing
     * the symbol would make tuning either a change to both, and the section-kind namespaces of `.idx`
     * and `.col` are kept apart for the same reason.
     */
    const val POSTING_TERM_RESTART_INTERVAL: Int = 16

    /** How many restart points a version-2 dictionary of [termCount] terms has. Derived, never stored. */
    fun postingRestartCount(termCount: Int): Int =
        if (termCount == 0) 0 else (termCount - 1) / POSTING_TERM_RESTART_INTERVAL + 1

    /**
     * Offset of the presence bitmap's `(offset, length, crc32c)` triple.
     *
     * The ordinals carrying **any** value at the indexed path, stored once rather than derived. An
     * existence query is otherwise the union of every posting list in the file, which is the whole
     * index read to answer the cheapest question anybody asks of it; and "present but not equal to"
     * — the shape a `NOT` takes — would cost the same. Twelve bytes and one bitmap against that.
     */
    const val POSTING_PRESENCE_OFFSET: Int = 48

    /** Offset of the header's checksum field. Everything before it, from [MAGIC_BYTES], is covered. */
    const val POSTING_CHECKSUM_OFFSET: Int = 60

    /** A posting list stored as a `BitmapFormat` bitmap. Permanent. */
    const val POSTING_ENCODING_BITMAP: Int = 1

    /**
     * A posting list of exactly one ordinal, held **inline in the term entry**. Permanent.
     *
     * `postingOffset` is the ordinal rather than an offset and `postingLength` is zero, so a term
     * matching one document costs no posting bytes whatsoever. The entry's own crc still covers
     * `encoding || ordinal`, so a retag is damage here exactly as it is for a bitmap.
     *
     * **The choice is a pure function of the posting list** — cardinality one, nothing else — and it
     * has to stay one. A threshold that depended on how a segment was built would break the byte
     * identity between a flush-written sidecar and a backfill-rebuilt one, which is what lets the
     * suites compare sidecars as *files*.
     */
    const val POSTING_ENCODING_SINGLE: Int = 2

    /** Ceiling on one term, so a path of enormous strings cannot make a directory unbounded. */
    const val MAX_TERM_BYTES: Int = 1 shl 16

    // --- index kinds -------------------------------------------------------------------------

    /**
     * The permanent id of an [IndexKind].
     *
     * Exhaustive with no `else`, so a kind added to the enum must be given a number here and the
     * compiler is what makes that unavoidable. **Never `IndexKind.ordinal`** — an ordinal is a
     * property of the source order of an enum, and inserting a kind in the middle would silently
     * change what every registry ever written means. The same trap `SketchFormat.typeId` avoids, and
     * it fails the same way: quietly.
     */
    fun indexKindId(kind: IndexKind): Int = when (kind) {
        IndexKind.INVERTED -> 1
        IndexKind.SHREDDED_COLUMN -> 2
        IndexKind.COMPOSITE_TERM -> 3
    }

    const val INDEX_KIND_INVERTED: Int = 1
    const val INDEX_KIND_SHREDDED_COLUMN: Int = 2

    /**
     * A composite term over one array element. Permanent, and **additive in three places at once**.
     *
     * The whole of what this kind cost the format, which is the argument for it being a kind rather
     * than anything larger:
     *
     * - the registry's per-index record **continues** for this kind alone, with the declared fields
     *   after `createdAtSequence`. An older build stops at the kind byte — `indexKindOfId` answers
     *   `null` and the registry is reported as written by a newer build — so it never reaches the
     *   extension and never mis-parses the records after it. That is the kind byte doing exactly what
     *   an encoding byte does one level down: reinterpreting a record rather than replacing a layout.
     * - the `.pst` is **unchanged**. A composite index's file is a posting file whose terms happen to
     *   be tuples; the dictionary, the directory, the presence bitmap, the two posting encodings and
     *   both checksums are the same bytes doing the same job, and `PostingFile` learned one extra
     *   value for a header field it was already validating.
     * - no version was bumped and no section kind was spent, because no file any earlier build can
     *   write means anything different.
     *
     * **A golden store was added later, and the delay is the instructive part.** This list originally
     * ended "and no golden store was added", which was sound as a statement about *backward*
     * compatibility — the committed directories go on meaning what they meant, and they already cover
     * the case an older reader takes, stopping at a kind byte it does not know. It was the wrong test
     * to apply. A golden store is never evidence for the build that wrote it; it is evidence for every
     * build after. The moment 0.2.0 shipped, a registry carrying this continuation became a file
     * *earlier than the next build* with nothing committed behind it, and the only cover was a round
     * trip through this same writer — which `.claude/rules/testing.md` is explicit is worth nothing
     * against a self-consistent change. `golden/store-v5` is those bytes; see `GoldenStoreV5`.
     */
    const val INDEX_KIND_COMPOSITE_TERM: Int = 3

    /** Index kinds whose sidecar is a `.pst`. A composite index reuses the posting file entire. */
    val POSTING_INDEX_KINDS: IntArray = intArrayOf(INDEX_KIND_INVERTED, INDEX_KIND_COMPOSITE_TERM)

    private val KIND_BY_ID: Array<IndexKind?> = arrayOfNulls<IndexKind>(8).also { table ->
        IndexKind.entries.forEach { table[indexKindId(it)] = it }
    }

    /** The kind [id] names, or `null` if this build does not know it. Never a default. */
    fun indexKindOfId(id: Int): IndexKind? = KIND_BY_ID.getOrNull(id)

    /** The name of a section kind, or `null` if this build does not know it. Never a default. */
    fun sectionKindName(kind: Int): String? = when (kind) {
        SECTION_KIND_META -> "META"
        SECTION_KIND_KEYS -> "KEYS"
        SECTION_KIND_PRESENT -> "PRESENT"
        SECTION_KIND_COLUMN -> "COLUMN"
        else -> null
    }

    /** The name of a posting encoding, or `null` if this build does not know it. Never a default. */
    fun postingEncodingName(encoding: Int): String? = when (encoding) {
        POSTING_ENCODING_BITMAP -> "BITMAP"
        POSTING_ENCODING_SINGLE -> "SINGLE"
        else -> null
    }

    // --- checksums ---------------------------------------------------------------------------

    /** CRC32C over `[offset, offset + length)`. */
    fun checksum(bytes: ByteArray, offset: Int, length: Int): Int {
        val crc = CRC32C()
        crc.update(bytes, offset, length)
        return crc.value.toInt()
    }

    /**
     * CRC32C over two ranges of one array.
     *
     * Every header here puts its own checksum field between the fields it protects and the payload
     * it protects, so covering both halves means skipping the four bytes in the middle. Writing that
     * as one call rather than at each site is what keeps the writer and the reader from disagreeing
     * about which four bytes those are.
     */
    fun checksum(bytes: ByteArray, firstOffset: Int, firstLength: Int, secondOffset: Int, secondLength: Int): Int {
        val crc = CRC32C()
        crc.update(bytes, firstOffset, firstLength)
        crc.update(bytes, secondOffset, secondLength)
        return crc.value.toInt()
    }

    /**
     * CRC32C over the version and length fields followed by the payload.
     *
     * One checksum over both halves rather than one each: the length field decides how much is read,
     * and a corrupt length is exactly the fault that becomes a wild read instead of a report. The
     * log, the manifest and the sketch sidecar all cover their length fields for the same reason.
     */
    fun checksum(header: ByteArray, headerOffset: Int, headerLength: Int, payload: ByteArray): Int {
        val crc = CRC32C()
        crc.update(header, headerOffset, headerLength)
        crc.update(payload, 0, payload.size)
        return crc.value.toInt()
    }
}

// --- file naming -------------------------------------------------------------------------------

/** The per-segment base sidecar: the key block, the present bitmap and the segment's statistics. */
internal const val BASE_SUFFIX: String = ".idx"

/** One index's posting lists over one segment. */
internal const val POSTING_SUFFIX: String = ".pst"

private const val TEMPORARY_SUFFIX = ".tmp"

/** The index registry. Named, not numbered: there is exactly one and it is rewritten in place. */
internal const val REGISTRY_FILE_NAME: String = "INDEXES"

internal fun registryFileName(): String = REGISTRY_FILE_NAME

internal fun temporaryRegistryFileName(): String = REGISTRY_FILE_NAME + TEMPORARY_SUFFIX

/**
 * `%010d.idx`, zero-padded so a sidecar sorts next to the `.seg` it describes.
 *
 * The same rule the log, the segment, the manifest and the `.cat` sidecar all follow. Ten digits so
 * name order is number order.
 */
internal fun baseFileName(segmentNumber: Long): String =
    String.format(Locale.ROOT, "%010d%s", segmentNumber, BASE_SUFFIX)

internal fun temporaryBaseFileName(segmentNumber: Long): String = baseFileName(segmentNumber) + TEMPORARY_SUFFIX

/** `%010d.%04d.pst` — segment number, then index id. */
internal fun postingFileName(segmentNumber: Long, indexId: Int): String =
    String.format(Locale.ROOT, "%010d.%04d%s", segmentNumber, indexId, POSTING_SUFFIX)

internal fun temporaryPostingFileName(segmentNumber: Long, indexId: Int): String =
    postingFileName(segmentNumber, indexId) + TEMPORARY_SUFFIX

/**
 * Writes a sidecar whole, under a temporary name, forced, then moved into place.
 *
 * A sidecar describes one immutable segment, so there is no second version of it to record and
 * nothing that could leave a torn tail — which is why there is no record frame here of the kind the
 * log and the manifest share. **A file that exists is a file that is complete**, and that is the
 * whole of its recovery story.
 *
 * The directory is deliberately *not* forced. These are derived: a sidecar whose bytes survive a
 * power cut but whose name does not reads as a segment that is not covered, which is a rescan rather
 * than a wrong answer. The registry is the one file here that does not get that relaxation, and
 * `IndexRegistry.write` says why.
 */
internal fun writeSidecarAtomically(temporary: java.nio.file.Path, target: java.nio.file.Path, bytes: ByteArray) {
    java.nio.channels.FileChannel.open(
        temporary,
        java.nio.file.StandardOpenOption.CREATE,
        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
        java.nio.file.StandardOpenOption.WRITE,
    ).use { channel ->
        val buffer = java.nio.ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            if (channel.write(buffer) <= 0) throw java.io.IOException("index sidecar write made no progress")
        }
        channel.force(true)
    }
    java.nio.file.Files.move(
        temporary,
        target,
        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
    )
}

/** The segment [baseFileName] would have produced this name for, or `null` if it would not have. */
internal fun baseSegmentNumber(name: String): Long? {
    if (!name.endsWith(BASE_SUFFIX)) return null
    return name.removeSuffix(BASE_SUFFIX).toLongOrNull()?.takeIf { it >= 0 }
}

/** The `(segment, index)` pair [postingFileName] would have produced this name for, or `null`. */
internal fun postingNumbers(name: String): Pair<Long, Int>? {
    if (!name.endsWith(POSTING_SUFFIX)) return null
    val stem = name.removeSuffix(POSTING_SUFFIX)
    val separator = stem.lastIndexOf('.')
    if (separator <= 0) return null
    val segment = stem.substring(0, separator).toLongOrNull()?.takeIf { it >= 0 } ?: return null
    val index = stem.substring(separator + 1).toIntOrNull()?.takeIf { it >= 0 } ?: return null
    return segment to index
}
