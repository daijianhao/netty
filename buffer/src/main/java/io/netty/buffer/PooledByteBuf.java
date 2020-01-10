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

import io.netty.util.Recycler;
import io.netty.util.Recycler.Handle;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

/**
 * 继承 AbstractReferenceCountedByteBuf 抽象类，对象池化的 ByteBuf 抽象基类，为基于对象池的 ByteBuf 实现类，提供公用的方法
 *
 * @param <T>
 */
abstract class PooledByteBuf<T> extends AbstractReferenceCountedByteBuf {

    /**
     * Recycler 处理器，用于回收对象
     */
    private final Recycler.Handle<PooledByteBuf<T>> recyclerHandle;

    /**
     * Chunk 对象
     * 在 Netty 中，使用 Jemalloc 算法管理内存，而 Chunk 是里面的一种内存块。在这里，我们可以理解 memory 所属的 PoolChunk 对象
     */
    protected PoolChunk<T> chunk;

    /**
     * 从 Chunk 对象中分配的内存块所处的位置
     */
    protected long handle;

    /**
     * 内存空间。具体什么样的数据，通过子类设置泛型。
     */
    protected T memory;

    /**
     * {@link #memory} 开始位置
     * <p>
     * 使用 memory 的开始位置
     * <p>
     * 因为 memory 属性，可以被多个 ByteBuf 使用。每个 ByteBuf 使用范围为 [offset, maxLength)
     * <p>
     * 这边是内存池化后可以分给多个ByteBuf使用不同的区间，因而不会冲突
     *
     * @see #idx(int)
     */
    protected int offset;

    /**
     * 容量
     * 目前使用 memory 的长度( 大小 )
     *
     * @see #capacity()
     */
    protected int length;

    /**
     * 占用 {@link #memory} 的大小
     * 最大使用 memory 的长度
     */
    int maxLength;

    /**
     * TODO 1013 Chunk
     */
    PoolThreadCache cache;

    /**
     * 临时 ByteBuff 对象
     * 临时 ByteBuff 对象，通过 #tmpNioBuf() 方法生成
     *
     * @see #internalNioBuffer()
     */
    ByteBuffer tmpNioBuf;

    /**
     * ByteBuf 分配器对象
     */
    private ByteBufAllocator allocator;

    @SuppressWarnings("unchecked")
    protected PooledByteBuf(Recycler.Handle<? extends PooledByteBuf<T>> recyclerHandle, int maxCapacity) {
        super(maxCapacity);
        this.recyclerHandle = (Handle<PooledByteBuf<T>>) recyclerHandle;
    }

    /**
     * 一般是基于 pooled 的 PoolChunk 对象，初始化 PooledByteBuf 对象
     */
    void init(PoolChunk<T> chunk, ByteBuffer nioBuffer,
              long handle, int offset, int length, int maxLength, PoolThreadCache cache) {
        init0(chunk, nioBuffer, handle, offset, length, maxLength, cache);
    }

    /**
     * 基于 unPoolooled 的 PoolChunk 对象，初始化 PooledByteBuf 对象
     */
    void initUnpooled(PoolChunk<T> chunk, int length) {
        //ps:传入init0的length、maxLength的参数都是length
        init0(chunk, null, 0, chunk.offset, length, length, null);
    }

    /**
     * 初始化 PooledByteBuf 对象
     */
    private void init0(PoolChunk<T> chunk, ByteBuffer nioBuffer,
                       long handle, int offset, int length, int maxLength, PoolThreadCache cache) {
        assert handle >= 0;
        assert chunk != null;

        // From PoolChunk 对象
        this.chunk = chunk;
        memory = chunk.memory;
        //这里的nioBuffer可能和memory是同一个对象
        tmpNioBuf = nioBuffer;
        allocator = chunk.arena.parent;
        // 其他
        this.cache = cache;
        this.handle = handle;
        this.offset = offset;
        this.length = length;
        this.maxLength = maxLength;
    }

    /**
     * Method must be called before reuse this {@link PooledByteBufAllocator}
     * <p>
     * 每次在重用 PooledByteBuf 对象时，需要调用该方法，重置属性
     */
    final void reuse(int maxCapacity) {
        // 设置最大容量
        maxCapacity(maxCapacity);
        // 设置引用数量为 0
        setRefCnt(1);
        // 重置读写索引为 0
        setIndex0(0, 0);
        // 重置读写标记位为 0
        discardMarks();
    }

    @Override
    public final int capacity() {
        //当前容量的值为 length 属性
        //要注意的是，maxLength 属性，不是表示最大容量。maxCapacity 属性，才是真正表示最大容量。
        //那么，maxLength 属性有什么用？表示占用 memory 的最大容量( 而不是 PooledByteBuf 对象的最大容量 )。在写入数据
        // 超过 maxLength 容量时，会进行扩容，但是容量的上限，为 maxCapacity
        return length;
    }

