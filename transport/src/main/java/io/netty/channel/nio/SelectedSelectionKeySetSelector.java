/*
 * Copyright 2017 The Netty Project
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

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.spi.SelectorProvider;
import java.util.Set;

/**
 * 基于 Netty SelectedSelectionKeySet 作为 selectionKeys 的 Selector 实现类
 */
final class SelectedSelectionKeySetSelector extends Selector {

    /**
     * SelectedSelectionKeySet 对象
     * 这里的selectionKeys与delegate中的selectedKeys、publicSelectedKeys属性其实是同一个对象
     */
    private final SelectedSelectionKeySet selectionKeys;

    /**
     * 原始 Java NIO Selector 对象
     */
    private final Selector delegate;

    SelectedSelectionKeySetSelector(Selector delegate, SelectedSelectionKeySet selectionKeys) {
        this.delegate = delegate;
        this.selectionKeys = selectionKeys;
    }

    @Override
    public boolean isOpen() {
        return delegate.isOpen();
    }

    @Override
    public SelectorProvider provider() {
        return delegate.provider();
    }

    @Override
    public Set<SelectionKey> keys() {
        return delegate.keys();
    }

    @Override
    public Set<SelectionKey> selectedKeys() {
        return delegate.selectedKeys();
    }

    @Override
    public int selectNow() throws IOException {
        //select 相关的 3 个方法，在调用对应的 Java NIO Selector 方法之前，会调用 SelectedSelectionKeySet#reset() 方法，
        // 重置 selectionKeys 。从而实现，每次 select 之后，都是新的已 select 的 NIO SelectionKey 集合。
        selectionKeys.reset();
        return delegate.selectNow();
    }

    @Override
    public int select(long timeout) throws IOException {
        selectionKeys.reset();
        return delegate.select(timeout);
    }

    @Override
    public int select() throws IOException {
        selectionKeys.reset();
        return delegate.select();
    }

    @Override
    public Selector wakeup() {
        return delegate.wakeup();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
