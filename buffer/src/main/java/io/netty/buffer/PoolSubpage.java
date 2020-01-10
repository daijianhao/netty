/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.buffer;

/**
 * 为了进一步提供提高内存分配效率并减少内存碎片，Jemalloc 算法将每个 Chunk 切分成多个小块 Page 。
 * 但是实际应用中，Page 也是比较大的内存块，如果直接使用，明显是很浪费的。因此，Jemalloc 算法将每个 Page 更进一步的切分
 * 为多个 Subpage 内存块。Page 切分成多个 Subpage 内存块，并未采用相对复杂的算法和数据结构，而是直接基于数组，通过数组
 * 来标记每个 Subpage 内存块是否已经分配.
 * <p>
 * 一个 Page ，切分出的多个 Subpage 内存块大小均等。
 * 每个 Page 拆分的 Subpage 内存块可以不同，以 Page 第一次拆分为 Subpage 内存块时请求分配的内存大小为准。例如：
 * 初始时，申请一个 16B 的内存块，那么 Page0 被拆成成 512( 8KB / 16B )个 Subpage 块，使用第 0 块。
 * 然后，申请一个 32B 的内存块，那么 Page1 被拆分成 256( 8KB / 32B )个 Subpage 块，使用第 0 块。
 * 最后，申请一个 16B 的内存块，那么重用 Page0 ，使用第 1 块。
 * <p>
 * 总结来说，申请 Subpage 内存块时，先去找大小匹配，且有可分配 Subpage 内存块的 Page ：
 * 1）如果有，则使用其中的一块 Subpage ；
 * 2）如果没有，则选择一个新的 Page 拆分成多个 Subpage 内存块，使用第 0 块 Subpage 。
 * <p>
 * <p>
 * 实现 PoolSubpageMetric 接口，Netty 对 Jemalloc Subpage 的实现类
 * <p>
 * 虽然，PoolSubpage 类的命名是“Subpage”，实际描述的是，Page 切分为多个 Subpage 内存块的分配情况。那么为什么不直接叫 PoolPage 呢？
 * 在 PoolChunk 中，我们可以看到，
 * 当申请分配的内存规格为 Normal 和 Huge 时，使用的是一块或多块 Page 内存块。如果 PoolSubpage 命名成 PoolPage 后，和这块的分配策略
 * 是有所冲突的。或者说，Subpage ，只是 Page 分配内存的一种形式。
 */
final class PoolSubpage<T> implements PoolSubpageMetric {

    /**
     * 所属 PoolChunk 对象
     */
    final PoolChunk<T> chunk;

    /**
     * 在 {@link PoolChunk#memoryMap} 的节点编号
     * <p>
     * 在 PoolChunk.memoryMap 的节点编号，例如节点编号 2048
     */
    private final int memoryMapIdx;

    /**
     * 在 Chunk 中，偏移字节量
     *
     * @see PoolChunk#runOffset(int)
     * <p>
     * 在 Chunk 中，偏移字节量，通过 PoolChunk#runOffset(id) 方法计算。在 PoolSubpage 中，无相关的逻辑，仅用于 #toString() 方法，
     * 打印信息
     */
    private final int runOffset;

    /**
     * Page 大小 {@link PoolChunk#pageSize}
     */
    private final int pageSize;

    /**
     * Subpage 分配信息数组
     * <p>
     * 每个 long 的 bits 位代表一个 Subpage 是否分配。
     * 因为 PoolSubpage 可能会超过 64 个( long 的 bits 位数 )，所以使用数组。
     * 例如：Page 默认大小为 8KB ，Subpage 默认最小为 16 B ，所以一个 Page 最多可包含 8 * 1024 / 16 = 512 个 Subpage 。
     * 因此，bitmap 数组大小为 512 / 64 = 8 。
     * 另外，bitmap 的数组大小，使用 {@link #bitmapLength} 来标记。或者说，bitmap 数组，默认按照 Subpage 的大小为 16B 来初始化。
     * 为什么是这样的设定呢？因为 PoolSubpage 可重用，通过 {@link #init(PoolSubpage, int)} 进行重新初始化。
     */
    private final long[] bitmap;

    /**
     * 双向链表，前一个 PoolSubpage 对象
     */
    PoolSubpage<T> prev;

    /**
     * 双向链表，后一个 PoolSubpage 对象
     */
    PoolSubpage<T> next;

    /**
     * 是否未销毁
     */
    boolean doNotDestroy;

    /**
     * 每个 Subpage 的占用内存大小
     * <p>
     * 每个 Subpage 的占用内存大小，例如 16B、32B 等等
     */
    int elemSize;

    /**
     * 总共 Subpage 的数量
     * <p>
     * 总共 Subpage 的数量。例如 16B 为 512 个，32b 为 256 个
     */
    private int maxNumElems;

    /**
     * {@link #bitmap} 长度
     */
    private int bitmapLength;

    /**
     * 下一个可分配 Subpage 的数组位置
     * <p>
     * 下一个可分配 Subpage 的数组( bitmap )位置。可能会有胖友有疑问，bitmap 又是数组，又考虑 bits 位，怎么计算位置呢？在 「
     * getNextAvail 见分晓
     */
    private int nextAvail;

