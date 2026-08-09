package app.oreshkov.rabosh.jsonpath

// A Thompson construction and the simulation that runs it. Five opcodes, no backtracking, no
// captures — and no `java.util.regex`.
//
// **The reason for writing this rather than translating is a security argument and not a purity
// one.** A filter is evaluated once per *document* over a corpus, so a pattern is applied a hundred
// thousand times and the subject may be a megabyte of somebody's data. `java.util.regex` backtracks:
// `(a|aa)+b` against a run of `a`s takes exponential time, so translating would put a complexity
// attack behind a query — reachable through the *document* as well as the query, because RFC 9535
// lets the pattern be a `ValueType`. Checking the pattern cannot close that: the guarantee has to
// come from the matcher. RFC 9485 §§6-8 says as much when it advises detecting resource consumption
// rather than trusting the expression. This is another instance of this repository's "library or by
// hand" question — RoaringBitmap, simdjson-java and Kotest were declined, the HyperLogLog was written
// — and the first where the by-hand argument is a **security** one rather than a format-control one:
// the others turned on owning the bytes, this one on owning the worst case.
//
// **The guarantee, stated exactly.** A run visits each instruction at most once per input position,
// so it costs `O(instructions × code points)` and never more; `IRegexpTest` asserts that in
// **transitions** rather than in wall-clock, per `testing.md`'s rule about clocks. The instruction
// count is bounded at compile time by [MAX_INSTRUCTIONS], which is what stops `(a{1000}){1000}` from
// turning a linear guarantee into a large constant.
//
// **A run allocates its own state, which is what makes a compiled query thread-safe.** The thread
// lists and the visit marks are per call, sized by the program; nothing on the program itself is
// written after it is built. `JsonPathQuery` promises immutability and this is the only part of the
// module with a loop that could have broken it.

/** How many instructions one pattern may compile to. See [IRegexpEmitter] for what this prices. */
private const val MAX_INSTRUCTIONS = 10_000

private const val OP_CHAR = 0
private const val OP_SPLIT = 1
private const val OP_JUMP = 2
private const val OP_ASSERT_START = 3
private const val OP_ASSERT_END = 4
private const val OP_MATCH = 5

/**
 * Counts instruction visits, so linearity can be asserted rather than believed.
 *
 * A test seam and nothing else: production runs pass `null` and the branch folds away. It counts
 * *transitions* — one per instruction reached in an epsilon closure, one per live thread offered a
 * code point — because those are the two things a backtracking engine would multiply and this one
 * cannot.
 */
internal class TransitionCounter {
    var transitions: Long = 0
        internal set
}

/**
 * A compiled I-Regexp, as a flat instruction array.
 *
 * `OP_CHAR`, `OP_ASSERT_START` and `OP_ASSERT_END` fall through to the next instruction; `OP_SPLIT`
 * and `OP_JUMP` name their targets. Flat rather than a tree of objects because the simulation walks
 * it by index and a visit mark per instruction is then one `IntArray`.
 */
internal class IRegexpProgram(
    private val operations: IntArray,
    private val arguments: IntArray,
    private val alternates: IntArray,
    private val classes: Array<CharacterClass>,
    /** How many instructions the pattern compiled to. The cost model's other factor. */
    val size: Int,
) {

    /**
     * Runs the program over [input].
     *
     * @param anchored `true` for `match()`, where the whole subject must be consumed; `false` for
     *   `search()`, where a start is seeded at every position and any accepting thread wins.
     */
    fun run(input: String, anchored: Boolean, counter: TransitionCounter?): Boolean =
        Machine(input, anchored, counter).run()

    /**
     * One run: two thread lists, a visit mark per instruction, and a stack for the epsilon closure.
     *
     * The closure is iterative for the same reason the descendant walk one file over is: its depth is
     * the *program's*, and a program is bounded by a pattern that may have come from a document.
     */
    private inner class Machine(
        private val input: String,
        private val anchored: Boolean,
        private val counter: TransitionCounter?,
    ) {
        private var current = IntArray(size)
        private var next = IntArray(size)
        private var currentCount = 0

        /** Which run of the closure last reached an instruction, so each is listed once per position. */
        private val marks = IntArray(size) { -1 }
        private var generation = -1

        /** At most two pushes per marked instruction, plus the one this starts with. */
        private val pending = IntArray(2 * size + 2)

        private var listing = current
        private var listed = 0
        private var matched = false

        fun run(): Boolean {
            begin(current)
            add(0, 0)
            currentCount = listed
            var position = 0
            while (true) {
                if (matched) return true
                if (position == input.length) return false
                // Only an anchored run can run out: an unanchored one seeds a new start every time,
                // and a program whose start is `$` has an empty closure everywhere but the end.
                if (currentCount == 0 && anchored) return false

                val codePoint = input.codePointAt(position)
                val after = position + Character.charCount(codePoint)
                begin(next)
                if (!anchored) add(0, after)
                for (index in 0 until currentCount) {
                    val instruction = current[index]
                    count()
                    if (classes[arguments[instruction]].contains(codePoint)) add(instruction + 1, after)
                }

                val swap = current
                current = next
                next = swap
                currentCount = listed
                position = after
            }
        }

        /** Starts a fresh thread list at a new input position. */
        private fun begin(list: IntArray) {
            listing = list
            listed = 0
            generation++
        }

        /**
         * The epsilon closure from [start], listing every `OP_CHAR` it reaches.
         *
         * [position] is where the closure is being taken, which the two assertions need and nothing
         * else does — `^` and `$` are the only zero-width instructions, and both are decided here so
         * that the stepping loop never sees them.
         */
        private fun add(start: Int, position: Int) {
            var depth = 0
            pending[depth++] = start
            while (depth > 0) {
                val instruction = pending[--depth]
                if (marks[instruction] == generation) continue
                marks[instruction] = generation
                count()
                when (operations[instruction]) {
                    OP_SPLIT -> {
                        pending[depth++] = alternates[instruction]
                        pending[depth++] = arguments[instruction]
                    }

                    OP_JUMP -> pending[depth++] = arguments[instruction]

                    OP_ASSERT_START -> if (position == 0) pending[depth++] = instruction + 1

                    OP_ASSERT_END -> if (position == input.length) pending[depth++] = instruction + 1

                    // An anchored run accepts only where the subject ends; an unanchored one accepts
                    // the first substring it finds, which is what `search()` asks.
                    OP_MATCH -> if (!anchored || position == input.length) matched = true

                    else -> listing[listed++] = instruction
                }
            }
        }

        private fun count() {
            if (counter != null) counter.transitions++
        }
    }

    companion object {
        /** Emits [node], or throws [NotAnIRegexp] if it will not fit in [MAX_INSTRUCTIONS]. */
        fun of(node: RegexNode): IRegexpProgram {
            val emitter = IRegexpEmitter()
            emitter.emit(node)
            return emitter.build()
        }
    }
}

