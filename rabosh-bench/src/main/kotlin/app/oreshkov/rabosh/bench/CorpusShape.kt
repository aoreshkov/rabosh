package app.oreshkov.rabosh.bench

import app.oreshkov.rabosh.catalog.CatalogPath
import app.oreshkov.rabosh.catalog.CatalogStep
import app.oreshkov.rabosh.variant.Variant
import app.oreshkov.rabosh.variant.VariantBasicType
import app.oreshkov.rabosh.variant.VariantKind

/** One type, and how far its instances are scattered across distinct location shapes. */
class TypeSpread(
    /** The discriminator's value. */
    val type: String,
    /** How many distinct [CatalogPath] shapes instances of it occupy. */
    val pathShapes: Int,
    /** How many instances there are in total. */
    val elements: Long,
)

/**
 * Where a corpus keeps things: the distinct location *shapes* it uses, and how a discriminated type
 * is scattered across them.
 *
 * The question behind it is whether an index over nested elements could ever be *declared* — whether
 * a caller, or the catalog on their behalf, could name the places a given kind of element lives. That
 * is answerable only against real data, because the thing that defeats it is a structure that
 * recurses into itself, and no synthetic corpus has one unless it was built to.
 *
 * **Shapes are [CatalogPath], not strings.** Array indices collapse to [CatalogStep.AnyElement], so
 * `$.items[0].sku` and `$.items[7].sku` are one shape — which is the whole point, since that is the
 * unit an index is defined over. Using the engine's own type rather than assembling path text here
 * means the rendering, the equality and the ordering are all the engine's, and this cannot drift into
 * a second opinion about what a path is.
 *
 * **It walks for a different reason than [ExplodeCost] and shares nothing with it.** That one needs
 * subtree byte extents and never builds a path at all; this one needs paths and never asks a byte
 * size. Two walks, no shared definition to disagree about.
 *
 * Recursion descends one frame per document level, so a corpus nested deeper than the JVM stack would
 * overflow — `DEFAULT_MAX_JSON_DEPTH` is 1000 and anything parsed through `JsonParser` is bounded by
 * it, which is every corpus this diagnostic can be handed.
 */
class CorpusShape(
    /** How many objects the corpus holds. */
    val objects: Long,
    /** How many arrays. */
    val arrays: Long,
    /**
     * The deepest **container** path, in steps.
     *
     * Containers rather than leaves, because a leaf is always one step past its container and
     * counting both would report a number one larger for no extra information.
     */
    val maxPathDepth: Int,
    /** Shape -> how many objects sit at it. */
    val objectPathShapes: Map<CatalogPath, Long>,
    /** Shape -> how many scalars sit at it. This is the space `CatalogOptions.maxPaths` budgets. */
    val leafPathShapes: Map<CatalogPath, Long>,
    /** Discriminator value -> shape -> how many instances of that type sit at that shape. */
    val typePathShapes: Map<String, Map<CatalogPath, Long>>,
) {
    /** How many distinct discriminator values appear. */
    val distinctTypes: Int get() = typePathShapes.size

    /** How many objects carry the discriminator at all. */
    val discriminatorHits: Long get() = typePathShapes.values.sumOf { shapes -> shapes.values.sum() }

    /** How many (type, shape) pairs there are — the size of a hypothetical declaration. */
    val typePathPairs: Int get() = typePathShapes.values.sumOf { it.size }

    /** Distinct shapes a type occupies -> how many types occupy exactly that many. */
    fun pathsPerType(): Map<Int, Int> =
        typePathShapes.values.groupingBy { it.size }.eachCount().toSortedMap()

    /**
     * Instances belonging to a type that occupies exactly **one** shape.
     *
     * The number that decides whether declaring scopes is realistic, and the one most easily misread
     * from its complement: a corpus can have most of its *types* at a single shape while most of its
     * *elements* are not, because the scattered types are the populous ones.
     */
    fun elementsOfSingleShapeTypes(): Long =
        typePathShapes.values.filter { it.size == 1 }.sumOf { shapes -> shapes.values.sum() }

    /** Types by how far they are scattered, widest first, then by instance count. */
    fun spread(): List<TypeSpread> =
        typePathShapes.map { (type, shapes) -> TypeSpread(type, shapes.size, shapes.values.sum()) }
            .sortedWith(compareByDescending<TypeSpread> { it.pathShapes }.thenByDescending { it.elements })

    companion object {
        /**
         * Measures [document], treating an object as a discriminated element when it carries a
         * string-valued field named [discriminator].
         *
         * The string check matches [ExplodeCost.measure]'s and is there for the same reason: an
         * object whose `@type` is itself an object is a field sharing the name, not an element.
         */
        fun measure(document: Variant, discriminator: String): CorpusShape {
            val accumulator = Accumulator()
            walk(document, discriminator, ArrayList(), accumulator)
            return CorpusShape(
                objects = accumulator.objects,
                arrays = accumulator.arrays,
                maxPathDepth = accumulator.maxPathDepth,
                objectPathShapes = accumulator.objectShapes.toSortedMap(),
                leafPathShapes = accumulator.leafShapes.toSortedMap(),
                typePathShapes = accumulator.typeShapes
                    .mapValues { (_, shapes) -> shapes.toSortedMap() as Map<CatalogPath, Long> }
                    .toSortedMap(),
            )
        }

        private class Accumulator {
            var objects = 0L
            var arrays = 0L
            var maxPathDepth = 0
            val objectShapes = HashMap<CatalogPath, Long>()
            val leafShapes = HashMap<CatalogPath, Long>()
            val typeShapes = HashMap<String, HashMap<CatalogPath, Long>>()
        }

        private fun walk(
            value: Variant,
            discriminator: String,
            steps: ArrayList<CatalogStep>,
            into: Accumulator,
        ) {
            when (value.basicType) {
                VariantBasicType.OBJECT -> {
                    into.objects++
                    if (steps.size > into.maxPathDepth) into.maxPathDepth = steps.size
                    val here = CatalogPath(steps.toList())
                    into.objectShapes.merge(here, 1L, Long::plus)

                    val tag = value.field(discriminator)
                    if (tag != null && tag.kind == VariantKind.STRING) {
                        into.typeShapes
                            .getOrPut(tag.stringValue()) { HashMap() }
                            .merge(here, 1L, Long::plus)
                    }

                    for (index in 0 until value.fieldCount) {
                        steps.add(CatalogStep.Field(value.fieldName(index)))
                        walk(value.fieldValue(index), discriminator, steps, into)
                        steps.removeAt(steps.size - 1)
                    }
                }

                VariantBasicType.ARRAY -> {
                    into.arrays++
                    if (steps.size > into.maxPathDepth) into.maxPathDepth = steps.size
                    // One step for the whole array, not one per index — the collapse that makes this
                    // a shape. `TermExtractor` narrows the same way for the same reason.
                    steps.add(CatalogStep.AnyElement)
                    for (index in 0 until value.elementCount) {
                        walk(value.element(index), discriminator, steps, into)
                    }
                    steps.removeAt(steps.size - 1)
                }

                VariantBasicType.PRIMITIVE, VariantBasicType.SHORT_STRING ->
                    into.leafShapes.merge(CatalogPath(steps.toList()), 1L, Long::plus)
            }
        }
    }
}
