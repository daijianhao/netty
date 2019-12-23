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

import io.netty.util.AsciiString;
import io.netty.util.ByteProcessor;
import io.netty.util.CharsetUtil;
import io.netty.util.IllegalReferenceCountException;
import io.netty.util.ResourceLeakDetector;
import io.netty.util.ResourceLeakDetectorFactory;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.ScatteringByteChannel;
import java.nio.charset.Charset;

import static io.netty.util.internal.MathUtil.isOutOfBounds;
import static io.netty.util.internal.ObjectUtil.checkPositiveOrZero;

/**
 * A skeletal implementation of a buffer.
 * <p>
 * 实现 ByteBuf 抽象类，ByteBuf 抽象实现类
 * 此类提供了ByteBuf的默认实现
 */
public abstract class AbstractByteBuf extends ByteBuf {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(AbstractByteBuf.class);
    private static final String LEGACY_PROP_CHECK_ACCESSIBLE = "io.netty.buffer.bytebuf.checkAccessible";
    private static final String PROP_CHECK_ACCESSIBLE = "io.netty.buffer.checkAccessible";
    /**
     * 是否检查可访问
     */
    static final boolean checkAccessible; // accessed from CompositeByteBuf
    private static final String PROP_CHECK_BOUNDS = "io.netty.buffer.checkBounds";
    private static final boolean checkBounds;

    static {
        if (SystemPropertyUtil.contains(PROP_CHECK_ACCESSIBLE)) {
            checkAccessible = SystemPropertyUtil.getBoolean(PROP_CHECK_ACCESSIBLE, true);
        } else {
            checkAccessible = SystemPropertyUtil.getBoolean(LEGACY_PROP_CHECK_ACCESSIBLE, true);
        }
        checkBounds = SystemPropertyUtil.getBoolean(PROP_CHECK_BOUNDS, true);
        if (logger.isDebugEnabled()) {
            logger.debug("-D{}: {}", PROP_CHECK_ACCESSIBLE, checkAccessible);
            logger.debug("-D{}: {}", PROP_CHECK_BOUNDS, checkBounds);
        }
    }

    static final ResourceLeakDetector<ByteBuf> leakDetector =
            ResourceLeakDetectorFactory.instance().newResourceLeakDetector(ByteBuf.class);

    /**
     * 读取位置
     */
    int readerIndex;

    /**
     * 写入位置
     */
    int writerIndex;

    /**
     * {@link #readerIndex} 的标记
     */
    private int markedReaderIndex;

    /**
     * {@link #writerIndex} 的标记
     */
    private int markedWriterIndex;

    //capacity 属性，在 AbstractByteBuf 未定义，而是由子类来实现。为什么呢？在后面的文章，我们会看到，ByteBuf 根据内存类
    // 型分成 Heap 和 Direct ，它们获取 capacity 的值的方式不同

    /**
     * 最大容量
     */
    private int maxCapacity;

    protected AbstractByteBuf(int maxCapacity) {
        checkPositiveOrZero(maxCapacity, "maxCapacity");
        this.maxCapacity = maxCapacity;
    }

    /**
     * 默认返回 false 。子类可覆写该方法
     *
     * @return
     */
    @Override
    public boolean isReadOnly() {
        return false;
    }

    /**
     * asReadOnly() 方法，转换成只读 ByteBuf 对象
     *
     * @return
     */
    @SuppressWarnings("deprecation")
    @Override
    public ByteBuf asReadOnly() {
        // 如果是只读，直接返回
        if (isReadOnly()) {
            return this;
        }
        // 转化成只读 Buffer 对象
        return Unpooled.unmodifiableBuffer(this);
    }

    @Override
    public int maxCapacity() {
        return maxCapacity;
    }

    protected final void maxCapacity(int maxCapacity) {
        this.maxCapacity = maxCapacity;
    }

    @Override
    public int readerIndex() {
        return readerIndex;
    }

    private static void checkIndexBounds(final int readerIndex, final int writerIndex, final int capacity) {
        if (readerIndex < 0 || readerIndex > writerIndex || writerIndex > capacity) {
            throw new IndexOutOfBoundsException(String.format(
                    "readerIndex: %d, writerIndex: %d (expected: 0 <= readerIndex <= writerIndex <= capacity(%d))",
                    readerIndex, writerIndex, capacity));
        }
    }

    @Override
    public ByteBuf readerIndex(int readerIndex) {
        if (checkBounds) {
            checkIndexBounds(readerIndex, writerIndex, capacity());
        }
        this.readerIndex = readerIndex;
        return this;
    }

    @Override
    public int writerIndex() {
        return writerIndex;
    }

    @Override
    public ByteBuf writerIndex(int writerIndex) {
        if (checkBounds) {
            checkIndexBounds(readerIndex, writerIndex, capacity());
        }
        this.writerIndex = writerIndex;
        return this;
    }

    /**
     * 设置读位置和写位置
     *
     * @param readerIndex
     * @param writerIndex
     * @return
     */
    @Override
    public ByteBuf setIndex(int readerIndex, int writerIndex) {
        if (checkBounds) {
            checkIndexBounds(readerIndex, writerIndex, capacity());
        }
        setIndex0(readerIndex, writerIndex);
        return this;
    }

    @Override
    public ByteBuf clear() {
        readerIndex = writerIndex = 0;
        return this;
    }

    @Override
    public boolean isReadable() {
        return writerIndex > readerIndex;
    }

