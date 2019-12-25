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

import io.netty.util.internal.EmptyArrays;
import io.netty.util.internal.PlatformDependent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.ScatteringByteChannel;

import static io.netty.util.internal.ObjectUtil.checkNotNull;

/**
 * Big endian Java heap buffer implementation. It is recommended to use
 * {@link UnpooledByteBufAllocator#heapBuffer(int, int)}, {@link Unpooled#buffer(int)} and
 * {@link Unpooled#wrappedBuffer(byte[])} instead of calling the constructor explicitly.
 * <p>
 * 实现 AbstractReferenceCountedByteBuf 抽象类，对应 「PooledHeapByteBuf」 的非池化 ByteBuf 实现类
 */
public class UnpooledHeapByteBuf extends AbstractReferenceCountedByteBuf {

    /**
     * ByteBuf 分配器对象
     */
    private final ByteBufAllocator alloc;

    /**
     * 字节数组
     */
    byte[] array;

    /**
     * 临时 ByteBuff 对象
     */
    private ByteBuffer tmpNioBuf;

    /**
     * Creates a new heap buffer with a newly allocated byte array.
     *
     * @param initialCapacity the initial capacity of the underlying byte array
     * @param maxCapacity     the max capacity of the underlying byte array
     */
    public UnpooledHeapByteBuf(ByteBufAllocator alloc, int initialCapacity, int maxCapacity) {
        // 设置最大容量
        super(maxCapacity);

        checkNotNull(alloc, "alloc");

        if (initialCapacity > maxCapacity) {
            throw new IllegalArgumentException(String.format(
                    "initialCapacity(%d) > maxCapacity(%d)", initialCapacity, maxCapacity));
        }

        this.alloc = alloc;
        // 创建并设置字节数组
        setArray(allocateArray(initialCapacity));
        // 设置读写索引
        setIndex(0, 0);
    }

    /**
     * Creates a new heap buffer with an existing byte array.
     *
     * @param initialArray the initial underlying byte array
     * @param maxCapacity  the max capacity of the underlying byte array
     */
    protected UnpooledHeapByteBuf(ByteBufAllocator alloc, byte[] initialArray, int maxCapacity) {
        super(maxCapacity);

        checkNotNull(alloc, "alloc");
        checkNotNull(initialArray, "initialArray");

        if (initialArray.length > maxCapacity) {
            throw new IllegalArgumentException(String.format(
                    "initialCapacity(%d) > maxCapacity(%d)", initialArray.length, maxCapacity));
        }

        this.alloc = alloc;
        setArray(initialArray);
        setIndex(0, initialArray.length);
    }

    protected byte[] allocateArray(int initialCapacity) {
        //创建字节数组
        return new byte[initialCapacity];
    }

    protected void freeArray(byte[] array) {
        // NOOP
    }

    /**
     * 设置 array 属性
     */
    private void setArray(byte[] initialArray) {
        array = initialArray;
        tmpNioBuf = null;
    }

    @Override
    public ByteBufAllocator alloc() {
        return alloc;
    }

    @Override
    public ByteOrder order() {
        return ByteOrder.BIG_ENDIAN;
    }

    @Override
    public boolean isDirect() {
        return false;
    }

    /**
     * 使用字节数组的大小，作为当前容量上限
     */
    @Override
    public int capacity() {
        return array.length;
    }

