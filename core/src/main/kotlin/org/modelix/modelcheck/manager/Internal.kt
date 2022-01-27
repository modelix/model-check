/*
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package org.modelix.modelcheck.manager

import jetbrains.mps.smodel.event.*
import jetbrains.mps.smodel.loading.ModelLoadingState
import org.jetbrains.mps.openapi.model.SModel
import java.util.concurrent.atomic.AtomicInteger

/**
 * This interface implements all members with an empty implementation to reduce the clutter in the [MPSModelCheckManager].
 * In [MPSModelCheckManager] we only override the interesting methods for model check and not everything else.
 */
interface ModelListenerBase : SModelListener {
    override fun languageAdded(event: SModelLanguageEvent?) {
        //noop
    }

    override fun languageRemoved(event: SModelLanguageEvent?) {
        //noop
    }

    override fun importAdded(event: SModelImportEvent?) {
        //noop
    }

    override fun importRemoved(event: SModelImportEvent?) {
        //noop
    }

    override fun devkitAdded(event: SModelDevKitEvent?) {
        //noop
    }

    override fun devkitRemoved(event: SModelDevKitEvent?) {
        //noop
    }

    override fun rootAdded(event: SModelRootEvent?) {
        //noop
    }

    override fun rootRemoved(event: SModelRootEvent?) {
        //noop
    }

    override fun beforeRootRemoved(event: SModelRootEvent?) {
        //noop
    }

    override fun beforeModelRenamed(event: SModelRenamedEvent?) {
        //noop
    }

    override fun modelRenamed(event: SModelRenamedEvent?) {
        //noop
    }

    override fun propertyChanged(event: SModelPropertyEvent?) {
        //noop
    }

    override fun childAdded(event: SModelChildEvent?) {
        //noop
    }

    override fun childRemoved(event: SModelChildEvent?) {
        //noop
    }

    override fun beforeChildRemoved(event: SModelChildEvent?) {
        //noop
    }

    override fun referenceAdded(event: SModelReferenceEvent?) {
        //noop
    }

    override fun referenceRemoved(event: SModelReferenceEvent?) {
        //noop
    }

    override fun modelSaved(sm: SModel?) {
        //noop
    }

    override fun modelLoadingStateChanged(sm: SModel?, newState: ModelLoadingState?) {
        //noop
    }

    override fun beforeModelDisposed(sm: SModel?) {
        //noop
    }
}

internal class RingBuffer<T> : Iterable<T>, Cloneable {
    private var currentSize: Int = 0
    private val capacity: Int
    private val data: Array<Any?>
    private var tail: Int = -1

    constructor(capacity: Int) {
        require(capacity > 0) { "Negative capacity makes no sense!" }
        this.capacity = capacity
        this.data = arrayOfNulls(capacity)
    }

    private constructor(other: RingBuffer<T>) {
        this.currentSize = other.currentSize
        this.capacity = other.capacity
        this.data = other.data.copyOf()
        tail = other.tail
    }

    private val head: Int
        get() = if (currentSize == data.size) (tail + 1) % currentSize else 0

    val empty
        get () = currentSize == 0
    /**
     * Add an element to the ring buffer.
     */
    fun add(item: T): RingBuffer<T> {
        tail = (tail + 1) % capacity
        data[tail] = item
        if (currentSize < data.size) currentSize++
        return this
    }

    /**
     * Get an element from the array.
     */
    @Suppress("UNCHECKED_CAST")
    operator fun get(index: Int): T =
        when {
            currentSize == 0 || index > currentSize || index < 0 -> throw IndexOutOfBoundsException("$index")
            currentSize == capacity -> data[(head + index) % capacity]
            else -> data[index]
        } as T

    /**
     * This ring buffer as a list.
     */
    @Suppress("UNCHECKED_CAST")
    fun toList(): List<T> = iterator().asSequence().toList()

    public override fun clone(): RingBuffer<T> = RingBuffer(this)

    override fun iterator(): Iterator<T> = object : Iterator<T> {
        private val index: AtomicInteger = AtomicInteger(0)

        override fun hasNext(): Boolean = index.get() < (currentSize % capacity)

        override fun next(): T = get(index.getAndIncrement())
    }
}