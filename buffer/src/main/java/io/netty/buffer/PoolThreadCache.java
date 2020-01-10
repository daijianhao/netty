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


import static io.netty.util.internal.ObjectUtil.checkPositiveOrZero;

import io.netty.buffer.PoolArena.SizeClass;
import io.netty.util.Recycler;
import io.netty.util.Recycler.Handle;
import io.netty.util.internal.MathUtil;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Acts a Thread cache for allocations. This implementation is moduled after
 * <a href="http://people.freebsd.org/~jasone/jemalloc/bsdcan2006/jemalloc.pdf">jemalloc</a> and the descripted
 * technics of
 * <a href="https://www.facebook.com/notes/facebook-engineering/scalable-memory-allocation-using-jemalloc/480222803919">
 * Scalable memory allocation using jemalloc</a>.
 * <p>
 * Netty 对 Jemalloc tcache 的实现类，内存分配的线程缓存
 */
final class PoolThreadCache {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(PoolThreadCache.class);

    /**
     * 对应的 Heap PoolArena 对象
     */
    final PoolArena<byte[]> heapArena;

    /**
     * 对应的 Direct PoolArena 对象
     */
    final PoolArena<ByteBuffer> directArena;

    // Hold the caches for the different size classes, which are tiny, small and normal.
    /**
     * Heap 类型的 tiny Subpage 内存块缓存数组
     */
    private final MemoryRegionCache<byte[]>[] tinySubPageHeapCaches;
    /**
     * Heap 类型的 small Subpage 内存块缓存数组
     */
    private final MemoryRegionCache<byte[]>[] smallSubPageHeapCaches;
    /**
     * Direct 类型的 tiny Subpage 内存块缓存数组
     */
    private final MemoryRegionCache<ByteBuffer>[] tinySubPageDirectCaches;
    /**
     * Direct 类型的 tiny Subpage 内存块缓存数组
     */
    private final MemoryRegionCache<ByteBuffer>[] smallSubPageDirectCaches;
    /**
     * Heap 类型的 normal 内存块缓存数组
     */
    private final MemoryRegionCache<byte[]>[] normalHeapCaches;
    /**
     * Direct 类型的 normal 内存块缓存数组
     */
    private final MemoryRegionCache<ByteBuffer>[] normalDirectCaches;

    // Used for bitshifting when calculate the index of normal caches later
    /**
     * 用于计算请求分配的 normal 类型的内存块，在 {@link #normalDirectCaches} 数组中的位置
     * <p>
     * 默认为 log2(pageSize) = log2(8192) = 13
     */
    private final int numShiftsNormalDirect;
    /**
     * 用于计算请求分配的 normal 类型的内存块，在 {@link #normalHeapCaches} 数组中的位置
     * <p>
     * 默认为 log2(pageSize) = log2(8192) = 13
     */
    private final int numShiftsNormalHeap;
    /**
     * {@link #allocations} 到达该阀值，释放缓存
     * <p>
     * 默认为 8192 次
     *
     * @see #free()
     */
    private final int freeSweepAllocationThreshold;
    private final AtomicBoolean freed = new AtomicBoolean();

