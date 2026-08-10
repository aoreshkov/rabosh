package app.oreshkov.rabosh.index

import app.oreshkov.rabosh.RaboshExperimental
import app.oreshkov.rabosh.catalog.CatalogPath
import app.oreshkov.rabosh.core.DocumentStore
import app.oreshkov.rabosh.core.Key
import app.oreshkov.rabosh.variant.Variant

/**
 * Answers a predicate using an index where it can and a scan where it cannot.
 *
 * **The narrow reference implementation, and it is kept as one.** `rabosh-query` owns the predicate
 * AST, the planner and execution, and answers anything this does — with more indexes, over more
 * segments, and without materialising a key list. What this is for now is what it was for before that
 * existed: a second, independently written implementation of the same claim over the same readers,
 * which is the instrument this project reaches for whenever a claim is worth more than an assertion.
 * It shares [TermExtractor] with the planner, so the two are not two definitions of what a path means
 * — only two ways of asking.
 *
 * One index, one operator, and a whole-store scan for anything else. Reach for `QueryEngine` instead.
 *
 * **The rule it implements is the whole reason an index here cannot change an answer.** Three parts,
 * and every one of them is load-bearing:
 *
 * 1. **Candidates, not answers.** The index reports that a segment's newest version of a key carried
 *    the value. A newer version may live in a shallower segment or a memtable, so every candidate is
 *    re-evaluated against the document the snapshot can actually see.
 * 2. **Everything uncovered is scanned.** A segment with no posting file, a segment too new for this
 *    snapshot, and a store with unflushed writes all mean the same thing: the candidate set is not a
 *    superset of the answer, so the answer comes from a scan instead.
 * 3. **The predicate is evaluated by the same walk that built the index.** Recheck runs
 *    [TermExtractor] over the visible document, so "does this document match" is answered by the code
 *    that decided what to index. A second, differently-shaped evaluation would be a second definition
 *    of what a path means, and the two would eventually disagree about an array or a nested null.
 */
@RaboshExperimental
public object IndexQuery {
    /** Keys whose visible document carries [term] at the reader's path. */
    public fun keysEqualTo(store: DocumentStore, reader: IndexReader, term: IndexTerm): List<Key> =
        answer(
            store = store,
            reader = reader,
            usable = reader.answers(term),
            select = { it.candidates(term) },
            present = { it },
            matches = { terms -> term in terms },
        )

    /** Keys whose visible document carries any of [terms] at the reader's path. The `IN` case. */
    public fun keysAnyOf(store: DocumentStore, reader: IndexReader, terms: Collection<IndexTerm>): List<Key> {
        val wanted = terms.toSet()
        if (wanted.isEmpty()) return emptyList()
        return answer(
            store = store,
            reader = reader,
            usable = wanted.all(reader::answers),
            select = { it.candidates(wanted) },
            present = { it },
            matches = { found -> found.any { it in wanted } },
        )
    }

    /** Keys whose visible document carries any value at the reader's path. The `EXISTS` case. */
    public fun keysExisting(store: DocumentStore, reader: IndexReader): List<Key> =
        answer(
            store = store,
            reader = reader,
            usable = true,
            select = { it.existing() },
            present = { it },
            matches = { true },
        )

    /** Keys whose visible document carries no value at the reader's path. The `NOT EXISTS` case. */
    public fun keysAbsent(store: DocumentStore, reader: IndexReader): List<Key> =
        answer(
            store = store,
            reader = reader,
            usable = true,
            select = { it.absent() },
            present = { !it },
            matches = { true },
        )

    /**
     * Every key a full scan says matches, with no index involved at all.
     *
     * The other half of every differential test in the suite, and the fallback whenever the index
     * cannot answer. Public because "what would a scan have said" is the question anybody debugging
     * an index result actually has.
     */
    public fun scanKeys(
        store: DocumentStore,
        reader: IndexReader,
        matches: (Set<IndexTerm>) -> Boolean,
        present: (Boolean) -> Boolean = { it },
    ): List<Key> {
        val keys = sortedSetOf<Key>()
        val evaluator = Evaluator(reader.path, reader.options)
        store.scan(snapshot = reader.snapshot).use { cursor ->
            while (cursor.next()) {
                val terms = evaluator.terms(cursor.document)
                if (present(terms != null) && (terms == null || matches(terms))) keys.add(cursor.key)
            }
        }
        return keys.toList()
    }

    private fun answer(
        store: DocumentStore,
        reader: IndexReader,
        usable: Boolean,
        select: (IndexReader) -> KeyCursor,
        present: (Boolean) -> Boolean,
        matches: (Set<IndexTerm>) -> Boolean,
    ): List<Key> {
        if (!usable || !reader.isAuthoritative) return scanKeys(store, reader, matches, present)

        val keys = sortedSetOf<Key>()
        val evaluator = Evaluator(reader.path, reader.options)
        val cursor = select(reader)
        while (cursor.next()) {
            val key = cursor.key
            if (key in keys) continue
            // The recheck. A candidate is a claim about the version *one segment* held; this asks the
            // store what the snapshot can see, which may be a newer version, or a deletion.
            val document = store.get(key, reader.snapshot) ?: continue
            val terms = evaluator.terms(document)
            if (present(terms != null) && (terms == null || matches(terms))) keys.add(key)
        }
        return keys.toList()
    }
}

/**
 * Evaluates a path against a document with the walk that built the index.
 *
 * Returns the terms at the path, or `null` when the document has no value there at all — which is
 * how existence is distinguished from "present but carrying nothing indexable", the distinction a
 * JSON null forces.
 */
internal class Evaluator(path: CatalogPath, private val options: IndexOptions) {
    private val extractor = TermExtractor(listOf(path), options)

    fun terms(document: Variant): Set<IndexTerm>? {
        var present = false
        val terms = LinkedHashSet<IndexTerm>()
        extractor.extract(document) { _, value ->
            present = true
            // The same bound the writer applies, so a value too long to key on is present here and
            // absent from the dictionary on both sides.
            IndexTerm.of(value, options)?.let(terms::add)
        }
        return if (present) terms else null
    }
}
