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

import io.netty.util.internal.StringUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import static java.lang.Math.*;

import java.nio.ByteBuffer;

/**
 * 我们看到 PoolChunk 有如下三个属性：
 * <p>
 * PoolChunkList<T> parent;
 * <p>
 * PoolChunk<T> prev;
 * <p>
 * PoolChunk<T> next;
 * <p>
 * 通过 prev 和 next 两个属性，形成一个双向 Chunk 链表 parent( PoolChunkList )。
 * 那么为什么需要有 PoolChunkList 这样一个链表呢？
 * <p>
 * 实现 PoolChunkListMetric 接口，负责管理多个 Chunk 的生命周期，在此基础上对内存分配进行进一步的优化
 */
final class PoolChunkList<T> implements PoolChunkListMetric {
    private static final Iterator<PoolChunkMetric> EMPTY_METRICS = Collections.<PoolChunkMetric>emptyList().iterator();
    /**
     * 所属 PoolArena 对象
     */
    private final PoolArena<T> arena;

    /**
     * 下一个 PoolChunkList 对象
     */
    private final PoolChunkList<T> nextList;
    /**
     * Chunk 最小内存使用率
     */
    private final int minUsage;

    //minUsage + maxUsage 属性，PoolChunkList 管理的 Chunk 们的内存使用率。
    //当 Chunk 分配的内存率超过 maxUsage 时，从当前 PoolChunkList 节点移除，添加到下一个 PoolChunkList 节点( nextList )。
    //当 Chunk 分配的内存率小于 minUsage 时，从当前 PoolChunkList 节点移除，添加到上一个 PoolChunkList 节点( prevList )。

    /**
     * Chunk 最大内存使用率
     */
    private final int maxUsage;

    /**
     * 每个 Chunk 最大可分配的容量
     *
     * @see #calculateMaxCapacity(int, int) 方法
     */
    private final int maxCapacity;

    /**
     * PoolChunk 头节点
     */
    private PoolChunk<T> head;

    /**
     * 前一个 PoolChunkList 对象
     */
    // This is only update once when create the linked like list of PoolChunkList in PoolArena constructor.
    private PoolChunkList<T> prevList;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    PoolChunkList(PoolArena<T> arena, PoolChunkList<T> nextList, int minUsage, int maxUsage, int chunkSize) {
        assert minUsage <= maxUsage;
        this.arena = arena;
        this.nextList = nextList;
        this.minUsage = minUsage;
        this.maxUsage = maxUsage;
        // 计算 maxUsage 属性
        maxCapacity = calculateMaxCapacity(minUsage, chunkSize);
    }

    /**
     * Calculates the maximum capacity of a buffer that will ever be possible to allocate out of the {@link PoolChunk}s
     * that belong to the {@link PoolChunkList} with the given {@code minUsage} and {@code maxUsage} settings.
     */
    private static int calculateMaxCapacity(int minUsage, int chunkSize) {
        // 计算 minUsage 值
        minUsage = minUsage0(minUsage);

        if (minUsage == 100) {
            // If the minUsage is 100 we can not allocate anything out of this list.
            return 0;
        }

        // Calculate the maximum amount of bytes that can be allocated from a PoolChunk in this PoolChunkList.
        //
        // As an example:
        // - If a PoolChunkList has minUsage == 25 we are allowed to allocate at most 75% of the chunkSize because
        //   this is the maximum amount available in any PoolChunk in this PoolChunkList.

        //因为 Chunk 进入当前 PoolChunkList 节点，意味着 Chunk 内存已经分配了 minUsage 比率，
        // 所以 Chunk 剩余的容量是 chunkSize * (100L - minUsage) / 100L 。😈 是不是豁然开朗噢？
        return (int) (chunkSize * (100L - minUsage) / 100L);
    }

    void prevList(PoolChunkList<T> prevList) {
        assert this.prevList == null;
        this.prevList = prevList;
    }

    /**
     * 随着 Chunk 中 Page 的不断分配和释放，会导致很多碎片内存段，大大增加了之后分配一段连续内存的失败率。针对这种情况，
     * 可以把内存使用率较大的 Chunk 放到PoolChunkList 链表更后面
     *
     * 给 PooledByteBuf 对象分配内存块，并返回是否分配内存块成功
     */
    boolean allocate(PooledByteBuf<T> buf, int reqCapacity, int normCapacity) {
        // 双向链表中无 Chunk
        // 申请分配的内存超过 ChunkList 的每个 Chunk 最大可分配的容量
        if (normCapacity > maxCapacity) {
            // Either this PoolChunkList is empty or the requested capacity is larger then the capacity which can
            // be handled by the PoolChunks that are contained in this PoolChunkList.
            return false;
        }

        // 遍历双向链表。注意，遍历的是 ChunkList 的内部双向链表
        for (PoolChunk<T> cur = head; cur != null; cur = cur.next) {
            // 分配内存块
            // 分配成功
            if (cur.allocate(buf, reqCapacity, normCapacity)) {
                // 超过当前 ChunkList 管理的 Chunk 的内存使用率上限
                if (cur.usage() >= maxUsage) {
                    // 从当前 ChunkList 节点移除
                    remove(cur);
                    // 添加到下一个 ChunkList 节点
                    nextList.add(cur);
                }
                // 分配成功
                return true;
            }
        }
        return false;
    }