    /**
     * 调整容量大小。在这个过程中，根据情况，可能对 memory 扩容或缩容
     */
    @Override
    public final ByteBuf capacity(int newCapacity) {
        // 校验新的容量，不能超过最大容量
        checkNewCapacity(newCapacity);

        // If the request capacity does not require reallocation, just update the length of the memory.
        // Chunk 内存，非池化
        if (chunk.unpooled) {
            if (newCapacity == length) {
                return this;
            }
        } else {
            // Chunk 内存，是池化
            if (newCapacity > length) {//扩容
                //新容量大于当前容量，但是小于 memory 最大容量，仅仅修改当前容量，无需进行扩容
                if (newCapacity <= maxLength) {
                    length = newCapacity;
                    return this;
                }
            } else if (newCapacity < length) {// 缩容
                // 新容量小于当前容量，但是不到 memory 最大容量的一半，因为缩容相对释放不多，无需进行缩容。否则，进行缩容。
                if (newCapacity > maxLength >>> 1) {
                    if (maxLength <= 512) {
                        if (newCapacity > maxLength - 16) {
                            // 因为 Netty SubPage 最小是 16 ，如果小于等 16 ，无法缩容。
                            length = newCapacity;
                            // 设置读写索引，避免超过最大容量
                            setIndex(Math.min(readerIndex(), newCapacity), Math.min(writerIndex(), newCapacity));
                            return this;
                        }
                    } else { // > 512 (i.e. >= 1024)
                        length = newCapacity;
                        // 设置读写索引，避免超过最大容量
                        setIndex(Math.min(readerIndex(), newCapacity), Math.min(writerIndex(), newCapacity));
                        return this;
                    }
                }
            } else {// 相等，无需扩容 / 缩容
                return this;
            }
        }

        // Reallocation required.
        // 重新分配新的内存空间，并将数据复制到其中。并且，释放老的内存空间
        chunk.arena.reallocate(this, newCapacity, true);
        return this;
    }

    @Override
    public final ByteBufAllocator alloc() {
        return allocator;
    }

    /**
     * 返回字节序为 ByteOrder.BIG_ENDIAN 大端
     * <p>
     * 统一大端模式
     * <p>
     * 在网络上传输数据时，由于数据传输的两端对应不同的硬件平台，采用的存储字节顺序可能不一致。所以在 TCP/IP 协议规定了在网络上必须
     * 采用网络字节顺序，也就是大端模式。
     */
    @Override
    public final ByteOrder order() {
        return ByteOrder.BIG_ENDIAN;
    }

    /**
     * 因为没有被装饰的 ByteBuffer 对象
     *
     * @return
     */
    @Override
    public final ByteBuf unwrap() {
        return null;
    }

    @Override
    public final ByteBuf retainedDuplicate() {
        //创建池化的 PooledDuplicatedByteBuf.newInstance 对象
        return PooledDuplicatedByteBuf.newInstance(this, this, readerIndex(), writerIndex());
    }

    @Override
    public final ByteBuf retainedSlice() {
        final int index = readerIndex();
        return retainedSlice(index, writerIndex() - index);
    }

    @Override
    public final ByteBuf retainedSlice(int index, int length) {
        //创建池化的 PooledSlicedByteBuf 对象
        return PooledSlicedByteBuf.newInstance(this, this, index, length);
    }

    /**
     * 获得临时 ByteBuf 对象( tmpNioBuf )
     *
     * @return
     */
    protected final ByteBuffer internalNioBuffer() {
        ByteBuffer tmpNioBuf = this.tmpNioBuf;
        if (tmpNioBuf == null) {
            // 为空，创建临时 ByteBuf 对象
            //当 tmpNioBuf 属性为空时，调用 #newInternalNioBuffer(T memory) 方法，创建 ByteBuffer 对象。
            // 因为 memory 的类型不确定，所以该方法定义成抽象方法，由子类实现
            this.tmpNioBuf = tmpNioBuf = newInternalNioBuffer(memory);
        }
        return tmpNioBuf;
    }

    /**
     * 为什么要有 tmpNioBuf 这个属性呢？{@link this#setBytes(int, FileChannel, long, int)}和{@link this#getBytes(int, FileChannel, long, int)}等方法中需要
     *
     * @param memory
     * @return
     */
    protected abstract ByteBuffer newInternalNioBuffer(T memory);

    /**
     *池化ByteBuf的释放
     */
    @Override
    protected final void deallocate() {
        if (handle >= 0) {
            // 重置属性
            final long handle = this.handle;
            this.handle = -1;
            memory = null;
            // 释放内存回 Arena 中
            chunk.arena.free(chunk, tmpNioBuf, handle, maxLength, cache);
            tmpNioBuf = null;
            chunk = null;
            // 回收对象
            recycle();
        }
    }

    private void recycle() {
        // 回收对象
        recyclerHandle.recycle(this);
    }

    /**
     * 获得指定位置在 memory 变量中的位置
     */
    protected final int idx(int index) {
        return offset + index;
    }
}