    /**
     * 剩余可用 Subpage 的数量
     */
    private int numAvail;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    /**
     * Special constructor that creates a linked list head
     * 用于创建双向链表的头( head )节点
     */
    PoolSubpage(int pageSize) {
        chunk = null;
        memoryMapIdx = -1;
        runOffset = -1;
        elemSize = -1;
        this.pageSize = pageSize;
        bitmap = null;
    }

    /**
     * 用于创建双向链表的 Page 节点
     */
    PoolSubpage(PoolSubpage<T> head, PoolChunk<T> chunk, int memoryMapIdx, int runOffset, int pageSize, int elemSize) {
        this.chunk = chunk;
        this.memoryMapIdx = memoryMapIdx;
        this.runOffset = runOffset;
        this.pageSize = pageSize;
        //为什么是固定大小呢？因为 PoolSubpage 可重用，通过 #init(PoolSubpage, int) 进行重新初始化。
        //那么数组大小怎么获得？通过 bitmapLength 属性来标记真正使用的数组大小
        bitmap = new long[pageSize >>> 10]; // pageSize / 16 / 64
        init(head, elemSize);
    }

    void init(PoolSubpage<T> head, int elemSize) {
        // 未销毁
        doNotDestroy = true;
        // 初始化 elemSize
        this.elemSize = elemSize;
        if (elemSize != 0) {
            // 初始化 maxNumElems
            maxNumElems = numAvail = pageSize / elemSize;
            // 初始化 nextAvail
            nextAvail = 0;
            // 计算 bitmapLength 的大小
            bitmapLength = maxNumElems >>> 6;
            if ((maxNumElems & 63) != 0) {// 未整除，补 1
                bitmapLength++;
            }

            // 初始化 bitmap
            for (int i = 0; i < bitmapLength; i++) {
                bitmap[i] = 0;
            }
        }
        // 添加到 Arena 的双向链表中
        //在每个 Arena 中，有 tinySubpagePools 和 smallSubpagePools 属性，
        // 分别表示 tiny 和 small 类型的 PoolSubpage 数组
        addToPool(head);
    }

    /**
     * Returns the bitmap index of the subpage allocation.
     * <p>
     * 分配一个 Subpage 内存块，并返回该内存块的位置 handle
     */
    long allocate() {
        // 防御性编程，不存在这种情况
        if (elemSize == 0) {
            return toHandle(0);
        }

        // 可用数量为 0 ，或者已销毁，返回 -1 ，即不可分配
        if (numAvail == 0 || !doNotDestroy) {
            return -1;
        }

        // 获得下一个可用的 Subpage 在 bitmap 中的总体位置
        final int bitmapIdx = getNextAvail();
        // 获得下一个可用的 Subpage 在 bitmap 中数组的位置
        int q = bitmapIdx >>> 6;
        // 获得下一个可用的 Subpage 在 bitmap 中数组的位置的第几 bits
        int r = bitmapIdx & 63;
        assert (bitmap[q] >>> r & 1) == 0;
        // 修改 Subpage 在 bitmap 中不可分配
        bitmap[q] |= 1L << r;

        // 可用 Subpage 内存块的计数减一
        if (--numAvail == 0) {
            // 从双向链表中移除
            removeFromPool();
        }
        // 计算 handle
        return toHandle(bitmapIdx);
    }

    /**
     * @return {@code true} if this subpage is in use.
     * {@code false} if this subpage is not used by its chunk and thus it's OK to be released.
     *
     * 释放指定位置的 Subpage 内存块，并返回当前 Page 是否正在使用中( true )
     *
     * 需要返回 true 或 false 呢？胖友再看看 PoolChunk#free(long handle) 方法，就能明白。答案是，如果不再使用，可以将该节点( Page )从 Chunk 中释放，标记为可用。
     */
    boolean free(PoolSubpage<T> head, int bitmapIdx) {
        // 防御性编程，不存在这种情况。
        if (elemSize == 0) {
            return true;
        }
        // 获得 Subpage 在 bitmap 中数组的位置
        int q = bitmapIdx >>> 6;
        // 获得 Subpage 在 bitmap 中数组的位置的第几 bits
        int r = bitmapIdx & 63;
        assert (bitmap[q] >>> r & 1) != 0;
        // 修改 Subpage 在 bitmap 中可分配
        bitmap[q] ^= 1L << r;

        // 设置下一个可用为当前 Subpage
        //设置下一个可用为当前 Subpage 的位置。这样，就能避免下次分配 Subpage 时，再去找位置
        setNextAvail(bitmapIdx);

        // 可用 Subpage 内存块的计数加一
        if (numAvail++ == 0) {
            // 添加到 Arena 的双向链表中。
            addToPool(head);
            return true;
        }

        // 还有 Subpage 在使用
        if (numAvail != maxNumElems) {
            return true;
        } else {
            // 没有 Subpage 在使用
            // Subpage not in use (numAvail == maxNumElems)
            if (prev == next) {
                // Do not remove if this subpage is the only one left in the pool.
                return true;
            }
            // 标记为已销毁
            // Remove this subpage from the pool if there are other subpages left in the pool.
            doNotDestroy = false;
            // 从双向链表中移除
            removeFromPool();
            return false;
        }
    }

