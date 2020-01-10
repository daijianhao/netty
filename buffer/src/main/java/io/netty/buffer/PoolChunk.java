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

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Description of algorithm for PageRun/PoolSubpage allocation from PoolChunk
 * <p>
 * Notation: The following terms are important to understand the code
 * > page  - a page is the smallest unit of memory chunk that can be allocated
 * > chunk - a chunk is a collection of pages
 * > in this code chunkSize = 2^{maxOrder} * pageSize
 * <p>
 * To begin we allocate a byte array of size = chunkSize
 * Whenever a ByteBuf of given size needs to be created we search for the first position
 * in the byte array that has enough empty space to accommodate the requested size and
 * return a (long) handle that encodes this offset information, (this memory segment is then
 * marked as reserved so it is always used by exactly one ByteBuf and no more)
 * <p>
 * For simplicity all sizes are normalized according to PoolArena#normalizeCapacity method
 * This ensures that when we request for memory segments of size >= pageSize the normalizedCapacity
 * equals the next nearest power of 2
 * <p>
 * To search for the first offset in chunk that has at least requested size available we construct a
 * complete balanced binary tree and store it in an array (just like heaps) - memoryMap
 * <p>
 * The tree looks like this (the size of each node being mentioned in the parenthesis)
 * <p>
 * depth=0        1 node (chunkSize)
 * depth=1        2 nodes (chunkSize/2)
 * ..
 * ..
 * depth=d        2^d nodes (chunkSize/2^d)
 * ..
 * depth=maxOrder 2^maxOrder nodes (chunkSize/2^{maxOrder} = pageSize)
 * <p>
 * depth=maxOrder is the last level and the leafs consist of pages
 * <p>
 * With this tree available searching in chunkArray translates like this:
 * To allocate a memory segment of size chunkSize/2^k we search for the first node (from left) at height k
 * which is unused
 * <p>
 * Algorithm:
 * ----------
 * Encode the tree in memoryMap with the notation
 * memoryMap[id] = x => in the subtree rooted at id, the first node that is free to be allocated
 * is at depth x (counted from depth=0) i.e., at depths [depth_of_id, x), there is no node that is free
 * <p>
 * As we allocate & free nodes, we update values stored in memoryMap so that the property is maintained
 * <p>
 * Initialization -
 * In the beginning we construct the memoryMap array by storing the depth of a node at each node
 * i.e., memoryMap[id] = depth_of_id
 * <p>
 * Observations:
 * -------------
 * 1) memoryMap[id] = depth_of_id  => it is free / unallocated
 * 2) memoryMap[id] > depth_of_id  => at least one of its child nodes is allocated, so we cannot allocate it, but
 * some of its children can still be allocated based on their availability
 * 3) memoryMap[id] = maxOrder + 1 => the node is fully allocated & thus none of its children can be allocated, it
 * is thus marked as unusable
 * <p>
 * Algorithm: [allocateNode(d) => we want to find the first node (from left) at height h that can be allocated]
 * ----------
 * 1) start at root (i.e., depth = 0 or id = 1)
 * 2) if memoryMap[1] > d => cannot be allocated from this chunk
 * 3) if left node value <= h; we can allocate from left subtree so move to left and repeat until found
 * 4) else try in right subtree
 * <p>
 * Algorithm: [allocateRun(size)]
 * ----------
 * 1) Compute d = log_2(chunkSize/size)
 * 2) Return allocateNode(d)
 * <p>
 * Algorithm: [allocateSubpage(size)]
 * ----------
 * 1) use allocateNode(maxOrder) to find an empty (i.e., unused) leaf (i.e., page)
 * 2) use this handle to construct the PoolSubpage object or if it already exists just call init(normCapacity)
 * note that this PoolSubpage object is added to subpagesPool in the PoolArena when we init() it
 * <p>
 * Note:
 * -----
 * In the implementation for improving cache coherence,
 * we store 2 pieces of information depth_of_id and x as two byte values in memoryMap and depthMap respectively
 * <p>
 * memoryMap[id]= depth_of_id  is defined above
 * depthMap[id]= x  indicates that the first node which is free to be allocated is at depth x (from root)
 * <p>
 * 实现 PoolChunkMetric 接口，Netty 对 Jemalloc Chunk 的实现类
 */
final class PoolChunk<T> implements PoolChunkMetric {

    private static final int INTEGER_SIZE_MINUS_ONE = Integer.SIZE - 1;

    /**
     * 所属 Arena 对象
     */
    final PoolArena<T> arena;

