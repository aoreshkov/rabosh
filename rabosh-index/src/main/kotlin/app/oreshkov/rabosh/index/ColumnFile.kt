package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.catalog.ValueBoundsBuilder
import app.oreshkov.rabosh.variant.Variant
import app.oreshkov.rabosh.variant.VariantBuilder
import app.oreshkov.rabosh.variant.VariantKind
import java.lang.foreign.MemorySegment
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path

/**
 * Accumulates one shredded column over one segment.
 *
 * Values arrive in ordinal order, one call per occurrence, so `$.tags[*]` over three tags is three
 * calls with one ordinal. The physical type is chosen **at the end**, when the whole multiset is
 * known — choosing from the first value would let one leading outlier decide the column, and choosing
 * per segment from a running majority would make the same path a different type in adjacent segments.
 *
 * **Bounds are accumulated over every value, including the ones that end up residual.** A bound is
 * what a query uses to decide a segment cannot contain a match, so a residual value of the
 * predicate's own family sitting outside the recorded bound would make skipping unsound. That is the
 * one rule here whose violation is a silently missing document.
 */
internal class ColumnBuilder(private val options: IndexOptions) {
    private val presence = Bitmap()
    private val bounds = ValueBoundsBuilder(options.columnTextBoundBytes)
    private val ordinals = ArrayList<Int>()
    private val values = ArrayList<ColumnValue>()

    /** Set when a budget was exceeded. The segment is then not covered by this column. */
    var overflowed: Boolean = false
        private set

    /** Why, when [overflowed]. */
    var overflowReason: String? = null
        private set

    val valueCount: Int get() = values.size

    fun add(ordinal: Int, value: Variant) {
        if (overflowed) return
        if (values.size >= options.maxColumnValuesPerSegment) {
            overflow("more than ${options.maxColumnValuesPerSegment} values at the path")
            return
        }
        presence.add(ordinal)
        // Over every value, residual included. See the class KDoc.
        bounds.add(value)
        ordinals.add(ordinal)
        values.add(ColumnValue.of(value))
    }

    private fun overflow(reason: String) {
        overflowed = true
        overflowReason = reason
        ordinals.clear()
        values.clear()
        presence.clear()
    }

    /**
     * Encodes the column, or returns `null` when there is nothing to shred.
     *
     * `null` means the segment is **not covered** — every value at the path was a double, a container
     * or absent — rather than covered and empty. Writing an empty column would claim coverage this
     * does not have, which is the one thing derived data must never do.
     */
    fun build(
        segmentNumber: Long,
        indexId: Int,
        path: String,
        documentCount: Int,
        largestSequence: Long,
    ): ByteArray? {
        if (overflowed) return null
        val type = ColumnType.choose(values) ?: return null

        val residual = Bitmap()
        val starts = Bitmap()
        val nulls = Bitmap()
        val emitted = ArrayList<ColumnValue>(values.size)

        var index = 0
        while (index < values.size) {
            val ordinal = ordinals[index]
            var end = index
            while (end < values.size && ordinals[end] == ordinal) end++

            // Residual is per *ordinal*, not per value, which keeps `presence = shredded + residual` a
            // partition and makes every bitmap identity checkable. The cost, recorded honestly: a
            // document with a hundred numbers and one string at one repeated path stores none of them.
            var shreddable = true
            for (at in index until end) {
                if (!isShreddable(type, values[at])) {
                    shreddable = false
                    break
                }
            }

            if (shreddable) {
                starts.add(emitted.size)
                for (at in index until end) {
                    if (values[at].isNull) nulls.add(emitted.size)
                    emitted.add(values[at])
                }
            } else {
                residual.add(ordinal)
            }
            index = end
        }

        if (ColumnValues.maxEncodedSize(type, emitted) > ColumnFormat.MAX_STRING_BLOB_BYTES) {
            overflow("the column's values exceed ${ColumnFormat.MAX_STRING_BLOB_BYTES} bytes")
            return null
        }

        val meta = IndexWriter(128)
        meta.writeLong(segmentNumber)
        meta.writeLong(largestSequence)
        meta.writeU32(indexId)
        meta.writeU32(documentCount)
        meta.writeU32(emitted.size)
        meta.writeU32(presence.cardinality - residual.cardinality)
        meta.writeByte(type.id)
        meta.writeByte(type.scale)
        meta.writeByte(statsEncodingFor(type))
        meta.pad(1)
        meta.writeString(path)
        ColumnBounds.writePair(meta, bounds.build())

        val fidelity = IndexWriter(4)
        fidelity.writeU32(if (reconstructsExactly(type, emitted)) ColumnFormat.FIDELITY_EXACT_VALUES else 0)

        return SectionDirectory.encode(
            ColumnFormat.MAGIC,
            ColumnFormat.VERSION,
            listOf(
                ColumnFormat.SECTION_META to meta.toByteArray(),
                ColumnFormat.SECTION_PRESENCE to presence.encode(),
                ColumnFormat.SECTION_RESIDUAL to residual.encode(),
                ColumnFormat.SECTION_STARTS to starts.encode(),
                ColumnFormat.SECTION_NULLS to nulls.encode(),
                ColumnFormat.SECTION_VALUES to ColumnValues.encode(type, emitted),
                ColumnFormat.SECTION_STATS to encodeStats(type, emitted),
                ColumnFormat.SECTION_FIDELITY to fidelity.toByteArray(),
            ),
        )
    }

