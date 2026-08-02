package app.oreshkov.rabosh.variant

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64

/**
 * Renders this value as JSON text.
 *
 * JSON is the format the engine ingests, not the format it stores, and the mapping back is not
 * total: a `date`, a `timestamp`, a `uuid` and a `binary` all become JSON strings — ISO-8601 for
 * the temporal types, base64 for binary — because JSON has nowhere else to put them. Every type
 * that arrived *through* JSON returns unchanged, which is the roundtrip the codec guarantees; the
 * rest is an interop and debugging convenience, and reading the result back yields strings.
 *
 * @throws JsonWriteException for a non-finite `double`, which JSON cannot express, or for nesting
 *   deeper than [DEFAULT_MAX_JSON_DEPTH].
 * @throws VariantFormatException if the bytes do not decode.
 */
public fun Variant.toJsonString(): String = buildString { appendJsonTo(this) }

/** Appends [Variant.toJsonString] to [out], avoiding an intermediate `String`. */
public fun Variant.appendJsonTo(out: StringBuilder) {
    writeJson(this, out, 0)
}

private fun writeJson(variant: Variant, out: StringBuilder, depth: Int) {
    if (depth > DEFAULT_MAX_JSON_DEPTH) {
        throw JsonWriteException("value nests deeper than $DEFAULT_MAX_JSON_DEPTH")
    }
    when (variant.kind) {
        VariantKind.ARRAY -> {
            out.append('[')
            val count = variant.elementCount
            for (index in 0 until count) {
                if (index > 0) out.append(',')
                writeJson(variant.element(index), out, depth + 1)
            }
            out.append(']')
        }

        VariantKind.OBJECT -> {
            out.append('{')
            val count = variant.fieldCount
            for (index in 0 until count) {
                if (index > 0) out.append(',')
                out.appendJsonString(variant.fieldName(index))
                out.append(':')
                writeJson(variant.fieldValue(index), out, depth + 1)
            }
            out.append('}')
        }

        else -> writeJsonScalar(variant, out)
    }
}

/**
 * Writes the JSON spelling of a value that is not a container.
 *
 * One implementation, two callers — [writeJson] and the summary writer in `VariantSummary.kt` —
 * shared for the same reason `describe` is shared in `rabosh-index`: a document and a summary of
 * that document must not be able to render the same scalar differently.
 *
 * @param stringLimit passed through to [appendJsonString]. It bounds a string, a base64 binary and
 *   an ISO-8601 temporal alike, since all three are written as JSON strings; the four that append
 *   directly are bounded by their own types, a decimal most widely at [MAX_DECIMAL_PRECISION]
 *   digits.
 * @throws VariantTypeException if [variant] is an object or an array.
 */
internal fun writeJsonScalar(variant: Variant, out: StringBuilder, stringLimit: Int = Int.MAX_VALUE) {
    when (variant.kind) {
        VariantKind.NULL -> out.append("null")
        VariantKind.BOOLEAN -> out.append(variant.booleanValue())
        VariantKind.INTEGER -> out.append(variant.longValue())
        VariantKind.DECIMAL -> out.append(variant.decimalValue().toString())
        VariantKind.STRING -> out.appendJsonString(variant.stringValue(), stringLimit)

        VariantKind.FLOAT, VariantKind.DOUBLE -> {
            val value = variant.doubleValue()
            if (!value.isFinite()) {
                throw JsonWriteException("$value has no JSON representation")
            }
            // Rendered through the stored width: a float printed as a double gains digits it never
            // had (0.1f would become 0.10000000149011612).
            if (variant.kind == VariantKind.FLOAT) out.append(value.toFloat()) else out.append(value)
        }

        VariantKind.BINARY ->
            out.appendJsonString(Base64.getEncoder().encodeToString(variant.binaryValue()), stringLimit)

        VariantKind.DATE -> out.appendJsonString(variant.renderTemporal(), stringLimit)
        VariantKind.TIME, VariantKind.TIMESTAMP -> out.appendJsonString(variant.renderTemporal(), stringLimit)
        VariantKind.UUID -> out.appendJsonString(variant.uuidString(), stringLimit)

        VariantKind.ARRAY, VariantKind.OBJECT ->
            throw VariantTypeException("${variant.kind} is a container, not a scalar")
    }
}