    /**
     * 内存空间
     * <p>
     * 即用于 PooledByteBuf.memory 属性，有 Direct ByteBuffer 和 byte[] 字节数组。
     */
    final T memory;

    /**
     * 是否非池化
     * <p>
     * 默认情况下，对于 分配 16M 以内的内存空间时，Netty 会分配一个 Normal 类型的 Chunk 块。并且，该 Chunk 块在使用完后，
     * 进行池化缓存，重复使用。
     * <p>
     * 认情况下，对于分配 16M 以上的内存空间时，Netty 会分配一个 Huge 类型的特殊的 Chunk 块。并且，由于 Huge 类型的 Chunk 占用
     * 内存空间较大，比较特殊，所以该 Chunk 块在使用完后，立即释放，不进行重复使用
     */
    final boolean unpooled;
    final int offset;
    /**
     * 分配信息满二叉树
     * <p>
     * index 为节点编号
     * <p>
     * Netty 实现的伙伴分配算法中，构造了两颗满二叉树。因为满二叉树非常适合数组存储，Netty 使用两个字节数组 memoryMap 和 depthMap
     * 来分别表示分配信息满二叉树、高度信息满二叉树
     */
    private final byte[] memoryMap;
    /**
     * 高度信息满二叉树
     * <p>
     * index 为节点编号
     */
    private final byte[] depthMap;
    /**
     * PoolSubpage 数组
     * <p>
     * PoolSubpage 数组。每个节点对应一个 PoolSubpage 对象。因为实际上，每个 Page 还是比较大的内存块，可以进一步切分成小块 SubPage
     */
    private final PoolSubpage<T>[] subpages;
    /**
     * Used to determine if the requested capacity is equal to or greater than pageSize.
     * 判断分配请求内存是否为 Tiny/Small ，即分配 Subpage 内存块
     * <p>
     * 判断分配请求内存是否为 Tiny/Small ，即分配 Subpage 内存块。默认，-8192 。
     * 在【13 行】的代码进行初始化。
     * 对于 -8192 的二进制，除了首 bits 为 1 ，其它都为 0 。
     * 这样，对于小于 8K 字节的申请，求 subpageOverflowMask & length 都等于 0 ；
     * 对于大于 8K 字节的申请，求 subpageOverflowMask & length 都不等于 0 。
     * 相当于说，做了 if ( length < pageSize ) 的计算优化。
     */
    private final int subpageOverflowMask;
    /**
     * Page 大小，默认 8KB = 8192B
     */
    private final int pageSize;
    /**
     * 从 1 开始左移到 {@link #pageSize} 的位数。默认 13 ，1 << 13 = 8192 。
     * <p>
     * 具体用途，见 {@link #allocateRun(int)} 方法，计算指定容量所在满二叉树的层级。
     * <p>
     * 具体用于计算指定容量所在满二叉树的层级
     */
    private final int pageShifts;
    /**
     * 满二叉树的高度。默认为 11 。
     */
    private final int maxOrder;
    /**
     * Chunk 内存块占用大小。默认为 16M = 16 * 1024  。
     */
    private final int chunkSize;
    /**
     * log2 {@link #chunkSize} 的结果。默认为 log2( 16M ) = 24 。
     */
    private final int log2ChunkSize;
    /**
     * 可分配 {@link #subpages} 的数量，即数组大小。默认为 1 << maxOrder = 1 << 11 = 2048 。
     */
    private final int maxSubpageAllocs;
    /**
     * 标记节点不可用。默认为 maxOrder + 1 = 12 。
     * Used to mark memory as unusable
     */
    private final byte unusable;

    // Use as cache for ByteBuffer created from the memory. These are just duplicates and so are only a container
    // around the memory itself. These are often needed for operations within the Pooled*ByteBuf and so
    // may produce extra GC, which can be greatly reduced by caching the duplicates.
    //
    // This may be null if the PoolChunk is unpooled as pooling the ByteBuffer instances does not make any sense here.
    private final Deque<ByteBuffer> cachedNioBuffers;

    /**
     * 剩余可用字节数
     */
    private int freeBytes;

    /**
     * 所属 PoolChunkList 对象
     */
    PoolChunkList<T> parent;

    /**
     * 上一个 Chunk 对象
     */
    PoolChunk<T> prev;

