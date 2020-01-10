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

import io.netty.util.internal.LongCounter;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.StringUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static io.netty.util.internal.ObjectUtil.checkPositiveOrZero;
import static java.lang.Math.max;

/**
 * 在应用程序里，我们可以使用 PooledByteBufAllocator 来创建 ByteBuf 对象。代码如下：
 * <p>
 * PooledByteBufAllocator.DEFAULT.buffer(1024);
 * 在方法的内部实现，通过 PoolArena 来进行内存分配
 * <p>
 * 实现 PoolArenaMetric 接口，Netty 对 Jemalloc Arena 的抽象实现类。
 * <p>
 * PoolArena 有两个子类实现：
 * <p>
 * HeapArena ，对 Heap 类型的内存分配。
 * DirectArena ，对 Direct 类型的内存分配。
 */
abstract class PoolArena<T> implements PoolArenaMetric {

    /**
     * 是否支持 Unsafe 操作
     */
    static final boolean HAS_UNSAFE = PlatformDependent.hasUnsafe();

    /**
     * 内存分类
     */
    enum SizeClass {
        Tiny,
        Small,
        Normal
        // 还有一个隐藏的，Huge
    }

    /**
     * {@link #tinySubpagePools} 数组的大小
     * <p>
     * 默认为 32
     */
    static final int numTinySubpagePools = 512 >>> 4;

    /**
     * 所属 PooledByteBufAllocator 对象
     */
    final PooledByteBufAllocator parent;

    /**
     * 满二叉树的高度。默认为 11 。
     */
    private final int maxOrder;

    /**
     * Page 大小，默认 8KB = 8192B
     */
    final int pageSize;

    /**
     * 从 1 开始左移到 {@link #pageSize} 的位数。默认 13 ，1 << 13 = 8192 。
     */
    final int pageShifts;

    /**
     * Chunk 内存块占用大小。默认为 16M = 16 * 1024  。
     */
    final int chunkSize;

    /**
     * 判断分配请求内存是否为 Tiny/Small ，即分配 Subpage 内存块。
     * <p>
     * Used to determine if the requested capacity is equal to or greater than pageSize.
     */
    final int subpageOverflowMask;

    /**
     * {@link #smallSubpagePools} 数组的大小
     * <p>
     * 默认为 23
     */
    final int numSmallSubpagePools;

    /**
     * 对齐基准
     */
    final int directMemoryCacheAlignment;

    /**
     * {@link #directMemoryCacheAlignment} 掩码
     */
    final int directMemoryCacheAlignmentMask;

    /**
     * tiny 类型的 PoolSubpage 数组
     * <p>
     * 数组的每个元素，都是双向链表
     */
    private final PoolSubpage<T>[] tinySubpagePools;

    /**
     * small 类型的 SubpagePools 数组
     * <p>
     * 数组的每个元素，都是双向链表
     */
    private final PoolSubpage<T>[] smallSubpagePools;

    // PoolChunkList 之间的双向链表
    private final PoolChunkList<T> q050;
    private final PoolChunkList<T> q025;
    private final PoolChunkList<T> q000;
    private final PoolChunkList<T> qInit;
    private final PoolChunkList<T> q075;
    private final PoolChunkList<T> q100;

    /**
     * PoolChunkListMetric 数组
     */
    private final List<PoolChunkListMetric> chunkListMetrics;

    // Metrics for allocations and deallocations
    /**
     * 分配 Normal 内存块的次数
     */
    private long allocationsNormal;
    // We need to use the LongCounter here as this is not guarded via synchronized block.

    /**
     * 分配 Tiny 内存块的次数
     */
    private final LongCounter allocationsTiny = PlatformDependent.newLongCounter();
    /**
     * 分配 Small 内存块的次数
     */
    private final LongCounter allocationsSmall = PlatformDependent.newLongCounter();
    /**
     * 分配 Huge 内存块的次数
     */
    private final LongCounter allocationsHuge = PlatformDependent.newLongCounter();
    /**
     * 正在使用中的 Huge 内存块的总共占用字节数
     */
    private final LongCounter activeBytesHuge = PlatformDependent.newLongCounter();

    /**
     * 释放 Tiny 内存块的次数
     */
    private long deallocationsTiny;

    /**
     * 释放 Small 内存块的次数
     */
    private long deallocationsSmall;

    /**
     * 释放 Normal 内存块的次数
     */
    private long deallocationsNormal;