    @Override
    public boolean isReadable(int numBytes) {
        return writerIndex - readerIndex >= numBytes;
    }

    //是否可写
    @Override
    public boolean isWritable() {
        return capacity() > writerIndex;
    }

    @Override
    public boolean isWritable(int numBytes) {
        return capacity() - writerIndex >= numBytes;
    }

    @Override
    public int readableBytes() {
        return writerIndex - readerIndex;
    }

    @Override
    public int writableBytes() {
        return capacity() - writerIndex;
    }

    @Override
    public int maxWritableBytes() {
        return maxCapacity() - writerIndex;
    }

    /**
     * 标记读位置
     *
     * @return
     */
    @Override
    public ByteBuf markReaderIndex() {
        markedReaderIndex = readerIndex;
        return this;
    }

    /**
     * 重置读位置
     *
     * @return
     */
    @Override
    public ByteBuf resetReaderIndex() {
        readerIndex(markedReaderIndex);
        return this;
    }

    //标记和重置写位置
    @Override
    public ByteBuf markWriterIndex() {
        markedWriterIndex = writerIndex;
        return this;
    }

    @Override
    public ByteBuf resetWriterIndex() {
        writerIndex(markedWriterIndex);
        return this;
    }

    @Override
    public ByteBuf discardReadBytes() {
        // 校验可访问
        ensureAccessible();
        // 无废弃段，直接返回
        if (readerIndex == 0) {
            return this;
        }

        // 未读取完
        if (readerIndex != writerIndex) {
            // 将可读段复制到 ByteBuf 头
            setBytes(0, this, readerIndex, writerIndex - readerIndex);
            // 写索引减小
            writerIndex -= readerIndex;
            // 调整标记位
            adjustMarkers(readerIndex);
            // 读索引重置为 0
            readerIndex = 0;
        } else {// 全部读取完
            // 调整标记位
            adjustMarkers(readerIndex);
            // 读写索引都重置为
            writerIndex = readerIndex = 0;
        }
        return this;
    }

    /**
     * 整体代码和 #discardReadBytes() 方法是一致的。差别在于，readerIndex >= capacity() >>> 1 ，读取超过容量的一半时，进行释放。
     * 也就是说，在空间和时间之间，做了一个平衡
     *
     * @return
     */
    @Override
    public ByteBuf discardSomeReadBytes() {
        // 校验可访问
        ensureAccessible();
        // 无废弃段，直接返回
        if (readerIndex == 0) {
            return this;
        }
        // 全部读取完
        if (readerIndex == writerIndex) {
            // 调整标记位
            adjustMarkers(readerIndex);
            // 读写索引都重置为 0
            writerIndex = readerIndex = 0;
            return this;
        }
        // 读取超过容量的一半，进行释放
        if (readerIndex >= capacity() >>> 1) {
            // 将可读段复制到 ByteBuf 头
            setBytes(0, this, readerIndex, writerIndex - readerIndex);
            // 写索引减小
            writerIndex -= readerIndex;
            // 调整标记位
            adjustMarkers(readerIndex);
            // 读索引重置为 0
            readerIndex = 0;
        }
        return this;
    }

    /**
     * 调整标记位
     *
     * @param decrement
     */
    protected final void adjustMarkers(int decrement) {
        int markedReaderIndex = this.markedReaderIndex;
        // 读标记位小于减少值(decrement)
        if (markedReaderIndex <= decrement) {
            // 重置读标记位为 0
            this.markedReaderIndex = 0;
            int markedWriterIndex = this.markedWriterIndex;
            // 写标记位小于减少值(decrement)
            if (markedWriterIndex <= decrement) {
                this.markedWriterIndex = 0;
            } else {
                this.markedWriterIndex = markedWriterIndex - decrement;
            }
        } else {
            this.markedReaderIndex = markedReaderIndex - decrement;
            markedWriterIndex -= decrement;
        }
    }

    /**
     * 保证有足够的可写空间。若不够，则进行扩容
     *
     * @param minWritableBytes the expected minimum number of writable bytes
     * @return
     */
    @Override
    public ByteBuf ensureWritable(int minWritableBytes) {
        checkPositiveOrZero(minWritableBytes, "minWritableBytes");
        ensureWritable0(minWritableBytes);
        return this;
    }

    final void ensureWritable0(int minWritableBytes) {
        // 检查是否可访问
        ensureAccessible();
        // 目前容量可写，直接返回
        if (minWritableBytes <= writableBytes()) {
            return;
        }
        if (checkBounds) {//如果需要检测边界
            // 超过最大上限，抛出 IndexOutOfBoundsException 异常
            if (minWritableBytes > maxCapacity - writerIndex) {
                throw new IndexOutOfBoundsException(String.format(
                        "writerIndex(%d) + minWritableBytes(%d) exceeds maxCapacity(%d): %s",
                        writerIndex, minWritableBytes, maxCapacity, this));
            }
        }

        // Normalize the current capacity to the power of 2.
        // 计算新的容量。默认情况下，2 倍扩容，并且不超过最大容量上限
        int newCapacity = alloc().calculateNewCapacity(writerIndex + minWritableBytes, maxCapacity);

        // Adjust to the new capacity.
        // 设置新的容量大小
        capacity(newCapacity);
    }

