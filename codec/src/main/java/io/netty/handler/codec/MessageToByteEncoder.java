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
package io.netty.handler.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandler;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.TypeParameterMatcher;


/**
 * {@link ChannelOutboundHandlerAdapter} which encodes message in a stream-like fashion from one message to an
 * {@link ByteBuf}.
 * <p>
 * <p>
 * Example implementation which encodes {@link Integer}s to a {@link ByteBuf}.
 *
 * <pre>
 *     public class IntegerEncoder extends {@link MessageToByteEncoder}&lt;{@link Integer}&gt; {
 *         {@code @Override}
 *         public void encode({@link ChannelHandlerContext} ctx, {@link Integer} msg, {@link ByteBuf} out)
 *                 throws {@link Exception} {
 *             out.writeInt(msg);
 *         }
 *     }
 * </pre>
 * <p>
 * ByteToMessageDecoder 本身是个抽象类，其下有多个子类，笔者简单整理成两类，可能不全哈：
 * <p>
 * 蓝框部分，将消息压缩，主要涉及相关压缩算法，例如：GZip、BZip 等等。
 * 它要求消息类型是 ByteBuf ，将已经转化好的字节流，进一步压缩。
 * 黄框部分，将消息使用指定序列化方式序列化成字节。例如：JSON、XML 等等。
 * 因为 Netty 没有内置的 JSON、XML 等相关的类库，所以不好提供类似 JSONEncoder 或 XMLEncoder ，
 * 所以图中笔者就使用 netty-example 提供的 NumberEncoder
 * <p>
 * 在实际使用 Netty 编码消息时，还需要有为了解决粘包拆包的 Encoder 实现类，例如：换行、定长等等方式。关于这块内容，
 * 胖友可以看看 《netty使用MessageToByteEncoder 自定义协议》
 * <p>
 * 继承 ChannelOutboundHandlerAdapter 类，负责将消息编码成字节，支持匹配指定类型的消息
 */
public abstract class MessageToByteEncoder<I> extends ChannelOutboundHandlerAdapter {

    /**
     * 类型匹配器
     */
    private final TypeParameterMatcher matcher;

    /**
     * 是否偏向使用 Direct 内存
     */
    private final boolean preferDirect;

    /**
     * see {@link #MessageToByteEncoder(boolean)} with {@code true} as boolean parameter.
     */
    protected MessageToByteEncoder() {
        this(true);
    }

    /**
     * see {@link #MessageToByteEncoder(Class, boolean)} with {@code true} as boolean value.
     */
    protected MessageToByteEncoder(Class<? extends I> outboundMessageType) {
        this(outboundMessageType, true);
    }

    /**
     * Create a new instance which will try to detect the types to match out of the type parameter of the class.
     *
     * @param preferDirect {@code true} if a direct {@link ByteBuf} should be tried to be used as target for
     *                     the encoded messages. If {@code false} is used it will allocate a heap
     *                     {@link ByteBuf}, which is backed by an byte array.
     */
    protected MessageToByteEncoder(boolean preferDirect) {
        // <1> 获得 matcher
        matcher = TypeParameterMatcher.find(this, MessageToByteEncoder.class, "I");
        this.preferDirect = preferDirect;
    }

    /**
     * Create a new instance
     *
     * @param outboundMessageType The type of messages to match
     * @param preferDirect        {@code true} if a direct {@link ByteBuf} should be tried to be used as target for
     *                            the encoded messages. If {@code false} is used it will allocate a heap
     *                            {@link ByteBuf}, which is backed by an byte array.
     */
    protected MessageToByteEncoder(Class<? extends I> outboundMessageType, boolean preferDirect) {
        // <2> 获得 matcher
        matcher = TypeParameterMatcher.get(outboundMessageType);
        this.preferDirect = preferDirect;
    }

    /**
     * Returns {@code true} if the given message should be handled. If {@code false} it will be passed to the next
     * {@link ChannelOutboundHandler} in the {@link ChannelPipeline}.
     * <p>
     * 判断消息类型是否匹配
     */
    public boolean acceptOutboundMessage(Object msg) throws Exception {
        return matcher.match(msg);
    }

    /**
     * 匹配指定的消息类型，编码消息成 ByteBuf 对象，继续写到下一个节点
     */
    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        ByteBuf buf = null;
        try {
            // 判断是否为匹配的消息
            if (acceptOutboundMessage(msg)) {
                @SuppressWarnings("unchecked")
                I cast = (I) msg;
                // 申请 buf
                buf = allocateBuffer(ctx, cast, preferDirect);
                // 编码
                try {
                    encode(ctx, cast, buf);
                } finally {
                    // 释放 msg
                    ReferenceCountUtil.release(cast);
                }
                // buf 可读，说明有编码到数据
                if (buf.isReadable()) {
                    ctx.write(buf, promise);
                } else {
                    // 释放 buf
                    buf.release();
                    // 写入 EMPTY_BUFFER 到下一个节点，为了 promise 的回调
                    ctx.write(Unpooled.EMPTY_BUFFER, promise);
                }
                // 置空 buf
                buf = null;
            } else {
                // 提交 write 事件给下一个节点
                ctx.write(msg, promise);
            }
        } catch (EncoderException e) {
            throw e;
        } catch (Throwable e) {
            throw new EncoderException(e);
        } finally {
            // 释放 buf
            if (buf != null) {
                buf.release();
            }
        }
    }

    /**
     * Allocate a {@link ByteBuf} which will be used as argument of {@link #encode(ChannelHandlerContext, I, ByteBuf)}.
     * Sub-classes may override this method to return {@link ByteBuf} with a perfect matching {@code initialCapacity}.
     */
    protected ByteBuf allocateBuffer(ChannelHandlerContext ctx, @SuppressWarnings("unused") I msg,
                                     boolean preferDirect) throws Exception {
        if (preferDirect) {
            return ctx.alloc().ioBuffer();
        } else {
            return ctx.alloc().heapBuffer();
        }
    }

    /**
     * Encode a message into a {@link ByteBuf}. This method will be called for each written message that can be handled
     * by this encoder.
     *
     * @param ctx the {@link ChannelHandlerContext} which this {@link MessageToByteEncoder} belongs to
     * @param msg the message to encode
     * @param out the {@link ByteBuf} into which the encoded message will be written
     * @throws Exception is thrown if an error occurs
     */
    protected abstract void encode(ChannelHandlerContext ctx, I msg, ByteBuf out) throws Exception;

    protected boolean isPreferDirect() {
        return preferDirect;
    }
}
