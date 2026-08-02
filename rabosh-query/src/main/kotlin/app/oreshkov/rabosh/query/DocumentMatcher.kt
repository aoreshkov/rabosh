package app.oreshkov.rabosh.query

import app.oreshkov.rabosh.index.IndexOptions
import app.oreshkov.rabosh.index.TermExtractor
import app.oreshkov.rabosh.variant.Variant
import java.util.IdentityHashMap

/**
 * Does this document match — asked once, by the code that built the index.
 *
 * The recheck of an index candidate and the scan of an uncovered segment are both this class, which
 * is what makes `CLAUDE.md`'s rule a fact rather than an intention: *the recheck runs the same walk
 * that built the index*, so "does this document match" is answered by the code that decided what to
 * index. A second, differently-shaped traversal would be a second definition of what a path means,
 * and the two would eventually disagree about an array or a nested null.
 *
 * **One walk per document, not one per leaf.** A single [TermExtractor] carries every distinct path
 * the predicate mentions, and its candidate narrowing prunes any subtree none of them reaches — so a
 * three-leaf predicate over shallow paths costs three field comparisons per document rather than
 * three traversals. That is also the difference from `IndexQuery`, which builds an extractor per
 * path per call, and from `ColumnQuery.satisfies`, which builds one per document.
 *
 * Not thread-safe: it reuses one array across documents. One matcher per cursor.
 */
internal class DocumentMatcher(private val normal: Normal, options: IndexOptions) {

    private val leaves: List<Normal.Leaf> = normal.leaves()
    private val paths = leaves.map { it.path }.distinct()
    private val extractor = TermExtractor(paths, options)

    /** Leaf indices by path index, so one reported value is offered only to the leaves that want it. */
    private val leavesOfPath: Array<IntArray> = Array(paths.size) { pathIndex ->
        leaves.indices.filter { leaves[it].path == paths[pathIndex] }.toIntArray()
    }

    /** By identity, not by value: two structurally equal leaves are still two leaves of the tree. */
    private val indexOfLeaf = IdentityHashMap<Normal.Leaf, Int>(leaves.size).apply {
        leaves.forEachIndexed { index, leaf -> put(leaf, index) }
    }

    private val satisfied = BooleanArray(leaves.size)

    /** Whether [document] satisfies the predicate. */
    fun matches(document: Variant): Boolean {
        if (leaves.isEmpty()) return fold(normal)
        satisfied.fill(false)
        extractor.extract(document) { pathIndex, value ->
            for (leaf in leavesOfPath[pathIndex]) {
                // A leaf is existential over the values at its path, so the first value that
                // satisfies it settles it — and `negated` is applied to that answer, at the end,
                // for the document rather than for the value.
                if (!satisfied[leaf] && leaves[leaf].test(value)) satisfied[leaf] = true
            }
        }
        return fold(normal)
    }

    private fun fold(node: Normal): Boolean = when (node) {
        Normal.AlwaysTrue -> true
        Normal.AlwaysFalse -> false
        is Normal.Conjunction -> node.operands.all(::fold)
        is Normal.Disjunction -> node.operands.any(::fold)
        is Normal.Leaf -> satisfied[checkNotNull(indexOfLeaf[node]) { "$node is not a leaf of this tree" }] !=
            node.negated
    }
}