    /**
     * 保证有足够的可写空间。若不够，则进行扩容
     * <p>
     * 和 #ensureWritable(int minWritableBytes) 方法，有两点不同：
     * <p>
     * 超过最大容量的上限时，不会抛出 IndexOutOfBoundsException 异常。
     * 根据执行的过程不同，返回不同的返回值
     */
    @Override
    public int ensureWritable(int minWritableBytes, boolean force) {
        // 检查是否可访问
        ensureAccessible();
        checkPositiveOrZero(minWritableBytes, "minWritableBytes");

        // 目前容量可写，直接返回 0
        if (minWritableBytes <= writableBytes()) {
            return 0;
        }

        final int maxCapacity = maxCapacity();
        final int writerIndex = writerIndex();
        // 超过最大上限
        if (minWritableBytes > maxCapacity - writerIndex) {
            // 不强制设置，或者已经到达最大容量
            if (!force || capacity() == maxCapacity) {
                return 1;
            }
            // 设置为最大容量
            capacity(maxCapacity);
            // 返回 3
            return 3;
        }

        // Normalize the current capacity to the power of 2.
        // 计算新的容量。默认情况下，2 倍扩容，并且不超过最大容量上限
        int newCapacity = alloc().calculateNewCapacity(writerIndex + minWritableBytes, maxCapacity);

        // Adjust to the new capacity.
        // 设置新的容量大小
        capacity(newCapacity);
        return 2;
    }

    /**
     * 获得字节序。由子类实现，因为 AbstractByteBuf 的内存类型，不确定是 Heap 还是 Direct
     */
    @Override
    public ByteBuf order(ByteOrder endianness) {
        // 未改变，直接返回
        if (endianness == order()) {
            return this;
        }
        if (endianness == null) {
            throw new NullPointerException("endianness");
        }
        // 创建 SwappedByteBuf 对象
        return newSwappedByteBuf();
    }

    /**
     * Creates a new {@link SwappedByteBuf} for this {@link ByteBuf} instance.
     */
    protected SwappedByteBuf newSwappedByteBuf() {
        return new SwappedByteBuf(this);
    }

    @Override
    public byte getByte(int index) {
        checkIndex(index);
        return _getByte(index);
    }

    protected abstract byte _getByte(int index);

    @Override
    public boolean getBoolean(int index) {
        return getByte(index) != 0;
    }

    @Override
    public short getUnsignedByte(int index) {
        return (short) (getByte(index) & 0xFF);
    }

    @Override
    public short getShort(int index) {
        checkIndex(index, 2);
        return _getShort(index);
    }

    protected abstract short _getShort(int index);

    @Override
    public short getShortLE(int index) {
        checkIndex(index, 2);
        return _getShortLE(index);
    }

    protected abstract short _getShortLE(int index);

    @Override
    public int getUnsignedShort(int index) {
        return getShort(index) & 0xFFFF;
    }

    @Override
    public int getUnsignedShortLE(int index) {
        return getShortLE(index) & 0xFFFF;
    }

    @Override
    public int getUnsignedMedium(int index) {
        checkIndex(index, 3);
        return _getUnsignedMedium(index);
    }

    protected abstract int _getUnsignedMedium(int index);

    @Override
    public int getUnsignedMediumLE(int index) {
        checkIndex(index, 3);
        return _getUnsignedMediumLE(index);
    }

    protected abstract int _getUnsignedMediumLE(int index);

    @Override
    public int getMedium(int index) {
        int value = getUnsignedMedium(index);
        if ((value & 0x800000) != 0) {
            value |= 0xff000000;
        }
        return value;
    }

    @Override
    public int getMediumLE(int index) {
        int value = getUnsignedMediumLE(index);
        if ((value & 0x800000) != 0) {
            value |= 0xff000000;
        }
        return value;
    }

    @Override
    public int getInt(int index) {
        //校验读取是否会超过容量。注意，不是超过 writerIndex 位置。因为，只是读取指定位置开始的 Int 数据，不会改变 readerIndex
        checkIndex(index, 4);
        //读取 Int 数据
        return _getInt(index);
    }

    /**
     * 读取 Int 数据。这是一个抽象方法，由子类实现
     */
    protected abstract int _getInt(int index);

    @Override
    public int getIntLE(int index) {
        checkIndex(index, 4);
        return _getIntLE(index);
    }

    protected abstract int _getIntLE(int index);

    @Override
    public long getUnsignedInt(int index) {
        return getInt(index) & 0xFFFFFFFFL;
    }

    @Override
    public long getUnsignedIntLE(int index) {
        return getIntLE(index) & 0xFFFFFFFFL;
    }

    @Override
    public long getLong(int index) {
        checkIndex(index, 8);
        return _getLong(index);
    }

    protected abstract long _getLong(int index);

    @Override
    public long getLongLE(int index) {
        checkIndex(index, 8);
        return _getLongLE(index);
    }

    protected abstract long _getLongLE(int index);

    @Override
    public char getChar(int index) {
        return (char) getShort(index);
    }

    @Override
    public float getFloat(int index) {
        return Float.intBitsToFloat(getInt(index));
    }

    @Override
    public double getDouble(int index) {
        return Double.longBitsToDouble(getLong(index));
    }

    @Override
    public ByteBuf getBytes(int index, byte[] dst) {
        getBytes(index, dst, 0, dst.length);
        return this;
    }

    @Override
    public ByteBuf getBytes(int index, ByteBuf dst) {
        getBytes(index, dst, dst.writableBytes());
        return this;
    }

    @Override
    public ByteBuf getBytes(int index, ByteBuf dst, int length) {
        getBytes(index, dst, dst.writerIndex(), length);
        dst.writerIndex(dst.writerIndex() + length);
        return this;
    }