    // We need to use the LongCounter here as this is not guarded via synchronized block.
    /**
     * 释放 Huge 内存块的次数
     */
    private final LongCounter deallocationsHuge = PlatformDependent.newLongCounter();

    //↑↑↑ 上述属性使用 LongCounter 还是 long 类型，主要是变量访问时，是否在 synchronized {} 代码块中访问，从而保证内存的可见性

    // Number of thread caches backed by this arena.
    /**
     * 该 PoolArena 被多少线程引用的计数器
     */
    final AtomicInteger numThreadCaches = new AtomicInteger();

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    protected PoolArena(PooledByteBufAllocator parent, int pageSize,
                        int maxOrder, int pageShifts, int chunkSize, int cacheAlignment) {
        this.parent = parent;
        this.pageSize = pageSize;
        this.maxOrder = maxOrder;
        this.pageShifts = pageShifts;
        this.chunkSize = chunkSize;
        directMemoryCacheAlignment = cacheAlignment;
        directMemoryCacheAlignmentMask = cacheAlignment - 1;
        subpageOverflowMask = ~(pageSize - 1);

        // 初始化 tinySubpagePools 数组
        tinySubpagePools = newSubpagePoolArray(numTinySubpagePools);
        for (int i = 0; i < tinySubpagePools.length; i++) {
            tinySubpagePools[i] = newSubpagePoolHead(pageSize);
        }

        // 初始化 smallSubpagePools 数组
        numSmallSubpagePools = pageShifts - 9;
        smallSubpagePools = newSubpagePoolArray(numSmallSubpagePools);
        for (int i = 0; i < smallSubpagePools.length; i++) {
            smallSubpagePools[i] = newSubpagePoolHead(pageSize);
        }

        // PoolChunkList 之间的双向链表，初始化
        q100 = new PoolChunkList<T>(this, null, 100, Integer.MAX_VALUE, chunkSize);
        q075 = new PoolChunkList<T>(this, q100, 75, 100, chunkSize);
        q050 = new PoolChunkList<T>(this, q075, 50, 100, chunkSize);
        q025 = new PoolChunkList<T>(this, q050, 25, 75, chunkSize);
        q000 = new PoolChunkList<T>(this, q025, 1, 50, chunkSize);
        qInit = new PoolChunkList<T>(this, q000, Integer.MIN_VALUE, 25, chunkSize);

        q100.prevList(q075);
        q075.prevList(q050);
        q050.prevList(q025);
        q025.prevList(q000);
        q000.prevList(null);// 无前置节点
        qInit.prevList(qInit);// 前置节点为自己

        // 创建 PoolChunkListMetric 数组
        List<PoolChunkListMetric> metrics = new ArrayList<PoolChunkListMetric>(6);
        metrics.add(qInit);
        metrics.add(q000);
        metrics.add(q025);
        metrics.add(q050);
        metrics.add(q075);
        metrics.add(q100);
        chunkListMetrics = Collections.unmodifiableList(metrics);
    }

    private PoolSubpage<T> newSubpagePoolHead(int pageSize) {
        PoolSubpage<T> head = new PoolSubpage<T>(pageSize);
        head.prev = head;
        head.next = head;
        return head;
    }

    @SuppressWarnings("unchecked")
    private PoolSubpage<T>[] newSubpagePoolArray(int size) {
        return new PoolSubpage[size];
    }

    abstract boolean isDirect();

    PooledByteBuf<T> allocate(PoolThreadCache cache, int reqCapacity, int maxCapacity) {
        //创建ByteBuf
        PooledByteBuf<T> buf = newByteBuf(maxCapacity);
        //上一步只是创建ByteBuf对象，实际的内存分配（byte[]或ByteBuffer）在下一步完成
        allocate(cache, buf, reqCapacity);
        return buf;
    }

    /**
     * 计算请求分配的内存大小在 tinySubpagePools 数组的下标
     */
    static int tinyIdx(int normCapacity) {
        //除以16B
        return normCapacity >>> 4;
    }

    /**
     * 计算请求分配的内存大小在 smallSubpagePools 数组的下标
     */
    static int smallIdx(int normCapacity) {
        int tableIdx = 0;
        int i = normCapacity >>> 10;
        while (i != 0) {
            i >>>= 1;
            tableIdx++;
        }
        return tableIdx;
    }

    // capacity < pageSize
    //判断请求分配的内存类型是否为 tiny 或 small 类型
    boolean isTinyOrSmall(int normCapacity) {
        return (normCapacity & subpageOverflowMask) == 0;
    }

