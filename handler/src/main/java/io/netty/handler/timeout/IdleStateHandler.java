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
package io.netty.handler.timeout;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.Channel.Unsafe;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPromise;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Triggers an {@link IdleStateEvent} when a {@link Channel} has not performed
 * read, write, or both operation for a while.
 *
 * <h3>Supported idle states</h3>
 * <table border="1">
 * <tr>
 * <th>Property</th><th>Meaning</th>
 * </tr>
 * <tr>
 * <td>{@code readerIdleTime}</td>
 * <td>an {@link IdleStateEvent} whose state is {@link IdleState#READER_IDLE}
 *     will be triggered when no read was performed for the specified period of
 *     time.  Specify {@code 0} to disable.</td>
 * </tr>
 * <tr>
 * <td>{@code writerIdleTime}</td>
 * <td>an {@link IdleStateEvent} whose state is {@link IdleState#WRITER_IDLE}
 *     will be triggered when no write was performed for the specified period of
 *     time.  Specify {@code 0} to disable.</td>
 * </tr>
 * <tr>
 * <td>{@code allIdleTime}</td>
 * <td>an {@link IdleStateEvent} whose state is {@link IdleState#ALL_IDLE}
 *     will be triggered when neither read nor write was performed for the
 *     specified period of time.  Specify {@code 0} to disable.</td>
 * </tr>
 * </table>
 *
 * <pre>
 * // An example that sends a ping message when there is no outbound traffic
 * // for 30 seconds.  The connection is closed when there is no inbound traffic
 * // for 60 seconds.
 *
 * public class MyChannelInitializer extends {@link ChannelInitializer}&lt;{@link Channel}&gt; {
 *     {@code @Override}
 *     public void initChannel({@link Channel} channel) {
 *         channel.pipeline().addLast("idleStateHandler", new {@link IdleStateHandler}(60, 30, 0));
 *         channel.pipeline().addLast("myHandler", new MyHandler());
 *     }
 * }
 *
 * // Handler should handle the {@link IdleStateEvent} triggered by {@link IdleStateHandler}.
 * public class MyHandler extends {@link ChannelDuplexHandler} {
 *     {@code @Override}
 *     public void userEventTriggered({@link ChannelHandlerContext} ctx, {@link Object} evt) throws {@link Exception} {
 *         if (evt instanceof {@link IdleStateEvent}) {
 *             {@link IdleStateEvent} e = ({@link IdleStateEvent}) evt;
 *             if (e.state() == {@link IdleState}.READER_IDLE) {
 *                 ctx.close();
 *             } else if (e.state() == {@link IdleState}.WRITER_IDLE) {
 *                 ctx.writeAndFlush(new PingMessage());
 *             }
 *         }
 *     }
 * }
 *
 * {@link ServerBootstrap} bootstrap = ...;
 * ...
 * bootstrap.childHandler(new MyChannelInitializer());
 * ...
 * </pre>
 * <p>
 * 在 netty-handler 模块的 timeout 包，实现 Channel 的读写操作的空闲检测。可能有胖友不太了解空闲检测的具体用途。
 * 请先研读理解下 https://www.cnblogs.com/ASPNET2008/p/7615973.html
 * <p>
 * <p>
 * 当 Channel 的读或者写空闲时间太长时，将会触发一个 IdleStateEvent 事件。然后，你可以自定义一个 ChannelInboundHandler ，
 * 重写 #userEventTriggered(ChannelHandlerContext ctx, Object evt) 方法，处理该事件。
 *
 * @see ReadTimeoutHandler
 * @see WriteTimeoutHandler
 */
public class IdleStateHandler extends ChannelDuplexHandler {

    /**
     * 最小的超时时间，单位：纳秒
     * <p>
     * 最小的超时时间为 1 ，单位：纳秒。因为 IdleStateHandler 创建的，检测定时任务的时间，以纳秒为单位。
     */
    private static final long MIN_TIMEOUT_NANOS = TimeUnit.MILLISECONDS.toNanos(1);


    // Not create a new ChannelFutureListener per write operation to reduce GC pressure.
    /**
     * 写入任务监听器
     * <p>
     * 写入操作，完成 flush 到对端的回调监听器。初始时，创建好，避免重复创建，从而减轻 GC 压力。
     */
    private final ChannelFutureListener writeListener = new ChannelFutureListener() {
        @Override
        public void operationComplete(ChannelFuture future) throws Exception {
            // 记录最后写时间
            lastWriteTime = ticksInNanos();
            // 重置 firstWriterIdleEvent 和 firstAllIdleEvent 为 true
            firstWriterIdleEvent = firstAllIdleEvent = true;
        }
    };


    /**
     * 是否观察 {@link ChannelOutboundBuffer} 写入队列
     */
    private final boolean observeOutput;

    /**
     * 配置的读空闲时间，单位：纳秒
     */
    private final long readerIdleTimeNanos;
    /**
     * 配置的写空闲时间，单位：纳秒
     */
    private final long writerIdleTimeNanos;

    /**
     * 配置的All( 读或写任一 )，单位：纳秒
     */
    private final long allIdleTimeNanos;

    /**
     * 读空闲的定时检测任务
     */
    private ScheduledFuture<?> readerIdleTimeout;

    /**
     * 最后读时间
     */
    private long lastReadTime;

    /**
     * 是否首次读空闲
     */
    private boolean firstReaderIdleEvent = true;

    /**
     * 写空闲的定时检测任务
     */
    private ScheduledFuture<?> writerIdleTimeout;

    /**
     * 最后写时间
     */
    private long lastWriteTime;

    /**
     * 是否首次写空闲
     */
    private boolean firstWriterIdleEvent = true;

    /**
     * All 空闲时间，单位：纳秒
     */
    private ScheduledFuture<?> allIdleTimeout;

    /**
     * 是否首次 All 空闲
     */
    private boolean firstAllIdleEvent = true;

    /**
     * 状态
     * <p>
     * 0 - none ，未初始化
     * 1 - initialized ，已经初始化
     * 2 - destroyed ，已经销毁
     */
    private byte state; // 0 - none, 1 - initialized, 2 - destroyed

    /**
     * 是否正在读取
     */
    private boolean reading;

    /**
     * 最后检测到 {@link ChannelOutboundBuffer} 发生变化的时间
     */
    private long lastChangeCheckTimeStamp;

    /**
     * 第一条准备 flash 到对端的消息( {@link ChannelOutboundBuffer#current()} )的 HashCode
     */
    private int lastMessageHashCode;

    /**
     * 总共等待 flush 到对端的内存大小( {@link ChannelOutboundBuffer#totalPendingWriteBytes()} )
     */
    private long lastPendingWriteBytes;

    /**
     * Creates a new instance firing {@link IdleStateEvent}s.
     *
     * @param readerIdleTimeSeconds an {@link IdleStateEvent} whose state is {@link IdleState#READER_IDLE}
     *                              will be triggered when no read was performed for the specified
     *                              period of time.  Specify {@code 0} to disable.
     * @param writerIdleTimeSeconds an {@link IdleStateEvent} whose state is {@link IdleState#WRITER_IDLE}
     *                              will be triggered when no write was performed for the specified
     *                              period of time.  Specify {@code 0} to disable.
     * @param allIdleTimeSeconds    an {@link IdleStateEvent} whose state is {@link IdleState#ALL_IDLE}
     *                              will be triggered when neither read nor write was performed for
     *                              the specified period of time.  Specify {@code 0} to disable.
     */
    public IdleStateHandler(
            int readerIdleTimeSeconds,
            int writerIdleTimeSeconds,
            int allIdleTimeSeconds) {

        this(readerIdleTimeSeconds, writerIdleTimeSeconds, allIdleTimeSeconds,
                TimeUnit.SECONDS);
    }

    /**
     * @see #IdleStateHandler(boolean, long, long, long, TimeUnit)
     */
    public IdleStateHandler(
            long readerIdleTime, long writerIdleTime, long allIdleTime,
            TimeUnit unit) {
        this(false, readerIdleTime, writerIdleTime, allIdleTime, unit);
    }

    /**
     * Creates a new instance firing {@link IdleStateEvent}s.
     *
     * @param observeOutput  whether or not the consumption of {@code bytes} should be taken into
     *                       consideration when assessing write idleness. The default is {@code false}.
     * @param readerIdleTime an {@link IdleStateEvent} whose state is {@link IdleState#READER_IDLE}
     *                       will be triggered when no read was performed for the specified
     *                       period of time.  Specify {@code 0} to disable.
     * @param writerIdleTime an {@link IdleStateEvent} whose state is {@link IdleState#WRITER_IDLE}
     *                       will be triggered when no write was performed for the specified
     *                       period of time.  Specify {@code 0} to disable.
     * @param allIdleTime    an {@link IdleStateEvent} whose state is {@link IdleState#ALL_IDLE}
     *                       will be triggered when neither read nor write was performed for
     *                       the specified period of time.  Specify {@code 0} to disable.
     * @param unit           the {@link TimeUnit} of {@code readerIdleTime},
     *                       {@code writeIdleTime}, and {@code allIdleTime}
     */
    public IdleStateHandler(boolean observeOutput,
                            long readerIdleTime, long writerIdleTime, long allIdleTime,
                            TimeUnit unit) {
        if (unit == null) {
            throw new NullPointerException("unit");
        }

        this.observeOutput = observeOutput;

        if (readerIdleTime <= 0) {
            readerIdleTimeNanos = 0;
        } else {
            readerIdleTimeNanos = Math.max(unit.toNanos(readerIdleTime), MIN_TIMEOUT_NANOS);
        }
        if (writerIdleTime <= 0) {
            writerIdleTimeNanos = 0;
        } else {
            writerIdleTimeNanos = Math.max(unit.toNanos(writerIdleTime), MIN_TIMEOUT_NANOS);
        }
        if (allIdleTime <= 0) {
            allIdleTimeNanos = 0;
        } else {
            allIdleTimeNanos = Math.max(unit.toNanos(allIdleTime), MIN_TIMEOUT_NANOS);
        }
    }

    /**
     * Return the readerIdleTime that was given when instance this class in milliseconds.
     */
    public long getReaderIdleTimeInMillis() {
        return TimeUnit.NANOSECONDS.toMillis(readerIdleTimeNanos);
    }

    /**
     * Return the writerIdleTime that was given when instance this class in milliseconds.
     */
    public long getWriterIdleTimeInMillis() {
        return TimeUnit.NANOSECONDS.toMillis(writerIdleTimeNanos);
    }

    /**
     * Return the allIdleTime that was given when instance this class in milliseconds.
     */
    public long getAllIdleTimeInMillis() {
        return TimeUnit.NANOSECONDS.toMillis(allIdleTimeNanos);
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        if (ctx.channel().isActive() && ctx.channel().isRegistered()) {
            // channelActive() event has been fired already, which means this.channelActive() will
            // not be invoked. We have to initialize here instead.
            initialize(ctx);
        } else {
            // channelActive() event has not been fired yet.  this.channelActive() will be invoked
            // and initialization will occur there.
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        destroy();
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        // Initialize early if channel is active already.
        if (ctx.channel().isActive()) {
            initialize(ctx);
        }
        super.channelRegistered(ctx);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // This method will be invoked only if this handler was added
        // before channelActive() event is fired.  If a user adds this handler
        // after the channelActive() event, initialize() will be called by beforeAdd().
        initialize(ctx);
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        destroy();
        super.channelInactive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        // 开启了 read 或 all 的空闲检测
        if (readerIdleTimeNanos > 0 || allIdleTimeNanos > 0) {
            // 标记正在读取
            reading = true;
            // 重置 firstWriterIdleEvent 和 firstAllIdleEvent 为 true
            firstReaderIdleEvent = firstAllIdleEvent = true;
        }
        // 继续传播 Channel Read 事件到下一个节点
        ctx.fireChannelRead(msg);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        // 开启了 read 或 all 的空闲检测
        if ((readerIdleTimeNanos > 0 || allIdleTimeNanos > 0) && reading) {
            // 记录最后读时间
            lastReadTime = ticksInNanos();
            // 标记不在读取
            reading = false;
        }
        // 继续传播 Channel ReadComplete 事件到下一个节点
        ctx.fireChannelReadComplete();
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        // Allow writing with void promise if handler is only configured for read timeout events.
        // 开启了 write 或 all 的空闲检测
        if (writerIdleTimeNanos > 0 || allIdleTimeNanos > 0) {
            // 写入，并添加写入监听器
            //该监听器会在消息( 数据 ) flush 到对端后，回调，修改最后写入时间 lastWriteTime 为 #ticksInNanos()
            ctx.write(msg, promise.unvoid()).addListener(writeListener);
        } else {
            // 写入，不添加监听器
            ctx.write(msg, promise);
        }
    }

    /**
     * <1> : 当客户端与服务端成功建立连接后，Channel 被激活，此时 channelActive 方法，的初始化被调用。
     * <2> ：当 Channel 被激活后，动态添加此 Handler ，则 handlerAdded 方法的初始化被调用。
     * <3> ：当 Channel 被激活后，用户主动切换 Channel 的所在的 EventLoop ，则 channelRegistered 方法的初始化被调用。
     *
     * @param ctx
     */
    private void initialize(ChannelHandlerContext ctx) {
        // 校验状态，避免因为 `#destroy()` 方法在 `#initialize(ChannelHandlerContext ctx)` 方法，执行之前
        // Avoid the case where destroy() is called before scheduling timeouts.
        // See: https://github.com/netty/netty/issues/143
        switch (state) {
            case 1:
            case 2:
                return;
        }
        // 标记为已初始化
        state = 1;
        // 初始化 ChannelOutboundBuffer 相关属性
        initOutputChanged(ctx);

        // 初始相应的定时任务
        lastReadTime = lastWriteTime = ticksInNanos();
        if (readerIdleTimeNanos > 0) {
            readerIdleTimeout = schedule(ctx, new ReaderIdleTimeoutTask(ctx),
                    readerIdleTimeNanos, TimeUnit.NANOSECONDS);
        }
        if (writerIdleTimeNanos > 0) {
            writerIdleTimeout = schedule(ctx, new WriterIdleTimeoutTask(ctx),
                    writerIdleTimeNanos, TimeUnit.NANOSECONDS);
        }
        if (allIdleTimeNanos > 0) {
            allIdleTimeout = schedule(ctx, new AllIdleTimeoutTask(ctx),
                    allIdleTimeNanos, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * This method is visible for testing!
     */
    long ticksInNanos() {
        return System.nanoTime();
    }

    /**
     * This method is visible for testing!
     * <p>
     * 初始相应的定时任务
     */
    ScheduledFuture<?> schedule(ChannelHandlerContext ctx, Runnable task, long delay, TimeUnit unit) {
        return ctx.executor().schedule(task, delay, unit);
    }


    /**
     * 该方法，会在多个 Channel 事件中被调用
     */
    private void destroy() {
        // 标记为销毁
        state = 2;

        // 销毁相应的定时任务
        if (readerIdleTimeout != null) {
            readerIdleTimeout.cancel(false);
            readerIdleTimeout = null;
        }
        if (writerIdleTimeout != null) {
            writerIdleTimeout.cancel(false);
            writerIdleTimeout = null;
        }
        if (allIdleTimeout != null) {
            allIdleTimeout.cancel(false);
            allIdleTimeout = null;
        }
    }

    /**
     * Is called when an {@link IdleStateEvent} should be fired. This implementation calls
     * {@link ChannelHandlerContext#fireUserEventTriggered(Object)}.
     *
     * ② 然后，调用 #channelIdle(ChannelHandlerContext ctx, IdleStateEvent evt) 方法，在 pipeline 中，触发 UserEvent 事件
     */
    protected void channelIdle(ChannelHandlerContext ctx, IdleStateEvent evt) throws Exception {
        ctx.fireUserEventTriggered(evt);
    }

    /**
     * Returns a {@link IdleStateEvent}.
     *
     * 在定时任务中，如果检测到空闲：
     *
     * ① 首先，调用 #newIdleStateEvent(IdleState state, boolean first) 方法，创建对应的空闲事件
     */
    protected IdleStateEvent newIdleStateEvent(IdleState state, boolean first) {
        switch (state) {
            case ALL_IDLE:
                return first ? IdleStateEvent.FIRST_ALL_IDLE_STATE_EVENT : IdleStateEvent.ALL_IDLE_STATE_EVENT;
            case READER_IDLE:
                return first ? IdleStateEvent.FIRST_READER_IDLE_STATE_EVENT : IdleStateEvent.READER_IDLE_STATE_EVENT;
            case WRITER_IDLE:
                return first ? IdleStateEvent.FIRST_WRITER_IDLE_STATE_EVENT : IdleStateEvent.WRITER_IDLE_STATE_EVENT;
            default:
                throw new IllegalArgumentException("Unhandled: state=" + state + ", first=" + first);
        }
    }

    /**
     * @see #hasOutputChanged(ChannelHandlerContext, boolean)
     */
    private void initOutputChanged(ChannelHandlerContext ctx) {
        if (observeOutput) {
            Channel channel = ctx.channel();
            Unsafe unsafe = channel.unsafe();
            ChannelOutboundBuffer buf = unsafe.outboundBuffer();

            if (buf != null) {
                // 记录第一条准备 flash 到对端的消息的 HashCode
                lastMessageHashCode = System.identityHashCode(buf.current());
                // 记录总共等待 flush 到对端的内存大小
                lastPendingWriteBytes = buf.totalPendingWriteBytes();
            }
        }
    }

    /**
     * Returns {@code true} if and only if the {@link IdleStateHandler} was constructed
     * with {@link #observeOutput} enabled and there has been an observed change in the
     * {@link ChannelOutboundBuffer} between two consecutive calls of this method.
     * <p>
     * https://github.com/netty/netty/issues/6150
     *
     * 判断 ChannelOutboundBuffer 是否发生变化
     */
    private boolean hasOutputChanged(ChannelHandlerContext ctx, boolean first) {
        // 开启观察 ChannelOutboundBuffer 队列
        if (observeOutput) {

            // We can take this shortcut if the ChannelPromises that got passed into write()
            // appear to complete. It indicates "change" on message level and we simply assume
            // that there's change happening on byte level. If the user doesn't observe channel
            // writability events then they'll eventually OOME and there's clearly a different
            // problem and idleness is least of their concerns.
            // 如果 lastChangeCheckTimeStamp 和 lastWriteTime 不一样，说明写操作进行过了，则更新此值
            if (lastChangeCheckTimeStamp != lastWriteTime) {
                lastChangeCheckTimeStamp = lastWriteTime;

                // But this applies only if it's the non-first call.
                if (!first) {// 非首次
                    return true;
                }
            }

            Channel channel = ctx.channel();
            Unsafe unsafe = channel.unsafe();
            ChannelOutboundBuffer buf = unsafe.outboundBuffer();

            if (buf != null) {
                // 获得新的 messageHashCode 和 pendingWriteBytes
                int messageHashCode = System.identityHashCode(buf.current());
                long pendingWriteBytes = buf.totalPendingWriteBytes();

                // 发生了变化
                if (messageHashCode != lastMessageHashCode || pendingWriteBytes != lastPendingWriteBytes) {
                    // 修改最后一次的 lastMessageHashCode 和 lastPendingWriteBytes
                    lastMessageHashCode = messageHashCode;
                    lastPendingWriteBytes = pendingWriteBytes;

                    if (!first) { // 非首次
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * 实现 Runnable 接口，空闲任务抽象类
     */
    private abstract static class AbstractIdleTask implements Runnable {

        private final ChannelHandlerContext ctx;

        AbstractIdleTask(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void run() {
            // <1> 忽略未打开的 Channel
            if (!ctx.channel().isOpen()) {
                return;
            }
            // <2> 执行任务
            run(ctx);
        }

        protected abstract void run(ChannelHandlerContext ctx);
    }

    /**
     * 继承 AbstractIdleTask 抽象类，检测 Read 空闲超时定时任务
     */
    private final class ReaderIdleTimeoutTask extends AbstractIdleTask {

        ReaderIdleTimeoutTask(ChannelHandlerContext ctx) {
            super(ctx);
        }

        @Override
        protected void run(ChannelHandlerContext ctx) {
            // 计算下一次检测的定时任务的延迟
            long nextDelay = readerIdleTimeNanos;
            if (!reading) {
                nextDelay -= ticksInNanos() - lastReadTime;
            }
            // 如果小于等于 0 ，说明检测到读空闲
            if (nextDelay <= 0) {
                // Reader is idle - set a new timeout and notify the callback.
                // 延迟时间为 readerIdleTimeNanos ，即再次检测
                readerIdleTimeout = schedule(ctx, this, readerIdleTimeNanos, TimeUnit.NANOSECONDS);

                // 获得当前是否首次检测到读空闲
                boolean first = firstReaderIdleEvent;
                // 标记 firstReaderIdleEvent 为 false 。也就说，下次检测到空闲，就非首次了
                firstReaderIdleEvent = false;

                try {
                    // 创建读空闲事件
                    IdleStateEvent event = newIdleStateEvent(IdleState.READER_IDLE, first);
                    // 通知通道空闲事件
                    channelIdle(ctx, event);
                } catch (Throwable t) {
                    // 触发 Exception Caught 到下一个节点
                    ctx.fireExceptionCaught(t);
                }
            } else {
                // 如果大于 0 ，说明未检测到读空闲
                // Read occurred before the timeout - set a new timeout with shorter delay.
                readerIdleTimeout = schedule(ctx, this, nextDelay, TimeUnit.NANOSECONDS);
            }
        }
    }

    /**
     * 继承 AbstractIdleTask 抽象类，检测 Write 空闲超时定时任务
     */
    private final class WriterIdleTimeoutTask extends AbstractIdleTask {

        WriterIdleTimeoutTask(ChannelHandlerContext ctx) {
            super(ctx);
        }

        @Override
        protected void run(ChannelHandlerContext ctx) {
            // 计算下一次检测的定时任务的延迟
            long lastWriteTime = IdleStateHandler.this.lastWriteTime;
            long nextDelay = writerIdleTimeNanos - (ticksInNanos() - lastWriteTime);

            // 如果小于等于 0 ，说明检测到写空闲
            if (nextDelay <= 0) {
                // Writer is idle - set a new timeout and notify the callback.
                writerIdleTimeout = schedule(ctx, this, writerIdleTimeNanos, TimeUnit.NANOSECONDS);

                // 获得当前是否首次检测到写空闲
                boolean first = firstWriterIdleEvent;
                firstWriterIdleEvent = false;

                try {
                    // 判断 ChannelOutboundBuffer 是否发生变化
                    if (hasOutputChanged(ctx, first)) {
                        return;
                    }
                    // 创建写空闲事件
                    IdleStateEvent event = newIdleStateEvent(IdleState.WRITER_IDLE, first);
                    // 通知通道空闲事件
                    channelIdle(ctx, event);
                } catch (Throwable t) {
                    // 触发 Exception Caught 到下一个节点
                    ctx.fireExceptionCaught(t);
                }
            } else {
                // Write occurred before the timeout - set a new timeout with shorter delay.
                writerIdleTimeout = schedule(ctx, this, nextDelay, TimeUnit.NANOSECONDS);
            }
        }
    }

    private final class AllIdleTimeoutTask extends AbstractIdleTask {

        AllIdleTimeoutTask(ChannelHandlerContext ctx) {
            super(ctx);
        }

        @Override
        protected void run(ChannelHandlerContext ctx) {

            long nextDelay = allIdleTimeNanos;
            if (!reading) {
                nextDelay -= ticksInNanos() - Math.max(lastReadTime, lastWriteTime);
            }
            if (nextDelay <= 0) {
                // Both reader and writer are idle - set a new timeout and
                // notify the callback.
                allIdleTimeout = schedule(ctx, this, allIdleTimeNanos, TimeUnit.NANOSECONDS);

                boolean first = firstAllIdleEvent;
                firstAllIdleEvent = false;

                try {
                    if (hasOutputChanged(ctx, first)) {
                        return;
                    }

                    IdleStateEvent event = newIdleStateEvent(IdleState.ALL_IDLE, first);
                    channelIdle(ctx, event);
                } catch (Throwable t) {
                    ctx.fireExceptionCaught(t);
                }
            } else {
                // Either read or write occurred before the timeout - set a new
                // timeout with shorter delay.
                allIdleTimeout = schedule(ctx, this, nextDelay, TimeUnit.NANOSECONDS);
            }
        }
    }
}
