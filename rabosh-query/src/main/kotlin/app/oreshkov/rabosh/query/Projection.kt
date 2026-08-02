package app.oreshkov.rabosh.query

import app.oreshkov.rabosh.core.Key
import app.oreshkov.rabosh.variant.Variant
import app.oreshkov.rabosh.variant.VariantPath
import app.oreshkov.rabosh.variant.toJsonString

/**
 * What a query returns for each document it matches.
 *
 * **The projection vocabulary is [VariantPath], and filtering's is `CatalogPath`.** That is not an
 * oversight: a `CatalogPath` describes a *set* of locations — `$.tags[*]` is every tag — which is
 * exactly right for "does any value here match" and has no answer at all for "what is the value
 * here". A `VariantPath` names one location, which is what makes it something a row can hold. So
 * `$.tags[*]` is rejected as a projection, with the position, the way `CatalogPath.parse` rejects
 * `$.items[0]` as a filter.
 *
 * A path that does not resolve is `null` in the row rather than an error, matching `Variant.select`:
 * a document without the field is a fact about the document, not a failure of the query.
 */
public class Projection private constructor(
    internal val fields: List<Field>,
    internal val wholeDocument: Boolean,
) {
    internal class Field(val name: String, val path: VariantPath)

    /** The names this projection produces, in order. Empty for [KEY] and [DOCUMENT]. */
    public val names: List<String> get() = fields.map { it.name }

    override fun toString(): String = when {
        wholeDocument -> "document"
        fields.isEmpty() -> "key"
        else -> names.joinToString(", ")
    }

    public companion object {
        /**
         * The whole document.
         *
         * A view over the snapshot's mappings, so it costs the fetch and nothing more.
         */
        public val DOCUMENT: Projection = Projection(emptyList(), wholeDocument = true)

        /**
         * The key alone.
         *
         * The one projection that can leave a document unread: where the plan proves the index
         * already decided the answer, a keys-only query opens nothing. That is what makes
         * `documentsRead == 0` reachable rather than aspirational.
         */
        public val KEY: Projection = Projection(emptyList(), wholeDocument = false)

        /**
         * Named fields, each written as a `VariantPath` expression: `$.user.name`, `$.items[0].sku`.
         *
         * The name is the expression, which is what a caller wrote and can therefore recognise.
         *
         * @throws IllegalArgumentException with the position for a malformed expression, and for a
         *   wildcard, which names no single value.
         */
        public fun of(vararg expressions: String): Projection =
            of(expressions.map { it to VariantPath.parse(it) })

        /** Named fields, already parsed. */
        public fun of(fields: List<Pair<String, VariantPath>>): Projection {
            require(fields.isNotEmpty()) { "a projection needs a field; use Projection.KEY or DOCUMENT" }
            return Projection(fields.map { (name, path) -> Field(name, path) }, wholeDocument = false)
        }
    }
}

/**
 * One result: a key, and whatever the projection asked for.
 *
 * **A row is a view, not a copy.** Every [Variant] in it reads straight out of a mapped segment, so a
 * row is valid while the snapshot behind the query is open and not afterwards — the same trade
 * `DocumentStore.get` offers, and the reason a snapshot exists at all. A caller who needs a row to
 * outlive its snapshot takes [toJsonString] or `Variant.toByteArray`, and pays for the copy where the
 * copy is wanted rather than on every row for the benefit of the few that need it.
 */
public class Row internal constructor(
    /** The key of the document this row is about. */
    public val key: Key,
    private val projection: Projection,
    private val source: Variant?,
    /**
     * The projected values read out of shredded columns, or `null` when they came from a document.
     *
     * Two ways in, one meaning out. Where every projected path has a column that can reconstruct its
     * values exactly, the row is filled from the columns and no document is opened at all — which is
     * the point of the whole mechanism, and the reason [document] refuses afterwards: there is nothing
     * to hand back, exactly as under [Projection.KEY].
     */
    private val values: Array<Variant?>? = null,
) {
    internal constructor(key: Key, projection: Projection, values: Array<Variant?>) :
        this(key, projection, source = null, values = values)

    /**
     * The whole document.
     *
     * @throws IllegalStateException under [Projection.KEY], which deliberately never reads one, and
     *   for a row filled from columns, which never read one either.
     */
    public fun document(): Variant = checkNotNull(source) {
        if (values != null) {
            "this row was read from shredded columns and no document was opened; " +
                "ask for Projection.DOCUMENT if you need one"
        } else {
            "this query projected keys only; ask for Projection.DOCUMENT to read documents"
        }
    }

    /** The value of the projected field [name], or `null` where the document does not have it. */
    public operator fun get(name: String): Variant? {
        val index = projection.fields.indexOfFirst { it.name == name }
        if (index < 0) throw IllegalArgumentException("$name is not projected; this query projects $projection")
        return get(index)
    }

    /**
     * The value of the projected field at [index], in the order the projection names them.
     *
     * The branch is on *which source filled this row*, not on whether a value happens to be there. A
     * `null` in [values] means the document has no value at that path — an answer, not a miss — and
     * falling through to [source] for it would be right only because a column-backed row has no
     * source, which is the kind of accident that becomes a bug the moment one of them gains both.
     */
    public operator fun get(index: Int): Variant? {
        val projected = values
        if (projected != null) return projected[index]
        return source?.select(projection.fields[index].path)
    }

    /** The row as JSON: the document itself, or an object of the projected fields. */
    public fun toJsonString(): String = when {
        projection.wholeDocument -> document().toJsonString()
        projection.fields.isEmpty() -> "{}"
        else -> projection.fields.mapIndexed { index, field ->
            "\"${field.name}\":${get(index)?.toJsonString() ?: "null"}"
        }.joinToString(",", "{", "}")
    }

    override fun toString(): String = "Row($key, $projection)"
}