    // normCapacity < 512
    //判断请求分配的内存类型是否为 tiny 类型
    static boolean isTiny(int normCapacity) {
        return (normCapacity & 0xFFFFFE00) == 0;
    }

    /**
     * PoolArena 根据申请分配的内存大小不同，提供了两种方式分配内存：
     * <p>
     * 1、PoolSubpage ，用于分配小于 8KB 的内存块
     * 1.1 tinySubpagePools 属性，用于分配小于 512B 的 tiny Subpage 内存块。
     * 1.2 smallSubpagePools 属性，用于分配小于 8KB 的 small Subpage 内存块。
     * 2、PoolChunkList ，用于分配大于等于 8KB 的内存块
     * 2.1 小于 32MB ，分配 normal 内存块，即一个 Chunk 中的 Page 内存块。
     * 2.2 大于等于 32MB ，分配 huge 内存块，即一整个 Chunk 内存块。
     */
    private void allocate(PoolThreadCache cache, PooledByteBuf<T> buf, final int reqCapacity) {
        // 标准化请求分配的容量
        final int normCapacity = normalizeCapacity(reqCapacity);
        // PoolSubpage 的情况
        if (isTinyOrSmall(normCapacity)) { // capacity < pageSize
            int tableIdx;
            PoolSubpage<T>[] table;
            // 判断是否为 tiny 类型的内存块申请
            boolean tiny = isTiny(normCapacity);
            if (tiny) { // < 512 类型的内存块申请
                //从线程缓存中分配内存，如果成功直接返回
                if (cache.allocateTiny(this, buf, reqCapacity, normCapacity)) {
                    // was able to allocate out of the cache so move on
                    return;
                }
                // 获得 tableIdx 和 table 属性
                tableIdx = tinyIdx(normCapacity);
                table = tinySubpagePools;
            } else {
                // 从 PoolThreadCache 缓存中，分配 small 内存块，并初始化到 PooledByteBuf 中。
                if (cache.allocateSmall(this, buf, reqCapacity, normCapacity)) {
                    // was able to allocate out of the cache so move on
                    return;
                }
                tableIdx = smallIdx(normCapacity);
                table = smallSubpagePools;
            }

            //如果从线程缓存中分配失败了，执行下方的--------

            // 获得 PoolSubpage 链表的头节点
            final PoolSubpage<T> head = table[tableIdx];

            /**
             * Synchronize on the head. This is needed as {@link PoolChunk#allocateSubpage(int)} and
             * {@link PoolChunk#free(long)} may modify the doubly linked list as well.
             */
            // 从 PoolSubpage 链表中，分配 Subpage 内存块
            synchronized (head) {
                final PoolSubpage<T> s = head.next;
                if (s != head) {//如果这一个尺寸PoolSubpage的组只有一个节点的话，head.next==head自身
                    assert s.doNotDestroy && s.elemSize == normCapacity;
                    // 分配 Subpage 内存块
                    long handle = s.allocate();
                    assert handle >= 0;
                    // 初始化 Subpage 内存块到 PooledByteBuf 对象中
                    s.chunk.initBufWithSubpage(buf, null, handle, reqCapacity);
                    // 增加 allocationsTiny 或 allocationsSmall 计数
                    incTinySmallAllocation(tiny);
                    // 返回，因为已经分配成功
                    return;
                }
            }
            // 申请 Normal Page 内存块。实际上，只占用其中一块 Subpage 内存块
            synchronized (this) {
                allocateNormal(buf, reqCapacity, normCapacity);
            }
            // 增加 allocationsTiny 或 allocationsSmall 计数
            incTinySmallAllocation(tiny);
            return;
        }
        if (normCapacity <= chunkSize) {
            // 从 PoolThreadCache 缓存中，分配 normal 内存块，并初始化到 PooledByteBuf 中
            if (cache.allocateNormal(this, buf, reqCapacity, normCapacity)) {
                // was able to allocate out of the cache so move on
                return;
            }
            // 申请 Normal Page 内存块
            synchronized (this) {
                allocateNormal(buf, reqCapacity, normCapacity);
                ++allocationsNormal;
            }
        } else {
            // 申请 Huge Page 内存块，huge内存块不池化
            // Huge allocations are never served via the cache so just call allocateHuge
            allocateHuge(buf, reqCapacity);
        }
    }

    // Method must be called inside synchronized(this) { ... } block

