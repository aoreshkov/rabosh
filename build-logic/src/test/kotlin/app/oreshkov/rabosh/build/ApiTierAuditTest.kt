package app.oreshkov.rabosh.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The tier audit, against fixtures rather than against the repository.
 *
 * **A check that reports nothing has to be shown capable of reporting something**, which is most of
 * this file: every "clean" case sits beside the same shape with one thing changed so that it leaks.
 * An audit whose parser quietly stopped understanding the codebase would go green for ever, and going
 * green is the only thing anybody reads it for.
 */
class ApiTierAuditTest {

    @TempDir
    lateinit var root: File

    // --- reading the sources ----------------------------------------------------------------------

    @Test
    fun `a marker on a type, a member and a constructor is found in each spelling`() {
        module(
            "rabosh-index",
            "Bitmap.kt" to """
                package app.oreshkov.rabosh.index

                import app.oreshkov.rabosh.RaboshExperimental

                @RaboshExperimental
                public class Bitmap private constructor(private val keys: IntArray) {
                    public fun cardinality(): Int = 0
                }
            """,
            "IndexCatalog.kt" to """
                package app.oreshkov.rabosh.index

                public class IndexCatalog @RaboshExperimental constructor(
                    public val directory: String,
                ) {
                    /** Doc mentioning @RaboshExperimental should not count. */
                    @RaboshExperimental
                    public fun read(handle: Int): Bitmap = Bitmap()

                    public fun indexes(): List<Int> = emptyList()
                }
            """,
        )

        val tier = ApiTierAudit.markedIn(listOf(File(root, "rabosh-index")))

        assertEquals(setOf("Bitmap"), tier.types)
        assertEquals(setOf("IndexCatalog.constructor", "IndexCatalog.read"), tier.members)
    }

    /** An import and an `@OptIn` both contain the word and neither applies it. */
    @Test
    fun `mentioning the marker is not applying it`() {
        module(
            "rabosh-core",
            "DocumentStore.kt" to """
                package app.oreshkov.rabosh.core

                import app.oreshkov.rabosh.RaboshExperimental

                /**
                 * See [RaboshExperimental] for what this means.
                 */
                @OptIn(RaboshExperimental::class)
                public class DocumentStore {
                    public fun put(key: String) {}
                }
            """,
        )

        assertTrue(ApiTierAudit.markedIn(listOf(File(root, "rabosh-core"))).isEmpty)
    }

    /**
     * A marked member is attributed to the type it is **inside**, not to the last one declared.
     *
     * The shape is `IndexCatalog`'s, and it is here because the first version of this scanner got it
     * wrong: it took the most recent type declaration, so `read` came out as
     * `CountedObservation.read` and the audit reported a marked member as a leak. A false positive is
     * what gets a gate switched off, so the sibling case is pinned rather than assumed.
     */
    @Test
    fun `a member is attributed to the type it is inside, not the last one declared`() {
        module(
            "rabosh-index",
            "IndexCatalog.kt" to """
                package app.oreshkov.rabosh.index

                public class IndexCatalog {

                    private class CountedObservation(private val count: Int) {
                        fun observe() {}
                    }

                    @RaboshExperimental
                    public fun read(handle: Int): Bitmap = Bitmap()
                }
            """,
        )

        val tier = ApiTierAudit.markedIn(listOf(File(root, "rabosh-index")))
        assertEquals(setOf("IndexCatalog.read"), tier.members)
    }

    /** A companion's members belong to the outer type, because that is where the dump puts them. */
    @Test
    fun `a companion member is attributed to its outer type`() {
        module(
            "rabosh-core",
            "DocumentStore.kt" to """
                package app.oreshkov.rabosh.core

                public class DocumentStore {

                    private class Pinned(val live: Set<Long>)

                    public companion object {
                        @RaboshExperimental
                        public fun open(directory: String): DocumentStore = DocumentStore()
                    }
                }
            """,
        )

        val tier = ApiTierAudit.markedIn(listOf(File(root, "rabosh-core")))
        assertEquals(setOf("DocumentStore.open"), tier.members)
    }