    private void addToPool(PoolSubpage<T> head) {
        assert prev == null && next == null;
        // 将当前节点，插入到 head 和 head.next 中间
        prev = head;
        next = head.next;
        next.prev = this;
        head.next = this;
    }

    private void removeFromPool() {
        assert prev != null && next != null;
        prev.next = next;
        next.prev = prev;
        next = null;
        prev = null;
    }

    private void setNextAvail(int bitmapIdx) {
        nextAvail = bitmapIdx;
    }

    private int getNextAvail() {
        int nextAvail = this.nextAvail;
        // <1> nextAvail 大于 0 ，意味着已经“缓存”好下一个可用的位置，直接返回即可
        if (nextAvail >= 0) {
            //获取好后，会将 nextAvail 置为 -1 。意味着，下次需要寻找下一个 nextAvail
            this.nextAvail = -1;
            return nextAvail;
        }
        // <2> 寻找下一个 nextAvail
        return findNextAvail();
    }

    private int findNextAvail() {
        final long[] bitmap = this.bitmap;
        final int bitmapLength = this.bitmapLength;
        // 循环 bitmap
        for (int i = 0; i < bitmapLength; i++) {
            long bits = bitmap[i];
            // ~ 操作，如果不等于 0 ，说明有可用的 Subpage
            if (~bits != 0) {
                // 在这 bits 寻找可用 nextAvail
                return findNextAvail0(i, bits);
            }
        }
        return -1;
    }

    private int findNextAvail0(int i, long bits) {
        final int maxNumElems = this.maxNumElems;
        // 计算基础值，表示在 bitmap 的数组下标
        final int baseVal = i << 6;

        // 遍历 64 bits
        for (int j = 0; j < 64; j++) {
            if ((bits & 1) == 0) {
                // 可能 bitmap 最后一个元素，并没有 64 位，通过 baseVal | j < maxNumElems 来保证不超过上限
                int val = baseVal | j;
                if (val < maxNumElems) {
                    return val;
                } else {
                    break;
                }
            }
            // 去掉当前 bit
            bits >>>= 1;
        }

        // 未找到
        return -1;
    }

    private long toHandle(int bitmapIdx) {
        //低 32 bits ：memoryMapIdx ，可以判断所属 Chunk 的哪个 Page 节点，即 memoryMap[memoryMapIdx]
        //高 32 bits ：bitmapIdx ，可以判断 Page 节点中的哪个 Subpage 的内存块，即 bitmap[bitmapIdx]
        //那么为什么会有 0x4000000000000000L 呢？因为在 PoolChunk#allocate(int normCapacity) 中：
        //如果分配的是 Page 内存块，返回的是 memoryMapIdx 。
        //如果分配的是 Subpage 内存块，返回的是 handle 。但但但是，如果说 bitmapIdx = 0 ，那么没有 0x4000000000000000L 情况下，
        //          就会和【分配 Page 内存块】冲突。因此，需要有 0x4000000000000000L 。
        //因为有了 0x4000000000000000L(最高两位为 01 ，其它位为 0 )，所以获取 bitmapIdx 时，通过 handle >>> 32 & 0x3FFFFFFF 操作。
        // 使用 0x3FFFFFFF( 最高两位为 00 ，其它位为 1 ) 进行消除 0x4000000000000000L 带来的影响。
        return 0x4000000000000000L | (long) bitmapIdx << 32 | memoryMapIdx;
    }

    @Override
    public String toString() {
        final boolean doNotDestroy;
        final int maxNumElems;
        final int numAvail;
        final int elemSize;
        synchronized (chunk.arena) {
            if (!this.doNotDestroy) {
                doNotDestroy = false;
                // Not used for creating the String.
                maxNumElems = numAvail = elemSize = -1;
            } else {
                doNotDestroy = true;
                maxNumElems = this.maxNumElems;
                numAvail = this.numAvail;
                elemSize = this.elemSize;
            }
        }

        if (!doNotDestroy) {
            return "(" + memoryMapIdx + ": not in use)";
        }

        return "(" + memoryMapIdx + ": " + (maxNumElems - numAvail) + '/' + maxNumElems +
                ", offset: " + runOffset + ", length: " + pageSize + ", elemSize: " + elemSize + ')';
    }

    @Override
    public int maxNumElements() {
        synchronized (chunk.arena) {
            return maxNumElems;
        }
    }

    @Override
    public int numAvailable() {
        synchronized (chunk.arena) {
            return numAvail;
        }
    }

    @Override
    public int elementSize() {
        synchronized (chunk.arena) {
            return elemSize;
        }
    }

    @Override
    public int pageSize() {
        return pageSize;
    }

    void destroy() {
        if (chunk != null) {
            chunk.destroy();
        }
    }
}
