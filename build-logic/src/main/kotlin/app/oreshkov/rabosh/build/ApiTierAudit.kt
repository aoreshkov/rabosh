package app.oreshkov.rabosh.build

import java.io.File

/**
 * Whether any declaration outside the experimental tier exposes a type that is inside it.
 *
 * **This exists because the compiler cannot do it, and the reason is worth stating precisely.**
 * `STABILITY.md` marks the entrances to the experimental tier rather than every member, and the
 * published modules opt in to their own marker module-wide so that the engine's internal use of its
 * own internals does not need several hundred `@OptIn`s. That opt-in is what blinds the compiler:
 * inside the library every use of an experimental type is permitted, *including a use in a public
 * signature that carries no marker of its own*. A consumer meeting that signature gets an
 * experimental type handed to them with nothing having asked them to opt in — the tier statement
 * quietly stops being true, and `checkKotlinAbi` cannot see it because the JVM dump format writes
 * signatures and never annotations.
 *
 * The gap is real rather than theoretical: it was found by hand in phase 23 with four leaks in it,
 * and phase 24 added public surface to every module in the chain with nothing but a script in a
 * private directory standing behind the claim. This is that script, promoted to a gate.
 *
 * **Both halves are derived, neither is listed.** The experimental set comes from the *sources* —
 * every `@RaboshExperimental` and the declaration under it — and the surface comes from the
 * *committed ABI dumps*. A hand-maintained list of experimental types in a build script would be a
 * second list free to disagree with the annotations, and it would disagree exactly once, silently,
 * in the direction of not reporting a leak. That is the [PublishedModules] rule applied to a
 * different question.
 *
 * **A scanner that does not understand an annotation is the defect this class exists to prevent, one
 * level up**, so [markedIn] counts the annotations it found against the declarations it attributed
 * and reports the difference rather than carrying on with a short set. Under-reporting is the only
 * failure mode that matters here: an audit that misses a leak passes, and passing is what it is
 * consulted for.
 *
 * Plain Kotlin over [File] with unit tests, for the reason [BenchmarkRunReport] and
 * [CentralBundleReport] are: an included build's tests are run by `./gradlew -p build-logic check`,
 * and a decision that fails the build is worth testing.
 */
object ApiTierAudit {

    /** The opt-in marker, by simple name. Its package is not needed: nothing else is called this. */
    const val MARKER: String = "RaboshExperimental"

    /** What the sources say is experimental. */
    class Tier(
        /** Simple names of types the marker is on. */
        val types: Set<String>,
        /** `Type.member` for members the marker is on; a constructor is spelled `Type.constructor`. */
        val members: Set<String>,
    ) {
        val isEmpty: Boolean get() = types.isEmpty() && members.isEmpty()

        override fun toString(): String = "Tier(${types.size} type(s), ${members.size} member(s))"
    }

    /** One public signature naming an experimental type without being marked itself. */
    class Leak(
        val module: String,
        val owner: String,
        val member: String,
        val referenced: String,
    ) {
        override fun toString(): String =
            "$module: $owner.$member exposes $referenced, which is @$MARKER, without being marked itself"
    }

    /**
     * The declarations [MARKER] is applied to, read from the Kotlin sources under [modules].
     *
     * Two spellings are recognised because both are in use: the annotation on its own line above a
     * declaration, and inline as `class Foo @RaboshExperimental constructor(`. A third would be a
     * silent hole, which is what [UnreadableMarker] is for.
     *
     * @throws IllegalStateException if an annotation was found that no declaration could be
     *   attributed to. See the class documentation: a short set is worse than a failure.
     */
    fun markedIn(modules: List<File>): Tier {
        val types = sortedSetOf<String>()
        val members = sortedSetOf<String>()
        val unreadable = ArrayList<String>()

        for (module in modules) {
            val sourceRoot = File(module, "src/main/kotlin")
            if (!sourceRoot.isDirectory) continue
            for (file in sourceRoot.walkTopDown()) {
                if (!file.isFile || file.extension != "kt") continue
                scan(file, types, members, unreadable)
            }
        }

        check(unreadable.isEmpty()) {
            "the tier audit found ${unreadable.size} @$MARKER annotation(s) it could not attribute to a " +
                "declaration, so its experimental set is incomplete and would under-report:\n" +
                unreadable.joinToString("\n") { "  $it" } +
                "\nTeach ApiTierAudit the spelling, or spell the declaration the way the others are."
        }
        return Tier(types, members)
    }

