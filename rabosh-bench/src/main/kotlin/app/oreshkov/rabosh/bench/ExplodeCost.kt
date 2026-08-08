package app.oreshkov.rabosh.bench

import app.oreshkov.rabosh.variant.Variant
import app.oreshkov.rabosh.variant.VariantBasicType
import app.oreshkov.rabosh.variant.VariantKind

/**
 * What storing every nested element as its own document would cost in bytes.
 *
 * The question this answers is the one standing between two ways of giving a query element-level
 * answers over a document like a protobuf-JSON dump. *Exploding* at ingest — one document per typed
 * element — needs no format change and no second ordinal space, because the elements simply become
 * documents and every index in the engine already works on them. An *element ordinal space* keeps
 * the document whole and pays a `BASE_VERSION` bump for it. The explode is the cheaper of the two
 * unless it duplicates too much, and "too much" is a number nobody had.
 *
 * Three models over the same corpus, so the number is a comparison rather than a magnitude:
 *
 * - [wholeModelBytes] — every typed element stored **entire**. A typed element nested inside another
 *   is stored twice: once on its own and once inside its parent. This is the model whose duplication
 *   is in question, and the ratio to [originalBytes] is the answer.
 * - [elidedModelBytes] — every typed element stored with its **nearest typed descendants replaced by
 *   a reference**, so each byte of the corpus belongs to exactly one document. No duplication, at the
 *   price of losing one-read subtree fetches. It does **not** include the bytes those references
 *   would themselves cost; see the class note below.
 * - [originalBytes] — the document as it stands, one document, which is what the engine does today.
 *
 * **Measured on the Variant encoding, not on JSON text**, which is the only form whose byte counts
 * mean anything here: a duplicated subtree in JSON carries its field *names*, while in Variant it
 * carries field *ids*, because `VariantMetadata` is one dictionary per SSTable and stored documents
 * hold value bytes only.
 *
 * **What that is worth, measured, is less than it sounds, and the reason is worth keeping.** On the
 * protobuf-JSON corpus of 2026-08-08 the encoding shrank the corpus by 2.33x — and moved the
 * duplication *factor* from 4.25 to 4.085, under 4%. A ratio is nearly encoding-independent, because
 * the encoding shrinks the copies and the original alike; only a subtree that encoded *differently*
 * from the whole would move it. So measure here for the absolute bytes, and do not expect the choice
 * of encoding to rescue a ratio.
 *
 * **What [elidedModelBytes] leaves out, deliberately.** Replacing a child with a reference costs
 * whatever a key costs, times the number of elements that have a typed parent — a number this
 * reports as [nestedElements] so the overhead can be computed against a key size the caller chooses,
 * rather than baked in against one this has no business picking.
 */
class ExplodeCost(
    /** Value bytes of the document as one document. */
    val originalBytes: Long,
    /** How many elements carry the discriminator. */
    val typedElements: Long,
    /** Of those, how many have at least one typed ancestor — the ones an explode would duplicate. */
    val nestedElements: Long,
    /** Total stored bytes if every typed element is stored entire. */
    val wholeModelBytes: Long,
    /** Total stored bytes if each typed element elides its nearest typed descendants. */
    val elidedModelBytes: Long,
    /** Typed ancestors -> how many elements sit at that nesting. */
    val elementsByTypedDepth: Map<Int, Long>,
    /** Typed ancestors -> whole-subtree bytes of the elements at that nesting. */
    val bytesByTypedDepth: Map<Int, Long>,
) {
    /** Stored bytes per original byte, storing every typed element entire. */
    val wholeFactor: Double get() = if (originalBytes == 0L) 0.0 else wholeModelBytes.toDouble() / originalBytes

    /** Stored bytes per original byte, eliding. Approaches 1 when the accounting is exact. */
    val elidedFactor: Double get() = if (originalBytes == 0L) 0.0 else elidedModelBytes.toDouble() / originalBytes

    /** The deepest typed nesting seen, in typed ancestors. `0` when nothing is nested. */
    val maxTypedDepth: Int get() = elementsByTypedDepth.keys.maxOrNull() ?: 0

    companion object {
        /**
         * Measures [document], treating an object as an element when it carries a string-valued
         * field named [discriminator].
         *
         * The string check is not incidental: protobuf-JSON's `@type` is always a string, and an
         * object with a `@type` that is an object is a field that happens to share the name rather
         * than a discriminated element. Counting it would inflate every number here.
         */
        fun measure(document: Variant, discriminator: String): ExplodeCost {
            val accumulator = Accumulator()
            walk(document, discriminator, typedDepth = 0, into = accumulator)
            return ExplodeCost(
                originalBytes = document.byteSize,
                typedElements = accumulator.typedElements,
                nestedElements = accumulator.nestedElements,
                wholeModelBytes = accumulator.wholeBytes,
                elidedModelBytes = accumulator.elidedBytes,
                elementsByTypedDepth = accumulator.elementsByDepth.toSortedMap(),
                bytesByTypedDepth = accumulator.bytesByDepth.toSortedMap(),
            )
        }

        private class Accumulator {
            var typedElements = 0L
            var nestedElements = 0L
            var wholeBytes = 0L
            var elidedBytes = 0L
            val elementsByDepth = HashMap<Int, Long>()
            val bytesByDepth = HashMap<Int, Long>()
        }

        private fun isTyped(value: Variant, discriminator: String): Boolean {
            if (value.basicType != VariantBasicType.OBJECT) return false
            val tag = value.field(discriminator) ?: return false
            return tag.kind == VariantKind.STRING
        }

        /**
         * Returns the subtree bytes this value contributes to the **nearest enclosing typed
         * element**, which is its own size when it is typed and its typed descendants' otherwise.
         *
         * That second half is the case worth stating: an untyped object or an array sitting between
         * two typed elements has to *pass its descendants through*, or a nested element separated
         * from its parent by a plain container would be counted as if it were not nested at all —
         * and the elided model would then double-count exactly the bytes it exists to count once.
         */
        private fun walk(value: Variant, discriminator: String, typedDepth: Int, into: Accumulator): Long =
            when (value.basicType) {
                VariantBasicType.OBJECT -> {
                    val typed = isTyped(value, discriminator)
                    val childDepth = if (typed) typedDepth + 1 else typedDepth
                    var typedChildBytes = 0L
                    for (index in 0 until value.fieldCount) {
                        typedChildBytes += walk(value.fieldValue(index), discriminator, childDepth, into)
                    }
                    if (typed) {
                        val size = value.byteSize
                        into.typedElements++
                        if (typedDepth > 0) into.nestedElements++
                        into.wholeBytes += size
                        into.elidedBytes += size - typedChildBytes
                        into.elementsByDepth.merge(typedDepth, 1L, Long::plus)
                        into.bytesByDepth.merge(typedDepth, size, Long::plus)
                        size
                    } else {
                        typedChildBytes
                    }
                }

                VariantBasicType.ARRAY -> {
                    var typedChildBytes = 0L
                    for (index in 0 until value.elementCount) {
                        typedChildBytes += walk(value.element(index), discriminator, typedDepth, into)
                    }
                    typedChildBytes
                }

                VariantBasicType.PRIMITIVE, VariantBasicType.SHORT_STRING -> 0L
            }
    }
}