    /**
     * 分配次数
     */
    private int allocations;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    PoolThreadCache(PoolArena<byte[]> heapArena, PoolArena<ByteBuffer> directArena,
                    int tinyCacheSize, int smallCacheSize, int normalCacheSize,
                    int maxCachedBufferCapacity, int freeSweepAllocationThreshold) {
        checkPositiveOrZero(maxCachedBufferCapacity, "maxCachedBufferCapacity");
        //freeSweepAllocationThreshold 属性，当 allocations 到达该阀值时，调用 #free() 方法，释放缓存。同时，会重置 allocations 计数器为 0
        this.freeSweepAllocationThreshold = freeSweepAllocationThreshold;
        this.heapArena = heapArena;
        this.directArena = directArena;
        if (directArena != null) {
            tinySubPageDirectCaches = createSubPageCaches(
                    tinyCacheSize, PoolArena.numTinySubpagePools, SizeClass.Tiny);
            smallSubPageDirectCaches = createSubPageCaches(
                    smallCacheSize, directArena.numSmallSubpagePools, SizeClass.Small);

            numShiftsNormalDirect = log2(directArena.pageSize);
            normalDirectCaches = createNormalCaches(
                    normalCacheSize, maxCachedBufferCapacity, directArena);

            //增加 directArena 的线程引用计数。通过这样的方式，我们能够知道，一个 PoolArena 对象，被多少线程所引用
            directArena.numThreadCaches.getAndIncrement();
        } else {
            // No directArea is configured so just null out all caches
            tinySubPageDirectCaches = null;
            smallSubPageDirectCaches = null;
            normalDirectCaches = null;
            numShiftsNormalDirect = -1;
        }
        if (heapArena != null) {
            // Create the caches for the heap allocations
            tinySubPageHeapCaches = createSubPageCaches(
                    tinyCacheSize, PoolArena.numTinySubpagePools, SizeClass.Tiny);
            smallSubPageHeapCaches = createSubPageCaches(
                    smallCacheSize, heapArena.numSmallSubpagePools, SizeClass.Small);

            numShiftsNormalHeap = log2(heapArena.pageSize);
            normalHeapCaches = createNormalCaches(
                    normalCacheSize, maxCachedBufferCapacity, heapArena);

            heapArena.numThreadCaches.getAndIncrement();
        } else {
            // No heapArea is configured so just null out all caches
            tinySubPageHeapCaches = null;
            smallSubPageHeapCaches = null;
            normalHeapCaches = null;
            numShiftsNormalHeap = -1;
        }

        // Only check if there are caches in use.
        if ((tinySubPageDirectCaches != null || smallSubPageDirectCaches != null || normalDirectCaches != null
                || tinySubPageHeapCaches != null || smallSubPageHeapCaches != null || normalHeapCaches != null)
                && freeSweepAllocationThreshold < 1) {
            throw new IllegalArgumentException("freeSweepAllocationThreshold: "
                    + freeSweepAllocationThreshold + " (expected: > 0)");
        }
    }

    /**
     * 创建 Subpage 内存块缓存数组
     * <p>
     * tiny 类型，默认 cacheSize = PooledByteBufAllocator.DEFAULT_TINY_CACHE_SIZE = 512 , numCaches = PoolArena.numTinySubpagePools = 512 >>> 4 = 32
     * small 类型，默认 cacheSize = PooledByteBufAllocator.DEFAULT_SMALL_CACHE_SIZE = 256 , numCaches = pageSize - 9 = 13 - 9 = 4
     * 创建的 Subpage 内存块缓存数组，实际和 PoolArena.tinySubpagePools 和 PoolArena.smallSubpagePools 数组大小保持一致。
     * 从而实现，相同大小的内存，能对应相同的数组下标
     */
    private static <T> MemoryRegionCache<T>[] createSubPageCaches(
            int cacheSize, int numCaches, SizeClass sizeClass) {
        if (cacheSize > 0 && numCaches > 0) {
            @SuppressWarnings("unchecked")
            MemoryRegionCache<T>[] cache = new MemoryRegionCache[numCaches];
            for (int i = 0; i < cache.length; i++) {
                // TODO: maybe use cacheSize / cache.length
                cache[i] = new SubPageMemoryRegionCache<T>(cacheSize, sizeClass);
            }
            return cache;
        } else {
            return null;
        }
    }

    /**
     * // normal 类型，默认 cacheSize = PooledByteBufAllocator.DEFAULT_NORMAL_CACHE_SIZE = 64 ,
     * maxCachedBufferCapacity = PoolArena.DEFAULT_MAX_CACHED_BUFFER_CAPACITY = 32 * 1024 = 32KB
     */
    private static <T> MemoryRegionCache<T>[] createNormalCaches(
            int cacheSize, int maxCachedBufferCapacity, PoolArena<T> area) {
        if (cacheSize > 0 && maxCachedBufferCapacity > 0) {
            int max = Math.min(area.chunkSize, maxCachedBufferCapacity);
            int arraySize = Math.max(1, log2(max / area.pageSize) + 1);

            @SuppressWarnings("unchecked")
            MemoryRegionCache<T>[] cache = new MemoryRegionCache[arraySize];
            for (int i = 0; i < cache.length; i++) {
                cache[i] = new NormalMemoryRegionCache<T>(cacheSize);
            }
            return cache;
        } else {
            return null;
        }
    }