    /**
     * 申请 Normal Page 内存块
     */
    private void allocateNormal(PooledByteBuf<T> buf, int reqCapacity, int normCapacity) {
        // 按照优先级，从多个 ChunkList 中，分配 Normal Page 内存块。如果有一分配成功，返回
        if (q050.allocate(buf, reqCapacity, normCapacity) || q025.allocate(buf, reqCapacity, normCapacity) ||
                q000.allocate(buf, reqCapacity, normCapacity) || qInit.allocate(buf, reqCapacity, normCapacity) ||
                q075.allocate(buf, reqCapacity, normCapacity)) {
            return;
        }

        // Add a new chunk.
        // 新建 Chunk 内存块
        PoolChunk<T> c = newChunk(pageSize, maxOrder, pageShifts, chunkSize);
        // 申请对应的 Normal Page 内存块。实际上，如果申请分配的内存类型为 tiny 或 small 类型，实际申请的是 Subpage 内存块
        //将申请的ByteBuffer内存分配给ByteBuf对象
        boolean success = c.allocate(buf, reqCapacity, normCapacity);
        assert success;
        // 添加到 ChunkList 双向链中
        qInit.add(c);
    }

    private void incTinySmallAllocation(boolean tiny) {
        if (tiny) {
            allocationsTiny.increment();
        } else {
            allocationsSmall.increment();
        }
    }

    /**
     * 申请 Huge 内存块。
     */
    private void allocateHuge(PooledByteBuf<T> buf, int reqCapacity) {
        // 新建 Chunk 内存块，它是 unpooled 的
        PoolChunk<T> chunk = newUnpooledChunk(reqCapacity);
        // 增加 activeBytesHuge
        activeBytesHuge.add(chunk.chunkSize());
        // 初始化 Huge 内存块到 PooledByteBuf 对象中
        buf.initUnpooled(chunk, reqCapacity);
        // 增加 allocationsHuge
        allocationsHuge.increment();
    }

    /**
     * 释放内存块
     */
    void free(PoolChunk<T> chunk, ByteBuffer nioBuffer, long handle, int normCapacity, PoolThreadCache cache) {
        //如果chunk不需要池化，例如huge类型
        if (chunk.unpooled) {
            int size = chunk.chunkSize();
            // 直接销毁 Chunk 内存块，因为占用空间较大
            destroyChunk(chunk);
            // 减少 activeBytesHuge 计数
            activeBytesHuge.add(-size);
            // 减少 deallocationsHuge 计数
            deallocationsHuge.increment();
        } else {
            //chunk需要池化，tiny/small/normal类型
            // 计算内存的 SizeClass
            SizeClass sizeClass = sizeClass(normCapacity);
            // 添加内存块到 PoolThreadCache 缓存，相当于回收到线程缓存中
            if (cache != null && cache.add(this, chunk, nioBuffer, handle, normCapacity, sizeClass)) {
                // cached so not free it.
                return;
            }
            // 释放 Page / Subpage 内存块回 Chunk 中
            freeChunk(chunk, handle, sizeClass, nioBuffer, false);
        }
    }

    void freeChunk(PoolChunk<T> chunk, long handle, SizeClass sizeClass, ByteBuffer nioBuffer, boolean finalizer) {
        final boolean destroyChunk;
        synchronized (this) {// 锁，避免并发
            // We only call this if freeChunk is not called because of the PoolThreadCache finalizer as otherwise this
            // may fail due lazy class-loading in for example tomcat.
            // 减小相应的计数
            if (!finalizer) {
                switch (sizeClass) {
                    case Normal:
                        ++deallocationsNormal;
                        break;
                    case Small:
                        ++deallocationsSmall;
                        break;
                    case Tiny:
                        ++deallocationsTiny;
                        break;
                    default:
                        throw new Error();
                }
            }
            // 释放指定位置的内存块
            destroyChunk = !chunk.parent.free(chunk, handle, nioBuffer);
        }
        // 当 destroyChunk 为 true 时，意味着 Chunk 中不存在在使用的 Page / Subpage 内存块。也就是说，内存使用率为 0 ，所以销毁 Chunk
        if (destroyChunk) {
            // destroyChunk not need to be called while holding the synchronized lock.
            destroyChunk(chunk);
        }
    }

    /**
     * 计算请求分配的内存的内存类型
     */
    private SizeClass sizeClass(int normCapacity) {
        if (!isTinyOrSmall(normCapacity)) {
            return SizeClass.Normal;
        }
        return isTiny(normCapacity) ? SizeClass.Tiny : SizeClass.Small;
    }