    /**
     * 下一个 Chunk 对象
     */
    PoolChunk<T> next;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    PoolChunk(PoolArena<T> arena, T memory, int pageSize, int maxOrder, int pageShifts, int chunkSize, int offset) {
        // 池化
        unpooled = false;
        this.arena = arena;
        this.memory = memory;
        this.pageSize = pageSize;
        this.pageShifts = pageShifts;
        this.maxOrder = maxOrder;
        this.chunkSize = chunkSize;
        this.offset = offset;
        unusable = (byte) (maxOrder + 1);
        log2ChunkSize = log2(chunkSize);
        subpageOverflowMask = ~(pageSize - 1);
        freeBytes = chunkSize;

        assert maxOrder < 30 : "maxOrder should be < 30, but is: " + maxOrder;
        maxSubpageAllocs = 1 << maxOrder;

        // Generate the memory map.
        // 初始化 memoryMap 和 depthMap
        //数组大小为 maxSubpageAllocs << 1 = 2048 << 1 = 4096,因为满二叉树一共有12层，就有4096个节点
        memoryMap = new byte[maxSubpageAllocs << 1];
        //分配节点时，depthMap 的值保持不变( 因为，节点的高度没发生变化 )，memoryMap 的值发生变化( 因为，节点的分配信息发生变化 )。
        // 当一个节点被分配后，该节点的值设为 unusable( 标记节点不可用。默认为 maxOrder + 1 = 12 ) 。并且，会更新祖先节点的值为
        // 其子节点较小的值( 因为，祖先节点共用该节点的 Page 内存；同时，一个父节点有两个子节点，一个节点不可用后，另一个子节点可能
        // 可用，所以更新为其子节点较小的值。 )
        depthMap = new byte[memoryMap.length];
        //memoryMap 数组的值，总结为 3 种情况：
        //1、memoryMap[id] = depthMap[id] ，该节点没有被分配。
        //2、最大高度 >= memoryMap[id] > depthMap[id] ，至少有一个子节点被分配，不能再分配该高度满足的内存，但可以根据实际分配较小
        // 一些的内存。比如，上图中父节点 2 分配了子节点 4，值从 1 更新为 2，表示该节点不能再分配 8MB 的只能最大分配 4MB 内存，即只剩下节点 5 可用。
        //3、memoryMap[id] = 最大高度 + 1 ，该节点及其子节点已被完全分配，没有剩余空间。


        //满二叉树的节点编号是从 1 开始。省略 0 是因为这样更容易计算父子关系：子节点加倍，父节点减半，
        int memoryMapIndex = 1;

        //初始时，memoryMap 和 depthMap 相等，值为节点高度
        for (int d = 0; d <= maxOrder; ++d) { // move down the tree one level at a time
            int depth = 1 << d;
            for (int p = 0; p < depth; ++p) {
                // in each level traverse left to right and set value to the depth of subtree
                memoryMap[memoryMapIndex] = (byte) d;
                depthMap[memoryMapIndex] = (byte) d;
                memoryMapIndex++;
            }
        }

        // 初始化 subpages
        subpages = newSubpageArray(maxSubpageAllocs);
        cachedNioBuffers = new ArrayDeque<ByteBuffer>(8);
    }

    /**
     * Creates a special chunk that is not pooled.
     * <p>
     * 分配一个Huge类型的内存块，不池化，因为所占内存较大
     */
    PoolChunk(PoolArena<T> arena, T memory, int size, int offset) {
        //Huge内存块不池化
        unpooled = true;
        this.arena = arena;
        this.memory = memory;
        this.offset = offset;
        memoryMap = null;
        depthMap = null;
        subpages = null;
        subpageOverflowMask = 0;
        pageSize = 0;
        pageShifts = 0;
        maxOrder = 0;
        unusable = (byte) (maxOrder + 1);
        chunkSize = size;
        log2ChunkSize = log2(chunkSize);
        maxSubpageAllocs = 0;
        cachedNioBuffers = null;
    }

    @SuppressWarnings("unchecked")
    private PoolSubpage<T>[] newSubpageArray(int size) {
        //数组大小为 maxSubpageAllocs = 2048
        return new PoolSubpage[size];
    }

    @Override
    public int usage() {
        final int freeBytes;
        //synchronized 的原因是，保证 freeBytes 对其它线程的可见性
        synchronized (arena) {
            freeBytes = this.freeBytes;
        }
        return usage(freeBytes);
    }

    private int usage(int freeBytes) {
        // 全部使用，100%
        if (freeBytes == 0) {
            return 100;
        }
        // 部分使用，最高 99%
        int freePercentage = (int) (freeBytes * 100L / chunkSize);
        if (freePercentage == 0) {
            return 99;
        }
        return 100 - freePercentage;
    }

