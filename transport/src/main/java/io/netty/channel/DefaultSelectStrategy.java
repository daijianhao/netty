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
package io.netty.channel;

import io.netty.util.IntSupplier;

/**
 * Default select strategy.
 *
 * 实现 SelectStrategy 接口，默认选择策略实现类
 */
final class DefaultSelectStrategy implements SelectStrategy {
    static final SelectStrategy INSTANCE = new DefaultSelectStrategy();

    private DefaultSelectStrategy() { }

    @Override
    public int calculateStrategy(IntSupplier selectSupplier, boolean hasTasks) throws Exception {
        //当 hasTasks 为 true ，表示当前已经有任务，所以调用 IntSupplier#get() 方法，返回当前 Channel 新增的 IO 就绪事件的数量
        //实际上，这里不调用 IntSupplier#get() 方法，也是可以的。
        // 只不过考虑到，可以通过 #selectNow() 方法，无阻塞的 select Channel 是否有感兴趣的就绪事件。
        return hasTasks ? selectSupplier.get() : SelectStrategy.SELECT;
    }
}
