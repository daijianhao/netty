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
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.ServerChannel;

import java.io.IOException;
import java.net.PortUnreachableException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link AbstractNioChannel} base class for {@link Channel}s that operate on messages.
 * <p>
 * 有Message为服务端使用
 */
public abstract class AbstractNioMessageChannel extends AbstractNioChannel {
    boolean inputShutdown;

    /**
     * @see AbstractNioChannel#AbstractNioChannel(Channel, SelectableChannel, int)
     */
    protected AbstractNioMessageChannel(Channel parent, SelectableChannel ch, int readInterestOp) {
        super(parent, ch, readInterestOp);
    }

    @Override
    protected AbstractNioUnsafe newUnsafe() {
        return new NioMessageUnsafe();
    }

    @Override
    protected void doBeginRead() throws Exception {
        if (inputShutdown) {
            return;
        }
        super.doBeginRead();
    }

    /**
     * NioMessageUnsafe 只有一个 #read() 方法，而该方法，“读取”新的客户端连接连入
     */
    private final class NioMessageUnsafe extends AbstractNioUnsafe {

        /**
         * 新读取的客户端连接数组
         */
        private final List<Object> readBuf = new ArrayList<Object>();

        @Override
        public void read() {
            assert eventLoop().inEventLoop();
            final ChannelConfig config = config();
            final ChannelPipeline pipeline = pipeline();
            // 获得 RecvByteBufAllocator.Handle 对象
            final RecvByteBufAllocator.Handle allocHandle = unsafe().recvBufAllocHandle();
            // 重置 RecvByteBufAllocator.Handle 对象
            allocHandle.reset(config);

            boolean closed = false;
            Throwable exception = null;
            try {
                try {
                    do {
                        // 读取客户端的连接到 readBuf 中
                        int localRead = doReadMessages(readBuf);//当OP是Accept时，这里最多接受一个客户端的连接
                        // 无可读取的客户端的连接，结束
                        if (localRead == 0) {
                            break;
                        }
                        // 读取出错
                        if (localRead < 0) {
                            closed = true;
                            break;
                        }
                        // 读取消息数量 + localRead
                        allocHandle.incMessagesRead(localRead);
                        //判断是否循环是否继续，读取( 接受 )新的客户端连接
                    } while (allocHandle.continueReading());// 循环判断是否继续读取
                } catch (Throwable t) {
                    // 记录异常
                    exception = t;
                }
                // 循环 readBuf 数组，触发 Channel read 事件到 pipeline 中。
                int size = readBuf.size();
                for (int i = 0; i < size; i++) {
                    readPending = false;
                    // 在内部，会通过 ServerBootstrapAcceptor ，将客户端的 Netty NioSocketChannel 注册到 EventLoop 上
                    // 注意，传入的方法参数是新接受的客户端 NioSocketChannel 连接
                    pipeline.fireChannelRead(readBuf.get(i));
                }
                // 清空 readBuf 数组
                readBuf.clear();
                // 读取完成
                allocHandle.readComplete();
                // 触发 Channel readComplete 事件到 pipeline 中。
                // 如果有需要，胖友可以自定义处理器，处理该事件。一般情况下，不需要。
                //如果没有自定义 ChannelHandler 进行处理，最终会被 pipeline 中的尾节点 TailContext 所处理
                pipeline.fireChannelReadComplete();

                // 发生异常
                if (exception != null) {
                    // 判断是否要关闭 TODO 芋艿
                    closed = closeOnReadError(exception);

                    // 触发 exceptionCaught 事件到 pipeline 中。
                    // 默认情况下，会使用 ServerBootstrapAcceptor 处理该事件
                    // 如果有需要，胖友可以自定义处理器，处理该事件。一般情况下，不需要
                    pipeline.fireExceptionCaught(exception);
                }

                if (closed) {
                    inputShutdown = true;
                    if (isOpen()) {
                        close(voidPromise());
                    }
                }
            } finally {
                // Check if there is a readPending which was not processed yet.
                // This could be for two reasons:
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelRead(...) method
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelReadComplete(...) method
                //
                // See https://github.com/netty/netty/issues/2254
                if (!readPending && !config.isAutoRead()) {
                    removeReadOp();
                }
            }
        }
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        final SelectionKey key = selectionKey();
        final int interestOps = key.interestOps();

        for (; ; ) {
            Object msg = in.current();
            if (msg == null) {
                // Wrote all messages.
                if ((interestOps & SelectionKey.OP_WRITE) != 0) {
                    key.interestOps(interestOps & ~SelectionKey.OP_WRITE);
                }
                break;
            }
            try {
                boolean done = false;
                for (int i = config().getWriteSpinCount() - 1; i >= 0; i--) {
                    if (doWriteMessage(msg, in)) {
                        done = true;
                        break;
                    }
                }

                if (done) {
                    in.remove();
                } else {
                    // Did not write all messages.
                    if ((interestOps & SelectionKey.OP_WRITE) == 0) {
                        key.interestOps(interestOps | SelectionKey.OP_WRITE);
                    }
                    break;
                }
            } catch (Exception e) {
                if (continueOnWriteError()) {
                    in.remove(e);
                } else {
                    throw e;
                }
            }
        }
    }

    /**
     * Returns {@code true} if we should continue the write loop on a write error.
     */
    protected boolean continueOnWriteError() {
        return false;
    }

    protected boolean closeOnReadError(Throwable cause) {
        if (!isActive()) {
            // If the channel is not active anymore for whatever reason we should not try to continue reading.
            return true;
        }
        if (cause instanceof PortUnreachableException) {
            return false;
        }
        if (cause instanceof IOException) {
            // ServerChannel should not be closed even on IOException because it can often continue
            // accepting incoming connections. (e.g. too many open files)
            return !(this instanceof ServerChannel);
        }
        return true;
    }

    /**
     * Read messages into the given array and return the amount which was read.
     * <p>
     * 读取客户端的连接到方法参数 buf 中。它是一个抽象方法，定义在 AbstractNioMessageChannel 抽象类中
     */
    protected abstract int doReadMessages(List<Object> buf) throws Exception;

    /**
     * Write a message to the underlying {@link java.nio.channels.Channel}.
     *
     * @return {@code true} if and only if the message has been written
     */
    protected abstract boolean doWriteMessage(Object msg, ChannelOutboundBuffer in) throws Exception;
}