    /**
     * 调整容量大小。在这个过程中，根据情况，可能对 array 扩容或缩容
     */
    @Override
    public ByteBuf capacity(int newCapacity) {
        // 校验新的容量，不能超过最大容量
        checkNewCapacity(newCapacity);

        int oldCapacity = array.length;
        byte[] oldArray = array;

        // 扩容
        if (newCapacity > oldCapacity) {
            // 创建新数组
            byte[] newArray = allocateArray(newCapacity);
            // 复制【全部】数据到新数组
            System.arraycopy(oldArray, 0, newArray, 0, oldArray.length);
            // 设置数组
            setArray(newArray);
            // 释放老数组
            freeArray(oldArray);
        } else if (newCapacity < oldCapacity) {
            // 创建新数组
            byte[] newArray = allocateArray(newCapacity);
            int readerIndex = readerIndex();
            // 如果写索引超过新容量，需要重置下，设置为最大容量。否则就越界了
            if (readerIndex < newCapacity) {
                int writerIndex = writerIndex();
                if (writerIndex > newCapacity) {
                    writerIndex(writerIndex = newCapacity);
                }
                // 只复制【读取段】数据到新数组
                System.arraycopy(oldArray, readerIndex, newArray, readerIndex, writerIndex - readerIndex);
            } else {
                // 因为读索引超过新容量，所以写索引超过新容量
                // 如果读写索引都超过新容量，需要重置下，都设置为最大容量。否则就越界了
                setIndex(newCapacity, newCapacity);
                // 这里要注意下，老的数据，相当于不进行复制，因为已经读取完了
            }
            // 设置数组
            setArray(newArray);
            // 释放老数组
            freeArray(oldArray);
        }
        return this;
    }

    @Override
    public boolean hasArray() {
        return true;
    }

    @Override
    public byte[] array() {
        ensureAccessible();
        return array;
    }

    @Override
    public int arrayOffset() {
        return 0;
    }

    @Override
    public boolean hasMemoryAddress() {
        return false;
    }

    @Override
    public long memoryAddress() {
        throw new UnsupportedOperationException();
    }

    @Override
    public ByteBuf getBytes(int index, ByteBuf dst, int dstIndex, int length) {
        checkDstIndex(index, length, dstIndex, dst.capacity());
        if (dst.hasMemoryAddress()) {
            PlatformDependent.copyMemory(array, index, dst.memoryAddress() + dstIndex, length);
        } else if (dst.hasArray()) {
            getBytes(index, dst.array(), dst.arrayOffset() + dstIndex, length);
        } else {
            dst.setBytes(dstIndex, array, index, length);
        }
        return this;
    }

    @Override
    public ByteBuf getBytes(int index, byte[] dst, int dstIndex, int length) {
        checkDstIndex(index, length, dstIndex, dst.length);
        System.arraycopy(array, index, dst, dstIndex, length);
        return this;
    }

    @Override
    public ByteBuf getBytes(int index, ByteBuffer dst) {
        checkIndex(index, dst.remaining());
        dst.put(array, index, dst.remaining());
        return this;
    }

    @Override
    public ByteBuf getBytes(int index, OutputStream out, int length) throws IOException {
        ensureAccessible();
        out.write(array, index, length);
        return this;
    }

    @Override
    public int getBytes(int index, GatheringByteChannel out, int length) throws IOException {
        ensureAccessible();
        return getBytes(index, out, length, false);
    }

    @Override
    public int getBytes(int index, FileChannel out, long position, int length) throws IOException {
        ensureAccessible();
        return getBytes(index, out, position, length, false);
    }

    private int getBytes(int index, GatheringByteChannel out, int length, boolean internal) throws IOException {
        ensureAccessible();
        ByteBuffer tmpBuf;
        if (internal) {
            tmpBuf = internalNioBuffer();
        } else {
            tmpBuf = ByteBuffer.wrap(array);
        }
        return out.write((ByteBuffer) tmpBuf.clear().position(index).limit(index + length));
    }

    private int getBytes(int index, FileChannel out, long position, int length, boolean internal) throws IOException {
        ensureAccessible();
        ByteBuffer tmpBuf = internal ? internalNioBuffer() : ByteBuffer.wrap(array);
        return out.write((ByteBuffer) tmpBuf.clear().position(index).limit(index + length), position);
    }

    @Override
    public int readBytes(GatheringByteChannel out, int length) throws IOException {
        checkReadableBytes(length);
        int readBytes = getBytes(readerIndex, out, length, true);
        readerIndex += readBytes;
        return readBytes;
    }