    /**
     * Whether every shredded value would come back out of the column as the document wrote it.
     *
     * The question projection turns on, and it is asked here because here is where the *document's*
     * value is in hand. A reader has only unscaled integers and the column's common scale, which is
     * exactly the information that cannot answer it.
     *
     * `STRING` and `BOOLEAN` transform nothing — UTF-8 bytes and one bit, back unchanged. The numeric
     * family is where the care goes, and the argument turns on a fact about the encoder rather than
     * about the column: **`decideNumber` strips trailing zeros**, so a document parsed from JSON never
     * holds anything but the canonical form of its number. `12.30` is stored as `12.3` and `10.00` as
     * the integer `10`. Rescaling that canonical value up to the column's scale is exact, and
     * canonicalising it again on the way out returns precisely what was there — which is why
     * [projectedValueAt] rebuilds through `appendNumberLiteral`, the same decision the parser made,
     * rather than reasoning about scales a second time.
     *
     * What that leaves is the value that is *not* canonical, which no JSON document produces and a
     * caller building a `Variant` by hand can: `appendDecimal(BigDecimal("10.00"))` is a decimal of
     * scale 2 whose canonical form is the integer `10`, and projecting it would hand back `10`. So the
     * flag is cleared for any value that is not already its own canonical form, and for a kind that
     * does not match the one canonicalisation implies.
     *
     * A single value failing clears the flag for this column **in this segment only**; the same path
     * in another segment is decided on its own values. Nulls are exempt because a null reconstructs
     * as a null, and residual values never reach a projection at all.
     *
     * A pure function of the value multiset, which is what keeps a flush-written column and a
     * backfill-rebuilt one byte-identical.
     */
    private fun reconstructsExactly(type: ColumnType, emitted: List<ColumnValue>): Boolean {
        if (!type.isNumeric) return true
        return emitted.all { value -> value.isNull || isCanonical(value) }
    }

    /**
     * Whether [value] is already the form `decideNumber` would have produced for it.
     *
     * Mirrors that function's normalisation — strip trailing zeros, fold a negative scale back to
     * zero — and then requires the kind the document actually held to be the one that normalisation
     * implies. A scale-0 value too wide for a `Long` is a decimal to the parser and is treated here as
     * not provable, which costs a document read and never an answer.
     */
    private fun isCanonical(value: ColumnValue): Boolean {
        val number = value.number ?: return false
        val stripped = number.stripTrailingZeros()
        val canonical = if (stripped.scale() < 0) stripped.setScale(0) else stripped
        if (number.scale() != canonical.scale()) return false
        val fitsLong = canonical.scale() == 0 && canonical.unscaledValue().bitLength() < Long.SIZE_BITS
        return value.sourceKind == if (fitsLong) VariantKind.INTEGER else VariantKind.DECIMAL
    }

    private fun isShreddable(type: ColumnType, value: ColumnValue): Boolean {
        if (value.isNull) return true
        return when (type.id) {
            ColumnFormat.COLUMN_TYPE_BOOLEAN -> value.family == ColumnFamily.BOOLEAN
            ColumnFormat.COLUMN_TYPE_STRING -> value.family == ColumnFamily.TEXT
            else -> value.family == ColumnFamily.NUMERIC && type.unscaledOrNull(value.number!!) != null
        }
    }

