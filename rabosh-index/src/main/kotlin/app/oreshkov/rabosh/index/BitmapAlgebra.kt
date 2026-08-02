package app.oreshkov.rabosh.index

/**
 * The set operations, block by block.
 *
 * Every one of them is a merge of two ascending key sequences, which is why they read alike: keys
 * present in both are combined by [ContainerAlgebra], and what happens to a key present in only one is
 * the whole difference between the four operations. Blocks with no keys in common are never opened, so
 * an intersection of two bitmaps over different parts of a segment costs a walk of two directories.
 *
 * A block taken from an operand rather than computed is **copied**. `materialise` hands back the
 * receiver itself for a heap block, so adopting one without copying would leave two bitmaps sharing
 * storage, and a later `add` to either would appear in both.
 */
internal object BitmapAlgebra {

    fun and(left: ContainerSource, right: ContainerSource): Bitmap {
        val builder = Builder(minOf(left.containerCount, right.containerCount))
        var i = 0
        var j = 0
        while (i < left.containerCount && j < right.containerCount) {
            val leftKey = left.keyAt(i)
            val rightKey = right.keyAt(j)
            if (leftKey < rightKey) {
                i++
            } else if (leftKey > rightKey) {
                j++
            } else {
                builder.append(leftKey, ContainerAlgebra.and(left.containerAt(i), right.containerAt(j)))
                i++
                j++
            }
        }
        return builder.build()
    }

    fun or(left: ContainerSource, right: ContainerSource): Bitmap {
        val builder = Builder(left.containerCount + right.containerCount)
        var i = 0
        var j = 0
        while (i < left.containerCount && j < right.containerCount) {
            val leftKey = left.keyAt(i)
            val rightKey = right.keyAt(j)
            if (leftKey < rightKey) {
                builder.append(leftKey, adopt(left.containerAt(i++)))
            } else if (leftKey > rightKey) {
                builder.append(rightKey, adopt(right.containerAt(j++)))
            } else {
                builder.append(leftKey, ContainerAlgebra.or(left.containerAt(i), right.containerAt(j)))
                i++
                j++
            }
        }
        while (i < left.containerCount) builder.append(left.keyAt(i), adopt(left.containerAt(i++)))
        while (j < right.containerCount) builder.append(right.keyAt(j), adopt(right.containerAt(j++)))
        return builder.build()
    }

    fun andNot(left: ContainerSource, right: ContainerSource): Bitmap {
        val builder = Builder(left.containerCount)
        var i = 0
        var j = 0
        while (i < left.containerCount) {
            val leftKey = left.keyAt(i)
            while (j < right.containerCount && right.keyAt(j) < leftKey) j++
            if (j < right.containerCount && right.keyAt(j) == leftKey) {
                builder.append(leftKey, ContainerAlgebra.andNot(left.containerAt(i), right.containerAt(j)))
            } else {
                builder.append(leftKey, adopt(left.containerAt(i)))
            }
            i++
        }
        return builder.build()
    }

    fun xor(left: ContainerSource, right: ContainerSource): Bitmap {
        val builder = Builder(left.containerCount + right.containerCount)
        var i = 0
        var j = 0
        while (i < left.containerCount && j < right.containerCount) {
            val leftKey = left.keyAt(i)
            val rightKey = right.keyAt(j)
            if (leftKey < rightKey) {
                builder.append(leftKey, adopt(left.containerAt(i++)))
            } else if (leftKey > rightKey) {
                builder.append(rightKey, adopt(right.containerAt(j++)))
            } else {
                builder.append(leftKey, ContainerAlgebra.xor(left.containerAt(i), right.containerAt(j)))
                i++
                j++
            }
        }
        while (i < left.containerCount) builder.append(left.keyAt(i), adopt(left.containerAt(i++)))
        while (j < right.containerCount) builder.append(right.keyAt(j), adopt(right.containerAt(j++)))
        return builder.build()
    }

    fun intersects(left: ContainerSource, right: ContainerSource): Boolean {
        var i = 0
        var j = 0
        while (i < left.containerCount && j < right.containerCount) {
            val leftKey = left.keyAt(i)
            val rightKey = right.keyAt(j)
            if (leftKey < rightKey) {
                i++
            } else if (leftKey > rightKey) {
                j++
            } else {
                if (containersIntersect(left.containerAt(i), right.containerAt(j))) return true
                i++
                j++
            }
        }
        return false
    }

    fun andCardinality(left: ContainerSource, right: ContainerSource): Int {
        var count = 0
        var i = 0
        var j = 0
        while (i < left.containerCount && j < right.containerCount) {
            val leftKey = left.keyAt(i)
            val rightKey = right.keyAt(j)
            if (leftKey < rightKey) {
                i++
            } else if (leftKey > rightKey) {
                j++
            } else {
                count += containersAndCardinality(left.containerAt(i), right.containerAt(j))
                i++
                j++
            }
        }
        return count
    }

    fun copyOf(source: ContainerSource): Bitmap {
        val builder = Builder(source.containerCount)
        for (index in 0 until source.containerCount) {
            builder.append(source.keyAt(index), adopt(source.containerAt(index)))
        }
        return builder.build()
    }

    /** A heap block that belongs to the caller, copied when it would otherwise be shared. */
    private fun adopt(container: ReadableContainer): Container {
        val heap = container.materialise()
        return if (heap === container) heap.copy() else heap
    }

    /**
     * Collects blocks in ascending key order, dropping the empty ones.
     *
     * Dropping them is not an optimisation. A bitmap that kept a block with nothing in it would encode
     * a directory entry the reader rejects, and — because the entry carries a prefix cardinality that
     * did not advance — would break the one arithmetic identity the directory rests on.
     */
    private class Builder(capacity: Int) {
        private var keys = IntArray(capacity.coerceAtLeast(1))
        private var blocks = arrayOfNulls<Container>(capacity.coerceAtLeast(1))
        private var size = 0

        fun append(key: Int, block: Container) {
            if (block.cardinality == 0) return
            if (size == keys.size) {
                keys = keys.copyOf(size * 2)
                blocks = blocks.copyOf(size * 2)
            }
            keys[size] = key
            blocks[size] = block
            size++
        }

        fun build(): Bitmap = Bitmap.fromBlocks(keys, blocks, size)
    }
}