    /**
     * A spelling the scanner cannot attribute is a **failure**, never a shorter set.
     *
     * This is the direction that matters. An audit that silently dropped an annotation would build a
     * short experimental set and then report no leaks against it — passing, for the worst reason.
     */
    @Test
    fun `an annotation the scanner cannot attribute fails loudly`() {
        module(
            "rabosh-core",
            "Odd.kt" to """
                package app.oreshkov.rabosh.core

                public class Odd {
                    @RaboshExperimental
                }
            """,
        )

        val failure = assertThrows<IllegalStateException> {
            ApiTierAudit.markedIn(listOf(File(root, "rabosh-core")))
        }
        assertTrue("could not attribute" in failure.message!!, failure.message)
        assertTrue("Odd.kt" in failure.message!!, failure.message)
    }

    // --- reading the dumps ------------------------------------------------------------------------

    private val tier = ApiTierAudit.Tier(
        types = setOf("Bitmap", "ColumnReader"),
        members = setOf("Rabosh.store", "IndexCatalog.read", "DocumentStore.open", "SchemaCatalog.constructor"),
    )

    @Test
    fun `a marked member returning an experimental type is not a leak`() {
        val dump = dump(
            "rabosh-index",
            """
            public final class app/oreshkov/rabosh/index/IndexCatalog {
            	public final fun read (I)Lapp/oreshkov/rabosh/index/Bitmap;
            	public final fun indexes ()Ljava/util/List;
            }
            """,
        )
        assertEquals(emptyList<ApiTierAudit.Leak>(), ApiTierAudit.leaks(dump, tier))
    }

    /** The same dump with the marker taken off `read`: the audit must now see it. */
    @Test
    fun `an unmarked member returning an experimental type is a leak`() {
        val dump = dump(
            "rabosh-index",
            """
            public final class app/oreshkov/rabosh/index/IndexCatalog {
            	public final fun read (I)Lapp/oreshkov/rabosh/index/Bitmap;
            }
            """,
        )
        val leaks = ApiTierAudit.leaks(dump, ApiTierAudit.Tier(tier.types, emptySet()))

        assertEquals(1, leaks.size, leaks.toString())
        assertEquals("IndexCatalog", leaks.single().owner)
        assertEquals("read", leaks.single().member)
        assertEquals("Bitmap", leaks.single().referenced)
    }

    /** A member *taking* an experimental type leaks exactly as one returning it does. */
    @Test
    fun `an experimental parameter is a leak too`() {
        val dump = dump(
            "rabosh-query",
            """
            public final class app/oreshkov/rabosh/query/Plan {
            	public final fun intersect (Lapp/oreshkov/rabosh/index/Bitmap;)V
            }
            """,
        )
        assertEquals(1, ApiTierAudit.leaks(dump, tier).size)
    }

    /** Everything inside an experimental class is already behind the opt-in that got you there. */
    @Test
    fun `members of an experimental class are not leaks`() {
        val dump = dump(
            "rabosh-index",
            """
            public final class app/oreshkov/rabosh/index/Bitmap {
            	public final fun copy ()Lapp/oreshkov/rabosh/index/Bitmap;
            	public final fun reader ()Lapp/oreshkov/rabosh/index/ColumnReader;
            }
            """,
        )
        assertEquals(emptyList<ApiTierAudit.Leak>(), ApiTierAudit.leaks(dump, tier))
    }

    /** An unmarked class *implementing* an experimental interface is a leak on the header line. */
    @Test
    fun `an experimental supertype is a leak`() {
        val dump = dump(
            "rabosh-index",
            """
            public final class app/oreshkov/rabosh/index/Mask : app/oreshkov/rabosh/index/Bitmap {
            	public final fun size ()I
            }
            """,
        )
        val leaks = ApiTierAudit.leaks(dump, tier)
        assertEquals(1, leaks.size, leaks.toString())
        assertEquals("(supertype)", leaks.single().member)
    }

