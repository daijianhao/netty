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

import io.netty.util.internal.DefaultPriorityQueue;
import io.netty.util.internal.PriorityQueueNode;

import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 实现 ScheduledFuture、PriorityQueueNode 接口，继承 PromiseTask 抽象类，Netty 定时任务
 * @param <V>
 */
@SuppressWarnings("ComparableImplementedButEqualsNotOverridden")
final class ScheduledFutureTask<V> extends PromiseTask<V> implements ScheduledFuture<V>, PriorityQueueNode {
    /**
     * 任务序号生成器，通过 AtomicLong 实现递增发号
     */
    private static final AtomicLong nextTaskId = new AtomicLong();
    /**
     * 定时任务时间起点。在 ScheduledFutureTask 中，定时任务的执行时间，都是基于 START_TIME 做相对时间
     *
     *
     * 因为是定时调度，我改了系统时间也没关系
     * 存的是距离下次调度还要多长时间
     * 不受系统时间影响
     * 最大的好处
     */
    private static final long START_TIME = System.nanoTime();

    static long nanoTime() {
        return System.nanoTime() - START_TIME;
    }

    /**
     * @param delay 延迟时长，单位：纳秒
     * @return 获得任务执行时间，也是相对 {@link #START_TIME} 来算的。
     *          实际上，返回的结果，会用于 {@link #deadlineNanos} 字段
     */
    static long deadlineNanos(long delay) {
        long deadlineNanos = nanoTime() + delay;
        // Guard against overflow 防御性编程
        return deadlineNanos < 0 ? Long.MAX_VALUE : deadlineNanos;
    }

    /**
     * 任务编号
     */
    private final long id = nextTaskId.getAndIncrement();

    /**
     * 任务执行时间，即到了该时间，该任务就会被执行
     */
    private long deadlineNanos;

    /**
     * 任务执行周期
     *
     * =0 - 只执行一次
     * >0 - 按照计划执行时间计算
     * <0 - 按照实际执行时间计算
     *
     * 推荐阅读文章 https://blog.csdn.net/gtuu0123/article/details/6040159
     */
    /* 0 - no repeat, >0 - repeat at fixed rate, <0 - repeat with fixed delay */
    private final long periodNanos;

    /**
     * 队列编号
     */
    private int queueIndex = INDEX_NOT_IN_QUEUE;

    ScheduledFutureTask(
            AbstractScheduledEventExecutor executor,
            Runnable runnable, V result, long nanoTime) {

        this(executor, toCallable(runnable, result), nanoTime);
    }

    ScheduledFutureTask(
            AbstractScheduledEventExecutor executor,
            Callable<V> callable, long nanoTime, long period) {

        super(executor, callable);
        if (period == 0) {
            throw new IllegalArgumentException("period: 0 (expected: != 0)");
        }
        deadlineNanos = nanoTime;
        periodNanos = period;
    }

    ScheduledFutureTask(
            AbstractScheduledEventExecutor executor,
            Callable<V> callable, long nanoTime) {

        super(executor, callable);
        deadlineNanos = nanoTime;
        periodNanos = 0;
    }

    @Override
    protected EventExecutor executor() {
        return super.executor();
    }

    public long deadlineNanos() {
        return deadlineNanos;
    }

    public long delayNanos() {
        //距离当前时间，还要多久可执行。若为负数，直接返回 0
        return Math.max(0, deadlineNanos() - nanoTime());
    }

    public long delayNanos(long currentTimeNanos) {
        //距离指定时间，还要多久可执行。若为负数，直接返回 0
        return Math.max(0, deadlineNanos() - (currentTimeNanos - START_TIME));
    }

    @Override
    public long getDelay(TimeUnit unit) {
        return unit.convert(delayNanos(), TimeUnit.NANOSECONDS);
    }

    /**
     * 用于队列( ScheduledFutureTask 使用 PriorityQueue 作为优先级队列 )排序
     * @param o
     * @return
     */
    @Override
    public int compareTo(Delayed o) {
        if (this == o) {
            return 0;
        }

        ScheduledFutureTask<?> that = (ScheduledFutureTask<?>) o;
        long d = deadlineNanos() - that.deadlineNanos();
        if (d < 0) {
            return -1;
        } else if (d > 0) {
            return 1;
        } else if (id < that.id) {
            return -1;
        } else if (id == that.id) {
            throw new Error();
        } else {
            return 1;
        }
    }

    @Override
    public void run() {
        assert executor().inEventLoop();
        try {
            if (periodNanos == 0) {
                // 设置任务不可取消
                if (setUncancellableInternal()) {
                    // 执行任务
                    V result = task.call();
                    // 通知任务执行成功
                    //回调通知注册在定时任务上的监听器
                    setSuccessInternal(result);
                }
            } else {
                // check if is done as it may was cancelled
                // 判断任务并未取消
                if (!isCancelled()) {
                    task.call();
                    if (!executor().isShutdown()) {
                        // 计算下次执行时间
                        long p = periodNanos;
                        if (p > 0) {
                            deadlineNanos += p;
                        } else {
                            deadlineNanos = nanoTime() - p;
                        }
                        // 判断任务并未取消
                        if (!isCancelled()) {
                            // scheduledTaskQueue can never be null as we lazy init it before submit the task!
                            // 重新添加到任务队列，等待下次定时执
                            Queue<ScheduledFutureTask<?>> scheduledTaskQueue =
                                    ((AbstractScheduledEventExecutor) executor()).scheduledTaskQueue;
                            assert scheduledTaskQueue != null;
                            scheduledTaskQueue.add(this);
                        }
                    }
                }
            }
        } catch (Throwable cause) {
            //发生异常，调用 PromiseTask#setFailureInternal(Throwable cause) 方法，回调通知注册在定时任务上的监听器。
            setFailureInternal(cause);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @param mayInterruptIfRunning this value has no effect in this implementation.
     */
    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        boolean canceled = super.cancel(mayInterruptIfRunning);
        // 取消成功，移除出定时任务队列
        if (canceled) {
            ((AbstractScheduledEventExecutor) executor()).removeScheduled(this);
        }
        return canceled;
    }

    // 移除任务
    boolean cancelWithoutRemove(boolean mayInterruptIfRunning) {
        return super.cancel(mayInterruptIfRunning);
    }

    @Override
    protected StringBuilder toStringBuilder() {
        StringBuilder buf = super.toStringBuilder();
        buf.setCharAt(buf.length() - 1, ',');

        return buf.append(" id: ")
                  .append(id)
                  .append(", deadline: ")
                  .append(deadlineNanos)
                  .append(", period: ")
                  .append(periodNanos)
                  .append(')');
    }

    /**
     * 获得或设置 queueIndex 属性
     *
     * 因为 ScheduledFutureTask 实现 PriorityQueueNode 接口，所以需要实现这两个方法。
     *
     * @param queue
     * @return
     */
    @Override
    public int priorityQueueIndex(DefaultPriorityQueue<?> queue) {
        return queueIndex;
    }

    @Override
    public void priorityQueueIndex(DefaultPriorityQueue<?> queue, int i) {
        // 设置
        queueIndex = i;
    }
}
