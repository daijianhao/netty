/*
 * Copyright 2013 The Netty Project
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

import io.netty.util.ResourceLeakDetector;
import io.netty.util.ResourceLeakTracker;
import io.netty.util.internal.ObjectUtil;

import java.nio.ByteOrder;

/**
 * 继承 WrappedByteBuf 类，Simple 级别的 LeakAware ByteBuf 实现类
 */
class SimpleLeakAwareByteBuf extends WrappedByteBuf {

    /**
     * This object's is associated with the {@link ResourceLeakTracker}. When {@link ResourceLeakTracker#close(Object)}
     * is called this object will be used as the argument. It is also assumed that this object is used when
     * {@link ResourceLeakDetector#track(Object)} is called to create {@link #leak}.
     * <p>
     * 关联的 ByteBuf 对象
     */
    private final ByteBuf trackedByteBuf;

    /**
     * ResourceLeakTracker 对象
     */
    final ResourceLeakTracker<ByteBuf> leak;

    /**
     * 对于构造方法 <2> ，wrapped 和 trackedByteBuf 一般不同
     */
    SimpleLeakAwareByteBuf(ByteBuf wrapped, ByteBuf trackedByteBuf, ResourceLeakTracker<ByteBuf> leak) {
        super(wrapped);
        this.trackedByteBuf = ObjectUtil.checkNotNull(trackedByteBuf, "trackedByteBuf");
        this.leak = ObjectUtil.checkNotNull(leak, "leak");
    }

    /**
     * 对于构造方法 <1> ，wrapped 和 trackedByteBuf 相同
     */
    SimpleLeakAwareByteBuf(ByteBuf wrapped, ResourceLeakTracker<ByteBuf> leak) {
        this(wrapped, wrapped, leak);
    }

    @Override
    public ByteBuf slice() {
        //首先，调用父 #slice(...) 方法，获得 slice ByteBuf 对象
        //之后，因为 slice ByteBuf 对象，并不是一个 LeakAware 的 ByteBuf 对象。
        // 所以调用 #newSharedLeakAwareByteBuf(ByteBuf wrapped) 方法，装饰成 LeakAware 的 ByteBuf 对象
        return newSharedLeakAwareByteBuf(super.slice());
    }

    @Override
    public ByteBuf retainedSlice() {
        //先，调用父 #retainedSlice(...) 方法，获得 slice ByteBuf 对象，引用计数加 1
        //之后，因为 slice ByteBuf 对象，并不是一个 LeakAware 的 ByteBuf 对象。所以调用 #unwrappedDerived(ByteBuf wrapped) 方法，装饰成 LeakAware 的 ByteBuf 对象
        return unwrappedDerived(super.retainedSlice());
    }

    @Override
    public ByteBuf retainedSlice(int index, int length) {
        return unwrappedDerived(super.retainedSlice(index, length));
    }

    @Override
    public ByteBuf retainedDuplicate() {
        return unwrappedDerived(super.retainedDuplicate());
    }

    @Override
    public ByteBuf readRetainedSlice(int length) {
        return unwrappedDerived(super.readRetainedSlice(length));
    }

    //在 SimpleLeakAwareByteBuf 中，还有如下方法，和 #slice(...) 方法是类似的，在调用完父对应的方法后，
    // 再调用 #newSharedLeakAwareByteBuf(ByteBuf wrapped) 方法，装饰成 LeakAware 的 ByteBuf 对象
    @Override
    public ByteBuf slice(int index, int length) {
        return newSharedLeakAwareByteBuf(super.slice(index, length));
    }

    @Override
    public ByteBuf duplicate() {
        return newSharedLeakAwareByteBuf(super.duplicate());
    }

    @Override
    public ByteBuf readSlice(int length) {
        return newSharedLeakAwareByteBuf(super.readSlice(length));
    }

    @Override
    public ByteBuf asReadOnly() {
        return newSharedLeakAwareByteBuf(super.asReadOnly());
    }

    //又一脸懵逼？！实际 SimpleLeakAwareByteBuf 也并未实现 #touch(...) 方法。而是在 AdvancedLeakAwareByteBuf 中才实现
    @Override
    public ByteBuf touch() {
        return this;
    }

    @Override
    public ByteBuf touch(Object hint) {
        return this;
    }

