package app.oreshkov.rabosh.index

/**
 * The layout of the RoaringBitmap **portable serialization format**, which is somebody else's.
 *
 * ```
 * roaring   := (runHeader | plainHeader) descriptive[count] offset[count]? container*
 *
 * plainHeader := cookie:u32 = 12346  count:u32
 * runHeader   := (cookie:u16 = 12347 | (count - 1):u16):u32  runBitmap:u8[ceil(count / 8)]
 * descriptive := key:u16 (cardinality - 1):u16
 * offset      := u32, from the start of the stream; present when there are no run containers,
 *                or when there are and count >= 4
 *
 * container := ARRAY   low:u16[cardinality]                                   cardinality <= 4096
 *            | BITSET  word:u64[1024]                                         cardinality >  4096
 *            | RUN     runCount:u16 (start:u16 lengthMinusOne:u16)[runCount]  run bit set
 * ```
 *
 * **None of these numbers is rabosh's, and none of them belongs on the permanent-id list in
 * `.claude/rules/format-permanence.md`.** They are fixed by the specification at
 * `RoaringBitmap/RoaringFormatSpec` and are what Lucene, Druid, Spark, CRoaring and pyroaring read.
 * If that specification grows a cookie or a
 * container encoding, this file changes to follow it — which is the opposite of the rule [BitmapFormat]
 * lives under, where a new encoding is a new id because files already written must keep their meaning.
 * An exchange format is not the storage form, and the difference is exactly that nothing rabosh has
 * written depends on this.
 *
 * Three differences from [BitmapFormat] are worth naming, because each is a place a shared helper would
 * have been silently wrong:
 *
 * - **A run container's count is a `u16` here and a `u32` there**, so a run costs two bytes less. That
 *   is enough to move the array/run boundary, which is why [kindFor] exists rather than a call to
 *   [BitmapFormat.smallestKind].
 * - **The container type is *derived*, never stored.** A reader infers it from the run bit and the
 *   cardinality, so an exporter that emitted a bitset at 4096 values or fewer would produce a file
 *   every other implementation reads as an array. [kindFor] cannot do that and [derivedKind] is what
 *   says so.
 * - **The offset header is conditional**, which is the branch on the hottest read that §9.6 of the
 *   design plan declined this format for. It costs nothing here because this is a decode into heap
 *   containers rather than a read in place.
 */
internal object RoaringPortableFormat {

    /** Cookie of a stream that has at least one run container; `count - 1` rides in the high half. */
    const val SERIAL_COOKIE: Int = 12_347

    /** Cookie of a stream with no run containers, followed by a 32-bit container count. */
    const val SERIAL_COOKIE_NO_RUNCONTAINER: Int = 12_346

    /** Below this many containers, a stream that has run containers carries no offset header. */
    const val NO_OFFSET_THRESHOLD: Int = 4

    /** Bytes of the fixed header of a stream with no run containers: cookie and count. */
    const val PLAIN_HEADER_BYTES: Int = 8

    /** Bytes of the cookie word of a stream with run containers; the run bitmap follows it. */
    const val RUN_HEADER_BYTES: Int = 4

    const val DESCRIPTIVE_ENTRY_BYTES: Int = 4

    const val OFFSET_BYTES: Int = 4

    /** Containers a stream can describe: one per 16-bit key. */
    const val MAX_CONTAINERS: Int = 1 shl 16

    /**
     * Bytes a run container occupies, **two fewer** than [BitmapFormat.runBytes] for the same runs.
     *
     * The whole difference between this format's encoding choice and rabosh's, and the reason
     * [kindFor] is a second copy rather than a call.
     */
    fun runBytes(runCount: Int): Int = 2 + runCount * 4

    /**
     * The encoding this format uses for a container of this shape.
     *
     * The same arithmetic as [BitmapFormat.smallestKind] — smallest of the three, candidates in
     * ascending kind order, each required to be *strictly* smaller so ties go to the lower id — over
     * this format's run size. Duplicated rather than shared, for the reason the bound codec is
     * duplicated between `SketchFormat` and `ColumnFormat`: [BitmapFormat.smallestKind] is rabosh's
     * permanent canonical rule and byte identity of every sidecar rests on it, so it must not become a
     * function whose behaviour tracks a format this project does not own. Semantics shared, bytes not.
     *
     * It agrees with what RoaringBitmap itself writes after `runOptimize()`, which is what makes an
     * exported stream byte-identical to the fixtures in `src/test/resources/roaring/` rather than
     * merely equivalent to them.
     */
    fun kindFor(cardinality: Int, runCount: Int): Int {
        require(cardinality in 1..BitmapFormat.CONTAINER_VALUES) {
            "a container holds 1..${BitmapFormat.CONTAINER_VALUES} values, not $cardinality"
        }
        var kind = BitmapFormat.KIND_ARRAY
        var bytes = if (cardinality <= BitmapFormat.ARRAY_MAX_CARDINALITY) {
            BitmapFormat.arrayBytes(cardinality)
        } else {
            Int.MAX_VALUE
        }
        if (BitmapFormat.BITSET_BYTES < bytes) {
            kind = BitmapFormat.KIND_BITSET
            bytes = BitmapFormat.BITSET_BYTES
        }
        if (runBytes(runCount) < bytes) return BitmapFormat.KIND_RUN
        return kind
    }

    /**
     * The encoding a *reader* infers, which is all the information the stream carries.
     *
     * [kindFor] and this must agree on everything [kindFor] produces, or a stream this writes says one
     * thing to rabosh and another to every other implementation. `RoaringPortableTest` asserts it over
     * generated bitmaps; the assertion is cheap and the failure would be invisible on our own read path.
     */
    fun derivedKind(cardinality: Int, isRun: Boolean): Int = when {
        isRun -> BitmapFormat.KIND_RUN
        cardinality <= BitmapFormat.ARRAY_MAX_CARDINALITY -> BitmapFormat.KIND_ARRAY
        else -> BitmapFormat.KIND_BITSET
    }

    /** Bytes a container of this kind occupies, given what the descriptive header said about it. */
    fun containerBytes(kind: Int, cardinality: Int, runCount: Int): Int = when (kind) {
        BitmapFormat.KIND_ARRAY -> BitmapFormat.arrayBytes(cardinality)
        BitmapFormat.KIND_BITSET -> BitmapFormat.BITSET_BYTES
        else -> runBytes(runCount)
    }
}
