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
package io.netty.util;

/**
 * 内存泄露追踪器接口。从 {@link io.netty.util.ResourceLeakDetector#track(java.lang.Object)} 中，
 * 我们已经看到，每个资源( 例如：ByteBuf 对象 )，
 * 会创建一个追踪它是否内存泄露的 ResourceLeakTracker 对象
 *
 * @param <T>
 */
public interface ResourceLeakTracker<T>  {

    /**
     * Records the caller's current stack trace so that the {@link ResourceLeakDetector} can tell where the leaked
     * resource was accessed lastly. This method is a shortcut to {@link #record(Object) record(null)}.
     *
     * 记录
     *
     * 出于调试目的，用一个额外的任意的( arbitrary )信息记录这个对象的当前访问地址。如果这个对象被检测到泄露了,
     * 这个操作记录的信息将通过ResourceLeakDetector 提供。实际上，就是 ReferenceCounted#touch(...) 方法，会调用 #record(...) 方法。
     */
    void record();

    /**
     * Records the caller's current stack trace and the specified additional arbitrary information
     * so that the {@link ResourceLeakDetector} can tell where the leaked resource was accessed lastly.
     *
     * 记录
     */
    void record(Object hint);

    /**
     * Close the leak so that {@link ResourceLeakTracker} does not warn about leaked resources.
     * After this method is called a leak associated with this ResourceLeakTracker should not be reported.
     *
     * 关闭
     * @return {@code true} if called first time, {@code false} if called already
     *
     * #close(T trackedObject) 方法，关闭 ResourceLeakTracker 。如果资源( 例如：ByteBuf 对象 )被正确释放，
     * 则会调用 #close(T trackedObject) 方法，关闭 ResourceLeakTracker ，从而结束追踪。
     * 这样，在 ResourceLeakDetector#reportLeak() 方法，就不会提示该资源泄露
     */
    boolean close(T trackedObject);
}