    @Override
    public boolean release() {
        // 释放完成
        if (super.release()) {
            //先调用父类的release()，以包装的UnpooledDirectByteBuf为例：
            //当调用UnpooledDirectByteBuf.release()时通过Netty通过反射取到ByteBuffer中的invokeCleaner()将ByteBuffer对应的堆外内存释放
            //同时，接着调用closeLeak()->leak.close(trackedByteBuf)->allLeaks.remove(leak)，经过这几步后，
            //leak已经将自己从allLeaks中删除，并且leak这个弱引用也不再引用ByteBuffer
            //所以当ByteBuffer的jvm对象（其堆外内存已经释放）被GC时，发现没有弱引用再引用该ByteBuffer的jvm对象，因此不会将关联的leak放入
            //referenceQueue中了；相反若在referenceQueue中发现了leak仍然存在，则说明与其关联的ByteBuffer在被GC前没有被手动release()
            //考虑到DirectByteBuffer在老年代占用很少内存（大部分内存为堆外内存），所以在GC未出发时，其可能占用大量堆外内存，因此造成内存泄露

            //虽然虚拟机在GC时,会回收DirectByteBuffer的jvm对象，而DirectByteBuffer的jvm对象中包含一个Cleaner对象，Cleaner对象为一个
            //虚引用，其引用对象正是当前的DirectByteBuffer，而当当前的DirectByteBuffer除了该Cleaner外已经没其他引用可达是就被被GC回收，GC回收时
            // 会将Cleaner放进 Reference类pending list静态变量里。然后另有一条ReferenceHandler线程，名字叫 "Reference Handler"的，
            // 关注着这个pending list，如果看到有对象类型是Cleaner，就会执行它的clean()，从而将堆外内存释放
            closeLeak();
            return true;
        }
        return false;
    }

    @Override
    public boolean release(int decrement) {
        if (super.release(decrement)) {
            closeLeak();
            return true;
        }
        return false;
    }

    private void closeLeak() {
        // Close the ResourceLeakTracker with the tracked ByteBuf as argument. This must be the same that was used when
        // calling DefaultResourceLeak.track(...).
        boolean closed = leak.close(trackedByteBuf);
        assert closed;
    }

    @Override
    public ByteBuf order(ByteOrder endianness) {
        if (order() == endianness) {
            return this;
        } else {
            return newSharedLeakAwareByteBuf(super.order(endianness));
        }
    }

    private ByteBuf unwrappedDerived(ByteBuf derived) {
        // We only need to unwrap SwappedByteBuf implementations as these will be the only ones that may end up in
        // the AbstractLeakAwareByteBuf implementations beside slices / duplicates and "real" buffers.
        ByteBuf unwrappedDerived = unwrapSwapped(derived);

        if (unwrappedDerived instanceof AbstractPooledDerivedByteBuf) {
            // Update the parent to point to this buffer so we correctly close the ResourceLeakTracker.
            ((AbstractPooledDerivedByteBuf) unwrappedDerived).parent(this);

            ResourceLeakTracker<ByteBuf> newLeak = AbstractByteBuf.leakDetector.track(derived);
            if (newLeak == null) {
                // No leak detection, just return the derived buffer.
                return derived;
            }
            return newLeakAwareByteBuf(derived, newLeak);
        }
        return newSharedLeakAwareByteBuf(derived);
    }

    @SuppressWarnings("deprecation")
    private static ByteBuf unwrapSwapped(ByteBuf buf) {
        if (buf instanceof SwappedByteBuf) {
            do {
                buf = buf.unwrap();
            } while (buf instanceof SwappedByteBuf);

            return buf;
        }
        return buf;
    }

    private SimpleLeakAwareByteBuf newSharedLeakAwareByteBuf(
            ByteBuf wrapped) {
        //我们可以看到，trackedByteBuf 代表的是原始的 ByteBuf 对象，它是跟 leak 真正进行关联的。而 wrapped 则不是
        return newLeakAwareByteBuf(wrapped, trackedByteBuf, leak);
    }

    private SimpleLeakAwareByteBuf newLeakAwareByteBuf(
            ByteBuf wrapped, ResourceLeakTracker<ByteBuf> leakTracker) {
        return newLeakAwareByteBuf(wrapped, wrapped, leakTracker);
    }

    protected SimpleLeakAwareByteBuf newLeakAwareByteBuf(
            ByteBuf buf, ByteBuf trackedByteBuf, ResourceLeakTracker<ByteBuf> leakTracker) {
        return new SimpleLeakAwareByteBuf(buf, trackedByteBuf, leakTracker);
    }
}
