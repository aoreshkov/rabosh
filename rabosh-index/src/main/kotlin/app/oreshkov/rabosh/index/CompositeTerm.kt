package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.RaboshExperimental
import app.oreshkov.rabosh.catalog.CatalogPath
import app.oreshkov.rabosh.catalog.CatalogStep
import app.oreshkov.rabosh.variant.Variant

/**
 * The key of a composite index: several fields **of one element**, spelled as one term.
 *
 * ```
 * $.items[*]  over (sku, qty)
 * {"sku":"A","qty":5}   ->   field 0 | text:A | field 1 | numeric:5
 * ```
 *
 * **Stored whole rather than hashed, and that is the decision in this file.** Postgres
 * `jsonb_path_ops` — the precedent this kind is taken from — hashes each path-and-value pair into 32
 * bits, and the plan that proposed this said "the hash of `(sku=A, qty=5)`". It is not hashed here,
 * for three reasons in increasing order of weight.
 *
 * A hash written into a file is **permanent**, and `format-permanence.md` lists every one this engine
 * has committed to. A third would be a third thing that can never change, bought to save bytes in a
 * file that already front-codes.
 *
 * The dictionary is **built for this**. A term region front-codes against the sorted sequence, and a
 * composite term's leading bytes are the same field header and the same type tag for every element —
 * so the shared prefix a tuple pays for is exactly the part a hash would have destroyed. Hashing
 * would make every term incompressible and fixed-width; a tuple of two short values front-codes to a
 * handful of bytes.
 *
 * And a hash makes every answer **inexact**. A collision is a false positive, which the recheck
 * absorbs — soundly, and at the price of a selectivity nobody could then state, since it would depend
 * on a hash quality this project has not measured. Stored whole, a composite lookup is *exact*: the
 * term says this element carried these values, so a plan may mark the leaf certain and skip the
 * recheck exactly as an inverted equality leaf does. That is the difference between reading fewer
 * documents and reading none.
 *
 * The cost of the choice is stated rather than hidden: a tuple over long strings is a long term, and
 * one above [IndexOptions.maxTermBytes] is not keyed at all. That is not a wrong answer — the query
 * side applies the same bound to the same bytes, so a tuple the writer dropped is one the planner
 * declines to look up and the leaf becomes a residual — but it is a real limit, and a corpus of long
 * identifiers is where a hash would win.
 *
 * ## What a term commits to
 *
 * **Every declared field, present, in declaration order.** An element missing one contributes no
 * term, which is what makes a lookup exact: a term exists only for an element that has the whole
 * tuple, so finding it means finding an element that satisfied every conjunct. An element with
 * *extra* fields contributes a term all the same — the tuple is over the declared fields and says
 * nothing about the others, which is why a composite index answers the declared conjunction rather
 * than a superset of it.
 *
 * **The field's position is part of the term.** `(a="x", b="y")` and `(a="y", b="x")` must not share
 * a spelling, so each value is preceded by its declared index. Concatenating the signatures alone
 * would make a query over two text fields find elements with the values swapped — a wrong answer, and
 * a silent one.
 *
 * **Both header fields are fixed-width**, unlike the varints everywhere else in this module. The rule
 * there is variable width only where a walk was happening anyway; here the bytes *are* the comparison
 * key, and a variable-width prefix would give one tuple two spellings the moment a length crossed
 * 128 — which is the canonicality rule `IndexBytes.varint` enforces, arriving from the other side.
 */
@RaboshExperimental
public object CompositeTerm {

    /** Bounded so the two-byte field index in a term cannot overflow, with room to spare. */
    public const val MAX_FIELDS: Int = 16

    /** Two bytes of field index and two of signature length, ahead of every value. */
    private const val FIELD_HEADER_BYTES = 4

    /**
     * The term for one element, or `null` when it has no complete tuple.
     *
     * `null` for an element missing any declared field, for one whose field holds a container or a
     * JSON null — neither has a signature — and for a tuple above [IndexOptions.maxTermBytes]. All
     * three mean the same thing to a caller: this element is not keyed, so the index cannot be asked
     * about it, and the document is found by the scan that covers what the index does not.
     *
     * @param values one entry per declared field, in declaration order, `null` where the element had
     *   no value there. Built by the caller's own [TermExtractor] over the relative paths, so that
     *   the writer and the recheck agree by construction rather than by inspection.
     */
    public fun of(values: List<Variant?>, options: IndexOptions): ByteArray? =
        ofSignatures(values.map { value -> value?.let { IndexTerm.of(it)?.bytes } }, options)

    /**
     * The same term, from signatures already in hand.
     *
     * The query side has `IndexTerm`s rather than `Variant`s — a literal was turned into a signature
     * when the leaf was lowered — and building the tuple from those is what makes the planner's term
     * and the writer's term the same bytes by construction rather than by two encoders agreeing.
     */
    internal fun ofSignatures(signatures: List<ByteArray?>, options: IndexOptions): ByteArray? {
        var length = 0
        for (signature in signatures) {
            if (signature == null) return null
            length += FIELD_HEADER_BYTES + signature.size
        }
        if (length == 0 || length > options.maxTermBytes) return null

        val bytes = ByteArray(length)
        var at = 0
        for (index in signatures.indices) {
            val signature = checkNotNull(signatures[index]) { "a null signature was already rejected above" }
            bytes[at++] = (index shr Byte.SIZE_BITS).toByte()
            bytes[at++] = index.toByte()
            bytes[at++] = (signature.size shr Byte.SIZE_BITS).toByte()
            bytes[at++] = signature.size.toByte()
            signature.copyInto(bytes, at)
            at += signature.size
        }
        return bytes
    }

    /**
     * Checks the relative paths a composite index is declared over.
     *
     * Each must be a chain of **field steps only**: no wildcard, and not the empty path. A wildcard
     * would let one element hold several values for one declared field, so an element would
     * contribute a *set* of tuples rather than one — and bounding that set would drop combinations,
     * which is a false negative and the one failure an index may never have.
     *
     * @throws IllegalArgumentException naming the offending path.
     */
    public fun requireSingleValued(fields: List<CatalogPath>) {
        require(fields.isNotEmpty()) {
            "a composite index needs at least one field: with none there is no tuple to key by"
        }
        require(fields.size <= MAX_FIELDS) {
            "a composite index may declare at most $MAX_FIELDS fields, not ${fields.size}"
        }
        require(fields.distinct().size == fields.size) { "a composite index names a field twice: $fields" }
        for (field in fields) {
            require(!field.isRoot) {
                "a composite index's field must name something inside the element, and '$field' is the " +
                    "element itself"
            }
            require(field.steps.all { it is CatalogStep.Field }) {
                "a composite index's field must be single-valued within an element, so '$field' cannot " +
                    "hold a wildcard: an element with several values there would contribute several " +
                    "tuples, and dropping any of them would be a missing answer"
            }
        }
    }
}