    @Override
    public CharSequence getCharSequence(int index, int length, Charset charset) {
        if (CharsetUtil.US_ASCII.equals(charset) || CharsetUtil.ISO_8859_1.equals(charset)) {
            // ByteBufUtil.getBytes(...) will return a new copy which the AsciiString uses directly
            return new AsciiString(ByteBufUtil.getBytes(this, index, length, true), false);
        }
        return toString(index, length, charset);
    }

    @Override
    public CharSequence readCharSequence(int length, Charset charset) {
        CharSequence sequence = getCharSequence(readerIndex, length, charset);
        readerIndex += length;
        return sequence;
    }

    @Override
    public ByteBuf setByte(int index, int value) {
        checkIndex(index);
        _setByte(index, value);
        return this;
    }

    protected abstract void _setByte(int index, int value);

    @Override
    public ByteBuf setBoolean(int index, boolean value) {
        setByte(index, value ? 1 : 0);
        return this;
    }

    @Override
    public ByteBuf setShort(int index, int value) {
        checkIndex(index, 2);
        _setShort(index, value);
        return this;
    }

    protected abstract void _setShort(int index, int value);

    @Override
    public ByteBuf setShortLE(int index, int value) {
        checkIndex(index, 2);
        _setShortLE(index, value);
        return this;
    }

    protected abstract void _setShortLE(int index, int value);

    @Override
    public ByteBuf setChar(int index, int value) {
        setShort(index, value);
        return this;
    }

    @Override
    public ByteBuf setMedium(int index, int value) {
        checkIndex(index, 3);
        _setMedium(index, value);
        return this;
    }

    protected abstract void _setMedium(int index, int value);

    @Override
    public ByteBuf setMediumLE(int index, int value) {
        checkIndex(index, 3);
        _setMediumLE(index, value);
        return this;
    }

    protected abstract void _setMediumLE(int index, int value);

    @Override
    public ByteBuf setInt(int index, int value) {
        // 校验写入是否会超过容量
        checkIndex(index, 4);
        // 设置 Int 数据
        _setInt(index, value);
        return this;
    }

    /**
     * 写入 Int 数据。这是一个抽象方法，由子类实现
     */
    protected abstract void _setInt(int index, int value);

    @Override
    public ByteBuf setIntLE(int index, int value) {
        checkIndex(index, 4);
        _setIntLE(index, value);
        return this;
    }

    protected abstract void _setIntLE(int index, int value);

    @Override
    public ByteBuf setFloat(int index, float value) {
        setInt(index, Float.floatToRawIntBits(value));
        return this;
    }

    @Override
    public ByteBuf setLong(int index, long value) {
        checkIndex(index, 8);
        _setLong(index, value);
        return this;
    }

    protected abstract void _setLong(int index, long value);

    @Override
    public ByteBuf setLongLE(int index, long value) {
        checkIndex(index, 8);
        _setLongLE(index, value);
        return this;
    }

    protected abstract void _setLongLE(int index, long value);

    @Override
    public ByteBuf setDouble(int index, double value) {
        setLong(index, Double.doubleToRawLongBits(value));
        return this;
    }

    @Override
    public ByteBuf setBytes(int index, byte[] src) {
        setBytes(index, src, 0, src.length);
        return this;
    }

    @Override
    public ByteBuf setBytes(int index, ByteBuf src) {
        setBytes(index, src, src.readableBytes());
        return this;
    }

    private static void checkReadableBounds(final ByteBuf src, final int length) {
        if (length > src.readableBytes()) {
            throw new IndexOutOfBoundsException(String.format(
                    "length(%d) exceeds src.readableBytes(%d) where src is: %s", length, src.readableBytes(), src));
        }
    }

    @Override
    public ByteBuf setBytes(int index, ByteBuf src, int length) {
        checkIndex(index, length);
        if (src == null) {
            throw new NullPointerException("src");
        }
        if (checkBounds) {
            checkReadableBounds(src, length);
        }

        setBytes(index, src, src.readerIndex(), length);
        src.readerIndex(src.readerIndex() + length);
        return this;
    }

    @Override
    public ByteBuf setZero(int index, int length) {
        if (length == 0) {
            return this;
        }

        checkIndex(index, length);

        int nLong = length >>> 3;
        int nBytes = length & 7;
        for (int i = nLong; i > 0; i--) {
            _setLong(index, 0);
            index += 8;
        }
        if (nBytes == 4) {
            _setInt(index, 0);
            // Not need to update the index as we not will use it after this.
        } else if (nBytes < 4) {
            for (int i = nBytes; i > 0; i--) {
                _setByte(index, (byte) 0);
                index++;
            }
        } else {
            _setInt(index, 0);
            index += 4;
            for (int i = nBytes - 4; i > 0; i--) {
                _setByte(index, (byte) 0);
                index++;
            }
        }
        return this;
    }

    @Override
    public int setCharSequence(int index, CharSequence sequence, Charset charset) {
        return setCharSequence0(index, sequence, charset, false);
    }

