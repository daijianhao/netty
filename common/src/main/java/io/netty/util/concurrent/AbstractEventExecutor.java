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
package io.netty.util.concurrent;

import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Abstract base class for {@link EventExecutor} implementations.
 * 实现 EventExecutor 接口，继承 AbstractExecutorService 抽象类，EventExecutor 抽象类。
 */
public abstract class AbstractEventExecutor extends AbstractExecutorService implements EventExecutor {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(AbstractEventExecutor.class);

    static final long DEFAULT_SHUTDOWN_QUIET_PERIOD = 2;
    static final long DEFAULT_SHUTDOWN_TIMEOUT = 15;

    /**
     * 所属 EventExecutorGroup
     */
    private final EventExecutorGroup parent;

    /**
     * EventExecutor 数组。只包含自己，用于 {@link #iterator()}
     */
    private final Collection<EventExecutor> selfCollection = Collections.<EventExecutor>singleton(this);

    protected AbstractEventExecutor() {
        this(null);
    }

    protected AbstractEventExecutor(EventExecutorGroup parent) {
        this.parent = parent;
    }

    /**
     * 获得所属 EventExecutorGroup
     */
    @Override
    public EventExecutorGroup parent() {
        return parent;
    }

    /**
     * 获得自己
     */
    @Override
    public EventExecutor next() {
        return this;
    }

    /**
     * 判断当前线程是否在 EventLoop 线程中
     */
    @Override
    public boolean inEventLoop() {
        //需要在子类实现。因为 AbstractEventExecutor 类还体现不出它所拥有的线程
        return inEventLoop(Thread.currentThread());
    }

    @Override
    public Iterator<EventExecutor> iterator() {
        return selfCollection.iterator();
    }

    @Override
    public Future<?> shutdownGracefully() {
        //#shutdownGracefully(long quietPeriod, long timeout, TimeUnit unit) 和 #shutdown() 方法的实现，在子类中。
        return shutdownGracefully(DEFAULT_SHUTDOWN_QUIET_PERIOD, DEFAULT_SHUTDOWN_TIMEOUT, TimeUnit.SECONDS);
    }

    /**
     * @deprecated {@link #shutdownGracefully(long, long, TimeUnit)} or {@link #shutdownGracefully()} instead.
     *
     * 关闭执行器
     */
    @Override
    @Deprecated
    public abstract void shutdown();

    /**
     * @deprecated {@link #shutdownGracefully(long, long, TimeUnit)} or {@link #shutdownGracefully()} instead.
     */
    @Override
    @Deprecated
    public List<Runnable> shutdownNow() {
        shutdown();
        return Collections.emptyList();
    }

    /**
     * 创建的 Promise 对象，都会传入自身作为 EventExecutor
     *
     * @param <V>
     * @return
     */
    @Override
    public <V> Promise<V> newPromise() {
        return new DefaultPromise<V>(this);
    }

    /**
     * 创建的 Promise 对象，都会传入自身作为 EventExecutor
     *
     * @param <V>
     * @return
     */
    @Override
    public <V> ProgressivePromise<V> newProgressivePromise() {
        return new DefaultProgressivePromise<V>(this);
    }

    /**
     * 创建成功结果的 Future 对象
     */
    @Override
    public <V> Future<V> newSucceededFuture(V result) {
        //创建的 Future 对象，会传入自身作为 EventExecutor ，并传入 result作为成功结果
        return new SucceededFuture<V>(this, result);
    }

    /**
     * 创建异常的 Future 对象
     */
    @Override
    public <V> Future<V> newFailedFuture(Throwable cause) {
        //创建的 Future 对象，会传入自身作为 EventExecutor ，并传入 cause 作为异常结果
        return new FailedFuture<V>(this, cause);
    }

    //=========submit每个方法的实现上，是调用父类 AbstractExecutorService 的实现。========

    @Override
    public Future<?> submit(Runnable task) {
        return (Future<?>) super.submit(task);
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return (Future<T>) super.submit(task, result);
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return (Future<T>) super.submit(task);
    }

    @Override
    protected final <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
        //创建的 PromiseTask 对象，会传入自身作为 EventExecutor ，并传入 Runnable + Value 或 Callable 作为任务( Task )。
        return new PromiseTask<T>(this, runnable, value);
    }

    @Override
    protected final <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
        //创建的 PromiseTask 对象，会传入自身作为 EventExecutor ，并传入 Runnable + Value 或 Callable 作为任务( Task )。
        return new PromiseTask<T>(this, callable);
    }

    //========都不支持，交给子类 AbstractScheduledEventExecutor 实现===============

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay,
                                       TimeUnit unit) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        throw new UnsupportedOperationException();
    }

    /**
     * Try to execute the given {@link Runnable} and just log if it throws a {@link Throwable}.
     *
     *  静态方法，安全的执行任务
     *  所谓“安全”指的是，当任务执行发生异常时，仅仅打印告警日志。
     */
    protected static void safeExecute(Runnable task) {
        try {
            task.run();
        } catch (Throwable t) {
            logger.warn("A task raised an exception. Task: {}", task, t);
        }
    }
}
