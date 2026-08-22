package app.oreshkov.rabosh.catalog

/**
 * The RFC 9535 construct that a rabosh path type has no step for.
 *
 * Carried by [PathNotRepresentableException] so that a caller can branch on *what* it was without
 * matching on a message. The values name constructs of the source grammar, not of this one — there
 * is deliberately no `WILDCARD`, because a wildcard is the one construct
 * [CatalogPath.parseJsonPath] accepts.
 */
public enum class PathConstruct {
    /** `[0]`, `[-1]` — names one element by position. A catalog path collapses positions. */
    INDEX,

    /**
     * `..` — selects at every depth.
     *
     * **No longer raised.** It was, when every catalog step was a child step;
     * [CatalogStep.AnyDescendant] is that step and [CatalogPath.parseJsonPath] now reads `..` into
     * it. The entry stays because removing it from an enum a caller may `when` over is a source
     * break with nothing to buy it, and because the shape of a refusal is worth keeping legible:
     * this is what it looked like for the one construct that stopped being one.
     *
     * A caller matching on it is not wrong, merely unreachable. `$..` with no selector after it is
     * still refused — as *malformed*, since RFC 9535 has no such query — and that is a different
     * failure from this one.
     */
    DESCENDANT,

    /** `[?…]` — selects by a test on the candidate node, which a `Predicate` cannot mean. */
    FILTER,

    /** `[1:3]`, `[::2]` — a slice with a bound or a step. Only the whole-array `[:]` is accepted. */
    SLICE,

    /** `['a','b']` — one segment selecting two things. A path step selects one. */
    MULTIPLE_SELECTORS,
}

/**
 * The expression is well formed and names something this path type cannot hold.
 *
 * **The distinction this exists to make is between a typo and a limit**, and it is invisible to a
 * caller today: `CatalogPath.parse`, `VariantPath.parse` and `JsonPathQuery.compile` all report
 * every failure as an `IllegalArgumentException`, so "you misspelled the field" and "a filter
 * selects documents, not positions" arrive as the same type and differ only in prose. A CLI that
 * wants to answer the first with *fix your path* and the second with *use the other flag* has to
 * match on the message, and matching on a message is not matching.
 *
 * `JsonPathLimitExceededException` made the same argument one case earlier — it is distinct so that
 * *too expensive* can be told from *malformed* and from *will not decode* — and closes with the line
 * this class is the fourth application of: separating them by message is not separating them.
 *
 * **Why a subclass of `IllegalArgumentException` and not a new hierarchy.** Every module here has a
 * `sealed` base — `CatalogException`, `VariantException`, `IndexException`, `StoreException` — and
 * none of them can be joined from outside its module, which is a property `CatalogException`'s own
 * documentation asks to keep. More to the point, a *supertype* cannot be retrofitted under an
 * exception callers already catch, and callers already catch this one: every existing
 * `catch (IllegalArgumentException)` and `catch (RuntimeException)` around a path parse keeps
 * working unchanged, and a caller that wants the distinction opts in by catching the narrower type
 * first. Subclassing is the additive direction; reparenting is not.
 *
 * **Not part of any module's sealed hierarchy on purpose.** Those describe state a store got into.
 * This describes an argument the caller passed, which is what `IllegalArgumentException` is for.
 *
 * @property construct the construct that could not be represented.
 */
public class PathNotRepresentableException internal constructor(
    public val construct: PathConstruct,
    message: String,
) : IllegalArgumentException(message)