    /**
     * Every unmarked public signature in [dumps] that names a type in [tier].
     *
     * Empty is the passing answer. The check runs over the *committed* dumps rather than over
     * compiled classes because the dumps are the artefact `checkKotlinAbi` already maintains: a
     * signature that is not in them is not published, and one that is has been reviewed in a diff.
     */
    fun leaks(dumps: Map<String, File>, tier: Tier): List<Leak> {
        if (tier.isEmpty) return emptyList()
        val found = ArrayList<Leak>()
        for ((module, dump) in dumps) {
            if (!dump.isFile) continue
            var owner = ""
            var ownerNames = emptyList<String>()
            var ownerIsExperimental = false

            for (line in dump.readLines()) {
                if (line.isBlank() || line == "}") continue
                if (!line.startsWith("\t")) {
                    owner = binaryNameOf(line) ?: ""
                    ownerNames = simpleNamesOf(owner)
                    ownerIsExperimental = ownerNames.any { it in tier.types }
                    // A supertype list is on this line too, so an unmarked class implementing an
                    // experimental interface is caught here rather than by any member below it.
                    // Matched as a *bare* binary name rather than as a descriptor: a header spells a
                    // supertype `: app/oreshkov/…/Bitmap`, with none of the `L…;` a signature has.
                    if (!ownerIsExperimental) {
                        for (referenced in experimentalTypesIn(line.substringAfter(owner), tier, descriptors = false)) {
                            found += Leak(module, simpleOwner(ownerNames), "(supertype)", referenced)
                        }
                    }
                    continue
                }
                if (ownerIsExperimental) continue

                val member = memberNameOf(line) ?: continue
                if (isMarked(ownerNames, member, tier)) continue
                for (referenced in experimentalTypesIn(line, tier)) {
                    found += Leak(module, simpleOwner(ownerNames), member, referenced)
                }
            }
        }
        return found.distinctBy { "${it.module}|${it.owner}|${it.member}|${it.referenced}" }
    }

    /** The published modules' dumps, by module name. The universe is [PublishedModules]'. */
    fun dumpsUnder(root: File): Map<String, File> =
        PublishedModules.under(root).associateWith { File(root, "$it/api/$it.api") }

    /** The published modules' directories. */
    fun modulesUnder(root: File): List<File> =
        PublishedModules.under(root).map { File(root, it) }

    // --- reading the sources ----------------------------------------------------------------------

    private val TYPE_DECLARATION =
        Regex("""^\s*(?:public |internal |private |protected )?(?:[\w@.]+ )*?(class|interface|object)\s+(\w+)""")

    private val FUNCTION_DECLARATION = Regex("""\bfun\s+(?:<[^>]*>\s*)?(\w+)\s*[(<]""")
    private val PROPERTY_DECLARATION = Regex("""\b(?:val|var)\s+(\w+)\s*[:=]""")
    private val INLINE_CONSTRUCTOR = Regex("""@$MARKER\s+constructor\s*\(""")

    /** One open type declaration and the column it was declared at. */
    private class Scope(val name: String, val indent: Int)