    private fun statsEncodingFor(type: ColumnType): Int =
        if (type.id == ColumnFormat.COLUMN_TYPE_STRING) ColumnFormat.STATS_PREFIX else ColumnFormat.STATS_TYPED

    /**
     * Per-block minima and maxima over the shredded values.
     *
     * A null slot holds the type's zero and **must not reach a bound** — a filler leaking into a min
     * widens it, which is correctness-safe and therefore invisible to every differential test. It
     * would simply make the column stop skipping, quietly, forever. Hence the `isNull` guard here and
     * the targeted assertion in the suite.
     */
    private fun encodeStats(type: ColumnType, emitted: List<ColumnValue>): ByteArray {
        val blocks = ColumnFormat.blockCount(emitted.size)
        val out = IndexWriter(blocks * 24 + 8)
        out.writeU32(blocks)
        out.pad(4)

        for (block in 0 until blocks) {
            val from = block shl ColumnFormat.COLUMN_BLOCK_SHIFT
            val to = minOf(from + ColumnFormat.COLUMN_BLOCK_VALUES, emitted.size)
            var nullCount = 0
            var minNumber: Long? = null
            var maxNumber: Long? = null
            var minText: ByteArray? = null
            var maxText: ByteArray? = null
            var sawTrue = false
            var sawFalse = false

            for (at in from until to) {
                val value = emitted[at]
                if (value.isNull) {
                    nullCount++
                    continue
                }
                when (type.id) {
                    ColumnFormat.COLUMN_TYPE_BOOLEAN -> if (value.boolean) sawTrue = true else sawFalse = true

                    ColumnFormat.COLUMN_TYPE_STRING -> {
                        val text = value.text!!
                        if (minText == null || compareText(text, minText) < 0) minText = text
                        if (maxText == null || compareText(text, maxText) > 0) maxText = text
                    }

                    else -> {
                        val unscaled = type.unscaledOrNull(value.number!!)!!
                        if (minNumber == null || unscaled < minNumber) minNumber = unscaled
                        if (maxNumber == null || unscaled > maxNumber) maxNumber = unscaled
                    }
                }
            }

            out.writeU32(nullCount)
            when (type.id) {
                ColumnFormat.COLUMN_TYPE_BOOLEAN -> {
                    out.writeByte(if (sawFalse) 0 else 1)
                    out.writeByte(if (sawTrue) 1 else 0)
                }

                ColumnFormat.COLUMN_TYPE_STRING -> {
                    // Truncated so a block of long strings does not put the documents in the
                    // statistics. Truncation widens: the minimum is a prefix and the maximum is a
                    // prefix with its last byte raised, so the bound stays correct.
                    out.writeBytes(truncateLow(minText ?: ByteArray(0)))
                    out.writeBytes(truncateHigh(maxText ?: ByteArray(0)))
                }

                ColumnFormat.COLUMN_TYPE_DECIMAL32 -> {
                    out.writeU32((minNumber ?: 0).toInt())
                    out.writeU32((maxNumber ?: 0).toInt())
                }

                else -> {
                    out.writeLong(minNumber ?: 0)
                    out.writeLong(maxNumber ?: 0)
                }
            }
        }
        return out.toByteArray()
    }

    private fun truncateLow(value: ByteArray): ByteArray =
        if (value.size <= options.columnTextBoundBytes) value else value.copyOf(options.columnTextBoundBytes)

    /** A prefix with its last byte below `0xFF` raised: no smaller than anything it stands for. */
    private fun truncateHigh(value: ByteArray): ByteArray {
        if (value.size <= options.columnTextBoundBytes) return value
        val prefix = value.copyOf(options.columnTextBoundBytes)
        for (index in prefix.indices.reversed()) {
            if (prefix[index] != 0xFF.toByte()) {
                val bound = prefix.copyOf(index + 1)
                bound[index] = (bound[index] + 1).toByte()
                return bound
            }
        }
        // Nothing representable is above it, so no upper claim: an empty maximum reads as unbounded.
        return ByteArray(0)
    }
}

/**
 * One shredded column over one segment, read in place off a mapping.
 *
 * The quadrant §7 describes, expressed as bitmaps: absent from [presence] is *field missing*; in
 * [residual] is *present but unshredded*, and the caller must read the document; otherwise the value
 * is here. `presence = shredded ⊎ residual` is a partition, which [verify] checks.
 */