    /**
     * 尝试将{@link PoolChunk#memory}表示的内存（byte[]或ByteBuffer）分配给ByteBuf对象
     */
    boolean allocate(PooledByteBuf<T> buf, int reqCapacity, int normCapacity) {
        final long handle;
        if ((normCapacity & subpageOverflowMask) != 0) { // >= pageSize
            // 大于等于 Page 大小，分配 Page 内存块
            handle = allocateRun(normCapacity);
        } else {
            // 小于 Page 大小，分配 Subpage 内存块
            handle = allocateSubpage(normCapacity);
        }

        if (handle < 0) {
            return false;
        }
        ByteBuffer nioBuffer = cachedNioBuffers != null ? cachedNioBuffers.pollLast() : null;
        initBuf(buf, nioBuffer, handle, reqCapacity);
        return true;
    }

    /**
     * Update method used by allocate
     * This is triggered only when a successor is allocated and all its predecessors
     * need to update their state
     * The minimal depth at which subtree rooted at id has some free space
     *
     * @param id id
     */
    private void updateParentsAlloc(int id) {
        while (id > 1) {
            // 获得父节点的编号
            int parentId = id >>> 1;
            // 获得子节点的值
            byte val1 = value(id);
            // 获得另外一个子节点的
            byte val2 = value(id ^ 1);
            // 获得子节点较小值，并设置到父节点
            byte val = val1 < val2 ? val1 : val2;
            setValue(parentId, val);
            // 跳到父节点
            id = parentId;
        }
    }

    /**
     * Update method used by free
     * This needs to handle the special case when both children are completely free
     * in which case parent be directly allocated on request of size = child-size * 2
     *
     * @param id id
     */
    private void updateParentsFree(int id) {
        // 获得当前节点的子节点的层级
        int logChild = depth(id) + 1;
        while (id > 1) {
            // 获得父节点的编号
            int parentId = id >>> 1;
            // 获得子节点的值
            byte val1 = value(id);
            // 获得另外一个子节点的值
            byte val2 = value(id ^ 1);
            // 获得当前节点的层级
            logChild -= 1; // in first iteration equals log, subsequently reduce 1 from logChild as we traverse up

            // 两个子节点都可用，则直接设置父节点的层级
            if (val1 == logChild && val2 == logChild) {
                setValue(parentId, (byte) (logChild - 1));
            } else {
                // 两个子节点任一不可用，则取子节点较小值，并设置到父节点
                byte val = val1 < val2 ? val1 : val2;
                setValue(parentId, val);
            }
            // 跳到父节点
            id = parentId;
        }
    }

    /**
     * Algorithm to allocate an index in memoryMap when we query for a free node
     * at depth d
     * 分配节点
     *
     * @param d depth
     * @return index in memoryMap
     */
    private int allocateNode(int d) {
        int id = 1;
        int initial = -(1 << d); // has last d bits = 0 and rest all = 1
        byte val = value(id);
        // 获得根节点的指值。
        // 如果根节点的值，大于 d ，说明，第 d 层没有符合的节点，
        // 也就是说 [0, d-1] 层也没有符合的节点。即，当前 Chunk 没有符合的节点
        if (val > d) { // unusable
            return -1;
        }
        // 获得第 d 层，匹配的节点。
        // id & initial 来保证，高度小于 d 会继续循环
        while (val < d || (id & initial) == 0) { // id & initial == 1 << d for all ids at depth d, for < d it is 0
            // 进入下一层
            // 获得左节点的编号
            id <<= 1;
            // 获得左节点的值
            val = value(id);
            // 如果值大于 d ，说明，以左节点作为根节点形成虚拟的虚拟满二叉树，没有符合的节点
            if (val > d) {
                // 获得右节点的编号
                id ^= 1;
                // 获得右节点的值
                val = value(id);
            }
        }
        // 校验获得的节点值合理
        byte value = value(id);
        assert value == d && (id & initial) == 1 << d : String.format("val = %d, id & initial = %d, d = %d",
                value, id & initial, d);

        // 更新获得的节点不可用
        setValue(id, unusable); // mark as unusable
        // 更新获得的节点的祖先都不可用
        updateParentsAlloc(id);
        // 返回节点编号
        return id;
    }