    @Override
    public int readBytes(FileChannel out, long position, int length) throws IOException {
        checkReadableBytes(length);
        int readBytes = getBytes(readerIndex, out, position, length, true);
        readerIndex += readBytes;
        return readBytes;
    }

    @Override
    public ByteBuf setBytes(int index, ByteBuf src, int srcIndex, int length) {
        checkSrcIndex(index, length, srcIndex, src.capacity());
        if (src.hasMemoryAddress()) {
            PlatformDependent.copyMemory(src.memoryAddress() + srcIndex, array, index, length);
        } else if (src.hasArray()) {
            setBytes(index, src.array(), src.arrayOffset() + srcIndex, length);
        } else {
            src.getBytes(srcIndex, array, index, length);
        }
        return this;
    }

    @Override
    public ByteBuf setBytes(int index, byte[] src, int srcIndex, int length) {
        checkSrcIndex(index, length, srcIndex, src.length);
        System.arraycopy(src, srcIndex, array, index, length);
        return this;
    }

    @Override
    public ByteBuf setBytes(int index, ByteBuffer src) {
        ensureAccessible();
        src.get(array, index, src.remaining());
        return this;
    }

    @Override
    public int setBytes(int index, InputStream in, int length) throws IOException {
        ensureAccessible();
        return in.read(array, index, length);
    }

    @Override
    public int setBytes(int index, ScatteringByteChannel in, int length) throws IOException {
        ensureAccessible();
        try {
            return in.read((ByteBuffer) internalNioBuffer().clear().position(index).limit(index + length));
        } catch (ClosedChannelException ignored) {
            return -1;
        }
    }

    @Override
    public int setBytes(int index, FileChannel in, long position, int length) throws IOException {
        ensureAccessible();
        try {
            return in.read((ByteBuffer) internalNioBuffer().clear().position(index).limit(index + length), position);
        } catch (ClosedChannelException ignored) {
            return -1;
        }
    }

    @Override
    public int nioBufferCount() {
        return 1;
    }

    @Override
    public ByteBuffer nioBuffer(int index, int length) {
        ensureAccessible();
        return ByteBuffer.wrap(array, index, length).slice();
    }

    @Override
    public ByteBuffer[] nioBuffers(int index, int length) {
        return new ByteBuffer[]{nioBuffer(index, length)};
    }

    @Override
    public ByteBuffer internalNioBuffer(int index, int length) {
        checkIndex(index, length);
        return (ByteBuffer) internalNioBuffer().clear().position(index).limit(index + length);
    }

    @Override
    public byte getByte(int index) {
        ensureAccessible();
        return _getByte(index);
    }

    @Override
    protected byte _getByte(int index) {
        return HeapByteBufUtil.getByte(array, index);
    }

    @Override
    public short getShort(int index) {
        ensureAccessible();
        return _getShort(index);
    }

    @Override
    protected short _getShort(int index) {
        return HeapByteBufUtil.getShort(array, index);
    }

    @Override
    public short getShortLE(int index) {
        ensureAccessible();
        return _getShortLE(index);
    }

    @Override
    protected short _getShortLE(int index) {
        return HeapByteBufUtil.getShortLE(array, index);
    }

    @Override
    public int getUnsignedMedium(int index) {
        ensureAccessible();
        return _getUnsignedMedium(index);
    }

    @Override
    protected int _getUnsignedMedium(int index) {
        return HeapByteBufUtil.getUnsignedMedium(array, index);
    }

    @Override
    public int getUnsignedMediumLE(int index) {
        ensureAccessible();
        return _getUnsignedMediumLE(index);
    }

    @Override
    protected int _getUnsignedMediumLE(int index) {
        return HeapByteBufUtil.getUnsignedMediumLE(array, index);
    }

    @Override
    public int getInt(int index) {
        ensureAccessible();
        return _getInt(index);
    }

    @Override
    protected int _getInt(int index) {
        return HeapByteBufUtil.getInt(array, index);
    }