    private int setCharSequence0(int index, CharSequence sequence, Charset charset, boolean expand) {
        if (charset.equals(CharsetUtil.UTF_8)) {
            int length = ByteBufUtil.utf8MaxBytes(sequence);
            if (expand) {
                ensureWritable0(length);
                checkIndex0(index, length);
            } else {
                checkIndex(index, length);
            }
            return ByteBufUtil.writeUtf8(this, index, sequence, sequence.length());
        }
        if (charset.equals(CharsetUtil.US_ASCII) || charset.equals(CharsetUtil.ISO_8859_1)) {
            int length = sequence.length();
            if (expand) {
                ensureWritable0(length);
                checkIndex0(index, length);
            } else {
                checkIndex(index, length);
            }
            return ByteBufUtil.writeAscii(this, index, sequence, length);
        }
        byte[] bytes = sequence.toString().getBytes(charset);
        if (expand) {
            ensureWritable0(bytes.length);
            // setBytes(...) will take care of checking the indices.
        }
        setBytes(index, bytes);
        return bytes.length;
    }

    @Override
    public byte readByte() {
        checkReadableBytes0(1);
        int i = readerIndex;
        byte b = _getByte(i);
        readerIndex = i + 1;
        return b;
    }

    @Override
    public boolean readBoolean() {
        return readByte() != 0;
    }

    @Override
    public short readUnsignedByte() {
        return (short) (readByte() & 0xFF);
    }

    @Override
    public short readShort() {
        checkReadableBytes0(2);
        short v = _getShort(readerIndex);
        readerIndex += 2;
        return v;
    }

    @Override
    public short readShortLE() {
        checkReadableBytes0(2);
        short v = _getShortLE(readerIndex);
        readerIndex += 2;
        return v;
    }

    @Override
    public int readUnsignedShort() {
        return readShort() & 0xFFFF;
    }

    @Override
    public int readUnsignedShortLE() {
        return readShortLE() & 0xFFFF;
    }

    @Override
    public int readMedium() {
        int value = readUnsignedMedium();
        if ((value & 0x800000) != 0) {
            value |= 0xff000000;
        }
        return value;
    }

    @Override
    public int readMediumLE() {
        int value = readUnsignedMediumLE();
        if ((value & 0x800000) != 0) {
            value |= 0xff000000;
        }
        return value;
    }

    @Override
    public int readUnsignedMedium() {
        checkReadableBytes0(3);
        int v = _getUnsignedMedium(readerIndex);
        readerIndex += 3;
        return v;
    }

    @Override
    public int readUnsignedMediumLE() {
        checkReadableBytes0(3);
        int v = _getUnsignedMediumLE(readerIndex);
        readerIndex += 3;
        return v;
    }

    @Override
    public int readInt() {
        // 校验读取是否会超过可读段
        checkReadableBytes0(4);
        // 读取 Int 数据
        int v = _getInt(readerIndex);
        // 修改 readerIndex ，加上已读取字节数
        readerIndex += 4;
        return v;
    }

    @Override
    public int readIntLE() {
        checkReadableBytes0(4);
        int v = _getIntLE(readerIndex);
        readerIndex += 4;
        return v;
    }

    @Override
    public long readUnsignedInt() {
        return readInt() & 0xFFFFFFFFL;
    }

    @Override
    public long readUnsignedIntLE() {
        return readIntLE() & 0xFFFFFFFFL;
    }

    @Override
    public long readLong() {
        checkReadableBytes0(8);
        long v = _getLong(readerIndex);
        readerIndex += 8;
        return v;
    }

    @Override
    public long readLongLE() {
        checkReadableBytes0(8);
        long v = _getLongLE(readerIndex);
        readerIndex += 8;
        return v;
    }

    @Override
    public char readChar() {
        return (char) readShort();
    }

    @Override
    public float readFloat() {
        return Float.intBitsToFloat(readInt());
    }

    @Override
    public double readDouble() {
        return Double.longBitsToDouble(readLong());
    }

    @Override
    public ByteBuf readBytes(int length) {
        checkReadableBytes(length);
        if (length == 0) {
            return Unpooled.EMPTY_BUFFER;
        }

        ByteBuf buf = alloc().buffer(length, maxCapacity);
        buf.writeBytes(this, readerIndex, length);
        readerIndex += length;
        return buf;
    }

    @Override
    public ByteBuf readSlice(int length) {
        checkReadableBytes(length);
        ByteBuf slice = slice(readerIndex, length);
        readerIndex += length;
        return slice;
    }

    @Override
    public ByteBuf readRetainedSlice(int length) {
        checkReadableBytes(length);
        ByteBuf slice = retainedSlice(readerIndex, length);
        readerIndex += length;
        return slice;
    }

    @Override
    public ByteBuf readBytes(byte[] dst, int dstIndex, int length) {
        checkReadableBytes(length);
        getBytes(readerIndex, dst, dstIndex, length);
        readerIndex += length;
        return this;
    }

    @Override
    public ByteBuf readBytes(byte[] dst) {
        readBytes(dst, 0, dst.length);
        return this;
    }

    @Override
    public ByteBuf readBytes(ByteBuf dst) {
        readBytes(dst, dst.writableBytes());
        return this;
    }

    @Override
    public ByteBuf readBytes(ByteBuf dst, int length) {
        if (checkBounds) {
            if (length > dst.writableBytes()) {
                throw new IndexOutOfBoundsException(String.format(
                        "length(%d) exceeds dst.writableBytes(%d) where dst is: %s", length, dst.writableBytes(), dst));
            }
        }
        readBytes(dst, dst.writerIndex(), length);
        dst.writerIndex(dst.writerIndex() + length);
        return this;
    }

    @Override
    public ByteBuf readBytes(ByteBuf dst, int dstIndex, int length) {
        checkReadableBytes(length);
        getBytes(readerIndex, dst, dstIndex, length);
        readerIndex += length;
        return this;
    }

