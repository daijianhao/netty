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

package io.netty.util;

import io.netty.util.internal.EmptyArrays;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.ref.ReferenceQueue;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import static io.netty.util.internal.StringUtil.EMPTY_STRING;
import static io.netty.util.internal.StringUtil.NEWLINE;
import static io.netty.util.internal.StringUtil.simpleClassName;

/**
 * 内存泄露检测器
 * <p>
 * ResourceLeakDetector 为了检测内存是否泄漏，使用了 WeakReference( 弱引用 )和 ReferenceQueue( 引用队列 )，过程如下：
 * <p>
 * 1.根据检测级别和采样率的设置，在需要时为需要检测的 ByteBuf 创建WeakReference 引用。
 * 2.当 JVM 回收掉 ByteBuf 对象时，JVM 会将 WeakReference 放入ReferenceQueue 队列中。
 * 3.通过对 ReferenceQueue 中 WeakReference 的检查，判断在 GC 前是否有释放ByteBuf 的资源，就可以知道是否有资源释放
 *
 * @param <T> 在 Level 中，枚举了四个级别。
 *            <p>
 *            禁用（DISABLED） - 完全禁止泄露检测，省点消耗。
 *            简单（SIMPLE） - 默认等级，告诉我们取样的1%的ByteBuf是否发生了泄露，但总共一次只打印一次，看不到就没有了。
 *            高级（ADVANCED） - 告诉我们取样的1%的ByteBuf发生泄露的地方。每种类型的泄漏（创建的地方与访问路径一致）只打印一次。对性能有影响。
 *            偏执（PARANOID） - 跟高级选项类似，但此选项检测所有ByteBuf，而不仅仅是取样的那1%。对性能有绝大的影响。
 */
public class ResourceLeakDetector<T> {

    private static final String PROP_LEVEL_OLD = "io.netty.leakDetectionLevel";
    private static final String PROP_LEVEL = "io.netty.leakDetection.level";

    /**
     * 默认内存检测级别
     */
    private static final Level DEFAULT_LEVEL = Level.SIMPLE;

    private static final String PROP_TARGET_RECORDS = "io.netty.leakDetection.targetRecords";


    private static final int DEFAULT_TARGET_RECORDS = 4;

    private static final String PROP_SAMPLING_INTERVAL = "io.netty.leakDetection.samplingInterval";
    // There is a minor performance benefit in TLR if this is a power of 2.
    /**
     * 默认采集频率
     */
    private static final int DEFAULT_SAMPLING_INTERVAL = 128;

    /**
     * 每个 DefaultResourceLeak 记录的 Record 数量
     */
    private static final int TARGET_RECORDS;
    static final int SAMPLING_INTERVAL;

    /**
     * Represents the level of resource leak detection.
     * <p>
     * 内存检测级别枚举
     */
    public enum Level {
        /**
         * Disables resource leak detection.
         */
        DISABLED,
        /**
         * Enables simplistic sampling resource leak detection which reports there is a leak or not,
         * at the cost of small overhead (default).
         */
        SIMPLE,
        /**
         * Enables advanced sampling resource leak detection which reports where the leaked object was accessed
         * recently at the cost of high overhead.
         */
        ADVANCED,
        /**
         * Enables paranoid resource leak detection which reports where the leaked object was accessed recently,
         * at the cost of the highest possible overhead (for testing purposes only).
         */
        PARANOID;

        /**
         * Returns level based on string value. Accepts also string that represents ordinal number of enum.
         *
         * @param levelStr - level string : DISABLED, SIMPLE, ADVANCED, PARANOID. Ignores case.
         * @return corresponding level or SIMPLE level in case of no match.
         */
        static Level parseLevel(String levelStr) {
            String trimmedLevelStr = levelStr.trim();
            for (Level l : values()) {
                if (trimmedLevelStr.equalsIgnoreCase(l.name()) || trimmedLevelStr.equals(String.valueOf(l.ordinal()))) {
                    return l;
                }
            }
            return DEFAULT_LEVEL;
        }
    }