    /**
     * 获得请求分配的 Subpage 类型的内存的链表的头节点
     */
    PoolSubpage<T> findSubpagePoolHead(int elemSize) {
        int tableIdx;
        PoolSubpage<T>[] table;
        if (isTiny(elemSize)) { // < 512
            // 实际上，就是 `#tinyIdx(int normCapacity)` 方法
            tableIdx = elemSize >>> 4;
            table = tinySubpagePools;
        } else {
            // 实际上，就是 `#smallIdx(int normCapacity)` 方法
            tableIdx = 0;
            elemSize >>>= 10;
            while (elemSize != 0) {
                elemSize >>>= 1;
                tableIdx++;
            }
            // 获得 table
            table = smallSubpagePools;
        }
        // 获得 Subpage 链表的头节点
        return table[tableIdx];
    }

    /**
     * 标准化请求分配的内存大小。通过这样的方式，保证分配的内存块统一
     */
    int normalizeCapacity(int reqCapacity) {
        checkPositiveOrZero(reqCapacity, "reqCapacity");

        // Huge 内存类型，直接使用 reqCapacity ，无需进行标准化
        if (reqCapacity >= chunkSize) {
            return directMemoryCacheAlignment == 0 ? reqCapacity : alignCapacity(reqCapacity);
        }
        // 非 tiny 内存类型
        if (!isTiny(reqCapacity)) { // >= 512
            // Doubled
            //Small、Normal 内存类型，转换成接近于两倍的容量
            int normalizedCapacity = reqCapacity;
            normalizedCapacity--;
            normalizedCapacity |= normalizedCapacity >>> 1;
            normalizedCapacity |= normalizedCapacity >>> 2;
            normalizedCapacity |= normalizedCapacity >>> 4;
            normalizedCapacity |= normalizedCapacity >>> 8;
            normalizedCapacity |= normalizedCapacity >>> 16;
            normalizedCapacity++;

            if (normalizedCapacity < 0) {
                normalizedCapacity >>>= 1;
            }
            assert directMemoryCacheAlignment == 0 || (normalizedCapacity & directMemoryCacheAlignmentMask) == 0;

            return normalizedCapacity;
        }

        if (directMemoryCacheAlignment > 0) {
            return alignCapacity(reqCapacity);
        }

        // Tiny 内存类型，补齐成 16 的倍数
        // Quantum-spaced
        if ((reqCapacity & 15) == 0) {
            return reqCapacity;
        }

        return (reqCapacity & ~15) + 16;
    }

    /**
     * 对齐请求分配的内存大小
     */
    int alignCapacity(int reqCapacity) {
        // 获得 delta
        int delta = reqCapacity & directMemoryCacheAlignmentMask;
        // 补齐 directMemoryCacheAlignment ，并减去 delta
        return delta == 0 ? reqCapacity : reqCapacity + directMemoryCacheAlignment - delta;
    }

    /**
     * 为要扩容或缩容，所以重新分配合适的内存块给 PooledByteBuf 对象
     */
    void reallocate(PooledByteBuf<T> buf, int newCapacity, boolean freeOldMemory) {
        if (newCapacity < 0 || newCapacity > buf.maxCapacity()) {
            throw new IllegalArgumentException("newCapacity: " + newCapacity);
        }
        int oldCapacity = buf.length;
        // 容量大小没有变化，直接返回
        if (oldCapacity == newCapacity) {
            return;
        }

        // 记录老的内存块的信息
        PoolChunk<T> oldChunk = buf.chunk;
        ByteBuffer oldNioBuffer = buf.tmpNioBuf;
        long oldHandle = buf.handle;
        T oldMemory = buf.memory;
        int oldOffset = buf.offset;
        int oldMaxLength = buf.maxLength;

        // 记录读写索引
        int readerIndex = buf.readerIndex();
        int writerIndex = buf.writerIndex();

        // 分配新的内存块给 PooledByteBuf 对象
        allocate(parent.threadCache(), buf, newCapacity);

        // 扩容
        if (newCapacity > oldCapacity) {
            // 将老的内存块的数据，复制到新的内存块中
            memoryCopy(
                    oldMemory, oldOffset,
                    buf.memory, buf.offset, oldCapacity);
        } else if (newCapacity < oldCapacity) {
            // 缩容
            if (readerIndex < newCapacity) {
                // 如果 writerIndex 大于 newCapacity ，重置为 newCapacity ，避免越界
                if (writerIndex > newCapacity) {
                    writerIndex = newCapacity;
                }
                // 将老的内存块的数据，复制到新的内存块中
                memoryCopy(
                        oldMemory, oldOffset + readerIndex,
                        buf.memory, buf.offset + readerIndex, writerIndex - readerIndex);
            } else {
                readerIndex = writerIndex = newCapacity;
            }
        }
        // 设置读写索引
        buf.setIndex(readerIndex, writerIndex);
        // 释放老的内存块
        if (freeOldMemory) {
            free(oldChunk, oldNioBuffer, oldHandle, oldMaxLength, buf.cache);
        }
    }

