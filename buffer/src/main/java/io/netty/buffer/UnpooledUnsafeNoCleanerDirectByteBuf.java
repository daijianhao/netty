/*
 * Copyright 2016 The Netty Project
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

import io.netty.util.internal.PlatformDependent;

import java.nio.ByteBuffer;

/**
 * 和 UnpooledUnsafeDirectByteBuf 最大区别在于 UnpooledUnsafeNoCleanerDirectByteBuf 在 allocate的时候通过反射构造函数的方式
 * 创建DirectByteBuffer，这样在DirectByteBuffer中没有对应的Cleaner函数(通过ByteBuffer.allocateDirect的方式会自动生成Cleaner函数，
 * Cleaner用于内存回收，具体可以看源码)，内存回收时，UnpooledUnsafeDirectByteBuf通过调用DirectByteBuffer中的Cleaner函数回收，
 * 而UnpooledUnsafeNoCleanerDirectByteBuf直接使用UNSAFE.freeMemory(address)释放内存地址。
 */
class UnpooledUnsafeNoCleanerDirectByteBuf extends UnpooledUnsafeDirectByteBuf {

    UnpooledUnsafeNoCleanerDirectByteBuf(ByteBufAllocator alloc, int initialCapacity, int maxCapacity) {
        super(alloc, initialCapacity, maxCapacity);
    }

    @Override
    protected ByteBuffer allocateDirect(int initialCapacity) {
        return PlatformDependent.allocateDirectNoCleaner(initialCapacity);
    }

    ByteBuffer reallocateDirect(ByteBuffer oldBuffer, int initialCapacity) {
        return PlatformDependent.reallocateDirectNoCleaner(oldBuffer, initialCapacity);
    }

    @Override
    protected void freeDirect(ByteBuffer buffer) {
        PlatformDependent.freeDirectNoCleaner(buffer);
    }

    @Override
    public ByteBuf capacity(int newCapacity) {
        checkNewCapacity(newCapacity);

        int oldCapacity = capacity();
        if (newCapacity == oldCapacity) {
            return this;
        }

        ByteBuffer newBuffer = reallocateDirect(buffer, newCapacity);

        if (newCapacity < oldCapacity) {
            if (readerIndex() < newCapacity) {
                if (writerIndex() > newCapacity) {
                    writerIndex(newCapacity);
                }
            } else {
                setIndex(newCapacity, newCapacity);
            }
        }
        setByteBuffer(newBuffer, false);
        return this;
    }
}