    @Override
    public ByteBuf readBytes(ByteBuffer dst) {
        int length = dst.remaining();
        checkReadableBytes(length);
        getBytes(readerIndex, dst);
        readerIndex += length;
        return this;
    }

    @Override
    public int readBytes(GatheringByteChannel out, int length)
            throws IOException {
        checkReadableBytes(length);
        int readBytes = getBytes(readerIndex, out, length);
        readerIndex += readBytes;
        return readBytes;
    }

    @Override
    public int readBytes(FileChannel out, long position, int length)
            throws IOException {
        checkReadableBytes(length);
        int readBytes = getBytes(readerIndex, out, position, length);
        readerIndex += readBytes;
        return readBytes;
    }

    @Override
    public ByteBuf readBytes(OutputStream out, int length) throws IOException {
        checkReadableBytes(length);
        getBytes(readerIndex, out, length);
        readerIndex += length;
        return this;
    }

    @Override
    public ByteBuf skipBytes(int length) {
        checkReadableBytes(length);
        readerIndex += length;
        return this;
    }

    @Override
    public ByteBuf writeBoolean(boolean value) {
        writeByte(value ? 1 : 0);
        return this;
    }

    @Override
    public ByteBuf writeByte(int value) {
        ensureWritable0(1);
        _setByte(writerIndex++, value);
        return this;
    }

    @Override
    public ByteBuf writeShort(int value) {
        ensureWritable0(2);
        _setShort(writerIndex, value);
        writerIndex += 2;
        return this;
    }

    @Override
    public ByteBuf writeShortLE(int value) {
        ensureWritable0(2);
        _setShortLE(writerIndex, value);
        writerIndex += 2;
        return this;
    }

    @Override
    public ByteBuf writeMedium(int value) {
        ensureWritable0(3);
        _setMedium(writerIndex, value);
        writerIndex += 3;
        return this;
    }

    @Override
    public ByteBuf writeMediumLE(int value) {
        ensureWritable0(3);
        _setMediumLE(writerIndex, value);
        writerIndex += 3;
        return this;
    }

    @Override
    public ByteBuf writeInt(int value) {
        // 保证可写入
        ensureWritable0(4);
        // 写入 Int 数据
        _setInt(writerIndex, value);
        // 修改 writerIndex ，加上已写入字节数
        writerIndex += 4;
        return this;
    }

    @Override
    public ByteBuf writeIntLE(int value) {
        ensureWritable0(4);
        _setIntLE(writerIndex, value);
        writerIndex += 4;
        return this;
    }

    @Override
    public ByteBuf writeLong(long value) {
        ensureWritable0(8);
        _setLong(writerIndex, value);
        writerIndex += 8;
        return this;
    }

    @Override
    public ByteBuf writeLongLE(long value) {
        ensureWritable0(8);
        _setLongLE(writerIndex, value);
        writerIndex += 8;
        return this;
    }

    @Override
    public ByteBuf writeChar(int value) {
        writeShort(value);
        return this;
    }

    @Override
    public ByteBuf writeFloat(float value) {
        writeInt(Float.floatToRawIntBits(value));
        return this;
    }

    @Override
    public ByteBuf writeDouble(double value) {
        writeLong(Double.doubleToRawLongBits(value));
        return this;
    }

    @Override
    public ByteBuf writeBytes(byte[] src, int srcIndex, int length) {
        ensureWritable(length);
        setBytes(writerIndex, src, srcIndex, length);
        writerIndex += length;
        return this;
    }

    @Override
    public ByteBuf writeBytes(byte[] src) {
        writeBytes(src, 0, src.length);
        return this;
    }

    @Override
    public ByteBuf writeBytes(ByteBuf src) {
        writeBytes(src, src.readableBytes());
        return this;
    }

    @Override
    public ByteBuf writeBytes(ByteBuf src, int length) {
        if (checkBounds) {
            checkReadableBounds(src, length);
        }
        writeBytes(src, src.readerIndex(), length);
        src.readerIndex(src.readerIndex() + length);
        return this;
    }

    @Override
    public ByteBuf writeBytes(ByteBuf src, int srcIndex, int length) {
        ensureWritable(length);
        setBytes(writerIndex, src, srcIndex, length);
        writerIndex += length;
        return this;
    }

    @Override
    public ByteBuf writeBytes(ByteBuffer src) {
        int length = src.remaining();
        ensureWritable0(length);
        setBytes(writerIndex, src);
        writerIndex += length;
        return this;
    }

    @Override
    public int writeBytes(InputStream in, int length)
            throws IOException {
        ensureWritable(length);
        int writtenBytes = setBytes(writerIndex, in, length);
        if (writtenBytes > 0) {
            writerIndex += writtenBytes;
        }
        return writtenBytes;
    }

    @Override
    public int writeBytes(ScatteringByteChannel in, int length) throws IOException {
        ensureWritable(length);
        int writtenBytes = setBytes(writerIndex, in, length);
        //writtenBytes > 0时表示读到了数据，-1则表示对端已关闭
        if (writtenBytes > 0) {
            writerIndex += writtenBytes;
        }
        return writtenBytes;
    }

    @Override
    public int writeBytes(FileChannel in, long position, int length) throws IOException {
        ensureWritable(length);
        int writtenBytes = setBytes(writerIndex, in, position, length);
        if (writtenBytes > 0) {
            writerIndex += writtenBytes;
        }
        return writtenBytes;
    }