    @Override
    public int numThreadCaches() {
        return numThreadCaches.get();
    }

    @Override
    public int numTinySubpages() {
        return tinySubpagePools.length;
    }

    @Override
    public int numSmallSubpages() {
        return smallSubpagePools.length;
    }

    @Override
    public int numChunkLists() {
        return chunkListMetrics.size();
    }

    @Override
    public List<PoolSubpageMetric> tinySubpages() {
        return subPageMetricList(tinySubpagePools);
    }

    @Override
    public List<PoolSubpageMetric> smallSubpages() {
        return subPageMetricList(smallSubpagePools);
    }

    @Override
    public List<PoolChunkListMetric> chunkLists() {
        return chunkListMetrics;
    }

    private static List<PoolSubpageMetric> subPageMetricList(PoolSubpage<?>[] pages) {
        List<PoolSubpageMetric> metrics = new ArrayList<PoolSubpageMetric>();
        for (PoolSubpage<?> head : pages) {
            if (head.next == head) {
                continue;
            }
            PoolSubpage<?> s = head.next;
            for (; ; ) {
                metrics.add(s);
                s = s.next;
                if (s == head) {
                    break;
                }
            }
        }
        return metrics;
    }

    @Override
    public long numAllocations() {
        final long allocsNormal;
        synchronized (this) {
            allocsNormal = allocationsNormal;
        }
        return allocationsTiny.value() + allocationsSmall.value() + allocsNormal + allocationsHuge.value();
    }

    @Override
    public long numTinyAllocations() {
        return allocationsTiny.value();
    }

    @Override
    public long numSmallAllocations() {
        return allocationsSmall.value();
    }

    @Override
    public synchronized long numNormalAllocations() {
        return allocationsNormal;
    }

    @Override
    public long numDeallocations() {
        final long deallocs;
        synchronized (this) {
            deallocs = deallocationsTiny + deallocationsSmall + deallocationsNormal;
        }
        return deallocs + deallocationsHuge.value();
    }

    @Override
    public synchronized long numTinyDeallocations() {
        return deallocationsTiny;
    }

    @Override
    public synchronized long numSmallDeallocations() {
        return deallocationsSmall;
    }

    @Override
    public synchronized long numNormalDeallocations() {
        return deallocationsNormal;
    }

    @Override
    public long numHugeAllocations() {
        return allocationsHuge.value();
    }

    @Override
    public long numHugeDeallocations() {
        return deallocationsHuge.value();
    }

    @Override
    public long numActiveAllocations() {
        long val = allocationsTiny.value() + allocationsSmall.value() + allocationsHuge.value()
                - deallocationsHuge.value();
        synchronized (this) {
            val += allocationsNormal - (deallocationsTiny + deallocationsSmall + deallocationsNormal);
        }
        return max(val, 0);
    }

    @Override
    public long numActiveTinyAllocations() {
        return max(numTinyAllocations() - numTinyDeallocations(), 0);
    }

    @Override
    public long numActiveSmallAllocations() {
        return max(numSmallAllocations() - numSmallDeallocations(), 0);
    }

    @Override
    public long numActiveNormalAllocations() {
        final long val;
        synchronized (this) {
            val = allocationsNormal - deallocationsNormal;
        }
        return max(val, 0);
    }

    @Override
    public long numActiveHugeAllocations() {
        return max(numHugeAllocations() - numHugeDeallocations(), 0);
    }

    @Override
    public long numActiveBytes() {
        long val = activeBytesHuge.value();
        synchronized (this) {
            for (int i = 0; i < chunkListMetrics.size(); i++) {
                for (PoolChunkMetric m : chunkListMetrics.get(i)) {
                    val += m.chunkSize();
                }
            }
        }
        return max(0, val);
    }

    /**
     * 新建 Chunk 内存块。需要 PoolArea 子类实现该方法
     */
    protected abstract PoolChunk<T> newChunk(int pageSize, int maxOrder, int pageShifts, int chunkSize);