internal class ColumnFile private constructor(
    val segmentNumber: Long,
    val indexId: Int,
    val path: String,
    val documentCount: Int,
    val valueCount: Int,
    val shreddedOrdinalCount: Int,
    val largestSequence: Long,
    val type: ColumnType,
    val statsEncoding: Int,
    val bounds: ColumnSegmentBounds,
    private val presenceSection: SectionDirectory.Section,
    private val residualSection: SectionDirectory.Section,
    private val startsSection: SectionDirectory.Section,
    private val nullsSection: SectionDirectory.Section,
    private val valuesSection: SectionDirectory.Section,
    private val statsSection: SectionDirectory.Section,
    /** Absent in every column written before phase 12, which reads as no claim at all. */
    private val fidelitySection: SectionDirectory.Section?,
    val file: String,
) {
    /**
     * Whether this column's values can be handed back as the documents wrote them.
     *
     * `false` for a column with no `FIDELITY` section — every column written before phase 12 — and
     * `false` for a numeric column whose values do not all carry its common scale. The consequence of
     * a wrong `true` is a caller handed `10.00` where the document says `10`, so absence is read as
     * the negative and the flag is never inferred from anything else.
     */
    val reconstructsExactly: Boolean by lazy {
        val section = fidelitySection ?: return@lazy false
        val bytes = section.verified()
        if (bytes.length < 4) bytes.corrupt("a FIDELITY section is a 4-byte flags word")
        val flags = bytes.u32(0, "fidelity flags", Int.MAX_VALUE)
        flags and ColumnFormat.FIDELITY_EXACT_VALUES != 0
    }
    /** Ordinals with at least one value at the path. */
    fun presence(): BitmapView = bitmap(presenceSection)

    /** Ordinals whose values are not all shreddable. Their documents must be read. */
    fun residual(): BitmapView = bitmap(residualSection)

    /** Ordinals whose values are all here. `presence` minus `residual`. */
    fun shredded(): Bitmap = presence().andNot(residual())

    /** Value positions at which a shredded ordinal's run of values begins. */
    fun starts(): BitmapView = bitmap(startsSection)

    /** Value positions holding the JSON null. Over positions, not ordinals — a null takes a slot. */
    fun nulls(): BitmapView = bitmap(nullsSection)

    val values: ColumnValueReader by lazy {
        ColumnValueReader(type, valuesSection.verified(), valueCount, file)
    }

    /** How many statistics blocks the column has. */
    val blockCount: Int get() = ColumnFormat.blockCount(valueCount)

    /**
     * The half-open range of value positions belonging to [ordinal], or `null` if it is not shredded.
     *
     * `rank` is inclusive, so a shredded ordinal's index among the shredded ones is `rank - 1`; the
     * run then begins at that index's start and ends at the next one, or at the end of the values.
     */
    fun valueRange(ordinal: Int): IntRange? {
        val shredded = shredded()
        if (!shredded.contains(ordinal)) return null
        val index = shredded.rank(ordinal) - 1
        val starts = starts()
        val from = starts.select(index)
        val to = if (index + 1 < starts.cardinality) starts.select(index + 1) else valueCount
        return from until to
    }

    /**
     * The value at [ordinal] rebuilt as the document held it, or `null` where there is none.
     *
     * Only meaningful when [reconstructsExactly] — `ColumnReader.canProject` is the gate, and this is
     * deliberately not defensive about it a second time, because a second check in a different place
     * is a second definition of the rule.
     *
     * `null` covers two cases the caller has already told apart: an ordinal with no value at the path,
     * which is a fact about the document and the right answer; and an ordinal whose values are
     * residual, which `canProject` refuses so that the document is read instead.
     *
     * The scalar is built with [VariantBuilder], the same appender the JSON parser reaches through
     * `appendNumberLiteral` — so for a document that arrived as JSON the bytes are identical, not
     * merely the value. A Variant hand-built at a wider physical width than its magnitude needs (an
     * `int32` holding 5) comes back at the narrow width: equal as a value and as JSON, not as
     * `toByteArray`.
     */
    fun projectedValueAt(ordinal: Int): Variant? {
        val range = valueRange(ordinal) ?: return null
        if (range.isEmpty()) return null
        val position = range.first
        val builder = VariantBuilder()
        if (nulls().contains(position)) {
            builder.appendNull()
        } else {
            when (type.id) {
                ColumnFormat.COLUMN_TYPE_BOOLEAN -> builder.appendBoolean(values.booleanAt(position))
                ColumnFormat.COLUMN_TYPE_STRING -> builder.appendString(textAt(position))
                // Through `appendNumberLiteral`, which is the parser's own decision — strip the
                // trailing zeros the common scale added, then integer or decimal by the same rule.
                // Choosing here instead would be a second definition of what a JSON number encodes
                // to, and the two would only have to disagree once.
                else -> builder.appendNumberLiteral(values.numberAt(position).toPlainString())
            }
        }
        return builder.buildVariant()
    }

    private fun textAt(position: Int): String = try {
        values.textAt(position).decodeToString(throwOnInvalidSequence = true)
    } catch (failure: java.nio.charset.CharacterCodingException) {
        valuesSection.bytes.corrupt("the string at position $position is not valid UTF-8", 0, failure)
    }

    /** Whether a numeric value in `[min, max]` could sit in block [block]. */
    fun blockMayContainNumeric(block: Int, min: BigDecimal?, max: BigDecimal?): Boolean {
        if (!type.isNumeric) return false
        val stats = statsSection.verified()
        val at = statsEntryOffset(block)
        val nullCount = stats.u32(at, "block $block null count", ColumnFormat.COLUMN_BLOCK_VALUES)
        if (nullCount == blockSize(block)) return false
        val low = readTypedBound(stats, at + 4)
        val high = readTypedBound(stats, at + 4 + type.fixedWidth)
        if (min != null && type.valueOf(high) < min) return false
        if (max != null && type.valueOf(low) > max) return false
        return true
    }

    /** Whether a string in `[min, max]` could sit in block [block]. */
    fun blockMayContainText(block: Int, min: ByteArray?, max: ByteArray?): Boolean {
        if (type.id != ColumnFormat.COLUMN_TYPE_STRING) return false
        val stats = statsSection.verified()
        var at = statsEntryOffset(block)
        val nullCount = stats.u32(at, "block $block null count", ColumnFormat.COLUMN_BLOCK_VALUES)
        if (nullCount == blockSize(block)) return false
        at += 4
        val lowLength = stats.u32(at, "block $block min length", stats.length - at - 4)
        val low = stats.bytes(at + 4, lowLength, "block $block min")
        at += 4 + lowLength
        val highLength = stats.u32(at, "block $block max length", stats.length - at - 4)
        val high = stats.bytes(at + 4, highLength, "block $block max")

        if (max != null && compareText(low, max) > 0) return false
        // An empty maximum is "no upper claim", which is +infinity, not the empty string.
        if (high.isEmpty()) return true
        if (min != null && compareText(high, min) < 0) return false
        return true
    }

    /** Whether block [block] holds a boolean equal to [wanted]. */
    fun blockMayContainBoolean(block: Int, wanted: Boolean): Boolean {
        if (type.id != ColumnFormat.COLUMN_TYPE_BOOLEAN) return false
        val stats = statsSection.verified()
        val at = statsEntryOffset(block)
        val nullCount = stats.u32(at, "block $block null count", ColumnFormat.COLUMN_BLOCK_VALUES)
        if (nullCount == blockSize(block)) return false
        val low = stats.u8(at + 4, "block $block min") != 0
        val high = stats.u8(at + 5, "block $block max") != 0
        return if (wanted) high else !low
    }

    /** Checks every section, every value, and the identities the quadrant depends on. */
    fun verify() {
        val presence = presence()
        val residual = residual()
        val starts = starts()
        val nulls = nulls()
        presence.verify()
        residual.verify()
        starts.verify()
        nulls.verify()
        values.verify()
        statsSection.verify()
        // Reading it is verifying it: the flag is behind its own section checksum.
        fidelitySection?.let { reconstructsExactly }

        if (residual.andCardinality(presence) != residual.cardinality) {
            residualSection.bytes.corrupt("RESIDUAL names ordinals PRESENCE does not")
        }
        val shredded = presence.andNot(residual)
        if (shredded.cardinality != shreddedOrdinalCount) {
            presenceSection.bytes.corrupt(
                "META says $shreddedOrdinalCount shredded ordinal(s) but PRESENCE minus RESIDUAL is " +
                    "${shredded.cardinality}",
            )
        }
        if (starts.cardinality != shreddedOrdinalCount) {
            startsSection.bytes.corrupt(
                "STARTS marks ${starts.cardinality} run(s) for $shreddedOrdinalCount shredded ordinal(s)",
            )
        }
        if (!presence.isEmpty && presence.last() >= documentCount) {
            presenceSection.bytes.corrupt("PRESENCE names ordinal ${presence.last()} beyond $documentCount")
        }
        if (!starts.isEmpty && starts.last() >= valueCount) {
            startsSection.bytes.corrupt("STARTS names position ${starts.last()} beyond $valueCount")
        }
        if (shreddedOrdinalCount > 0 && (starts.isEmpty || starts.first() != 0)) {
            startsSection.bytes.corrupt("STARTS must begin at value position 0")
        }
        if (!nulls.isEmpty && nulls.last() >= valueCount) {
            nullsSection.bytes.corrupt("NULLS names position ${nulls.last()} beyond $valueCount")
        }

        val stats = statsSection.verified()
        val declared = stats.u32(0, "block count", Int.MAX_VALUE)
        if (declared != blockCount) {
            stats.corrupt("STATS declares $declared block(s) for $valueCount value(s), not $blockCount")
        }
    }

    private fun blockSize(block: Int): Int {
        val from = block shl ColumnFormat.COLUMN_BLOCK_SHIFT
        return minOf(ColumnFormat.COLUMN_BLOCK_VALUES, valueCount - from)
    }

    private fun statsEntryOffset(block: Int): Int {
        require(block in 0 until blockCount) { "block $block of $blockCount" }
        if (statsEncoding == ColumnFormat.STATS_TYPED) {
            val width = if (type.id == ColumnFormat.COLUMN_TYPE_BOOLEAN) 1 else type.fixedWidth
            return 8 + block * (4 + 2 * width)
        }
        // Prefix bounds are variable-width, so the entry is found by walking. Blocks are 8192 values,
        // so a column of ten million strings has 1 221 of them: a walk, not a scan.
        var at = 8
        repeat(block) {
            val stats = statsSection.bytes
            at += 4
            val lowLength = stats.u32(at, "block min length", stats.length - at - 4)
            at += 4 + lowLength
            val highLength = stats.u32(at, "block max length", stats.length - at - 4)
            at += 4 + highLength
        }
        return at
    }

    private fun readTypedBound(stats: IndexBytes, at: Int): Long = when (type.fixedWidth) {
        4 -> stats.i32(at, "block bound").toLong()
        else -> stats.i64(at, "block bound")
    }

    private fun bitmap(section: SectionDirectory.Section): BitmapView {
        val bytes = section.verified()
        return BitmapView.open(bytes.source, bytes.sourceOffset, bytes.length, file)
    }

    override fun toString(): String =
        "ColumnFile(#$segmentNumber/$indexId, $path, $type, $valueCount value(s))"

    companion object {
        /**
         * Reads the directory and `META` of a mapped column.
         *
         * Every identity the file carries is checked against what the caller already knows, for the
         * reason the posting file does it: a `.col` copied from another store, or left by an index
         * whose id was reused, would otherwise decode perfectly and answer with somebody else's data.
         */
        fun open(
            segment: MemorySegment,
            length: Int,
            file: String,
            expectedSegmentNumber: Long,
            expectedIndexId: Int,
            expectedPath: String,
            expectedLargestSequence: Long,
        ): ColumnFile {
            val sections = SectionDirectory.open(
                segment,
                length,
                file,
                ColumnFormat.MAGIC,
                // One version, and it stays one. `.idx` reads two since phase 18 because it replaced
                // a layout; nothing has replaced a layout here, and a second entry would be a claim
                // about bytes no build has ever written.
                intArrayOf(ColumnFormat.VERSION),
                "column",
                ColumnFormat::sectionName,
            )
            val meta = sections.require(ColumnFormat.SECTION_META, "META").verified()

            val segmentNumber = meta.i64(0, "segment number")
            val largestSequence = meta.i64(8, "largest sequence")
            val indexId = meta.u32(16, "index id", Int.MAX_VALUE)
            val documentCount = meta.u32(20, "document count", BitmapFormat.MAX_ORDINAL)
            val valueCount = meta.u32(24, "value count", BitmapFormat.MAX_ORDINAL)
            val shreddedOrdinals = meta.u32(28, "shredded ordinal count", documentCount)
            val typeId = meta.u8(32, "column type")
            val scale = meta.u8(33, "column scale")
            val statsEncoding = meta.u8(34, "stats encoding")

            if (!ColumnFormat.isSupported(typeId)) {
                throw UnsupportedIndexFormatException(
                    "the column $file is of type ${ColumnFormat.columnTypeName(typeId) ?: typeId}, " +
                        "which this build does not read",
                )
            }
            if (ColumnFormat.statsEncodingName(statsEncoding) == null) {
                throw UnsupportedIndexFormatException(
                    "the column $file uses statistics encoding $statsEncoding, which this build does not know",
                )
            }
            if (scale > ColumnFormat.MAX_SCALE) meta.corrupt("a column scale of $scale is beyond the encoding", 33)
            if (shreddedOrdinals > valueCount && valueCount == 0) {
                meta.corrupt("a column with no values cannot have $shreddedOrdinals shredded ordinal(s)", 28)
            }

            val pathLength = meta.u32(36, "path length", meta.length - 40)
            val path = try {
                meta.bytes(40, pathLength, "path").decodeToString(throwOnInvalidSequence = true)
            } catch (failure: java.nio.charset.CharacterCodingException) {
                meta.corrupt("the column's path is not valid UTF-8", 40, failure)
            }
            val (bounds, boundsEnd) = ColumnBounds.readPair(meta, 40 + pathLength)
            if (boundsEnd != meta.length) {
                meta.corrupt("the column's META section has trailing bytes after its bounds", boundsEnd)
            }

            if (segmentNumber != expectedSegmentNumber) {
                meta.corrupt("the column filed under segment $expectedSegmentNumber describes segment $segmentNumber")
            }
            if (indexId != expectedIndexId) {
                meta.corrupt("the column filed under index #$expectedIndexId describes index #$indexId", 16)
            }
            if (path != expectedPath) {
                meta.corrupt("the column for index #$indexId is over '$path', but the registry says '$expectedPath'")
            }
            if (largestSequence != expectedLargestSequence) {
                meta.corrupt(
                    "the column reports largest sequence $largestSequence but its base sidecar says " +
                        "$expectedLargestSequence",
                    8,
                )
            }

            return ColumnFile(
                segmentNumber = segmentNumber,
                indexId = indexId,
                path = path,
                documentCount = documentCount,
                valueCount = valueCount,
                shreddedOrdinalCount = shreddedOrdinals,
                largestSequence = largestSequence,
                type = ColumnType.of(typeId, scale),
                statsEncoding = statsEncoding,
                bounds = bounds,
                presenceSection = sections.require(ColumnFormat.SECTION_PRESENCE, "PRESENCE"),
                residualSection = sections.require(ColumnFormat.SECTION_RESIDUAL, "RESIDUAL"),
                startsSection = sections.require(ColumnFormat.SECTION_STARTS, "STARTS"),
                nullsSection = sections.require(ColumnFormat.SECTION_NULLS, "NULLS"),
                valuesSection = sections.require(ColumnFormat.SECTION_VALUES, "VALUES"),
                statsSection = sections.require(ColumnFormat.SECTION_STATS, "STATS"),
                // Optional, and deliberately not `require`: a column written before phase 12 has none,
                // and a missing one is an absence rather than damage.
                fidelitySection = sections[ColumnFormat.SECTION_FIDELITY],
                file = file,
            )
        }
    }
}

/** Writing and deleting columns. */
internal object ColumnFileIo {
    fun write(directory: Path, segmentNumber: Long, indexId: Int, bytes: ByteArray) {
        writeSidecarAtomically(
            directory.resolve(temporaryColumnFileName(segmentNumber, indexId)),
            directory.resolve(columnFileName(segmentNumber, indexId)),
            bytes,
        )
    }

    fun delete(directory: Path, segmentNumber: Long, indexId: Int) {
        Files.deleteIfExists(directory.resolve(columnFileName(segmentNumber, indexId)))
        Files.deleteIfExists(directory.resolve(temporaryColumnFileName(segmentNumber, indexId)))
    }
}