    @Override
    public ByteBuf writeZero(int length) {
        if (length == 0) {
            return this;
        }

        ensureWritable(length);
        int wIndex = writerIndex;
        checkIndex0(wIndex, length);

        int nLong = length >>> 3;
        int nBytes = length & 7;
        for (int i = nLong; i > 0; i--) {
            _setLong(wIndex, 0);
            wIndex += 8;
        }
        if (nBytes == 4) {
            _setInt(wIndex, 0);
            wIndex += 4;
        } else if (nBytes < 4) {
            for (int i = nBytes; i > 0; i--) {
                _setByte(wIndex, (byte) 0);
                wIndex++;
            }
        } else {
            _setInt(wIndex, 0);
            wIndex += 4;
            for (int i = nBytes - 4; i > 0; i--) {
                _setByte(wIndex, (byte) 0);
                wIndex++;
            }
        }
        writerIndex = wIndex;
        return this;
    }

    @Override
    public int writeCharSequence(CharSequence sequence, Charset charset) {
        int written = setCharSequence0(writerIndex, sequence, charset, true);
        writerIndex += written;
        return written;
    }

    @Override
    public ByteBuf copy() {
        //调用 #readableBytes() 方法，获得可读的字节数
        //调用 #copy(int index, int length) 方法，拷贝指定部分的字节数组。独立，互相不影响。具体的实现，需要子类中实现，
        // 原因是做深拷贝，需要根据内存类型是 Heap 和 Direct 会有不同
        return copy(readerIndex, readableBytes());
    }

    /**
     * 创建的 UnpooledDuplicatedByteBuf 对象。在它内部，会调用当前 ByteBuf 对象，所以这也是为什么说是共享的。或者说，
     * 我们可以认为这是一个浅拷贝。
     * 它和 #slice() 方法的差别在于，前者是整个，后者是可写段
     * @return
     */
    @Override
    public ByteBuf duplicate() {
        ensureAccessible();
        return new UnpooledDuplicatedByteBuf(this);
    }

    /**
     * 调用 #duplicate() 方法，拷贝整个的字节数组。也就说，返回 UnpooledDuplicatedByteBuf 对象。
     * 调用 UnpooledDuplicatedByteBuf#retain() 方法，，引用计数加 1
     * @return
     */
    @Override
    public ByteBuf retainedDuplicate() {
        return duplicate().retain();
    }

    /**
     * 拷贝可读部分的字节数组
     *
     * @return
     */
    @Override
    public ByteBuf slice() {
        //调用 #readableBytes() 方法，获得可读的字节数
        //调用 #slice(int index, int length) 方法，拷贝指定部分的字节数组。共享，互相影响
        return slice(readerIndex, readableBytes());
    }

    /**
     * 在 #slice() 方法的基础上，引用计数加 1
     * @return
     */
    @Override
    public ByteBuf retainedSlice() {
        return slice().retain();
    }

    @Override
    public ByteBuf slice(int index, int length) {
        // 校验可访问
        ensureAccessible();
        // 创建 UnpooledSlicedByteBuf 对象
        //返回的是创建的 UnpooledSlicedByteBuf 对象。在它内部，会调用当前 ByteBuf 对象，所以这也是为什么说是共享的。
        // 或者说，我们可以认为这是一个浅拷贝。
        return new UnpooledSlicedByteBuf(this, index, length);
    }

    @Override
    public ByteBuf retainedSlice(int index, int length) {
        return slice(index, length).retain();
    }

    @Override
    public ByteBuffer nioBuffer() {
        //在方法内部，会调用 #nioBuffer(int index, int length) 方法。而该方法，由具体的子类实现
        //将当前 ByteBuf 的可读缓冲区( readerIndex 到 writerIndex 之间的内容) 转换为 ByteBuffer 对象，两者共享共享缓冲区的内容。
        // 对 ByteBuffer 的读写操作不会影响 ByteBuf 的读写索引。
        //注意：ByteBuffer 无法感知 ByteBuf 的动态扩展操作。ByteBuffer 的长度为readableBytes()
        return nioBuffer(readerIndex, readableBytes());
    }

    @Override
    public ByteBuffer[] nioBuffers() {
        //在方法内部，会调用 #nioBuffers(int index, int length) 方法。而该方法，由具体的子类实现。
        //😈 为什么会产生数组的情况呢？例如 CompositeByteBuf
        return nioBuffers(readerIndex, readableBytes());
    }

    @Override
    public String toString(Charset charset) {
        return toString(readerIndex, readableBytes(), charset);
    }

    @Override
    public String toString(int index, int length, Charset charset) {
        return ByteBufUtil.decodeString(this, index, length, charset);
    }

    @Override
    public int indexOf(int fromIndex, int toIndex, byte value) {
        return ByteBufUtil.indexOf(this, fromIndex, toIndex, value);
    }

    @Override
    public int bytesBefore(byte value) {
        return bytesBefore(readerIndex(), readableBytes(), value);
    }

    @Override
    public int bytesBefore(int length, byte value) {
        checkReadableBytes(length);
        return bytesBefore(readerIndex(), length, value);
    }

    @Override
    public int bytesBefore(int index, int length, byte value) {
        int endIndex = indexOf(index, index + length, value);
        if (endIndex < 0) {
            return -1;
        }
        return endIndex - index;
    }

    @Override
    public int forEachByte(ByteProcessor processor) {
        ensureAccessible();
        try {
            return forEachByteAsc0(readerIndex, writerIndex, processor);
        } catch (Exception e) {
            PlatformDependent.throwException(e);
            return -1;
        }
    }

