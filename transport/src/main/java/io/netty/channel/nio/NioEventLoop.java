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
package io.netty.channel.nio;

import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopException;
import io.netty.channel.SelectStrategy;
import io.netty.channel.SingleThreadEventLoop;
import io.netty.util.IntSupplier;
import io.netty.util.concurrent.RejectedExecutionHandler;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.ReflectionUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.Selector;
import java.nio.channels.SelectionKey;

import java.nio.channels.spi.SelectorProvider;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link SingleThreadEventLoop} implementation which register the {@link Channel}'s to a
 * {@link Selector} and so does the multi-plexing of these in the event loop.
 * <p>
 * 继承 SingleThreadEventLoop 抽象类，NIO EventLoop 实现类，实现对注册到其中的 Channel 的就绪的 IO 事件，
 * 和对用户提交的任务进行处理。
 */
public final class NioEventLoop extends SingleThreadEventLoop {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(NioEventLoop.class);

    /**
     * TODO 1007 NioEventLoop cancel
     */
    private static final int CLEANUP_INTERVAL = 256; // XXX Hard-coded value, but won't need customization.

    /**
     * 是否禁用 SelectionKey 的优化，默认开启
     */
    private static final boolean DISABLE_KEY_SET_OPTIMIZATION =
            SystemPropertyUtil.getBoolean("io.netty.noKeySetOptimization", false);

    /**
     * 少于该 N 值，不开启空轮询重建新的 Selector 对象的功能
     */
    private static final int MIN_PREMATURE_SELECTOR_RETURNS = 3;

    /**
     * NIO Selector 空轮询该 N 次后，重建新的 Selector 对象，用以解决 JDK NIO 的 epoll 空轮询 Bug
     */
    private static final int SELECTOR_AUTO_REBUILD_THRESHOLD;

    private final IntSupplier selectNowSupplier = new IntSupplier() {
        @Override
        public int get() throws Exception {
            return selectNow();
        }
    };

    // Workaround for JDK NIO bug.
    //
    // See:
    // - http://bugs.sun.com/view_bug.do?bug_id=6427854
    // - https://github.com/netty/netty/issues/203
    static {
        // 解决 Selector#open() 方法 // <1>
        final String key = "sun.nio.ch.bugLevel";
        final String bugLevel = SystemPropertyUtil.get(key);
        if (bugLevel == null) {
            try {
                AccessController.doPrivileged(new PrivilegedAction<Void>() {
                    @Override
                    public Void run() {
                        //因为Selector.open()可能会由于bugLevel==null造成NullPointerException,
                        //所以在此处直接设置 "sun.nio.ch.bugLevel" 属性为 "" 来避免这个问题
                        System.setProperty(key, "");
                        return null;
                    }
                });
            } catch (final SecurityException e) {
                logger.debug("Unable to get/set System Property: " + key, e);
            }
        }

        int selectorAutoRebuildThreshold = SystemPropertyUtil.getInt("io.netty.selectorAutoRebuildThreshold", 512);
        if (selectorAutoRebuildThreshold < MIN_PREMATURE_SELECTOR_RETURNS) {
            selectorAutoRebuildThreshold = 0;
        }

        SELECTOR_AUTO_REBUILD_THRESHOLD = selectorAutoRebuildThreshold;

        if (logger.isDebugEnabled()) {
            logger.debug("-Dio.netty.noKeySetOptimization: {}", DISABLE_KEY_SET_OPTIMIZATION);
            logger.debug("-Dio.netty.selectorAutoRebuildThreshold: {}", SELECTOR_AUTO_REBUILD_THRESHOLD);
        }
    }

    /**
     * The NIO {@link Selector}.
     * <p>
     * 包装的 Selector 对象，经过优化
     */
    private Selector selector;

    /**
     * 未包装的 Selector 对象
     */
    private Selector unwrappedSelector;

    /**
     * 注册的 SelectionKey 集合。Netty 自己实现，经过优化。
     */
    private SelectedSelectionKeySet selectedKeys;

    /**
     * SelectorProvider 对象，用于创建 Selector 对象
     * 在 <1> 处，调用 #openSelector() 方法，创建 NIO Selector 对象。
     */
    private final SelectorProvider provider;

    /**
     * Boolean that controls determines if a blocked Selector.select should
     * break out of its selection process. In our case we use a timeout for
     * the select method and the select method will block for that time unless
     * waken up.
     * <p>
     * 唤醒标记。因为唤醒方法 {@link Selector#wakeup()} 开销比较大，通过该标识，减少调用
     */
    private final AtomicBoolean wakenUp = new AtomicBoolean();

    /**
     * Select 策略
     *
     * @see #select(boolean)
     */
    private final SelectStrategy selectStrategy;

    /**
     * 处理 Channel 的就绪的 IO 事件，占处理任务的总时间的比例
     * 在 NioEventLoop 中，会三种类型的任务：1) Channel 的就绪的 IO 事件；2) 普通任务；3) 定时任务。
     * 而 ioRatio 属性，处理 Channel 的就绪的 IO 事件，占处理任务的总时间的比例。
     */
    private volatile int ioRatio = 50;

    /**
     * 取消 SelectionKey 的数量
     * <p>
     * TODO 1007 NioEventLoop cancel
     */
    private int cancelledKeys;

    /**
     * 是否需要再次 select Selector 对象
     * <p>
     * TODO 1007 NioEventLoop cancel
     */
    private boolean needsToSelectAgain;