private fun Variant.uuidString(): String = uuidValue().toString()

private const val MICROS_PER_SECOND = 1_000_000L
private const val NANOS_PER_MICRO = 1_000L
private const val NANOS_PER_SECOND = 1_000_000_000L

/** ISO-8601, which is the only interchange form for these that a JSON consumer will recognise. */
private fun Variant.renderTemporal(): String = try {
    when (val type = primitiveType) {
        VariantPrimitiveType.DATE -> LocalDate.ofEpochDay(epochDay().toLong()).toString()
        VariantPrimitiveType.TIME_NTZ -> LocalTime.ofNanoOfDay(temporalValue() * NANOS_PER_MICRO).toString()

        VariantPrimitiveType.TIMESTAMP_TZ ->
            OffsetDateTime.ofInstant(instantOf(temporalValue(), MICROS_PER_SECOND, NANOS_PER_MICRO), ZoneOffset.UTC)
                .toString()

        VariantPrimitiveType.TIMESTAMP_NTZ ->
            LocalDateTime.ofInstant(instantOf(temporalValue(), MICROS_PER_SECOND, NANOS_PER_MICRO), ZoneOffset.UTC)
                .toString()

        VariantPrimitiveType.TIMESTAMP_NANOS_TZ ->
            OffsetDateTime.ofInstant(instantOf(temporalValue(), NANOS_PER_SECOND, 1), ZoneOffset.UTC).toString()

        VariantPrimitiveType.TIMESTAMP_NANOS_NTZ ->
            LocalDateTime.ofInstant(instantOf(temporalValue(), NANOS_PER_SECOND, 1), ZoneOffset.UTC).toString()

        else -> throw VariantTypeException("$type is not a temporal value")
    }
} catch (failure: DateTimeException) {
    throw JsonWriteException("temporal value is outside the range ISO-8601 can express: ${failure.message}")
}

private fun instantOf(value: Long, unitsPerSecond: Long, nanosPerUnit: Long): Instant {
    val seconds = Math.floorDiv(value, unitsPerSecond)
    val fraction = Math.floorMod(value, unitsPerSecond)
    return Instant.ofEpochSecond(seconds, fraction * nanosPerUnit)
}

/** Form feed, `U+000C`. Kotlin has no `\f` char escape, so the code point is named here. */
private val FORM_FEED: Char = 0x0C.toChar()

/**
 * Appends [value] as an escaped JSON string literal.
 *
 * @param limit characters to write before eliding the rest with `…`. The cut lands on a code-point
 *   boundary — a lone surrogate has no UTF-8 encoding, so splitting a pair here would produce text
 *   that cannot survive being written out. Same rule as the code-point cut in `rabosh-index`'s
 *   `ColumnFile.truncateLow`, in characters rather than bytes.
 *
 *   Escaping happens *after* the cut, never before, and that ordering is the point of doing the
 *   truncation here rather than in a caller: post-truncating already-escaped text can land inside a
 *   `\u00xx` and emit a literal that does not close.
 *
 *   [Int.MAX_VALUE] writes all of it, with no sentinel and no special case.
 *
 *   (`rabosh-testkit`'s `JsonGens.safeTake` makes the same cut. It cannot be shared — the testkit
 *   depends on this module, not the reverse — and should not be: one shortens a generated fixture,
 *   this one elides for a reader.)
 */
internal fun StringBuilder.appendJsonString(value: String, limit: Int = Int.MAX_VALUE) {
    var cut = minOf(limit, value.length)
    if (cut > 0 && cut < value.length && value[cut - 1].isHighSurrogate()) cut--

    append('"')
    for (index in 0 until cut) {
        when (val character = value[index]) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            FORM_FEED -> append("\\f")
            else ->
                // JSON forbids literal control characters; everything else, including non-ASCII,
                // is written through unescaped, since the output is UTF-8 by definition.
                if (character < ' ') {
                    append("\\u").append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
        }
    }
    if (cut < value.length) append(ELISION)
    append('"')
}