    protected abstract PoolChunk<T> newUnpooledChunk(int capacity);

    protected abstract PooledByteBuf<T> newByteBuf(int maxCapacity);

    protected abstract void memoryCopy(T src, int srcOffset, T dst, int dstOffset, int length);

    protected abstract void destroyChunk(PoolChunk<T> chunk);

    @Override
    public synchronized String toString() {
        StringBuilder buf = new StringBuilder()
                .append("Chunk(s) at 0~25%:")
                .append(StringUtil.NEWLINE)
                .append(qInit)
                .append(StringUtil.NEWLINE)
                .append("Chunk(s) at 0~50%:")
                .append(StringUtil.NEWLINE)
                .append(q000)
                .append(StringUtil.NEWLINE)
                .append("Chunk(s) at 25~75%:")
                .append(StringUtil.NEWLINE)
                .append(q025)
                .append(StringUtil.NEWLINE)
                .append("Chunk(s) at 50~100%:")
                .append(StringUtil.NEWLINE)
                .append(q050)
                .append(StringUtil.NEWLINE)
                .append("Chunk(s) at 75~100%:")
                .append(StringUtil.NEWLINE)
                .append(q075)
                .append(StringUtil.NEWLINE)
                .append("Chunk(s) at 100%:")
                .append(StringUtil.NEWLINE)
                .append(q100)
                .append(StringUtil.NEWLINE)
                .append("tiny subpages:");
        appendPoolSubPages(buf, tinySubpagePools);
        buf.append(StringUtil.NEWLINE)
                .append("small subpages:");
        appendPoolSubPages(buf, smallSubpagePools);
        buf.append(StringUtil.NEWLINE);

        return buf.toString();
    }

    private static void appendPoolSubPages(StringBuilder buf, PoolSubpage<?>[] subpages) {
        for (int i = 0; i < subpages.length; i++) {
            PoolSubpage<?> head = subpages[i];
            if (head.next == head) {
                continue;
            }

            buf.append(StringUtil.NEWLINE)
                    .append(i)
                    .append(": ");
            PoolSubpage<?> s = head.next;
            for (; ; ) {
                buf.append(s);
                s = s.next;
                if (s == head) {
                    break;
                }
            }
        }
    }

    @Override
    protected final void finalize() throws Throwable {
        try {
            // 调用父方法
            super.finalize();
        } finally {
            // 清理 tiny Subpage 们
            destroyPoolSubPages(smallSubpagePools);
            // 清理 small Subpage 们
            destroyPoolSubPages(tinySubpagePools);
            // 清理 ChunkList 们
            destroyPoolChunkLists(qInit, q000, q025, q050, q075, q100);
        }
    }

    private static void destroyPoolSubPages(PoolSubpage<?>[] pages) {
        for (PoolSubpage<?> page : pages) {
            page.destroy();
        }
    }

    private void destroyPoolChunkLists(PoolChunkList<T>... chunkLists) {
        for (PoolChunkList<T> chunkList : chunkLists) {
            chunkList.destroy(this);
        }
    }

    /**
     * 继承 PoolArena 抽象类，对 Heap 类型的内存分配
     */
    static final class HeapArena extends PoolArena<byte[]> {

        HeapArena(PooledByteBufAllocator parent, int pageSize, int maxOrder,
                  int pageShifts, int chunkSize, int directMemoryCacheAlignment) {
            super(parent, pageSize, maxOrder, pageShifts, chunkSize,
                    directMemoryCacheAlignment);
        }

        private static byte[] newByteArray(int size) {
            return PlatformDependent.allocateUninitializedArray(size);
        }

        @Override
        boolean isDirect() {
            return false;
        }

        @Override
        protected PoolChunk<byte[]> newChunk(int pageSize, int maxOrder, int pageShifts, int chunkSize) {
            return new PoolChunk<byte[]>(this, newByteArray(chunkSize), pageSize, maxOrder, pageShifts, chunkSize, 0);
        }

        @Override
        protected PoolChunk<byte[]> newUnpooledChunk(int capacity) {
            return new PoolChunk<byte[]>(this, newByteArray(capacity), capacity, 0);
        }

        @Override
        protected void destroyChunk(PoolChunk<byte[]> chunk) {
            // Rely on GC. 依赖 GC
        }

