package app.oreshkov.rabosh.variant

/**
 * Base class for every failure raised by the Variant codec.
 *
 * Sealed, so a caller can exhaustively distinguish *the bytes are unreadable* from *the caller
 * asked the wrong question* — those two want very different handling, and collapsing them into
 * one `RuntimeException` is how a corrupt-file report ends up looking like a bug in the query.
 */
public sealed class VariantException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * The bytes are not a Variant value this implementation can read.
 *
 * Raised for a truncated value, an out-of-range offset, a field id that is not in the dictionary,
 * a metadata version beyond this implementation, and an unrecognised primitive type id. The
 * project rule is that unknown data is *signalled*, never defaulted: an unreadable byte means the
 * file cannot be read by this version, not that the value is absent.
 *
 * @property offset byte offset, relative to the start of the segment being decoded, at which
 *   decoding failed. `-1` when no single byte is to blame.
 */
public class VariantFormatException(
    message: String,
    public val offset: Long = -1,
    cause: Throwable? = null,
) : VariantException(
    if (offset >= 0) "$message (at byte offset $offset)" else message,
    cause,
)

/**
 * A well-formed value was asked for a type it does not hold — `longValue()` on a string, or a
 * field lookup on an array.
 *
 * This is a programming error, not a data error. Use [Variant.kind] to branch instead of catching
 * this.
 */
public class VariantTypeException(message: String) : VariantException(message)

/**
 * JSON input is malformed.
 *
 * The position is always reported, because "invalid JSON" without a position is useless against a
 * multi-megabyte ingest payload.
 *
 * @property offset zero-based byte offset into the UTF-8 input.
 * @property line one-based line number.
 * @property column one-based column, **counted in bytes** rather than code points. Bytes are what
 *   the parser has; for the ASCII structure of JSON the two agree, and inside a multi-byte string
 *   an exact byte position is more useful for debugging than an approximate character one.
 */
public class JsonParseException(
    message: String,
    public val offset: Int,
    public val line: Int,
    public val column: Int,
) : VariantException("$message at line $line, column $column (byte offset $offset)")

/**
 * A Variant value has no JSON representation.
 *
 * Raised only by the JSON writer, and only for values JSON genuinely cannot express: a non-finite
 * `double`, or nesting deeper than the writer's guard allows.
 */
public class JsonWriteException(message: String) : VariantException(message)