    /**
     * Allocate a run of pages (>=1)
     *
     * @param normCapacity normalized capacity
     * @return index in memoryMap
     */
    private long allocateRun(int normCapacity) {
        // 获得层级
        int d = maxOrder - (log2(normCapacity) - pageShifts);
        // 获得节点
        int id = allocateNode(d);
        // 未获得到节点，直接返回
        if (id < 0) {
            return id;
        }
        // 减少剩余可用字节数
        freeBytes -= runLength(id);
        return id;
    }

    /**
     * Create / initialize a new PoolSubpage of normCapacity
     * Any PoolSubpage created / initialized here is added to subpage pool in the PoolArena that owns this PoolChunk
     *
     * @param normCapacity normalized capacity
     * @return index in memoryMap
     */
    private long allocateSubpage(int normCapacity) {
        // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
        // This is need as we may add it back and so alter the linked-list structure.
        // 获得对应内存规格的 Subpage 双向链表的 head 节点
        PoolSubpage<T> head = arena.findSubpagePoolHead(normCapacity);
        int d = maxOrder; // subpages are only be allocated from pages i.e., leaves
        // 加锁，分配过程会修改双向链表的结构，会存在多线程的情况
        synchronized (head) {
            // 获得最底层的一个节点。Subpage 只能使用二叉树的最底层的节点
            int id = allocateNode(d);
            // 获取失败，直接返回
            if (id < 0) {
                return id;
            }

            final PoolSubpage<T>[] subpages = this.subpages;
            final int pageSize = this.pageSize;

            // 减少剩余可用字节数
            freeBytes -= pageSize;

            // 获得节点对应的 subpages 数组的编号
            int subpageIdx = subpageIdx(id);
            // 获得节点对应的 subpages 数组的 PoolSubpage 对象
            PoolSubpage<T> subpage = subpages[subpageIdx];
            if (subpage == null) {
                // 不存在，则进行创建 PoolSubpage 对象
                subpage = new PoolSubpage<T>(head, this, id, runOffset(id), pageSize, normCapacity);
                subpages[subpageIdx] = subpage;
            } else {
                // 存在，则重新初始化 PoolSubpage 对象
                subpage.init(head, normCapacity);
            }
            return subpage.allocate();
        }
    }

    /**
     * Free a subpage or a run of pages
     * When a subpage is freed from PoolSubpage, it might be added back to subpage pool of the owning PoolArena
     * If the subpage pool in PoolArena has at least one other PoolSubpage of given elemSize, we can
     * completely free the owning Page so it is available for subsequent allocations
     * <p>
     * 释放指定位置的内存块。根据情况，内存块可能是 SubPage ，也可能是 Page ，也可能是释放 SubPage 并且释放对应的 Page
     *
     * @param handle handle to free
     */
    void free(long handle, ByteBuffer nioBuffer) {
        // 获得 memoryMap 数组的编号( 下标 )
        int memoryMapIdx = memoryMapIdx(handle);
        // 获得 bitmap 数组的编号( 下标 )。注意，此时获得的还不是真正的 bitmapIdx 值，需要经过 `bitmapIdx & 0x3FFFFFFF` 运算
        int bitmapIdx = bitmapIdx(handle);

        if (bitmapIdx != 0) { // free a subpage 非空，说明释放的是 Subpage
            // 获得 PoolSubpage 对象
            PoolSubpage<T> subpage = subpages[subpageIdx(memoryMapIdx)];
            assert subpage != null && subpage.doNotDestroy;

            // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
            // This is need as we may add it back and so alter the linked-list structure.
            // 获得对应内存规格的 Subpage 双向链表的 head 节点
            PoolSubpage<T> head = arena.findSubpagePoolHead(subpage.elemSize);
            // 加锁，分配过程会修改双向链表的结构，会存在多线程的情况。
            synchronized (head) {
                // 释放 Subpage
                if (subpage.free(head, bitmapIdx & 0x3FFFFFFF)) {
                    // ↑↑↑ 返回 false ，说明 Page 中无切分正在使用的 Subpage 内存块，所以可以继续向下执行，释放 Page
                    return;
                }
            }
        }
        // 增加剩余可用字节数
        freeBytes += runLength(memoryMapIdx);
        // 设置 Page 对应的节点可用
        setValue(memoryMapIdx, depth(memoryMapIdx));
        // 更新 Page 对应的节点的祖先可用
        updateParentsFree(memoryMapIdx);

        if (nioBuffer != null && cachedNioBuffers != null &&
                cachedNioBuffers.size() < PooledByteBufAllocator.DEFAULT_MAX_CACHED_BYTEBUFFERS_PER_CHUNK) {
            cachedNioBuffers.offer(nioBuffer);
        }
    }