        @Override
        protected PooledByteBuf<byte[]> newByteBuf(int maxCapacity) {
            return HAS_UNSAFE ? PooledUnsafeHeapByteBuf.newUnsafeInstance(maxCapacity)
                    : PooledHeapByteBuf.newInstance(maxCapacity);
        }

        @Override
        protected void memoryCopy(byte[] src, int srcOffset, byte[] dst, int dstOffset, int length) {
            if (length == 0) {
                return;
            }

            System.arraycopy(src, srcOffset, dst, dstOffset, length);
        }
    }

    /**
     * 对 Direct 类型的内存分配
     */
    static final class DirectArena extends PoolArena<ByteBuffer> {

        DirectArena(PooledByteBufAllocator parent, int pageSize, int maxOrder,
                    int pageShifts, int chunkSize, int directMemoryCacheAlignment) {
            super(parent, pageSize, maxOrder, pageShifts, chunkSize,
                    directMemoryCacheAlignment);
        }

        @Override
        boolean isDirect() {
            return true;
        }

        // mark as package-private, only for unit test
        int offsetCacheLine(ByteBuffer memory) {
            // We can only calculate the offset if Unsafe is present as otherwise directBufferAddress(...) will
            // throw an NPE.
            int remainder = HAS_UNSAFE
                    ? (int) (PlatformDependent.directBufferAddress(memory) & directMemoryCacheAlignmentMask)
                    : 0;

            // offset = alignment - address & (alignment - 1)
            return directMemoryCacheAlignment - remainder;
        }

        @Override
        protected PoolChunk<ByteBuffer> newChunk(int pageSize, int maxOrder,
                                                 int pageShifts, int chunkSize) {
            if (directMemoryCacheAlignment == 0) {
                //真正的向操作系统申请DirectByteBuffer内存
                return new PoolChunk<ByteBuffer>(this,
                        allocateDirect(chunkSize), pageSize, maxOrder,
                        pageShifts, chunkSize, 0);
            }
            final ByteBuffer memory = allocateDirect(chunkSize
                    + directMemoryCacheAlignment);
            return new PoolChunk<ByteBuffer>(this, memory, pageSize,
                    maxOrder, pageShifts, chunkSize,
                    offsetCacheLine(memory));
        }

        @Override
        protected PoolChunk<ByteBuffer> newUnpooledChunk(int capacity) {
            if (directMemoryCacheAlignment == 0) {
                return new PoolChunk<ByteBuffer>(this,
                        allocateDirect(capacity), capacity, 0);
            }
            final ByteBuffer memory = allocateDirect(capacity
                    + directMemoryCacheAlignment);
            return new PoolChunk<ByteBuffer>(this, memory, capacity,
                    offsetCacheLine(memory));
        }

        private static ByteBuffer allocateDirect(int capacity) {
            return PlatformDependent.useDirectBufferNoCleaner() ?
                    PlatformDependent.allocateDirectNoCleaner(capacity) : ByteBuffer.allocateDirect(capacity);
        }

        /**
         * 直接销毁chunk，针对大的内存块
         * 调用unsafe来释放，根据java的不同版本，使用cleaner来释放
         */
        @Override
        protected void destroyChunk(PoolChunk<ByteBuffer> chunk) {
            if (PlatformDependent.useDirectBufferNoCleaner()) {
                PlatformDependent.freeDirectNoCleaner(chunk.memory);
            } else {
                PlatformDependent.freeDirectBuffer(chunk.memory);
            }
        }

        @Override
        protected PooledByteBuf<ByteBuffer> newByteBuf(int maxCapacity) {
            //根据是否有Unsafe来决定使用哪个ByteBuf
            if (HAS_UNSAFE) {
                return PooledUnsafeDirectByteBuf.newInstance(maxCapacity);
            } else {
                return PooledDirectByteBuf.newInstance(maxCapacity);
            }
        }

        @Override
        protected void memoryCopy(ByteBuffer src, int srcOffset, ByteBuffer dst, int dstOffset, int length) {
            if (length == 0) {
                return;
            }

            if (HAS_UNSAFE) {
                PlatformDependent.copyMemory(
                        PlatformDependent.directBufferAddress(src) + srcOffset,
                        PlatformDependent.directBufferAddress(dst) + dstOffset, length);
            } else {
                // We must duplicate the NIO buffers because they may be accessed by other Netty buffers.
                src = src.duplicate();
                dst = dst.duplicate();
                src.position(srcOffset).limit(srcOffset + length);
                dst.position(dstOffset);
                dst.put(src);
            }
        }
    }
}