    /**
     * Scans one file, tracking which type each declaration is inside.
     *
     * **Nesting is followed by indentation rather than by counting braces**, and the choice is a
     * decision. Kotlin string templates put `{` and `}` inside string literals, so a brace counter
     * needs a lexer to be right and is wrong in a way nobody notices until it mis-attributes one
     * member. Indentation needs no lexer and is exact for any code laid out the way this repository's
     * is: a declaration at column *n* is inside the nearest type declared at a column below *n*.
     *
     * Getting this wrong was demonstrated rather than imagined — the first version took the *most
     * recent* type declaration, and `IndexCatalog.read` came out attributed to a `private class`
     * declared two hundred lines above it, which reports a marked member as a leak. A false positive
     * is what gets a gate switched off, so it is worth the ten lines.
     *
     * A `companion object` re-pushes its **outer** name: in a dump its members appear on
     * `Foo$Companion` and statically on `Foo`, and attributing them to `Foo` is what makes both
     * spellings match the one source declaration.
     */
    private fun scan(file: File, types: MutableSet<String>, members: MutableSet<String>, unreadable: MutableList<String>) {
        val lines = file.readLines()
        val scopes = ArrayList<Scope>()

        fun enclosing(indent: Int): String = scopes.lastOrNull { it.indent < indent }?.name.orEmpty()

        for ((index, line) in lines.withIndex()) {
            val indent = line.indexOfFirst { !it.isWhitespace() }
            if (indent >= 0) {
                val declared = typeNameOf(line)
                val companion = line.contains("companion object")
                if (declared != null || companion) {
                    while (scopes.isNotEmpty() && scopes.last().indent >= indent) scopes.removeLast()
                    scopes += Scope(declared ?: enclosing(indent), indent)
                }
            }

            if (!line.contains("@$MARKER")) continue
            val at = if (indent >= 0) indent else 0

            // Inline: `public class SchemaCatalog @RaboshExperimental constructor(`.
            if (INLINE_CONSTRUCTOR.containsMatchIn(line)) {
                val owner = typeNameOf(line) ?: enclosing(at)
                if (owner.isEmpty()) {
                    unreadable += "${file.name}:${index + 1} — inline constructor with no type on the line"
                } else {
                    members += "$owner.constructor"
                }
                continue
            }
            // An import, a KDoc reference, or an `@OptIn` naming it: not an application of it.
            if (!isAnnotationApplication(line)) continue

            val declaration = declarationAfter(lines, index)
            if (declaration == null) {
                unreadable += "${file.name}:${index + 1} — no declaration follows the annotation"
                continue
            }
            val type = typeNameOf(declaration)
            if (type != null) {
                types += type
                continue
            }
            val name = FUNCTION_DECLARATION.find(declaration)?.groupValues?.get(1)
                ?: PROPERTY_DECLARATION.find(declaration)?.groupValues?.get(1)
            // The declaration's own column, and the comparison is **strictly** less than it. A
            // sibling declared at the same column — `private class CountedObservation` beside
            // `public fun read` — is not an enclosing scope, and treating it as one is exactly the
            // mis-attribution this tracking exists to avoid.
            val owner = enclosing(declaration.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0))
            if (name == null || owner.isEmpty()) {
                unreadable += "${file.name}:${index + 1} — cannot name the declaration: ${declaration.trim()}"
            } else {
                members += "$owner.$name"
            }
        }
    }

    /**
     * Whether this line *applies* the marker rather than merely mentioning it.
     *
     * An `import`, a KDoc line and an `@OptIn(RaboshExperimental::class)` all contain the word. Only
     * the first token being the annotation means it is being applied here.
     */
    private fun isAnnotationApplication(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.startsWith("import ") || trimmed.startsWith("*") || trimmed.startsWith("//")) return false
        return trimmed.startsWith("@$MARKER")
    }

    /** The next line that is neither blank, a comment, nor another annotation. */
    private fun declarationAfter(lines: List<String>, from: Int): String? {
        for (index in from + 1 until lines.size) {
            val trimmed = lines[index].trim()
            if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("*") ||
                trimmed.startsWith("/*") || trimmed.startsWith("@")
            ) {
                continue
            }
            return lines[index]
        }
        return null
    }

    private fun typeNameOf(line: String): String? {
        if (line.contains("companion object")) return null
        val match = TYPE_DECLARATION.find(line) ?: return null
        // `object : Foo {` and `enum class` both land here; only a named declaration counts.
        return match.groupValues[2].takeIf { it.isNotEmpty() }
    }

    // --- reading the dumps ------------------------------------------------------------------------

    /** `public final class app/oreshkov/rabosh/index/Bitmap : … {` -> the binary name. */
    private fun binaryNameOf(header: String): String? =
        Regex("""\b(app/oreshkov/[\w/$]+)""").find(header)?.groupValues?.get(1)

    /** `app/oreshkov/rabosh/query/Predicate$And` -> `[Predicate, And]`. */
    private fun simpleNamesOf(binaryName: String): List<String> =
        binaryName.substringAfterLast('/').split('$').filter { it.isNotEmpty() }

    private fun simpleOwner(names: List<String>): String = names.joinToString(".").ifEmpty { "?" }

    /** `\tpublic final fun getStore ()L…;` -> `getStore`. Fields and constructors included. */
    private fun memberNameOf(line: String): String? =
        Regex("""\b(?:fun|field)\s+([\w$<>]+)""").find(line)?.groupValues?.get(1)

    /**
     * Whether [member] of a class named by [ownerNames] carries the marker in the sources.
     *
     * The dump's spelling and the source's differ in three ways and all three are normalised here: a
     * property is `getFoo`/`setFoo` against `foo`, a constructor is `<init>` against `constructor`,
     * and a default-argument bridge is `foo$default` against `foo`. Every enclosing name is tried, so
     * a member on `Foo$Companion` matches a source declaration attributed to `Foo`.
     */
    private fun isMarked(ownerNames: List<String>, member: String, tier: Tier): Boolean {
        val candidates = LinkedHashSet<String>()
        candidates += member
        candidates += member.substringBefore("\$default")
        if (member == "<init>") candidates += "constructor"
        for (prefix in listOf("get", "set")) {
            if (member.length > prefix.length && member.startsWith(prefix) && member[prefix.length].isUpperCase()) {
                candidates += member.removePrefix(prefix).replaceFirstChar { it.lowercase() }
            }
        }
        return ownerNames.any { owner -> candidates.any { "$owner.$it" in tier.members } }
    }

    /**
     * Every experimental type this line names, in order of appearance.
     *
     * Two spellings, because the dump has two. A *signature* carries JVM descriptors —
     * `Lapp/oreshkov/rabosh/index/Bitmap;` — and matching those with delimiters is what stops
     * `…/BitmapView` being read as `Bitmap`. A *class header* carries bare binary names after the
     * colon, with no `L` and no `;`, so the supertype case has to ask for the looser form; there the
     * `$`-split of a whole name is the boundary instead.
     */
    private fun experimentalTypesIn(line: String, tier: Tier, descriptors: Boolean = true): List<String> {
        val pattern = if (descriptors) Regex("""L(app/oreshkov/[\w/$]+);""") else Regex("""\b(app/oreshkov/[\w/$]+)""")
        return pattern.findAll(line)
            .flatMap { simpleNamesOf(it.groupValues[1]).asSequence() }
            .filter { it in tier.types }
            .distinct()
            .toList()
    }
}