    void initBuf(PooledByteBuf<T> buf, ByteBuffer nioBuffer, long handle, int reqCapacity) {
        // 获得 memoryMap 数组的编号( 下标 )
        int memoryMapIdx = memoryMapIdx(handle);
        // 获得 bitmap 数组的编号( 下标 )。注意，此时获得的还不是真正的 bitmapIdx 值，需要经过 `bitmapIdx & 0x3FFFFFFF` 运算
        int bitmapIdx = bitmapIdx(handle);
        // 内存块为 Page
        if (bitmapIdx == 0) {
            byte val = value(memoryMapIdx);
            assert val == unusable : String.valueOf(val);
            // 初始化 Page 内存块到 PooledByteBuf 中
            //runOffset(memoryMapIdx) + offset 代码块，计算 Page 内存块在 memory 中的开始位置
            buf.init(this, nioBuffer, handle, runOffset(memoryMapIdx) + offset,
                    reqCapacity, runLength(memoryMapIdx), arena.parent.threadCache());
        } else {
            // 内存块为 SubPage
            // 初始化 SubPage 内存块到 PooledByteBuf 中
            initBufWithSubpage(buf, nioBuffer, handle, bitmapIdx, reqCapacity);
        }
    }

    void initBufWithSubpage(PooledByteBuf<T> buf, ByteBuffer nioBuffer, long handle, int reqCapacity) {
        initBufWithSubpage(buf, nioBuffer, handle, bitmapIdx(handle), reqCapacity);
    }

    private void initBufWithSubpage(PooledByteBuf<T> buf, ByteBuffer nioBuffer,
                                    long handle, int bitmapIdx, int reqCapacity) {
        assert bitmapIdx != 0;
        // 获得 memoryMap 数组的编号( 下标 )
        int memoryMapIdx = memoryMapIdx(handle);
        // 获得 SubPage 对象
        PoolSubpage<T> subpage = subpages[subpageIdx(memoryMapIdx)];
        assert subpage.doNotDestroy;
        assert reqCapacity <= subpage.elemSize;

        // 初始化 SubPage 内存块到 PooledByteBuf 中
        //其中，runOffset(memoryMapIdx) + (bitmapIdx & 0x3FFFFFFF) * subpage.elemSize + offset 代码块，
        // 计算 SubPage 内存块在 memory 中的开始位置。
        buf.init(
                this, nioBuffer, handle,
                runOffset(memoryMapIdx) + (bitmapIdx & 0x3FFFFFFF) * subpage.elemSize + offset,
                reqCapacity, subpage.elemSize, arena.parent.threadCache());
    }

    private byte value(int id) {
        return memoryMap[id];
    }

    private void setValue(int id, byte val) {
        memoryMap[id] = val;
    }

    private byte depth(int id) {
        return depthMap[id];
    }

    private static int log2(int val) {
        // compute the (0-based, with lsb = 0) position of highest set bit i.e, log2
        return INTEGER_SIZE_MINUS_ONE - Integer.numberOfLeadingZeros(val);
    }

    private int runLength(int id) {
        // represents the size in #bytes supported by node 'id' in the tree
        return 1 << log2ChunkSize - depth(id);
    }

    private int runOffset(int id) {
        // represents the 0-based offset in #bytes from start of the byte-array chunk
        int shift = id ^ 1 << depth(id);
        return shift * runLength(id);
    }

    private int subpageIdx(int memoryMapIdx) {
        return memoryMapIdx ^ maxSubpageAllocs; // remove highest set bit, to get offset
    }

    private static int memoryMapIdx(long handle) {
        return (int) handle;
    }

    private static int bitmapIdx(long handle) {
        //获得 bitmap 数组的编号( 下标
        return (int) (handle >>> Integer.SIZE);
    }

    @Override
    public int chunkSize() {
        return chunkSize;
    }

    @Override
    public int freeBytes() {
        synchronized (arena) {
            return freeBytes;
        }
    }

    @Override
    public String toString() {
        final int freeBytes;
        synchronized (arena) {
            freeBytes = this.freeBytes;
        }

        return new StringBuilder()
                .append("Chunk(")
                .append(Integer.toHexString(System.identityHashCode(this)))
                .append(": ")
                .append(usage(freeBytes))
                .append("%, ")
                .append(chunkSize - freeBytes)
                .append('/')
                .append(chunkSize)
                .append(')')
                .toString();
    }

    void destroy() {
        //从 Arena 中销毁当前 Chunk
        arena.destroyChunk(this);
    }
}