    boolean free(PoolChunk<T> chunk, long handle, ByteBuffer nioBuffer) {
        // 释放 PoolChunk 的指定位置( handle )的内存块
        chunk.free(handle, nioBuffer);
        // 小于当前 ChunkList 管理的 Chunk 的内存使用率下限
        if (chunk.usage() < minUsage) {
            // 从当前 ChunkList 节点移除
            remove(chunk);
            // 添加到上一个 ChunkList 节点
            // Move the PoolChunk down the PoolChunkList linked-list.
            return move0(chunk);
        }
        // 释放成功
        return true;
    }

    private boolean move(PoolChunk<T> chunk) {
        assert chunk.usage() < maxUsage;

        if (chunk.usage() < minUsage) {
            // Move the PoolChunk down the PoolChunkList linked-list.
            return move0(chunk);
        }

        // PoolChunk fits into this PoolChunkList, adding it here.
        add0(chunk);
        return true;
    }

    /**
     * Moves the {@link PoolChunk} down the {@link PoolChunkList} linked-list so it will end up in the right
     * {@link PoolChunkList} that has the correct minUsage / maxUsage in respect to {@link PoolChunk#usage()}.
     *
     *  添加到“上”一个 ChunkList 节点
     */
    private boolean move0(PoolChunk<T> chunk) {
        if (prevList == null) {
            // There is no previous PoolChunkList so return false which result in having the PoolChunk destroyed and
            // all memory associated with the PoolChunk will be released.
            assert chunk.usage() == 0;
            return false;
        }
        return prevList.move(chunk);
    }

    /**
     * 将 PoolChunk 添加到 ChunkList 节点中
     */
    void add(PoolChunk<T> chunk) {
        // 超过当前 ChunkList 管理的 Chunk 的内存使用率上限，继续递归到下一个 ChunkList 节点进行添加
        if (chunk.usage() >= maxUsage) {
            nextList.add(chunk);
            return;
        }
        // 执行真正的添加
        add0(chunk);
    }

    /**
     * Adds the {@link PoolChunk} to this {@link PoolChunkList}.
     *
     * 执行真正的添加
     */
    void add0(PoolChunk<T> chunk) {
        chunk.parent = this;
        // <1> 无头节点，自己成为头节点
        if (head == null) {
            head = chunk;
            chunk.prev = null;
            chunk.next = null;
        } else {
            // <2> 有头节点，自己成为头节点，原头节点成为自己的下一个节点
            chunk.prev = null;
            chunk.next = head;
            head.prev = chunk;
            head = chunk;
        }
    }

    /**
     * 从当前 ChunkList 节点移
     */
    private void remove(PoolChunk<T> cur) {
        if (cur == head) {
            head = cur.next;
            if (head != null) {
                head.prev = null;
            }
        } else {
            PoolChunk<T> next = cur.next;
            cur.prev.next = next;
            if (next != null) {
                next.prev = cur.prev;
            }
        }
    }

    @Override
    public int minUsage() {
        return minUsage0(minUsage);
    }

    @Override
    public int maxUsage() {
        return min(maxUsage, 100);
    }

    private static int minUsage0(int value) {
        return max(1, value);
    }

    @Override
    public Iterator<PoolChunkMetric> iterator() {
        synchronized (arena) {
            if (head == null) {
                return EMPTY_METRICS;
            }
            List<PoolChunkMetric> metrics = new ArrayList<PoolChunkMetric>();
            for (PoolChunk<T> cur = head; ; ) {
                metrics.add(cur);
                cur = cur.next;
                if (cur == null) {
                    break;
                }
            }
            return metrics.iterator();
        }
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        synchronized (arena) {
            if (head == null) {
                return "none";
            }

            for (PoolChunk<T> cur = head; ; ) {
                buf.append(cur);
                cur = cur.next;
                if (cur == null) {
                    break;
                }
                buf.append(StringUtil.NEWLINE);
            }
        }
        return buf.toString();
    }

    void destroy(PoolArena<T> arena) {
        PoolChunk<T> chunk = head;
        while (chunk != null) {
            arena.destroyChunk(chunk);
            chunk = chunk.next;
        }
        head = null;
    }
}