    /**
     * 内存泄露检测等级
     */
    private static Level level;

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ResourceLeakDetector.class);

    // 获得是否禁用泄露检测
    static {
        final boolean disabled;
        if (SystemPropertyUtil.get("io.netty.noResourceLeakDetection") != null) {
            disabled = SystemPropertyUtil.getBoolean("io.netty.noResourceLeakDetection", false);
            logger.debug("-Dio.netty.noResourceLeakDetection: {}", disabled);
            logger.warn(
                    "-Dio.netty.noResourceLeakDetection is deprecated. Use '-D{}={}' instead.",
                    PROP_LEVEL, DEFAULT_LEVEL.name().toLowerCase());
        } else {
            disabled = false;
        }

        // 获得默认级别
        Level defaultLevel = disabled ? Level.DISABLED : DEFAULT_LEVEL;

        // First read old property name
        // 获得配置的级别字符串，从老版本的配置，兼容老版本
        String levelStr = SystemPropertyUtil.get(PROP_LEVEL_OLD, defaultLevel.name());

        // If new property name is present, use it
        // 获得配置的级别字符串，从新版本的配置
        levelStr = SystemPropertyUtil.get(PROP_LEVEL, levelStr);
        // 获得最终的级别
        Level level = Level.parseLevel(levelStr);

        // 初始化 TARGET_RECORDS
        TARGET_RECORDS = SystemPropertyUtil.getInt(PROP_TARGET_RECORDS, DEFAULT_TARGET_RECORDS);
        SAMPLING_INTERVAL = SystemPropertyUtil.getInt(PROP_SAMPLING_INTERVAL, DEFAULT_SAMPLING_INTERVAL);
        // 设置最终的级别
        ResourceLeakDetector.level = level;
        if (logger.isDebugEnabled()) {
            logger.debug("-D{}: {}", PROP_LEVEL, level.name().toLowerCase());
            logger.debug("-D{}: {}", PROP_TARGET_RECORDS, TARGET_RECORDS);
        }
    }

    /**
     * @deprecated Use {@link #setLevel(Level)} instead.
     */
    @Deprecated
    public static void setEnabled(boolean enabled) {
        setLevel(enabled ? Level.SIMPLE : Level.DISABLED);
    }

    /**
     * Returns {@code true} if resource leak detection is enabled.
     */
    public static boolean isEnabled() {
        return getLevel().ordinal() > Level.DISABLED.ordinal();
    }

    /**
     * Sets the resource leak detection level.
     */
    public static void setLevel(Level level) {
        if (level == null) {
            throw new NullPointerException("level");
        }
        ResourceLeakDetector.level = level;
    }

    /**
     * Returns the current resource leak detection level.
     */
    public static Level getLevel() {
        return level;
    }

    /**
     * the collection of active resources
     * <p>
     * DefaultResourceLeak 集合
     * <p>
     * 因为 Java 没有自带的 ConcurrentSet ，所以只好使用使用 ConcurrentMap 。也就是说，value 属性实际没有任何用途
     */
    private final Set<DefaultResourceLeak<?>> allLeaks =
            Collections.newSetFromMap(new ConcurrentHashMap<DefaultResourceLeak<?>, Boolean>());

    /**
     * 引用队列
     */
    private final ReferenceQueue<Object> refQueue = new ReferenceQueue<Object>();

    /**
     * 已汇报的内存泄露的资源类型的集合
     */
    private final ConcurrentMap<String, Boolean> reportedLeaks = PlatformDependent.newConcurrentHashMap();

    /**
     * 资源类型
     */
    private final String resourceType;

    /**
     * 采集评率
     */
    private final int samplingInterval;

    /**
     * @deprecated use {@link ResourceLeakDetectorFactory#newResourceLeakDetector(Class, int, long)}.
     */
    @Deprecated
    public ResourceLeakDetector(Class<?> resourceType) {
        this(simpleClassName(resourceType));
    }

    /**
     * @deprecated use {@link ResourceLeakDetectorFactory#newResourceLeakDetector(Class, int, long)}.
     */
    @Deprecated
    public ResourceLeakDetector(String resourceType) {
        this(resourceType, DEFAULT_SAMPLING_INTERVAL, Long.MAX_VALUE);
    }

    /**
     * @param maxActive This is deprecated and will be ignored.
     * @deprecated Use {@link ResourceLeakDetector#ResourceLeakDetector(Class, int)}.
     * <p>
     * This should not be used directly by users of {@link ResourceLeakDetector}.
     * Please use {@link ResourceLeakDetectorFactory#newResourceLeakDetector(Class)}
     * or {@link ResourceLeakDetectorFactory#newResourceLeakDetector(Class, int, long)}
     */
    @Deprecated
    public ResourceLeakDetector(Class<?> resourceType, int samplingInterval, long maxActive) {
        this(resourceType, samplingInterval);
    }

    /**
     * This should not be used directly by users of {@link ResourceLeakDetector}.
     * Please use {@link ResourceLeakDetectorFactory#newResourceLeakDetector(Class)}
     * or {@link ResourceLeakDetectorFactory#newResourceLeakDetector(Class, int, long)}
     */
    @SuppressWarnings("deprecation")
    public ResourceLeakDetector(Class<?> resourceType, int samplingInterval) {
        this(simpleClassName(resourceType), samplingInterval, Long.MAX_VALUE);
    }

    /**
     * @param maxActive This is deprecated and will be ignored.
     * @deprecated use {@link ResourceLeakDetectorFactory#newResourceLeakDetector(Class, int, long)}.
     * <p>
     */
    @Deprecated
    public ResourceLeakDetector(String resourceType, int samplingInterval, long maxActive) {
        if (resourceType == null) {
            throw new NullPointerException("resourceType");
        }

        this.resourceType = resourceType;
        this.samplingInterval = samplingInterval;
    }

    /**
     * Creates a new {@link ResourceLeak} which is expected to be closed via {@link ResourceLeak#close()} when the
     * related resource is deallocated.
     *
     * @return the {@link ResourceLeak} or {@code null}
     * @deprecated use {@link #track(Object)}
     */
    @Deprecated
    public final ResourceLeak open(T obj) {
        return track0(obj);
    }

    /**
     * Creates a new {@link ResourceLeakTracker} which is expected to be closed via
     * {@link ResourceLeakTracker#close(Object)} when the related resource is deallocated.
     *
     * @return the {@link ResourceLeakTracker} or {@code null}
     * <p>
     * 给指定资源( 例如 ByteBuf 对象 )创建一个检测它是否泄漏的 ResourceLeakTracker 对象
     */
    @SuppressWarnings("unchecked")
    public final ResourceLeakTracker<T> track(T obj) {
        return track0(obj);
    }

    /**
     * 原本以为，ResourceLeakDetector 会有一个定时任务，不断检测是否有内存泄露。从这里的代码来看，
     * 它是在每次一次创建 DefaultResourceLeak 对象时，调用 #reportLeak() 方法，汇报内存是否泄漏
     */
    @SuppressWarnings("unchecked")
    private DefaultResourceLeak track0(T obj) {
        Level level = ResourceLeakDetector.level;
        // DISABLED 级别，不创建
        if (level == Level.DISABLED) {
            return null;
        }

        // SIMPLE 和 ADVANCED
        if (level.ordinal() < Level.PARANOID.ordinal()) {
            // 随机
            //随机，概率为 1 / samplingInterval ，创建 DefaultResourceLeak 对象。默认情况下 samplingInterval = 128 ，
            // 约等于 1% ，这也是就为什么说“告诉我们取样的 1% 的ByteBuf发生泄露的地方”
            if ((PlatformDependent.threadLocalRandom().nextInt(samplingInterval)) == 0) {
                // 汇报内存是否泄漏
                reportLeak();
                // 创建 DefaultResourceLeak 对象
                return new DefaultResourceLeak(obj, refQueue, allLeaks);
            }
            return null;
        }
        // PARANOID 级别
        // 汇报内存是否泄漏
        //PARANOID 级别时，一定创建 DefaultResourceLeak 对象。这也是为什么说“对性能有绝大的影响”
        reportLeak();
        // 创建 DefaultResourceLeak 对象
        return new DefaultResourceLeak(obj, refQueue, allLeaks);
    }

    private void clearRefQueue() {
        for (; ; ) {
            @SuppressWarnings("unchecked")
            DefaultResourceLeak ref = (DefaultResourceLeak) refQueue.poll();
            if (ref == null) {
                break;
            }
            // 清理，并返回是否内存泄露
            ref.dispose();
        }
    }

    private void reportLeak() {
        // 如果不允许打印错误日志，则无法汇报，清理队列，并直接结束
        if (!logger.isErrorEnabled()) {
            // 清理队列
            clearRefQueue();
            return;
        }

        // Detect and report previous leaks.
        // 循环引用队列，直到为空
        for (; ; ) {
            @SuppressWarnings("unchecked")
            DefaultResourceLeak ref = (DefaultResourceLeak) refQueue.poll();
            if (ref == null) {
                break;
            }
            // 清理，并返回是否内存泄露
            if (!ref.dispose()) {
                //如果未泄露，就直接 continue
                continue;
            }

            // 获得 Record 日志
            String records = ref.toString();
            // 相同 Record 日志，只汇报一次
            if (reportedLeaks.putIfAbsent(records, Boolean.TRUE) == null) {
                if (records.isEmpty()) {
                    reportUntracedLeak(resourceType);
                } else {
                    reportTracedLeak(resourceType, records);
                }
            }
        }
    }

    /**
     * This method is called when a traced leak is detected. It can be overridden for tracking how many times leaks
     * have been detected.
     */
    protected void reportTracedLeak(String resourceType, String records) {
        logger.error(
                "LEAK: {}.release() was not called before it's garbage-collected. " +
                        "See http://netty.io/wiki/reference-counted-objects.html for more information.{}",
                resourceType, records);
    }

    /**
     * This method is called when an untraced leak is detected. It can be overridden for tracking how many times leaks
     * have been detected.
     */
    protected void reportUntracedLeak(String resourceType) {
        logger.error("LEAK: {}.release() was not called before it's garbage-collected. " +
                        "Enable advanced leak reporting to find out where the leak occurred. " +
                        "To enable advanced leak reporting, " +
                        "specify the JVM option '-D{}={}' or call {}.setLevel() " +
                        "See http://netty.io/wiki/reference-counted-objects.html for more information.",
                resourceType, PROP_LEVEL, Level.ADVANCED.name().toLowerCase(), simpleClassName(this));
    }

    /**
     * @deprecated This method will no longer be invoked by {@link ResourceLeakDetector}.
     */
    @Deprecated
    protected void reportInstancesLeak(String resourceType) {
    }

    /**
     * 继承 java.lang.ref.WeakReference 类，实现 ResourceLeakTracker 接口，默认 ResourceLeakTracker 实现类。
     * 同时，它是 ResourceLeakDetector 内部静态类
     *
     * @param <T>
     */
    @SuppressWarnings("deprecation")
    private static final class DefaultResourceLeak<T>
            extends WeakReference<Object> implements ResourceLeakTracker<T>, ResourceLeak {

        /**
         * {@link #head} 的更新器
         */
        @SuppressWarnings("unchecked") // generics and updaters do not mix.
        private static final AtomicReferenceFieldUpdater<DefaultResourceLeak<?>, Record> headUpdater =
                (AtomicReferenceFieldUpdater)
                        AtomicReferenceFieldUpdater.newUpdater(DefaultResourceLeak.class, Record.class, "head");

        /**
         * {@link #droppedRecords} 的更新器
         */
        @SuppressWarnings("unchecked") // generics and updaters do not mix.
        private static final AtomicIntegerFieldUpdater<DefaultResourceLeak<?>> droppedRecordsUpdater =
                (AtomicIntegerFieldUpdater)
                        AtomicIntegerFieldUpdater.newUpdater(DefaultResourceLeak.class, "droppedRecords");

        /**
         * Record 链的头节点
         * <p>
         * 实际上，head 是尾节点，即最后( 新 )的一条 Record 记录
         * <p>
         * 看完 {@link #record()} 方法后，实际上，head 是尾节点，即最后( 新 )的一条 Record 。
         */
        @SuppressWarnings("unused")
        private volatile Record head;

        /**
         * 丢弃的 Record 计数
         */
        @SuppressWarnings("unused")
        private volatile int droppedRecords;

        /**
         * DefaultResourceLeak 集合。来自 {@link ResourceLeakDetector#allLeaks}
         */
        private final Set<DefaultResourceLeak<?>> allLeaks;

        /**
         * hash 值
         * <p>
         * 保证 {@link #close(Object)} 传入的对象，就是 {@link #referent} 对象
         */
        private final int trackedHash;

        /**
         * 引用队列(Reference Queue)
         * <p>
         * 一旦弱引用对象开始返回null，该弱引用指向的对象就被标记成了垃圾。而这个弱引用对象（非其指向的对象）就没有什么用了。
         * 通常这时候需要进行一些清理工作。比如WeakHashMap会在这时候移除没用的条目来避免保存无限制增长的没有意义的弱引用。
         * <p>
         * 引用队列可以很容易地实现跟踪不需要的引用。当你在构造WeakReference时传入一个ReferenceQueue对象，当该引用指向的
         * 对象被标记为垃圾的时候，这个引用对象会自动地加入到引用队列里面。接下来，你就可以在固定的周期，处理传入的引用队列，
         * 比如做一些清理工作来处理这些没有用的引用对象。
         * <p>
         * 也就是说，referent 被标记为垃圾的时候，它对应的 WeakReference 对象会被添加到 refQueue 队列中。在此处，
         * 即将 DefaultResourceLeak 添加到 referent 队列中
         * <p>
         * 那又咋样呢？假设 referent 为 ByteBuf 对象。如果它被正确的释放，即调用了 「3.3.4 release」 方法，从而调
         * 用了 AbstractReferenceCountedByteBuf#closeLeak() 方法，最终调用到 ResourceLeakTracker#close(trackedByteBuf) 方法，
         * 那么该 ByteBuf 对象对应的 ResourceLeakTracker 对象，将从 ResourceLeakDetector.allLeaks 中移除。
         * 那这又意味着什么呢？ 在 ResourceLeakDetector#reportLeak() 方法中，即使从 refQueue 队列中，获取到该 ByteBuf 对象对应
         * ResourceLeakTracker 对象，因为在 ResourceLeakDetector.allLeaks 中移除了，所以在 ResourceLeakDetector#reportLeak()
         * 方法的【第 19 行】代码 !ref.dispose() = true ，直接 continue 。
         * <p>
         * <p>
         * 以{@link io.netty.buffer.AdvancedLeakAwareByteBuf}为例：
         * 调用{@link io.netty.buffer.AdvancedLeakAwareByteBuf#release()}释放时，调用链
         * |
         * {@link DefaultResourceLeak#record()}
         * |
         * {@link io.netty.buffer.SimpleLeakAwareByteBuf#release()}
         * |
         * {@link io.netty.buffer.SimpleLeakAwareByteBuf#closeLeak()}  当上一步release()返回true时，会执行
         * |
         * {@link DefaultResourceLeak#close(java.lang.Object)}
         * |
         * {@link DefaultResourceLeak#close()} close()方法将当前 DefaultResourceLeak 从{@link DefaultResourceLeak#allLeaks}中移出,
         * 并将当前DefaultResourceLeak中的head属性设置为null
         * |
         * {@link Reference#clear()} 将DefaultResourceLeak中对实际未包装的ByteBuf的引用（referent）职位null
         * <p>
         * 在经过了以上流程后，当调用{@link ResourceLeakDetector#reportLeak()}汇报内存是否泄漏时，!ref.dispose() = true ，直接 continue
         * 如果没有正确释放，则会在{@link ResourceLeakDetector#reportLeak()}是打出错误日志
         */
        DefaultResourceLeak(
                Object referent,
                ReferenceQueue<Object> refQueue,
                Set<DefaultResourceLeak<?>> allLeaks) {
            // 父构造方法
            //会将 referent( 资源，例如：ByteBuf 对象 )和 refQueue( 引用队列 )传入父 WeakReference 构造方法
            super(referent, refQueue);

            assert referent != null;

            // Store the hash of the tracked object to later assert it in the close(...) method.
            // It's important that we not store a reference to the referent as this would disallow it from
            // be collected via the WeakReference.
            //hash 值。保证在 #close(T trackedObject) 方法，传入的对象，就是 referent 属性，
            // 即就是 DefaultResourceLeak 指向的资源( 例如：ByteBuf 对象 )
            trackedHash = System.identityHashCode(referent);
            allLeaks.add(this);
            // Create a new Record so we always have the creation stacktrace included.
            //会默认创建尾节点 Record.BOTTOM
            headUpdater.set(this, new Record(Record.BOTTOM));
            this.allLeaks = allLeaks;
        }

        @Override
        public void record() {
            record0(null);
        }

        @Override
        public void record(Object hint) {
            record0(hint);
        }

        /**
         * This method works by exponentially backing off as more records are present in the stack. Each record has a
         * 1 / 2^n chance of dropping the top most record and replacing it with itself. This has a number of convenient
         * properties:
         *
         * <ol>
         * <li>  The current record is always recorded. This is due to the compare and swap dropping the top most
         *       record, rather than the to-be-pushed record.
         * <li>  The very last access will always be recorded. This comes as a property of 1.
         * <li>  It is possible to retain more records than the target, based upon the probability distribution.
         * <li>  It is easy to keep a precise record of the number of elements in the stack, since each element has to
         *     know how tall the stack is.
         * </ol>
         * <p>
         * In this particular implementation, there are also some advantages. A thread local random is used to decide
         * if something should be recorded. This means that if there is a deterministic access pattern, it is now
         * possible to see what other accesses occur, rather than always dropping them. Second, after
         * {@link #TARGET_RECORDS} accesses, backoff occurs. This matches typical access patterns,
         * where there are either a high number of accesses (i.e. a cached buffer), or low (an ephemeral buffer), but
         * not many in between.
         * <p>
         * The use of atomics avoids serializing a high number of accesses, when most of the records will be thrown
         * away. High contention only happens when there are very few existing records, which is only likely when the
         * object isn't shared! If this is a problem, the loop can be aborted and the record dropped, because another
         * thread won the race.
         * <p>
         * 创建 Record 对象，添加到 head 链中
         */
        private void record0(Object hint) {
            // Check TARGET_RECORDS > 0 here to avoid similar check before remove from and add to lastRecords
            if (TARGET_RECORDS > 0) {
                Record oldHead;
                Record prevHead;
                Record newHead;
                boolean dropped;
                do {
                    if ((prevHead = oldHead = headUpdater.get(this)) == null) {
                        // 已经关闭，则返回
                        // already closed.
                        return;
                    }
                    final int numElements = oldHead.pos + 1;
                    // 当超过 TARGET_RECORDS 数量时，随机丢到头节点
                    //当当前 DefaultResourceLeak 对象所拥有的 Record 数量超过 TARGET_RECORDS 时，随机丢弃当前 head 节点的数据。
                    // 也就是说，尽量保留老的 Record 节点。这是为什么呢?越是老( 开始 )的 Record 节点，越有利于排查问题。另外，随机丢弃的的概率，按照 1 - (1 / 2^n） 几率，越来越大
                    if (numElements >= TARGET_RECORDS) {
                        final int backOffFactor = Math.min(numElements - TARGET_RECORDS, 30);
                        if (dropped = PlatformDependent.threadLocalRandom().nextInt(1 << backOffFactor) != 0) {
                            //创建新 Record 对象，作为头节点，指向原头节点。这也是为什么说，“实际上，head 是尾节点，即最后( 新 )的一条 Record”。
                            prevHead = oldHead.next;
                        }
                    } else {
                        dropped = false;
                    }
                    // 创建新的头节点
                    newHead = hint != null ? new Record(prevHead, hint) : new Record(prevHead);
                } while (!headUpdater.compareAndSet(this, oldHead, newHead));
                if (dropped) {
                    // 若丢弃，增加 droppedRecordsUpdater 计数
                    droppedRecordsUpdater.incrementAndGet(this);
                }
            }
        }

        boolean dispose() {
            // 清理 referent 的引用
            clear();
            // 移除出 allLeaks 。移除成功，意味着内存泄露
            return allLeaks.remove(this);
        }

        @Override
        public boolean close() {
            if (allLeaks.remove(this)) {
                // Call clear so the reference is not even enqueued.
                clear();
                headUpdater.set(this, null);
                return true;
            }
            return false;
        }

        @Override
        public boolean close(T trackedObject) {
            // Ensure that the object that was tracked is the same as the one that was passed to close(...).
            // 校验一致
            assert trackedHash == System.identityHashCode(trackedObject);

            try {
                // 关闭
                return close();
            } finally {
                // This method will do `synchronized(trackedObject)` and we should be sure this will not cause deadlock.
                // It should not, because somewhere up the callstack should be a (successful) `trackedObject.release`,
                // therefore it is unreasonable that anyone else, anywhere, is holding a lock on the trackedObject.
                // (Unreasonable but possible, unfortunately.)
                reachabilityFence0(trackedObject);
            }
        }

        /**
         * Ensures that the object referenced by the given reference remains
         * <a href="package-summary.html#reachability"><em>strongly reachable</em></a>,
         * regardless of any prior actions of the program that might otherwise cause
         * the object to become unreachable; thus, the referenced object is not
         * reclaimable by garbage collection at least until after the invocation of
         * this method.
         *
         * <p> Recent versions of the JDK have a nasty habit of prematurely deciding objects are unreachable.
         * see: https://stackoverflow.com/questions/26642153/finalize-called-on-strongly-reachable-object-in-java-8
         * The Java 9 method Reference.reachabilityFence offers a solution to this problem.
         *
         * <p> This method is always implemented as a synchronization on {@code ref}, not as
         * {@code Reference.reachabilityFence} for consistency across platforms and to allow building on JDK 6-8.
         * <b>It is the caller's responsibility to ensure that this synchronization will not cause deadlock.</b>
         *
         * @param ref the reference. If {@code null}, this method has no effect.
         * @see java.lang.ref.Reference#reachabilityFence
         */
        private static void reachabilityFence0(Object ref) {
            if (ref != null) {
                synchronized (ref) {
                    // Empty synchronized is ok: https://stackoverflow.com/a/31933260/1151521
                }
            }
        }

        /**
         * 当 DefaultResourceLeak 追踪到内存泄露，会在 ResourceLeakDetector#reportLeak() 方法中，
         * 调用 DefaultResourceLeak#toString() 方法，拼接提示信息
         *
         * @return
         */
        @Override
        public String toString() {
            // 获得 head 属性，并置空
            Record oldHead = headUpdater.getAndSet(this, null);
            // 若为空，说明已经关闭
            if (oldHead == null) {
                // Already closed
                return EMPTY_STRING;
            }

            final int dropped = droppedRecordsUpdater.get(this);
            int duped = 0;

            int present = oldHead.pos + 1;
            // Guess about 2 kilobytes per stack trace
            StringBuilder buf = new StringBuilder(present * 2048).append(NEWLINE);
            buf.append("Recent access records: ").append(NEWLINE);

            // 拼接 Record 练
            int i = 1;
            Set<String> seen = new HashSet<String>(present);
            for (; oldHead != Record.BOTTOM; oldHead = oldHead.next) {
                String s = oldHead.toString();
                if (seen.add(s)) {
                    if (oldHead.next == Record.BOTTOM) {
                        buf.append("Created at:").append(NEWLINE).append(s);
                    } else {
                        buf.append('#').append(i++).append(':').append(NEWLINE).append(s);
                    }
                } else {
                    duped++;
                }
            }

            // 拼接 dropped (丢弃) 次数
            if (duped > 0) {
                buf.append(": ")
                        .append(duped)
                        .append(" leak records were discarded because they were duplicates")
                        .append(NEWLINE);
            }
            // 拼接 duped ( 重复 ) 次数
            if (dropped > 0) {
                buf.append(": ")
                        .append(dropped)
                        .append(" leak records were discarded because the leak record count is targeted to ")
                        .append(TARGET_RECORDS)
                        .append(". Use system property ")
                        .append(PROP_TARGET_RECORDS)
                        .append(" to increase the limit.")
                        .append(NEWLINE);
            }

            buf.setLength(buf.length() - NEWLINE.length());
            return buf.toString();
        }
    }

    private static final AtomicReference<String[]> excludedMethods =
            new AtomicReference<String[]>(EmptyArrays.EMPTY_STRINGS);

    /**
     * 添加忽略方法的集合
     */
    public static void addExclusions(Class clz, String... methodNames) {
        Set<String> nameSet = new HashSet<String>(Arrays.asList(methodNames));
        // Use loop rather than lookup. This avoids knowing the parameters, and doesn't have to handle
        // NoSuchMethodException.
        for (Method method : clz.getDeclaredMethods()) {
            if (nameSet.remove(method.getName()) && nameSet.isEmpty()) {
                break;
            }
        }
        if (!nameSet.isEmpty()) {
            throw new IllegalArgumentException("Can't find '" + nameSet + "' in " + clz.getName());
        }
        String[] oldMethods;
        String[] newMethods;
        do {
            oldMethods = excludedMethods.get();
            newMethods = Arrays.copyOf(oldMethods, oldMethods.length + 2 * methodNames.length);
            for (int i = 0; i < methodNames.length; i++) {
                newMethods[oldMethods.length + i * 2] = clz.getName();
                newMethods[oldMethods.length + i * 2 + 1] = methodNames[i];
            }
        } while (!excludedMethods.compareAndSet(oldMethods, newMethods));
    }

    private static final class Record extends Throwable {
        private static final long serialVersionUID = 6065153674892850720L;

        private static final Record BOTTOM = new Record();

        private final String hintString;
        private final Record next;
        private final int pos;

        Record(Record next, Object hint) {
            // This needs to be generated even if toString() is never called as it may change later on.
            hintString = hint instanceof ResourceLeakHint ? ((ResourceLeakHint) hint).toHintString() : hint.toString();
            this.next = next;
            this.pos = next.pos + 1;
        }

        Record(Record next) {
            hintString = null;
            this.next = next;
            this.pos = next.pos + 1;
        }

        // Used to terminate the stack
        private Record() {
            hintString = null;
            next = null;
            pos = -1;
        }

        @Override
        public String toString() {
            StringBuilder buf = new StringBuilder(2048);
            if (hintString != null) {
                buf.append("\tHint: ").append(hintString).append(NEWLINE);
            }

            // Append the stack trace.
            StackTraceElement[] array = getStackTrace();
            // Skip the first three elements.
            out:
            for (int i = 3; i < array.length; i++) {
                StackTraceElement element = array[i];
                // Strip the noisy stack trace elements.
                String[] exclusions = excludedMethods.get();
                for (int k = 0; k < exclusions.length; k += 2) {
                    if (exclusions[k].equals(element.getClassName())
                            && exclusions[k + 1].equals(element.getMethodName())) {
                        continue out;
                    }
                }

                buf.append('\t');
                buf.append(element.toString());
                buf.append(NEWLINE);
            }
            return buf.toString();
        }
    }
}
