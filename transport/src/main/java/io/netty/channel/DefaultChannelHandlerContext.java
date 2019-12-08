/*
* Copyright 2014 The Netty Project
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
package io.netty.channel;

import io.netty.util.concurrent.EventExecutor;

/**
 * 实现 AbstractChannelHandlerContext 抽象类
 *
 * 不同于 HeadContext、TailContext，它们自身就是一个 Context 的同时，也是一个 ChannelHandler 。
 * 而 DefaultChannelHandlerContext 是内嵌 一个 ChannelHandler 对象，即 handler 。这个属性通过构造方法传入
 */
final class DefaultChannelHandlerContext extends AbstractChannelHandlerContext {

    private final ChannelHandler handler;

    DefaultChannelHandlerContext(
            DefaultChannelPipeline pipeline, EventExecutor executor, String name, ChannelHandler handler) {
        //调用父 AbstractChannelHandlerContext 的构造方法，通过判断传入的 handler 是否为 ChannelInboundHandler 和
        // ChannelOutboundHandler 来分别判断是否为 inbound 和 outbound 。
        super(pipeline, executor, name, isInbound(handler), isOutbound(handler));
        if (handler == null) {
            throw new NullPointerException("handler");
        }
        this.handler = handler;
    }

    @Override
    public ChannelHandler handler() {
        return handler;
    }

    private static boolean isInbound(ChannelHandler handler) {
        return handler instanceof ChannelInboundHandler;
    }

    private static boolean isOutbound(ChannelHandler handler) {
        return handler instanceof ChannelOutboundHandler;
    }
}