    /**
     * The three spellings the dump and the sources disagree on, each pinned.
     *
     * A property is `getStore` against `store`; a constructor is `<init>` against `constructor`; a
     * default-argument bridge is `read$default` against `read`. Getting any of them wrong makes the
     * audit report a marked declaration as a leak, which is the failure that gets a gate switched off.
     */
    @Test
    fun `the dump's spellings are normalised to the source's`() {
        val dump = dump(
            "rabosh-api",
            """
            public final class app/oreshkov/rabosh/api/Rabosh {
            	public final fun getStore ()Lapp/oreshkov/rabosh/index/Bitmap;
            }

            public final class app/oreshkov/rabosh/catalog/SchemaCatalog {
            	public fun <init> (Lapp/oreshkov/rabosh/index/Bitmap;)V
            }

            public final class app/oreshkov/rabosh/index/IndexCatalog {
            	public static synthetic fun read${'$'}default (Lapp/oreshkov/rabosh/index/Bitmap;)Lapp/oreshkov/rabosh/index/Bitmap;
            }
            """,
        )
        assertEquals(emptyList<ApiTierAudit.Leak>(), ApiTierAudit.leaks(dump, tier))
    }

    /** A companion's members appear on `Foo${'$'}Companion` and are declared inside `Foo`. */
    @Test
    fun `a companion member matches a marker attributed to its outer type`() {
        val dump = dump(
            "rabosh-core",
            """
            public final class app/oreshkov/rabosh/core/DocumentStore${'$'}Companion {
            	public final fun open (Ljava/lang/String;)Lapp/oreshkov/rabosh/index/Bitmap;
            }
            """,
        )
        assertEquals(emptyList<ApiTierAudit.Leak>(), ApiTierAudit.leaks(dump, tier))
    }

    /** A signature naming nothing experimental is never a leak, however much else it names. */
    @Test
    fun `an ordinary signature is not a leak`() {
        val dump = dump(
            "rabosh-core",
            """
            public final class app/oreshkov/rabosh/core/Key {
            	public final fun successor ()Lapp/oreshkov/rabosh/core/Key;
            	public static final fun of (Ljava/lang/String;)Lapp/oreshkov/rabosh/core/Key;
            }
            """,
        )
        assertEquals(emptyList<ApiTierAudit.Leak>(), ApiTierAudit.leaks(dump, tier))
    }

    /** With nothing marked there is nothing to leak, and the audit says so rather than scanning. */
    @Test
    fun `an empty tier reports nothing`() {
        val dump = dump(
            "rabosh-index",
            """
            public final class app/oreshkov/rabosh/index/IndexCatalog {
            	public final fun read (I)Lapp/oreshkov/rabosh/index/Bitmap;
            }
            """,
        )
        assertEquals(emptyList<ApiTierAudit.Leak>(), ApiTierAudit.leaks(dump, ApiTierAudit.Tier(emptySet(), emptySet())))
    }

    /** A missing dump is not a failure here: `checkKotlinAbi` owns that, and owning it twice is worse. */
    @Test
    fun `a module with no dump is skipped`() {
        assertEquals(
            emptyList<ApiTierAudit.Leak>(),
            ApiTierAudit.leaks(mapOf("rabosh-ghost" to File(root, "nowhere.api")), tier),
        )
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private fun module(name: String, vararg sources: Pair<String, String>) {
        val directory = File(root, "$name/src/main/kotlin/app/oreshkov/rabosh")
        directory.mkdirs()
        for ((file, text) in sources) File(directory, file).writeText(text.trimIndent())
    }

    private fun dump(module: String, text: String): Map<String, File> {
        val file = File(root, "$module.api")
        file.writeText(text.trimIndent())
        return mapOf(module to file)
    }
}