    private static int log2(int val) {
        int res = 0;
        while (val > 1) {
            val >>= 1;
            res++;
        }
        return res;
    }

    //allocate相关三个方法：三个方法，从缓存中分别获取不同容量大小的内存块，初始化到 PooledByteBuf 对象中。
    // 通过调用 #allocate(MemoryRegionCache<?> cache, PooledByteBuf buf, int reqCapacity) 方法

    /**
     * Try to allocate a tiny buffer out of the cache. Returns {@code true} if successful {@code false} otherwise
     */
    boolean allocateTiny(PoolArena<?> area, PooledByteBuf<?> buf, int reqCapacity, int normCapacity) {
        return allocate(cacheForTiny(area, normCapacity), buf, reqCapacity);
    }

    /**
     * Try to allocate a small buffer out of the cache. Returns {@code true} if successful {@code false} otherwise
     */
    boolean allocateSmall(PoolArena<?> area, PooledByteBuf<?> buf, int reqCapacity, int normCapacity) {
        return allocate(cacheForSmall(area, normCapacity), buf, reqCapacity);
    }

    /**
     * Try to allocate a small buffer out of the cache. Returns {@code true} if successful {@code false} otherwise
     */
    boolean allocateNormal(PoolArena<?> area, PooledByteBuf<?> buf, int reqCapacity, int normCapacity) {
        return allocate(cacheForNormal(area, normCapacity), buf, reqCapacity);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean allocate(MemoryRegionCache<?> cache, PooledByteBuf buf, int reqCapacity) {
        if (cache == null) {
            // no cache found so just return false here
            return false;
        }
        // 分配内存块，并初始化到 MemoryRegionCache 中
        boolean allocated = cache.allocate(buf, reqCapacity);
        // 到达阀值，整理缓存
        if (++allocations >= freeSweepAllocationThreshold) {
            allocations = 0;
            trim();
        }
        //返回是否分配成功。如果从缓存中分配失败，后续就从 PoolArena 中获取内存块
        return allocated;
    }

    /**
     * Add {@link PoolChunk} and {@code handle} to the cache if there is enough room.
     * Returns {@code true} if it fit into the cache {@code false} otherwise.
     * <p>
     * 添加内存块到 PoolThreadCache 的指定 MemoryRegionCache 的队列中，进行缓存。并且，返回是否添加成功
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    boolean add(PoolArena<?> area, PoolChunk chunk, ByteBuffer nioBuffer,
                long handle, int normCapacity, SizeClass sizeClass) {
        // 获得对应的 MemoryRegionCache 对象
        MemoryRegionCache<?> cache = cache(area, normCapacity, sizeClass);
        if (cache == null) {
            return false;
        }
        // 添加到 MemoryRegionCache 内存块中
        return cache.add(chunk, nioBuffer, handle);
    }

    /**
     * 当然，考虑到使用便利，封装了 #cache(PoolArena<?> area, int normCapacity, SizeClass sizeClass) 方法，
     * 支持获取对应内存类型的 MemoryRegionCache 对象
     */
    private MemoryRegionCache<?> cache(PoolArena<?> area, int normCapacity, SizeClass sizeClass) {
        switch (sizeClass) {
            case Normal:
                return cacheForNormal(area, normCapacity);
            case Small:
                return cacheForSmall(area, normCapacity);
            case Tiny:
                return cacheForTiny(area, normCapacity);
            default:
                throw new Error();
        }
    }

    /// TODO: In the future when we move to Java9+ we should use java.lang.ref.Cleaner.
    //对象销毁时，清空缓存等等
    @Override
    protected void finalize() throws Throwable {
        try {
            super.finalize();
        } finally {
            free(true);
        }
    }

    /**
     * Should be called if the Thread that uses this cache is about to exist to release resources out of the cache
     */
    void free(boolean finalizer) {
        // As free() may be called either by the finalizer or by FastThreadLocal.onRemoval(...) we need to ensure
        // we only call this one time.
        // <2> 清空缓存
        if (freed.compareAndSet(false, true)) {
            int numFreed = free(tinySubPageDirectCaches, finalizer) +
                    free(smallSubPageDirectCaches, finalizer) +
                    free(normalDirectCaches, finalizer) +
                    free(tinySubPageHeapCaches, finalizer) +
                    free(smallSubPageHeapCaches, finalizer) +
                    free(normalHeapCaches, finalizer);

            if (numFreed > 0 && logger.isDebugEnabled()) {
                logger.debug("Freed {} thread-local buffer(s) from thread: {}", numFreed,
                        Thread.currentThread().getName());
            }
            // <3.1> 减小 directArena 的线程引用计数
            if (directArena != null) {
                directArena.numThreadCaches.getAndDecrement();
            }
            // <3.2> 减小 heapArena 的线程引用计数
            if (heapArena != null) {
                heapArena.numThreadCaches.getAndDecrement();
            }
        }
    }

    private static int free(MemoryRegionCache<?>[] caches, boolean finalizer) {
        if (caches == null) {
            return 0;
        }

        int numFreed = 0;
        for (MemoryRegionCache<?> c : caches) {
            numFreed += free(c, finalizer);
        }
        return numFreed;
    }

    private static int free(MemoryRegionCache<?> cache, boolean finalizer) {
        if (cache == null) {
            return 0;
        }
        return cache.free(finalizer);
    }

    /**
     * 整理缓存，释放使用频度较少的内存块缓存
     */
    void trim() {
        trim(tinySubPageDirectCaches);
        trim(smallSubPageDirectCaches);
        trim(normalDirectCaches);
        trim(tinySubPageHeapCaches);
        trim(smallSubPageHeapCaches);
        trim(normalHeapCaches);
    }

    private static void trim(MemoryRegionCache<?>[] caches) {
        if (caches == null) {
            return;
        }
        for (MemoryRegionCache<?> c : caches) {
            trim(c);
        }
    }

    private static void trim(MemoryRegionCache<?> cache) {
        if (cache == null) {
            return;
        }
        cache.trim();
    }

    // cacheForTiny()
    // cacheForSmall()
    // cacheForNormal()
    // 三个方法，分别获取内存容量对应所在的 MemoryRegionCache 对象。通过调用 #cache(MemoryRegionCache<T>[] cache, int idx) 方法

    private MemoryRegionCache<?> cacheForTiny(PoolArena<?> area, int normCapacity) {
        //因为tinySubpagePools是按容量大小进行分组的
        int idx = PoolArena.tinyIdx(normCapacity);
        if (area.isDirect()) {
            return cache(tinySubPageDirectCaches, idx);
        }
        return cache(tinySubPageHeapCaches, idx);
    }

    private MemoryRegionCache<?> cacheForSmall(PoolArena<?> area, int normCapacity) {
        int idx = PoolArena.smallIdx(normCapacity);
        if (area.isDirect()) {
            return cache(smallSubPageDirectCaches, idx);
        }
        return cache(smallSubPageHeapCaches, idx);
    }

    private MemoryRegionCache<?> cacheForNormal(PoolArena<?> area, int normCapacity) {
        if (area.isDirect()) {
            int idx = log2(normCapacity >> numShiftsNormalDirect);
            return cache(normalDirectCaches, idx);
        }
        int idx = log2(normCapacity >> numShiftsNormalHeap);
        return cache(normalHeapCaches, idx);
    }

    private static <T> MemoryRegionCache<T> cache(MemoryRegionCache<T>[] cache, int idx) {
        // 不在范围内，说明不缓存该容量的内存块
        if (cache == null || idx > cache.length - 1) {
            return null;
        }
        // 获得 MemoryRegionCache 对象
        return cache[idx];
    }

    /**
     * Cache used for buffers which are backed by TINY or SMALL size.
     *
     * 是 PoolThreadCache 的内部静态类，继承 MemoryRegionCache 抽象类，Subpage MemoryRegionCache 实现类
     */
    private static final class SubPageMemoryRegionCache<T> extends MemoryRegionCache<T> {
        SubPageMemoryRegionCache(int size, SizeClass sizeClass) {
            super(size, sizeClass);
        }

        @Override
        protected void initBuf(
                PoolChunk<T> chunk, ByteBuffer nioBuffer, long handle, PooledByteBuf<T> buf, int reqCapacity) {
            // 初始化 Subpage 内存块到 PooledByteBuf 对象中
            chunk.initBufWithSubpage(buf, nioBuffer, handle, reqCapacity);
        }
    }

    /**
     * Cache used for buffers which are backed by NORMAL size.
     *
     * 是 PoolThreadCache 的内部静态类，继承 MemoryRegionCache 抽象类，Page MemoryRegionCache 实现类
     */
    private static final class NormalMemoryRegionCache<T> extends MemoryRegionCache<T> {
        NormalMemoryRegionCache(int size) {
            super(size, SizeClass.Normal);
        }

        @Override
        protected void initBuf(
                PoolChunk<T> chunk, ByteBuffer nioBuffer, long handle, PooledByteBuf<T> buf, int reqCapacity) {
            // 初始化 Page 内存块到 PooledByteBuf 对象中
            chunk.initBuf(buf, nioBuffer, handle, reqCapacity);
        }
    }

    /**
     * 是 PoolThreadCache 的内部静态类，内存块缓存。在其内部，有一个队列，存储缓存的内存块
     */
    private abstract static class MemoryRegionCache<T> {
        /**
         * {@link #queue} 队列大小
         */
        private final int size;
        /**
         * 队列。里面存储内存块
         * <p>
         * 队列，里面存储内存块。每个元素为 Entry 对象，对应一个内存块
         */
        private final Queue<Entry<T>> queue;
        /**
         * 内存类型
         */
        private final SizeClass sizeClass;
        /**
         * 分配次数计数器
         */
        private int allocations;

        MemoryRegionCache(int size, SizeClass sizeClass) {
            //size 属性，队列大小
            this.size = MathUtil.safeFindNextPositivePowerOfTwo(size);
            //我们可以看到创建的 queue 属性，类型为 MPSC( Multiple Producer Single Consumer ) 队列，即多个生产者单一消费者。
            // 为什么使用 MPSC 队列呢?
            //多个生产者，指的是多个线程，移除( 释放 )内存块出队列。
            //单个消费者，指的是单个线程，添加( 缓存 )内存块到队列。
            queue = PlatformDependent.newFixedMpscQueue(this.size);
            this.sizeClass = sizeClass;
        }

        /**
         * Init the {@link PooledByteBuf} using the provided chunk and handle with the capacity restrictions.
         * <p>
         * 该抽象方法需要子类 SubPageMemoryRegionCache 和 NormalMemoryRegionCache 来实现。并且，这也是 MemoryRegionCache 的
         * 唯一的抽象方法
         */
        protected abstract void initBuf(PoolChunk<T> chunk, ByteBuffer nioBuffer, long handle,
                                        PooledByteBuf<T> buf, int reqCapacity);

        /**
         * Add to cache if not already full.
         * <p>
         * 添加( 缓存 )内存块到队列，并返回是否添加成功
         */
        @SuppressWarnings("unchecked")
        public final boolean add(PoolChunk<T> chunk, ByteBuffer nioBuffer, long handle) {
            // 创建 Entry 对象
            Entry<T> entry = newEntry(chunk, nioBuffer, handle);
            // 添加到队列
            boolean queued = queue.offer(entry);
            // 若添加失败，说明队列已满，回收 Entry 对象
            if (!queued) {
                // If it was not possible to cache the chunk, immediately recycle the entry
                entry.recycle();
            }
            // 是否添加成功
            return queued;
        }

        /**
         * Allocate something out of the cache if possible and remove the entry from the cache.
         * <p>
         * 从队列中获取缓存的内存块，初始化到 PooledByteBuf 对象中，并返回是否分配成功
         */
        public final boolean allocate(PooledByteBuf<T> buf, int reqCapacity) {
            // 获取并移除队列首个 Entry 对象
            Entry<T> entry = queue.poll();
            // 获取失败，返回 false
            if (entry == null) {
                return false;
            }
            // <1> 初始化内存块到 PooledByteBuf 对象中
            initBuf(entry.chunk, entry.nioBuffer, entry.handle, buf, reqCapacity);
            // 回收 Entry 对象
            entry.recycle();

            // allocations is not thread-safe which is fine as this is only called from the same thread all time.
            // 增加 allocations 计数。因为分配总是在相同线程，所以不需要考虑线程安全的问题
            ++allocations;
            // 返回 true ，分配成功
            return true;
        }

        /**
         * Clear out this cache and free up all previous cached {@link PoolChunk}s and {@code handle}s.
         * <p>
         * 清除队列中的全部
         */
        public final int free(boolean finalizer) {
            return free(Integer.MAX_VALUE, finalizer);
        }

        /**
         * 清除队列中的指定数量元素
         */
        private int free(int max, boolean finalizer) {
            int numFreed = 0;
            for (; numFreed < max; numFreed++) {
                // 获取并移除首元素
                Entry<T> entry = queue.poll();
                if (entry != null) {
                    // 释放缓存的内存块回 Chunk 中
                    freeEntry(entry, finalizer);
                } else {
                    // all cleared
                    return numFreed;
                }
            }
            return numFreed;
        }

        /**
         * Free up cached {@link PoolChunk}s if not allocated frequently enough.
         *
         * 内部类MemoryRegionCache
         *
         * 在分配过程还有一个trim()方法，当分配操作达到一定阈值（Netty默认8192）时，没有被分配出去的缓存空间都要被释放，以防止内存泄漏
         *
         * 也就是说，期望一个 MemoryRegionCache 频繁进行回收-分配，这样 allocations > size ，将不会释放队列中的任何一个节点表示的内存空间；
         *
         * 但如果长时间没有分配，则应该释放这一部分空间，防止内存占据过多。Tiny请求缓存512 个节点，
         * 由此可知当使用率超过 512 / 8192 = 6.25% 时就不会释放节点
         */
        public final void trim() {
            // allocations 表示已经重新分配出去的ByteBuf个数
            int free = size - allocations;
            allocations = 0;

            // We not even allocated all the number that are
            // 在一定阈值内还没被分配出去的空间将被释放
            if (free > 0) {
                // 释放队列中的节点
                free(free, false);
            }
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private void freeEntry(Entry entry, boolean finalizer) {
            PoolChunk chunk = entry.chunk;
            long handle = entry.handle;
            ByteBuffer nioBuffer = entry.nioBuffer;

            if (!finalizer) {
                // recycle now so PoolChunk can be GC'ed. This will only be done if this is not freed because of
                // a finalizer.
                // 回收 Entry 对象
                entry.recycle();
            }
            // 释放缓存的内存块回 Chunk 中
            chunk.arena.freeChunk(chunk, handle, sizeClass, nioBuffer, finalizer);
        }

        /**
         * 通过 chunk 和 handle 属性，可以唯一确认一个内存块。
         */
        static final class Entry<T> {
            /**
             * Recycler 处理器，用于回收 Entry 对象
             */
            final Handle<Entry<?>> recyclerHandle;
            /**
             * PoolChunk 对象
             */
            PoolChunk<T> chunk;

            ByteBuffer nioBuffer;
            /**
             * 内存块在 {@link #chunk} 的位置
             */
            long handle = -1;

            Entry(Handle<Entry<?>> recyclerHandle) {
                this.recyclerHandle = recyclerHandle;
            }

            void recycle() {
                // 置空
                chunk = null;
                nioBuffer = null;
                handle = -1;
                // 回收 Entry 对象
                recyclerHandle.recycle(this);
            }
        }

        /**
         * 创建 Entry 对象
         */
        @SuppressWarnings("rawtypes")
        private static Entry newEntry(PoolChunk<?> chunk, ByteBuffer nioBuffer, long handle) {
            // 从 Recycler 对象中，获得 Entry 对象
            Entry entry = RECYCLER.get();
            // 初始化属性
            entry.chunk = chunk;
            entry.nioBuffer = nioBuffer;
            entry.handle = handle;
            return entry;
        }

        @SuppressWarnings("rawtypes")
        private static final Recycler<Entry> RECYCLER = new Recycler<Entry>() {
            @SuppressWarnings("unchecked")
            @Override
            protected Entry newObject(Handle<Entry> handle) {
                // 创建 Entry 对象
                return new Entry(handle);
            }
        };
    }
}
