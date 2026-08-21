package app.oreshkov.rabosh.query

import app.oreshkov.rabosh.index.ElementExtractor
import app.oreshkov.rabosh.index.IndexOptions
import app.oreshkov.rabosh.index.TermExtractor
import app.oreshkov.rabosh.variant.Variant
import java.util.IdentityHashMap

/**
 * Does this document match — asked once, by the code that built the index.
 *
 * The recheck of an index candidate and the scan of an uncovered segment are both this class, which
 * is what makes the rule in `.claude/rules/index-and-query.md` a fact rather than an intention: *the
 * recheck runs the same walk that built the index*, so "does this document match" is answered by the
 * code that decided what to
 * index. A second, differently-shaped traversal would be a second definition of what a path means,
 * and the two would eventually disagree about an array or a nested null.
 *
 * **Same walk, and deliberately no budget on it** — `TermExtractor.reading`, not the constructor the
 * writer uses. This one decides answers, so a bound firing here would make what a predicate *means*
 * depend on a number chosen to keep compaction cheap; and because a segment whose index build hit a
 * budget is left uncovered, the documents an index answers for are exactly the documents on which the
 * two walks visit the same children. The [IndexOptions] is still carried: `maxTermBytes` is the one
 * bound the writer and the reader apply to the same bytes, and `lower` needs the rest.
 *
 * **One walk per document, not one per leaf.** A single [TermExtractor] carries every distinct path
 * the predicate mentions, and its candidate narrowing prunes any subtree none of them reaches — so a
 * three-leaf predicate over shallow paths costs three field comparisons per document rather than
 * three traversals. That is also the difference from `IndexQuery`, which builds an extractor per
 * path per call, and from `ColumnQuery.satisfies`, which builds one per document.
 *
 * **An `elemMatch` is one more level of exactly the same thing.** Its paths read an *element*, so
 * they cannot join the document's walk; instead each [Normal.Element] gets an [ElementExtractor] over
 * its container path and a **nested `DocumentMatcher`** over its operand. The composite index builds
 * its terms with those same two classes over the same options, so the recheck rule holds one level
 * down as well — and it holds recursively, because a nested matcher is this class.
 *
 * Not thread-safe: it reuses one array across documents. One matcher per cursor.
 */
internal class DocumentMatcher(private val normal: Normal, private val options: IndexOptions) {

    /** Every leaf of the tree, in order. Exposed for `Explain`, which reports on all of them and
     * not only on the ones an index answered — a path with no index is where a caller has no other
     * signal at all. */
    internal val leaves: List<Normal.Leaf> = normal.leaves()
    private val paths = leaves.map { it.path }.distinct()
    private val extractor = TermExtractor.reading(paths)

    /** Leaf indices by path index, so one reported value is offered only to the leaves that want it. */
    private val leavesOfPath: Array<IntArray> = Array(paths.size) { pathIndex ->
        leaves.indices.filter { leaves[it].path == paths[pathIndex] }.toIntArray()
    }

    /** By identity, not by value: two structurally equal leaves are still two leaves of the tree. */
    private val indexOfLeaf = IdentityHashMap<Normal.Leaf, Int>(leaves.size).apply {
        leaves.forEachIndexed { index, leaf -> put(leaf, index) }
    }

    private val satisfied = BooleanArray(leaves.size)

    /**
     * One walk and one nested matcher per element node, built once rather than per document.
     *
     * Empty for every predicate that does not correlate, which is why nothing here costs anything to
     * a query that never asks: the array is empty, the fold never reaches an element branch, and the
     * document's own walk is the one it always was.
     */
    private val elementNodes: List<Normal.Element> = normal.elements()
    private val elementExtractor = ElementExtractor.reading(elementNodes.map { it.path })
    private val elementMatchers: List<DocumentMatcher> = elementNodes.map { DocumentMatcher(it.inner, options) }
    private val indexOfElement = IdentityHashMap<Normal.Element, Int>(elementNodes.size).apply {
        elementNodes.forEachIndexed { index, node -> put(node, index) }
    }
    private val elementSatisfied = BooleanArray(elementNodes.size)

    /** Whether [document] satisfies the predicate. */
    fun matches(document: Variant): Boolean {
        if (leaves.isEmpty() && elementNodes.isEmpty()) return fold(normal)
        satisfied.fill(false)
        elementSatisfied.fill(false)
        if (paths.isNotEmpty()) {
            extractor.extract(document) { pathIndex, value ->
                for (leaf in leavesOfPath[pathIndex]) {
                    // A leaf is existential over the values at its path, so the first value that
                    // satisfies it settles it — and `negated` is applied to that answer, at the end,
                    // for the document rather than for the value.
                    if (!satisfied[leaf] && leaves[leaf].test(value)) satisfied[leaf] = true
                }
            }
        }
        if (elementNodes.isNotEmpty()) {
            // Existential over *elements*, settled by the first one that satisfies the whole operand.
            // That is the difference from a conjunction of leaves, in one line.
            elementExtractor.extract(document) { nodeIndex, element ->
                if (!elementSatisfied[nodeIndex] && elementMatchers[nodeIndex].matches(element)) {
                    elementSatisfied[nodeIndex] = true
                }
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

        is Normal.Element ->
            elementSatisfied[checkNotNull(indexOfElement[node]) { "$node is not an element node of this tree" }] !=
                node.negated
    }
}