    @Override
    public int forEachByte(int index, int length, ByteProcessor processor) {
        checkIndex(index, length);
        try {
            return forEachByteAsc0(index, index + length, processor);
        } catch (Exception e) {
            PlatformDependent.throwException(e);
            return -1;
        }
    }

    int forEachByteAsc0(int start, int end, ByteProcessor processor) throws Exception {
        for (; start < end; ++start) {
            if (!processor.process(_getByte(start))) {
                return start;
            }
        }

        return -1;
    }

    @Override
    public int forEachByteDesc(ByteProcessor processor) {
        ensureAccessible();
        try {
            return forEachByteDesc0(writerIndex - 1, readerIndex, processor);
        } catch (Exception e) {
            PlatformDependent.throwException(e);
            return -1;
        }
    }

    @Override
    public int forEachByteDesc(int index, int length, ByteProcessor processor) {
        checkIndex(index, length);
        try {
            return forEachByteDesc0(index + length - 1, index, processor);
        } catch (Exception e) {
            PlatformDependent.throwException(e);
            return -1;
        }
    }

    int forEachByteDesc0(int rStart, final int rEnd, ByteProcessor processor) throws Exception {
        for (; rStart >= rEnd; --rStart) {
            if (!processor.process(_getByte(rStart))) {
                return rStart;
            }
        }
        return -1;
    }

    @Override
    public int hashCode() {
        return ByteBufUtil.hashCode(this);
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof ByteBuf && ByteBufUtil.equals(this, (ByteBuf) o));
    }

    @Override
    public int compareTo(ByteBuf that) {
        return ByteBufUtil.compare(this, that);
    }

    @Override
    public String toString() {
        if (refCnt() == 0) {
            return StringUtil.simpleClassName(this) + "(freed)";
        }

        StringBuilder buf = new StringBuilder()
                .append(StringUtil.simpleClassName(this))
                .append("(ridx: ").append(readerIndex)
                .append(", widx: ").append(writerIndex)
                .append(", cap: ").append(capacity());
        if (maxCapacity != Integer.MAX_VALUE) {
            buf.append('/').append(maxCapacity);
        }

        ByteBuf unwrapped = unwrap();
        if (unwrapped != null) {
            buf.append(", unwrapped: ").append(unwrapped);
        }
        buf.append(')');
        return buf.toString();
    }

    protected final void checkIndex(int index) {
        checkIndex(index, 1);
    }

    protected final void checkIndex(int index, int fieldLength) {
        // 校验是否可访问
        ensureAccessible();
        // 校验是否会超过容量
        checkIndex0(index, fieldLength);
    }

    private static void checkRangeBounds(final int index, final int fieldLength, final int capacity) {
        if (isOutOfBounds(index, fieldLength, capacity)) {
            throw new IndexOutOfBoundsException(String.format(
                    "index: %d, length: %d (expected: range(0, %d))", index, fieldLength, capacity));
        }
    }

    final void checkIndex0(int index, int fieldLength) {
        if (checkBounds) {
            checkRangeBounds(index, fieldLength, capacity());
        }
    }

    protected final void checkSrcIndex(int index, int length, int srcIndex, int srcCapacity) {
        checkIndex(index, length);
        if (checkBounds) {
            checkRangeBounds(srcIndex, length, srcCapacity);
        }
    }

    protected final void checkDstIndex(int index, int length, int dstIndex, int dstCapacity) {
        checkIndex(index, length);
        if (checkBounds) {
            checkRangeBounds(dstIndex, length, dstCapacity);
        }
    }

    /**
     * Throws an {@link IndexOutOfBoundsException} if the current
     * {@linkplain #readableBytes() readable bytes} of this buffer is less
     * than the specified value.
     */
    protected final void checkReadableBytes(int minimumReadableBytes) {
        checkPositiveOrZero(minimumReadableBytes, "minimumReadableBytes");
        checkReadableBytes0(minimumReadableBytes);
    }

    protected final void checkNewCapacity(int newCapacity) {
        ensureAccessible();
        if (checkBounds) {
            if (newCapacity < 0 || newCapacity > maxCapacity()) {
                throw new IllegalArgumentException("newCapacity: " + newCapacity +
                        " (expected: 0-" + maxCapacity() + ')');
            }
        }
    }

    /**
     * 校验读取是否会超过可读段
     *
     * @param minimumReadableBytes
     */
    private void checkReadableBytes0(int minimumReadableBytes) {
        // 是否可访问
        ensureAccessible();
        if (checkBounds) {
            // 是否超过写索引，即超过可读段
            if (readerIndex > writerIndex - minimumReadableBytes) {
                throw new IndexOutOfBoundsException(String.format(
                        "readerIndex(%d) + length(%d) exceeds writerIndex(%d): %s",
                        readerIndex, minimumReadableBytes, writerIndex, this));
            }
        }
    }

    /**
     * Should be called by every method that tries to access the buffers content to check
     * if the buffer was released before.
     */
    protected final void ensureAccessible() {
        if (checkAccessible && !isAccessible()) {
            throw new IllegalReferenceCountException(0);
        }
    }

    final void setIndex0(int readerIndex, int writerIndex) {
        this.readerIndex = readerIndex;
        this.writerIndex = writerIndex;
    }

    final void discardMarks() {
        markedReaderIndex = markedWriterIndex = 0;
    }
}