    @Override
    public int getIntLE(int index) {
        ensureAccessible();
        return _getIntLE(index);
    }

    @Override
    protected int _getIntLE(int index) {
        return HeapByteBufUtil.getIntLE(array, index);
    }

    @Override
    public long getLong(int index) {
        ensureAccessible();
        return _getLong(index);
    }

    @Override
    protected long _getLong(int index) {
        return HeapByteBufUtil.getLong(array, index);
    }

    @Override
    public long getLongLE(int index) {
        ensureAccessible();
        return _getLongLE(index);
    }

    @Override
    protected long _getLongLE(int index) {
        return HeapByteBufUtil.getLongLE(array, index);
    }

    @Override
    public ByteBuf setByte(int index, int value) {
        ensureAccessible();
        _setByte(index, value);
        return this;
    }

    @Override
    protected void _setByte(int index, int value) {
        HeapByteBufUtil.setByte(array, index, value);
    }

    @Override
    public ByteBuf setShort(int index, int value) {
        ensureAccessible();
        _setShort(index, value);
        return this;
    }

    @Override
    protected void _setShort(int index, int value) {
        HeapByteBufUtil.setShort(array, index, value);
    }

    @Override
    public ByteBuf setShortLE(int index, int value) {
        ensureAccessible();
        _setShortLE(index, value);
        return this;
    }

    @Override
    protected void _setShortLE(int index, int value) {
        HeapByteBufUtil.setShortLE(array, index, value);
    }

    @Override
    public ByteBuf setMedium(int index, int value) {
        ensureAccessible();
        _setMedium(index, value);
        return this;
    }

    @Override
    protected void _setMedium(int index, int value) {
        HeapByteBufUtil.setMedium(array, index, value);
    }

    @Override
    public ByteBuf setMediumLE(int index, int value) {
        ensureAccessible();
        _setMediumLE(index, value);
        return this;
    }

    @Override
    protected void _setMediumLE(int index, int value) {
        HeapByteBufUtil.setMediumLE(array, index, value);
    }

    @Override
    public ByteBuf setInt(int index, int value) {
        ensureAccessible();
        _setInt(index, value);
        return this;
    }

    @Override
    protected void _setInt(int index, int value) {
        HeapByteBufUtil.setInt(array, index, value);
    }

    @Override
    public ByteBuf setIntLE(int index, int value) {
        ensureAccessible();
        _setIntLE(index, value);
        return this;
    }

    @Override
    protected void _setIntLE(int index, int value) {
        HeapByteBufUtil.setIntLE(array, index, value);
    }

    @Override
    public ByteBuf setLong(int index, long value) {
        ensureAccessible();
        _setLong(index, value);
        return this;
    }

    @Override
    protected void _setLong(int index, long value) {
        HeapByteBufUtil.setLong(array, index, value);
    }

    @Override
    public ByteBuf setLongLE(int index, long value) {
        ensureAccessible();
        _setLongLE(index, value);
        return this;
    }

    @Override
    protected void _setLongLE(int index, long value) {
        HeapByteBufUtil.setLongLE(array, index, value);
    }

    @Override
    public ByteBuf copy(int index, int length) {
        checkIndex(index, length);
        byte[] copiedArray = PlatformDependent.allocateUninitializedArray(length);
        System.arraycopy(array, index, copiedArray, 0, length);
        return new UnpooledHeapByteBuf(alloc(), copiedArray, maxCapacity());
    }

    private ByteBuffer internalNioBuffer() {
        ByteBuffer tmpNioBuf = this.tmpNioBuf;
        if (tmpNioBuf == null) {
            this.tmpNioBuf = tmpNioBuf = ByteBuffer.wrap(array);
        }
        return tmpNioBuf;
    }

    @Override
    protected void deallocate() {
        // 释放老数组
        freeArray(array);
        // 设置为空字节数组
        array = EmptyArrays.EMPTY_BYTES;
    }

    @Override
    public ByteBuf unwrap() {
        return null;
    }
}