    NioEventLoop(NioEventLoopGroup parent, Executor executor, SelectorProvider selectorProvider,
                 SelectStrategy strategy, RejectedExecutionHandler rejectedExecutionHandler) {
        super(parent, executor, false, DEFAULT_MAX_PENDING_TASKS, rejectedExecutionHandler);
        if (selectorProvider == null) {
            throw new NullPointerException("selectorProvider");
        }
        if (strategy == null) {
            throw new NullPointerException("selectStrategy");
        }
        provider = selectorProvider;
        final SelectorTuple selectorTuple = openSelector();
        selector = selectorTuple.selector;
        unwrappedSelector = selectorTuple.unwrappedSelector;
        selectStrategy = strategy;
    }

    /**
     * Selector 元组
     */
    private static final class SelectorTuple {
        /**
         * 未包装的 Selector 对象
         */
        final Selector unwrappedSelector;

        /**
         * 可能是Netty自己实现的 Selector 对象，也可能是未优化的原始Java Selector
         */
        final Selector selector;

        SelectorTuple(Selector unwrappedSelector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = unwrappedSelector;
        }

        SelectorTuple(Selector unwrappedSelector, Selector selector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = selector;
        }
    }

    /**
     * 创建 NIO Selector 对象
     *
     * @return
     */
    private SelectorTuple openSelector() {
        final Selector unwrappedSelector;
        try {
            //创建selector对象
            unwrappedSelector = provider.openSelector();
        } catch (IOException e) {
            throw new ChannelException("failed to open a new selector", e);
        }

        // 禁用 SelectionKey 的优化，则直接返回 SelectorTuple 对象。即，selector 也使用 unwrappedSelector
        if (DISABLE_KEY_SET_OPTIMIZATION) {
            return new SelectorTuple(unwrappedSelector);
        }

        // 获得 SelectorImpl 类
        Object maybeSelectorImplClass = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                try {
                    return Class.forName(
                            "sun.nio.ch.SelectorImpl",
                            false,
                            PlatformDependent.getSystemClassLoader());
                } catch (Throwable cause) {
                    return cause;
                }
            }
        });

        // 获得 SelectorImpl 类失败，则直接返回 SelectorTuple 对象。即，selector 也使用 unwrappedSelector 。
        if (!(maybeSelectorImplClass instanceof Class) ||
                // ensure the current selector implementation is what we can instrument.
                !((Class<?>) maybeSelectorImplClass).isAssignableFrom(unwrappedSelector.getClass())) {
            if (maybeSelectorImplClass instanceof Throwable) {
                Throwable t = (Throwable) maybeSelectorImplClass;
                logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, t);
            }
            return new SelectorTuple(unwrappedSelector);
        }

        final Class<?> selectorImplClass = (Class<?>) maybeSelectorImplClass;
        // 创建 SelectedSelectionKeySet 对象
        final SelectedSelectionKeySet selectedKeySet = new SelectedSelectionKeySet();

        // 设置 SelectedSelectionKeySet 对象到 unwrappedSelector 中
        Object maybeException = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                try {
                    Field selectedKeysField = selectorImplClass.getDeclaredField("selectedKeys");
                    //publicSelectedKeys是供外部访问并且已经就绪的SelectionKey集合
                    Field publicSelectedKeysField = selectorImplClass.getDeclaredField("publicSelectedKeys");

                    if (PlatformDependent.javaVersion() >= 9 && PlatformDependent.hasUnsafe()) {
                        // Let us try to use sun.misc.Unsafe to replace the SelectionKeySet.
                        // This allows us to also do this in Java9+ without any extra flags.
                        //获取这两个字段相对于java对象地址的偏移量
                        long selectedKeysFieldOffset = PlatformDependent.objectFieldOffset(selectedKeysField);
                        long publicSelectedKeysFieldOffset =
                                PlatformDependent.objectFieldOffset(publicSelectedKeysField);

                        if (selectedKeysFieldOffset != -1 && publicSelectedKeysFieldOffset != -1) {
                            //相当于将selectedKeySet设置到unwrappedSelector对象中
                            PlatformDependent.putObject(
                                    unwrappedSelector, selectedKeysFieldOffset, selectedKeySet);
                            PlatformDependent.putObject(
                                    unwrappedSelector, publicSelectedKeysFieldOffset, selectedKeySet);
                            return null;
                        }
                        //我们无法获取偏移量，请尝试将反射作为最后的手段
                        // We could not retrieve the offset, lets try reflection as last-resort.
                    }
                    // 设置 Field 可访问
                    Throwable cause = ReflectionUtil.trySetAccessible(selectedKeysField, true);
                    if (cause != null) {
                        return cause;
                    }
                    // 设置 Field 可访问
                    cause = ReflectionUtil.trySetAccessible(publicSelectedKeysField, true);
                    if (cause != null) {
                        return cause;
                    }
                    // 设置 SelectedSelectionKeySet 对象到 unwrappedSelector 的 Field 中
                    selectedKeysField.set(unwrappedSelector, selectedKeySet);
                    publicSelectedKeysField.set(unwrappedSelector, selectedKeySet);
                    return null;
                } catch (NoSuchFieldException e) {
                    // 失败，则返回该异常
                    return e;
                } catch (IllegalAccessException e) {
                    return e;
                }
            }
        });

        if (maybeException instanceof Exception) {
            // 设置 SelectedSelectionKeySet 对象到 unwrappedSelector 中失败，
            // 则直接返回 SelectorTuple 对象。即，selector 也使用 unwrappedSelector 。
            selectedKeys = null;
            Exception e = (Exception) maybeException;
            logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, e);
            return new SelectorTuple(unwrappedSelector);
        }
        selectedKeys = selectedKeySet;
        logger.trace("instrumented a special java.util.Set into: {}", unwrappedSelector);
        // 创建 SelectedSelectionKeySetSelector 对象
        // 创建 SelectorTuple 对象。即，selector 也使用 SelectedSelectionKeySetSelector 对象
        return new SelectorTuple(unwrappedSelector,
                new SelectedSelectionKeySetSelector(unwrappedSelector, selectedKeySet));
    }

    /**
     * Returns the {@link SelectorProvider} used by this {@link NioEventLoop} to obtain the {@link Selector}.
     */
    public SelectorProvider selectorProvider() {
        return provider;
    }

    /**
     * 创建任务队列
     * 该方法覆写父类的该方法
     */
    @Override
    protected Queue<Runnable> newTaskQueue(int maxPendingTasks) {
        // This event loop never calls takeTask()
        //调用 PlatformDependent#newMpscQueue(...) 方法，创建 mpsc 队列。我们来看看代码注释对 mpsc 队列的描述：
        return maxPendingTasks == Integer.MAX_VALUE ? PlatformDependent.<Runnable>newMpscQueue()
                : PlatformDependent.<Runnable>newMpscQueue(maxPendingTasks);
    }

    /**
     * Registers an arbitrary {@link SelectableChannel}, not necessarily created by Netty, to the {@link Selector}
     * of this event loop.  Once the specified {@link SelectableChannel} is registered, the specified {@code task} will
     * be executed by this event loop when the {@link SelectableChannel} is ready.
     * <p>
     * 注册 Java NIO Channel ( 不一定需要通过 Netty 创建的 Channel )到 Selector 上，相当于说，也注册到了 EventLoop 上
     * <p>
     * 这里我们可以看到，attachment 为 NioTask 对象，而不是 Netty Channel 对象。
     */
    public void register(final SelectableChannel ch, final int interestOps, final NioTask<?> task) {
        if (ch == null) {
            throw new NullPointerException("ch");
        }
        if (interestOps == 0) {
            throw new IllegalArgumentException("interestOps must be non-zero.");
        }
        if ((interestOps & ~ch.validOps()) != 0) {
            throw new IllegalArgumentException(
                    "invalid interestOps: " + interestOps + "(validOps: " + ch.validOps() + ')');
        }
        if (task == null) {
            throw new NullPointerException("task");
        }

        if (isShutdown()) {
            throw new IllegalStateException("event loop shut down");
        }

        if (inEventLoop()) {
            register0(ch, interestOps, task);
        } else {
            try {
                // Offload to the EventLoop as otherwise java.nio.channels.spi.AbstractSelectableChannel.register
                // may block for a long time while trying to obtain an internal lock that may be hold while selecting.
                submit(new Runnable() {
                    @Override
                    public void run() {
                        register0(ch, interestOps, task);
                    }
                }).sync();
            } catch (InterruptedException ignore) {
                // Even if interrupted we did schedule it so just mark the Thread as interrupted.
                Thread.currentThread().interrupt();
            }
        }
    }

    private void register0(SelectableChannel ch, int interestOps, NioTask<?> task) {
        try {
            ch.register(unwrappedSelector, interestOps, task);
        } catch (Exception e) {
            throw new EventLoopException("failed to register a channel", e);
        }
    }

    /**
     * Returns the percentage of the desired amount of time spent for I/O in the event loop.
     */
    public int getIoRatio() {
        return ioRatio;
    }

    /**
     * Sets the percentage of the desired amount of time spent for I/O in the event loop.  The default value is
     * {@code 50}, which means the event loop will try to spend the same amount of time for I/O as for non-I/O tasks.
     * <p>
     * 设置 ioRatio 属性
     */
    public void setIoRatio(int ioRatio) {
        if (ioRatio <= 0 || ioRatio > 100) {
            throw new IllegalArgumentException("ioRatio: " + ioRatio + " (expected: 0 < ioRatio <= 100)");
        }
        this.ioRatio = ioRatio;
    }

    /**
     * Replaces the current {@link Selector} of this event loop with newly created {@link Selector}s to work
     * around the infamous epoll 100% CPU bug.
     * <p>
     * 重建 NIO Selector 对象，来解决epoll 100% CPU bug.
     * 重建也是异步实现的
     */
    public void rebuildSelector() {
        //只允许在 EventLoop 的线程中，调用 #rebuildSelector0() 方法，重建 Selector 对象
        if (!inEventLoop()) {
            //此时调用execute()，由于线程已经启动，所以会将任务加入任务队列并在select()循环时执行
            execute(new Runnable() {
                @Override
                public void run() {
                    rebuildSelector0();
                }
            });
            return;
        }
        rebuildSelector0();
    }

    /**
     * 重建 Selector 对象
     * <p>
     * 由于一个每个NioEventLoop都维护一个自己的Selector,
     * 并且每个NioEventLoop只由一个线程执行，就不存在线程安全问题
     */
    private void rebuildSelector0() {
        //老的Selector
        final Selector oldSelector = selector;
        final SelectorTuple newSelectorTuple;

        if (oldSelector == null) {
            return;
        }

        try {
            //创建新的SelectorTuple
            newSelectorTuple = openSelector();
        } catch (Exception e) {
            logger.warn("Failed to create a new Selector.", e);
            return;
        }

        // Register all channels to the new Selector.
        // 遍历老的 Selector 对象的 selectionKeys ，将注册在 NioEventLoop 上的所有 Channel ，注册到新创建 Selector 对象上。
        int nChannels = 0;
        for (SelectionKey key : oldSelector.keys()) {
            Object a = key.attachment();
            try {
                //校验 SelectionKey 有效，并且 Java NIO Channel 并未注册在新的 Selector 对象上。
                //keyFor(Selector sel):获取表示通道向给定选择器注册的键
                if (!key.isValid() || key.channel().keyFor(newSelectorTuple.unwrappedSelector) != null) {
                    continue;
                }
                //获取感兴趣的事件
                int interestOps = key.interestOps();
                //取消老的 SelectionKey
                key.cancel();
                //将 Java NIO Channel 注册到新的 Selector 对象上，返回新的 SelectionKey 对象。
                SelectionKey newKey = key.channel().register(newSelectorTuple.unwrappedSelector, interestOps, a);
                if (a instanceof AbstractNioChannel) {
                    // Update SelectionKey
                    // 修改 Channel 的 selectionKey 指向新的 SelectionKey 对象
                    ((AbstractNioChannel) a).selectionKey = newKey;
                }
                nChannels++;
            } catch (Exception e) {
                logger.warn("Failed to re-register a Channel to the new Selector.", e);
                if (a instanceof AbstractNioChannel) {
                    //当 attachment 是 Netty NIO Channel 时，调用 Unsafe#close(ChannelPromise promise) 方法，
                    // 关闭发生异常的 Channel 。
                    AbstractNioChannel ch = (AbstractNioChannel) a;
                    ch.unsafe().close(ch.unsafe().voidPromise());
                } else {
                    //当 attachment 是 Netty NioTask 时，调用 #invokeChannelUnregistered(NioTask<SelectableChannel> task,
                    // SelectionKey k, Throwable cause) 方法，通知 Channel 取消注册。
                    @SuppressWarnings("unchecked")
                    NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                    invokeChannelUnregistered(task, key, e);
                }
            }
        }
        //修改 selector 和 unwrappedSelector 指向新的 Selector 对象。
        selector = newSelectorTuple.selector;
        unwrappedSelector = newSelectorTuple.unwrappedSelector;

        try {
            // time to close the old selector as everything else is registered to the new one
            //调用 Selector#close() 方法，关闭老的 Selector 对象。
            oldSelector.close();
        } catch (Throwable t) {
            if (logger.isWarnEnabled()) {
                logger.warn("Failed to close the old Selector.", t);
            }
        }

        if (logger.isInfoEnabled()) {
            logger.info("Migrated " + nChannels + " channel(s) to the new Selector.");
        }
    }

    @Override
    protected void run() {
        //“死”循环，直到 NioEventLoop 关闭
        for (; ; ) {
            try {
                try {
                    //获得使用的 select 策略
                    switch (selectStrategy.calculateStrategy(selectNowSupplier, hasTasks())) {
                        case SelectStrategy.CONTINUE:
                            // 默认实现下，不存在这个情况
                            continue;

                        case SelectStrategy.BUSY_WAIT:
                            // fall-through to SELECT since the busy-wait is not supported with NIO

                        case SelectStrategy.SELECT:
                            // 重置 wakenUp 标记为 false
                            // 选择( 查询 )任务
                            select(wakenUp.getAndSet(false));

                            // 'wakenUp.compareAndSet(false, true)' is always evaluated
                            // before calling 'selector.wakeup()' to reduce the wake-up
                            // overhead. (Selector.wakeup() is an expensive operation.)
                            //
                            // However, there is a race condition in this approach.
                            // The race condition is triggered when 'wakenUp' is set to
                            // true too early.
                            //
                            // 'wakenUp' is set to true too early if:
                            // 1) Selector is waken up between 'wakenUp.set(false)' and
                            //    'selector.select(...)'. (BAD)
                            // 2) Selector is waken up between 'selector.select(...)' and
                            //    'if (wakenUp.get()) { ... }'. (OK)
                            //
                            // In the first case, 'wakenUp' is set to true and the
                            // following 'selector.select(...)' will wake up immediately.
                            // Until 'wakenUp' is set to false again in the next round,
                            // 'wakenUp.compareAndSet(false, true)' will fail, and therefore
                            // any attempt to wake up the Selector will fail, too, causing
                            // the following 'selector.select(...)' call to block
                            // unnecessarily.
                            //
                            // To fix this problem, we wake up the selector again if wakenUp
                            // is true immediately after selector.select(...).
                            // It is inefficient in that it wakes up the selector for both
                            // the first case (BAD - wake-up required) and the second case
                            // (OK - no wake-up required).
                            // 唤醒。原因，见上面中文注释
                            /**
                             *若唤醒标识 wakeup 为 true 时，调用 Selector#wakeup() 方法，唤醒 Selector 。可能看到此处，
                             * 很多胖友会和我一样，一脸懵逼。实际上，耐下性子，答案在上面的英文注释中。笔者来简单解析下：
                             * 1）在 wakenUp.getAndSet(false) 和 #select(boolean oldWakeUp) 之间，
                             * 在标识 wakeUp 设置为 false 时，在 #select(boolean oldWakeUp) 方法中，正在调用 Selector#select(...) 方法，处于阻塞中。
                             * 2）此时，有另外的线程调用了 #wakeup() 方法，会将标记 wakeUp 设置为 true ，并唤醒 Selector#select(...) 方法的阻塞等待。
                             * 3）标识 wakeUp 为 true ，所以再有另外的线程调用 #wakeup() 方法，都无法唤醒 Selector#select(...) 。
                             * 为什么呢？因为 NioEventLoop#wakeup() 的 CAS 修改 false => true 会失败，导致无法调用 Selector#wakeup() 方法。
                             * 解决方式：所以在 #select(boolean oldWakeUp) 执行完后，增加了下面三行来解决。
                             */
                            if (wakenUp.get()) {
                                selector.wakeup();
                            }
                            // fall through
                        default:
                    }
                } catch (IOException e) {
                    // If we receive an IOException here its because the Selector is messed up. Let's rebuild
                    // the selector and retry. https://github.com/netty/netty/issues/8566
                    rebuildSelector0();
                    handleLoopException(e);
                    continue;
                }
                //第三种，>= 0 ，已经有可以处理的任务，直接向下

                cancelledKeys = 0;
                needsToSelectAgain = false;
                final int ioRatio = this.ioRatio;

                //根据 ioRatio 的配置不同，分成略有差异的 2 种：

                if (ioRatio == 100) {
                    //第一种，ioRatio 为 100 ，则不考虑时间占比的分配
                    try {
                        //调用 #processSelectedKeys() 方法，处理 Channel 感兴趣的就绪 IO 事件
                        processSelectedKeys();
                    } finally {
                        // Ensure we always run tasks.
                        //调用 #runAllTasks() 方法，运行所有普通任务和定时任务，不限制时间
                        runAllTasks();
                    }
                } else {
                    //ioRatio 为 < 100 ，则考虑时间占比的分配。
                    final long ioStartTime = System.nanoTime();
                    try {
                        processSelectedKeys();
                    } finally {
                        // Ensure we always run tasks.
                        //比较巧妙的方式，是不是和胖友之前认为的不太一样。它是以 #processSelectedKeys() 方法的执行时间作为基准，
                        // 计算 #runAllTasks(long timeoutNanos) 方法可执行的时间。
                        final long ioTime = System.nanoTime() - ioStartTime;
                        //调用 #runAllTasks(long timeoutNanos)` 方法，运行所有普通任务和定时任务，限制时间。
                        runAllTasks(ioTime * (100 - ioRatio) / ioRatio);
                    }
                }
            } catch (Throwable t) {
                handleLoopException(t);
            }
            // Always handle shutdown even if the loop processing threw an exception.
            try {
                if (isShuttingDown()) {//如果检测到Executor关闭，就关闭注册的channel
                    closeAll();
                    if (confirmShutdown()) {
                        return;//退出
                    }
                }
            } catch (Throwable t) {
                handleLoopException(t);
            }
        }
    }

    private static void handleLoopException(Throwable t) {
        logger.warn("Unexpected exception in the selector loop.", t);

        // Prevent possible consecutive immediate failures that lead to
        // excessive CPU consumption.
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            // Ignore.
        }
    }

    /**
     * 处理 Channel 新增就绪的 IO 事件
     */
    private void processSelectedKeys() {
        //当 selectedKeys 非空，意味着使用优化的 SelectedSelectionKeySetSelector
        if (selectedKeys != null) {
            processSelectedKeysOptimized();
        } else {
            processSelectedKeysPlain(selector.selectedKeys());
        }
    }

    @Override
    protected void cleanup() {
        try {
            selector.close();
        } catch (IOException e) {
            logger.warn("Failed to close a selector.", e);
        }
    }

    void cancel(SelectionKey key) {
        key.cancel();
        cancelledKeys++;
        if (cancelledKeys >= CLEANUP_INTERVAL) {
            cancelledKeys = 0;
            needsToSelectAgain = true;
        }
    }

    @Override
    protected Runnable pollTask() {
        Runnable task = super.pollTask();
        if (needsToSelectAgain) {
            selectAgain();
        }
        return task;
    }

    /**
     * 基于 Java NIO 原生 Selecotr ，处理 Channel 新增就绪的 IO 事件
     *
     * @param selectedKeys
     */
    private void processSelectedKeysPlain(Set<SelectionKey> selectedKeys) {
        // check if the set is empty and if so just return to not create garbage by
        // creating a new Iterator every time even if there is nothing to process.
        // See https://github.com/netty/netty/issues/597
        if (selectedKeys.isEmpty()) {
            return;
        }
        //在这里才创建迭代器的原因：check if the set is empty and if so just return to not create garbage by
        // creating a new Iterator every time even if there is nothing to process.
        Iterator<SelectionKey> i = selectedKeys.iterator();
        for (; ; ) {
            //获得下一个 SelectionKey 对象，并从迭代器中移除
            final SelectionKey k = i.next();
            final Object a = k.attachment();
            i.remove();

            if (a instanceof AbstractNioChannel) {
                processSelectedKey(k, (AbstractNioChannel) a);
            } else {
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);
            }

            if (!i.hasNext()) {
                break;
            }

            if (needsToSelectAgain) {
                selectAgain();
                selectedKeys = selector.selectedKeys();

                // Create the iterator again to avoid ConcurrentModificationException
                if (selectedKeys.isEmpty()) {
                    break;
                } else {
                    i = selectedKeys.iterator();
                }
            }
        }
    }

    /**
     * 基于 Netty SelectedSelectionKeySetSelector ，处理 Channel 新增就绪的 IO 事件
     */
    private void processSelectedKeysOptimized() {
        // 遍历数组
        for (int i = 0; i < selectedKeys.size; ++i) {
            final SelectionKey k = selectedKeys.keys[i];
            // null out entry in the array to allow to have it GC'ed once the Channel close
            //数组中的空输出条目允许通道关闭后对其进行GC处理
            // See https://github.com/netty/netty/issues/2363
            selectedKeys.keys[i] = null;

            final Object a = k.attachment();

            if (a instanceof AbstractNioChannel) {
                //：当 attachment 是 Netty NIO Channel 时，调用 #processSelectedKey(SelectionKey k, AbstractNioChannel ch) 方法，
                // 处理一个 Channel 就绪的 IO 事件
                processSelectedKey(k, (AbstractNioChannel) a);
            } else {
                //当 attachment 是 Netty NioTask 时，调用 #processSelectedKey(SelectionKey k, NioTask<SelectableChannel> task) 方法，
                // 使用 NioTask 处理一个 Channel 的 IO 事件
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);
            }

            if (needsToSelectAgain) {
                // null out entries in the array to allow to have it GC'ed once the Channel close
                // See https://github.com/netty/netty/issues/2363
                selectedKeys.reset(i + 1);

                selectAgain();
                i = -1;
            }
        }
    }

    /**
     * 处理一个 Channel 就绪的 IO 事件
     *
     * @param k
     * @param ch
     */
    private void processSelectedKey(SelectionKey k, AbstractNioChannel ch) {
        // 如果 SelectionKey 是不合法的，则关闭 Channel
        final AbstractNioChannel.NioUnsafe unsafe = ch.unsafe();
        if (!k.isValid()) {
            final EventLoop eventLoop;
            try {
                eventLoop = ch.eventLoop();
            } catch (Throwable ignored) {
                // If the channel implementation throws an exception because there is no event loop, we ignore this
                // because we are only trying to determine if ch is registered to this event loop and thus has authority
                // to close ch.
                return;
            }
            // Only close ch if ch is still registered to this EventLoop. ch could have deregistered from the event loop
            // and thus the SelectionKey could be cancelled as part of the deregistration process, but the channel is
            // still healthy and should not be closed.
            // See https://github.com/netty/netty/issues/5125
            if (eventLoop != this || eventLoop == null) {
                return;
            }
            // close the channel if the key is not valid anymore
            unsafe.close(unsafe.voidPromise());
            return;
        }

        try {
            // 获得就绪的 IO 事件的 ops
            int readyOps = k.readyOps();
            // We first need to call finishConnect() before try to trigger a read(...) or write(...) as otherwise
            // the NIO JDK channel implementation may throw a NotYetConnectedException.
            // OP_CONNECT 事件就绪
            if ((readyOps & SelectionKey.OP_CONNECT) != 0) {
                // remove OP_CONNECT as otherwise Selector.select(..) will always return without blocking
                // See https://github.com/netty/netty/issues/924
                int ops = k.interestOps();
                // 移除对 OP_CONNECT 感兴趣
                ops &= ~SelectionKey.OP_CONNECT;
                k.interestOps(ops);
                // 完成连接
                unsafe.finishConnect();
            }

            // Process OP_WRITE first as we may be able to write some queued buffers and so free memory.
            // OP_WRITE 事件就绪
            if ((readyOps & SelectionKey.OP_WRITE) != 0) {
                // Call forceFlush which will also take care of clear the OP_WRITE once there is nothing left to write
                // 向 Channel 写入数据
                ch.unsafe().forceFlush();
            }

            // Also check for readOps of 0 to workaround possible JDK bug which may otherwise lead
            // to a spin loop
            // SelectionKey.OP_READ 或 SelectionKey.OP_ACCEPT 就绪,处理读或者者接受客户端连接的事件。
            // readyOps == 0 是对 JDK Bug 的处理，防止空的死循环
            //当 (readyOps & SelectionKey.OP_ACCEPT) != 0 时，这就是服务端 NioServerSocketChannel 的 boss EventLoop 线程轮
            // 询到有新的客户端连接接入。
            if ((readyOps & (SelectionKey.OP_READ | SelectionKey.OP_ACCEPT)) != 0 || readyOps == 0) {
                //如果是服务端，则调用 NioMessageUnsafe#read() 方法，“读取”( 😈 这个抽象很灵性 )新的客户端连接连入
                //如果是NioSocketchannel就会调用NioByteUnsafed#read()方法，读取客户端传来的数据
                unsafe.read();
            }
        } catch (CancelledKeyException ignored) {
            unsafe.close(unsafe.voidPromise());
        }
    }

    /**
     * 使用 NioTask ，自定义实现 Channel 处理 Channel IO 就绪的事件
     *
     * @param k
     * @param task
     */
    private static void processSelectedKey(SelectionKey k, NioTask<SelectableChannel> task) {
        int state = 0;// 未执行
        try {
            // 调用 NioTask 的 Channel 就绪事件
            task.channelReady(k.channel(), k);
            state = 1;// 执行成功
        } catch (Exception e) {
            // SelectionKey 取消
            k.cancel();
            // 执行 Channel 取消注册
            invokeChannelUnregistered(task, k, e);
            state = 2;
        } finally {
            switch (state) {
                case 0:
                    // SelectionKey 取消
                    k.cancel();
                    // 执行 Channel 取消注册
                    invokeChannelUnregistered(task, k, null);
                    break;
                case 1:
                    // SelectionKey 不合法，则执行 Channel 取消注册
                    if (!k.isValid()) { // Cancelled by channelReady()
                        invokeChannelUnregistered(task, k, null);
                    }
                    break;
            }
        }
    }

    private void closeAll() {
        selectAgain();
        Set<SelectionKey> keys = selector.keys();
        Collection<AbstractNioChannel> channels = new ArrayList<AbstractNioChannel>(keys.size());
        for (SelectionKey k : keys) {
            Object a = k.attachment();
            if (a instanceof AbstractNioChannel) {
                channels.add((AbstractNioChannel) a);
            } else {
                k.cancel();
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                invokeChannelUnregistered(task, k, null);
            }
        }

        for (AbstractNioChannel ch : channels) {
            ch.unsafe().close(ch.unsafe().voidPromise());
        }
    }

    /**
     * 执行 Channel 取消注册
     *
     * @param task
     * @param k
     * @param cause
     */
    private static void invokeChannelUnregistered(NioTask<SelectableChannel> task, SelectionKey k, Throwable cause) {
        try {
            //在方法内部，调用 NioTask#channelUnregistered() 方法，执行 Channel 取消注册。
            task.channelUnregistered(k.channel(), cause);
        } catch (Exception e) {
            logger.warn("Unexpected exception while running NioTask.channelUnregistered()", e);
        }
    }

    @Override
    protected void wakeup(boolean inEventLoop) {
        //因为 Selector#wakeup() 方法的唤醒操作是开销比较大的操作，并且每次重复调用相当于重复唤醒。
        // 所以，通过 wakenUp 属性，通过 CAS 修改 false => true ，保证有且仅有进行一次唤醒
        if (!inEventLoop && wakenUp.compareAndSet(false, true)) {
            //因为 NioEventLoop 的线程阻塞，主要是调用 Selector#select(long timeout) 方法，
            // 阻塞等待有 Channel 感兴趣的 IO 事件，或者超时。所以需要调用 Selector#wakeup() 方法，进行唤醒 Selector
            selector.wakeup();
        }
    }

    Selector unwrappedSelector() {
        return unwrappedSelector;
    }

    int selectNow() throws IOException {
        try {
            //调用 Selector#selectorNow() 方法，立即( 无阻塞 )返回 Channel 新增的感兴趣的就绪 IO 事件数量。
            /**
             * 选择一组键，其相应的通道已为 I/O 操作准备就绪。
             * 此方法执行非阻塞的选择操作。如果自从前一次选择操作后，没有通道变成可选择的，则此方法直接返回零。
             *
             * 调用此方法会清除所有以前调用 wakeup 方法所得的结果
             */
            return selector.selectNow();
        } finally {
            // restore wakeup state if needed
            //若唤醒标识 wakeup 为 true 时，调用 Selector#wakeup() 方法，唤醒 Selector 。
            // 因为Selector#selectorNow() 会使用我们对 Selector 的唤醒，所以需要进行复原
            if (wakenUp.get()) {
                //注意，如果有其它线程调用了 #wakeup() 方法，但当前没有线程阻塞在 #select() 方法上，
                // 下个调用 #select() 方法的线程会立即被唤醒
                selector.wakeup();
            }
        }
    }

    private void select(boolean oldWakenUp) throws IOException {
        //获得使用的 Selector 对象，不需要每次访问使用 volatile 修饰的 selector 属性
        Selector selector = this.selector;
        try {
            //获得 select 操作的计数器。主要用于记录 Selector 空轮询次数，
            // 所以每次在正在轮询完成( 例如：轮询超时 )，则重置 selectCnt 为 1
            int selectCnt = 0;
            //记录当前时间，单位：纳秒
            long currentTimeNanos = System.nanoTime();
            //计算 select 操作的截止时间，单位：纳秒。
            //#delayNanos(currentTimeNanos) 方法返回的为下一个定时任务距离现在的时间，如果不存在定时任务，则默认返回 1000 ms
            //即本次select需要在下次定时任务开始前完成
            long selectDeadLineNanos = currentTimeNanos + delayNanos(currentTimeNanos);

            //“死”循环，直到符合如下任一一种情况后结束
            for (; ; ) {
                //计算本次 select 的超时时长
                //因为 Selector#select(timeoutMillis) 方法，可能因为各种情况结束，所以需要循环，
                // 并且每次重新计算超时时间。至于 + 500000L 和 / 1000000L 的用途，看下代码注释
                long timeoutMillis = (selectDeadLineNanos - currentTimeNanos + 500000L) / 1000000L;
                //如果超过 select 超时时长，则结束 select
                if (timeoutMillis <= 0) {
                    //select 操作超时
                    if (selectCnt == 0) {
                        //如果是首次 select ，则调用 Selector#selectNow() 方法，
                        // 获得非阻塞的 Channel 感兴趣的就绪的 IO 事件，并重置 selectCnt 为 1 。
                        selector.selectNow();
                        selectCnt = 1;
                    }
                    break;
                }

                // If a task was submitted when wakenUp value was true, the task didn't get a chance to call
                // Selector#wakeup. So we need to check task queue again before executing select operation.
                // If we don't, the task might be pended until select operation was timed out.
                // It might be pended until idle timeout if IdleStateHandler existed in pipeline.
                //若有新的任务加入。这里实际要分成两种情况
                //第一种，提交的任务的类型是 NonWakeupRunnable ，那么它并不会调用 #wakeup() 方法，
                // 原因胖友自己看 #execute(Runnable task) 思考下。Netty 在 #select() 方法的设计上，能尽快执行任务。
                // 此时如果标记 wakeup 为 false ，说明符合这种情况，直接结束 select 。
                //第二种，提交的任务的类型不是 NonWakeupRunnable ，那么在 #run() 方法的【第 8 至 11 行】的
                // wakenUp.getAndSet(false) 之前，发起了一次 #wakeup() 方法，那么因为 wakenUp.getAndSet(false) 会将
                // 标记 wakeUp 设置为 false ，所以就能满足 hasTasks() && wakenUp.compareAndSet(false, true) 的条件。
                //这就和上面的英文注释有出入了？这是为什么呢？因为 Selector 被提前 wakeup 了，所以下一次 Selector 的 select 是被直接唤醒结束的。
                //这里需要将唤醒标志设为true的原因：If a task was submitted when wakenUp value was true, the task didn't get a chance to call
                if (hasTasks() && wakenUp.compareAndSet(false, true)) {
                    //虽然已经发现任务，但是还是调用 Selector#selectNow() 方法，非阻塞的获取一次 Channel 新增的就绪的 IO 事件
                    selector.selectNow();
                    selectCnt = 1;
                    break;
                }
                //调用 Selector#select(timeoutMillis) 方法，阻塞 select ，获得 Channel 新增的就绪的 IO 事件的数量
                int selectedKeys = selector.select(timeoutMillis);
                //select 计数器加 1
                selectCnt++;

                //查询到任务或者唤醒
                //如果满足下面任一一个条件，结束 select ：
                //selectedKeys != 0 时，表示有 Channel 新增的就绪的 IO 事件，所以结束 select ，很好理解。
                //oldWakenUp || wakenUp.get() 时，表示 Selector 被唤醒，所以结束 select 。
                //hasTasks() || hasScheduledTasks() ，表示有普通任务或定时任务，所以结束 select 。
                //那么剩余的情况，主要是 select 超时或者发生空轮询，即后面的代码。
                if (selectedKeys != 0 || oldWakenUp || wakenUp.get() || hasTasks() || hasScheduledTasks()) {
                    // - Selected something,
                    // - waken up by user, or
                    // - the task queue has a pending task.
                    // - a scheduled task is ready for processing
                    break;
                }
                //线程被异常打断
                if (Thread.interrupted()) {
                    //线程被打断。一般情况下不会出现，出现基本是 bug ，或者错误使用
                    // Thread was interrupted so reset selected keys and break so we not run into a busy loop.
                    // As this is most likely a bug in the handler of the user or it's client library we will
                    // also log it.
                    //
                    // See https://github.com/netty/netty/issues/2426
                    if (logger.isDebugEnabled()) {
                        logger.debug("Selector.select() returned prematurely because " +
                                "Thread.currentThread().interrupt() was called. Use " +
                                "NioEventLoop.shutdownGracefully() to shutdown the NioEventLoop.");
                    }
                    selectCnt = 1;
                    break;
                }
                //记录当前时间
                long time = System.nanoTime();
                if (time - TimeUnit.MILLISECONDS.toNanos(timeoutMillis) >= currentTimeNanos) {
                    // timeoutMillis elapsed without anything selected.
                    //若满足 time - TimeUnit.MILLISECONDS.toNanos(timeoutMillis) >= currentTimeNanos ，
                    // 说明到达此处时，Selector 是超时 select ，那么是正常的，所以重置 selectCnt 为 1 。
                    selectCnt = 1;
                } else if (SELECTOR_AUTO_REBUILD_THRESHOLD > 0 &&
                        selectCnt >= SELECTOR_AUTO_REBUILD_THRESHOLD) {
                    //发生 NIO 空轮询的 Bug 后重建 Selector 对象后，
                    // The code exists in an extra method to ensure the method is not too big to inline as this
                    // branch is not very likely to get hit very frequently.
                    //不符合 select 超时的提交，若 select 次数到达重建 Selector 对象的上限，进行重建。
                    // 这就是 Netty 判断发生 NIO Selector 空轮询的方式，N ( 默认 512 )次 select 并未阻塞超时这么长，
                    // 那么就认为发生 NIO Selector 空轮询。过多的 NIO Selector 将会导致 CPU 100%
                    selector = selectRebuildSelector(selectCnt);
                    selectCnt = 1;
                    break;
                }
                //记录新的当前时间，用于【第 16 行】，重新计算本次 select 的超时时长。
                currentTimeNanos = time;
            }

            if (selectCnt > MIN_PREMATURE_SELECTOR_RETURNS) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Selector.select() returned prematurely {} times in a row for Selector {}.",
                            selectCnt - 1, selector);
                }
            }
        } catch (CancelledKeyException e) {
            if (logger.isDebugEnabled()) {
                logger.debug(CancelledKeyException.class.getSimpleName() + " raised by a Selector {} - JDK bug?",
                        selector, e);
            }
            // Harmless exception - log anyway
        }
    }

    private Selector selectRebuildSelector(int selectCnt) throws IOException {
        // The selector returned prematurely many times in a row.
        // Rebuild the selector to work around the problem.
        logger.warn(
                "Selector.select() returned prematurely {} times in a row; rebuilding Selector {}.",
                selectCnt, selector);

        rebuildSelector();
        Selector selector = this.selector;

        // Select again to populate selectedKeys.
        selector.selectNow();
        return selector;
    }

    private void selectAgain() {
        needsToSelectAgain = false;
        try {
            selector.selectNow();
        } catch (Throwable t) {
            logger.warn("Failed to update SelectionKeys.", t);
        }
    }
}