/**
 * Turns a [RegexNode] tree into instructions.
 *
 * Every emission leaves the program *falling through* on success, so a sequence is a concatenation
 * and nothing needs a continuation passed to it. The two patching helpers exist because a split's
 * targets are only known after its body has been emitted.
 */
private class IRegexpEmitter {

    private var operations = IntArray(INITIAL_CAPACITY)
    private var arguments = IntArray(INITIAL_CAPACITY)
    private var alternates = IntArray(INITIAL_CAPACITY)
    private val classes = ArrayList<CharacterClass>()
    private var size = 0

    fun build(): IRegexpProgram {
        emit(OP_MATCH)
        return IRegexpProgram(
            operations.copyOf(size),
            arguments.copyOf(size),
            alternates.copyOf(size),
            classes.toTypedArray(),
            size,
        )
    }

    fun emit(node: RegexNode) {
        when (node) {
            is RegexNode.Characters -> emit(OP_CHAR, classIndex(node.characters))
            RegexNode.AtStart -> emit(OP_ASSERT_START)
            RegexNode.AtEnd -> emit(OP_ASSERT_END)
            is RegexNode.Sequence -> node.pieces.forEach { emit(it) }
            is RegexNode.Alternation -> emitAlternation(node)
            is RegexNode.Repeat -> emitRepeat(node)
        }
    }

    /** `a|b|c` as nested splits, each branch jumping past the rest once it has matched. */
    private fun emitAlternation(node: RegexNode.Alternation) {
        val jumps = IntArray(node.branches.size - 1)
        for (index in node.branches.indices) {
            if (index == node.branches.lastIndex) {
                emit(node.branches[index])
            } else {
                val split = emit(OP_SPLIT)
                arguments[split] = size
                emit(node.branches[index])
                jumps[index] = emit(OP_JUMP)
                alternates[split] = size
            }
        }
        for (jump in jumps) arguments[jump] = size
    }

    /**
     * `{n,m}` by copying, which is the whole reason [MAX_INSTRUCTIONS] exists.
     *
     * The bounded case is emitted as *n* copies followed by `m-n` optional ones, and that is a
     * language identity rather than an approximation: `e?e?e?` and `(e(e(e)?)?)?` accept the same
     * strings, and this matcher answers a boolean, so the flatter of the two is the one to emit.
     * The bounds are checked against [MAX_INSTRUCTIONS] before the loop rather than inside it,
     * because a body that emits *nothing* — `(){1000000000}` — would otherwise spin without ever
     * reaching the instruction limit.
     */
    private fun emitRepeat(node: RegexNode.Repeat) {
        val bounded = node.max != RegexNode.UNBOUNDED
        if (node.min > MAX_INSTRUCTIONS || (bounded && node.max > MAX_INSTRUCTIONS)) {
            throw NotAnIRegexp("a range quantifier repeats more than $MAX_INSTRUCTIONS times")
        }
        repeat(node.min) { emit(node.node) }
        if (!bounded) {
            val split = emit(OP_SPLIT)
            arguments[split] = size
            emit(node.node)
            val jump = emit(OP_JUMP)
            arguments[jump] = split
            alternates[split] = size
            return
        }
        val optional = IntArray(node.max - node.min)
        for (index in optional.indices) {
            val split = emit(OP_SPLIT)
            arguments[split] = size
            emit(node.node)
            optional[index] = split
        }
        for (split in optional) alternates[split] = size
    }

    /**
     * The index of [characters] among the program's classes.
     *
     * Compared by identity rather than by contents: a repeated atom is the same node, so `a{1000}`
     * finds its class on the first probe, and two classes that happen to be equal cost one entry
     * each — which is a byte or two against a structural comparison per emitted instruction.
     */
    private fun classIndex(characters: CharacterClass): Int {
        for (index in classes.indices) {
            if (classes[index] === characters) return index
        }
        classes += characters
        return classes.size - 1
    }

    private fun emit(operation: Int, argument: Int = 0): Int {
        if (size == MAX_INSTRUCTIONS) {
            throw NotAnIRegexp("a pattern may compile to at most $MAX_INSTRUCTIONS instructions")
        }
        if (size == operations.size) grow()
        operations[size] = operation
        arguments[size] = argument
        alternates[size] = 0
        return size++
    }

    private fun grow() {
        val capacity = (size * 2).coerceAtMost(MAX_INSTRUCTIONS)
        operations = operations.copyOf(capacity)
        arguments = arguments.copyOf(capacity)
        alternates = alternates.copyOf(capacity)
    }

    private companion object {
        const val INITIAL_CAPACITY = 16
    }
}
