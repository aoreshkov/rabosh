package app.oreshkov.rabosh

/**
 * Marks a declaration as outside the stable core: it may change or disappear in any release, with no
 * deprecation cycle.
 *
 * `STABILITY.md` holds the tiers and the promise attached to each. The short version is that the
 * stable core is implicit — the surface `Rabosh`, `Query`, `Key`, `Variant` and the samples actually
 * use — and everything reached *past* it is marked with this. That is not a demotion of the marked
 * declarations; it is the accurate statement, and it is what makes the unmarked list mean something.
 * "Major version zero, anything may change" is honest and unactionable: a consumer cannot tell
 * whether `Key.of` is as volatile as `IndexCatalog.readColumn`, so the rational response is to wrap
 * all of the API or none of it.
 *
 * **What gets marked is the way *in*, not every member.** A consumer holding a `ColumnReader` had to
 * pass through `Rabosh.indexCatalog` or `IndexCatalog.readColumn` to get one, and both of those are
 * marked — so the reader's own methods carry nothing. Marking every member instead would
 * cost around a hundred and fifty annotations and, worse, would force every stable signature naming
 * an experimental *type* to be marked as well, which is a cascade that ends with the stable core
 * inside the experimental tier. Gate the entrances.
 *
 * The counterpart rule, and the reason a few classes here are deliberately **not** marked: a type
 * named by a stable signature cannot be marked without dragging that signature in with it.
 * `SegmentObserver` is the case worth knowing — `RaboshOptions` takes one, so marking the interface
 * would make constructing `RaboshOptions` require opt-in. It is a supported seam and it is stable.
 *
 * It lives in `rabosh-variant` because that is the only module every other one can see; a marker in
 * `rabosh-api` could not be applied in `rabosh-index`, which is the dependency edge this project
 * does not have. The package is `app.oreshkov.rabosh` rather than `…rabosh.variant` because it
 * belongs to the project rather than to the Variant codec.
 *
 * `ERROR` rather than a warning: reaching past the stable core is a decision, and a warning in a
 * build that does not fail on warnings is a line nobody reads.
 *
 * ```kotlin
 * @OptIn(RaboshExperimental::class)
 * fun dumpPostings(db: Rabosh) { … }              // one function
 *
 * // or, for a module that lives down there:
 * kotlin { compilerOptions { optIn.add("app.oreshkov.rabosh.RaboshExperimental") } }
 * ```
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This is outside rabosh's stable core: it may change or be removed in any release, " +
        "with no deprecation cycle. Opt in with @OptIn(RaboshExperimental::class) — or module-wide " +
        "via the compiler's opt-in option — and see STABILITY.md for what each tier promises.",
)
// BINARY is what an opt-in marker is required to have: the compiler reads it from the class file of
// a dependency, and nothing reads it at run time.
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER,
    AnnotationTarget.TYPEALIAS,
)
public annotation class RaboshExperimental
