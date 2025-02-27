/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.util;

import static io.netty.util.internal.ObjectUtil.checkInRange;
import static io.netty.util.internal.ObjectUtil.checkPositive;
import static io.netty.util.internal.ObjectUtil.checkNotNull;

import io.netty.util.concurrent.ImmediateExecutor;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.Collections;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLong;

import static io.netty.util.internal.StringUtil.simpleClassName;

/**
 * A {@link Timer} optimized for approximated I/O timeout scheduling.
 *
 * <h3>Tick Duration</h3>
 *
 * As described with 'approximated', this timer does not execute the scheduled
 * {@link TimerTask} on time.  {@link HashedWheelTimer}, on every tick, will
 * check if there are any {@link TimerTask}s behind the schedule and execute
 * them.
 * <p>
 * You can increase or decrease the accuracy of the execution timing by
 * specifying smaller or larger tick duration in the constructor.  In most
 * network applications, I/O timeout does not need to be accurate.  Therefore,
 * the default tick duration is 100 milliseconds and you will not need to try
 * different configurations in most cases.
 *
 * <h3>Ticks per Wheel (Wheel Size)</h3>
 *
 * {@link HashedWheelTimer} maintains a data structure called 'wheel'.
 * To put simply, a wheel is a hash table of {@link TimerTask}s whose hash
 * function is 'dead line of the task'.  The default number of ticks per wheel
 * (i.e. the size of the wheel) is 512.  You could specify a larger value
 * if you are going to schedule a lot of timeouts.
 *
 * <h3>Do not create many instances.</h3>
 *
 * {@link HashedWheelTimer} creates a new thread whenever it is instantiated and
 * started.  Therefore, you should make sure to create only one instance and
 * share it across your application.  One of the common mistakes, that makes
 * your application unresponsive, is to create a new instance for every connection.
 *
 * <h3>Implementation Details</h3>
 *
 * {@link HashedWheelTimer} is based on
 * <a href="https://cseweb.ucsd.edu/users/varghese/">George Varghese</a> and
 * Tony Lauck's paper,
 * <a href="https://cseweb.ucsd.edu/users/varghese/PAPERS/twheel.ps.Z">'Hashed
 * and Hierarchical Timing Wheels: data structures to efficiently implement a
 * timer facility'</a>.  More comprehensive slides are located
 * <a href="https://www.cse.wustl.edu/~cdgill/courses/cs6874/TimingWheels.ppt">here</a>.
 */
public class HashedWheelTimer implements Timer {

    static final InternalLogger logger =
            InternalLoggerFactory.getInstance(HashedWheelTimer.class);

    // HashedWheelTimer 实例统计原子变量
    private static final AtomicInteger INSTANCE_COUNTER = new AtomicInteger();
    // 过多 HashedWheelTimer 阈值开关
    private static final AtomicBoolean WARNED_TOO_MANY_INSTANCES = new AtomicBoolean();
    // HashedWheelTimer 数量的阈值
    private static final int INSTANCE_COUNT_LIMIT = 64;
    // 最小延时时间，默认是 1 毫秒
    private static final long MILLISECOND_NANOS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final ResourceLeakDetector<HashedWheelTimer> leakDetector = ResourceLeakDetectorFactory.instance()
            .newResourceLeakDetector(HashedWheelTimer.class, 1);

    // 时间轮状态，可以控制工作线程执行任务的状态。
    private static final AtomicIntegerFieldUpdater<HashedWheelTimer> WORKER_STATE_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(HashedWheelTimer.class, "workerState");

    private final ResourceLeakTracker<HashedWheelTimer> leak;
    // 工作任务
    private final Worker worker = new Worker();
    // 工作线程
    private final Thread workerThread;
    // 状态  0 - init, 1 - started, 2 - shut down
    public static final int WORKER_STATE_INIT = 0;
    public static final int WORKER_STATE_STARTED = 1;
    public static final int WORKER_STATE_SHUTDOWN = 2;
    @SuppressWarnings({"unused", "FieldMayBeFinal"})
    private volatile int workerState; // 0 - init, 1 - started, 2 - shut down
    // 走一个 bucket 需要花费的纳秒时长
    private final long tickDuration;
    // bucket 数组，用于存储任务，即 HashedWheelTimeout 实例们
    private final HashedWheelBucket[] wheel;
    // 掩码，用于与运算 ，计算属于 wheel 哪个下标
    private final int mask;
    // 调用 newTimeout 方法线程等待工作线程 workerThread 开启执行任务
    private final CountDownLatch startTimeInitialized = new CountDownLatch(1);
    //HashedWheelTimeout 任务队列，这里使用的优先级队列是一个 MPSC（Multiple Producer Single Consumer）的队列，
    //刚好适用于这里的多生产线程，单消费线程的场景。
    //提交的任务会先进入到该队列中，每次 tick 才会将队列中的任务（一次最多 10 万个）加入到 bucket 中的链表里
    private final Queue<HashedWheelTimeout> timeouts = PlatformDependent.newMpscQueue();
    // HashedWheelTimeout 任务取消队列
    // 取消的任务会加入到该队列中，此次 tick 会将该队列中的任务从 bucket 中移除
    private final Queue<HashedWheelTimeout> cancelledTimeouts = PlatformDependent.newMpscQueue();
    // 时间轮中处于等待执行的任务数
    private final AtomicLong pendingTimeouts = new AtomicLong(0);
    // 允许最大的等待任务数
    private final long maxPendingTimeouts;
    private final Executor taskExecutor;
    private long currRound;
    // 工作线程启动时间，作为时间轮的基准时间
    private volatile long startTime;

    /**
     * Creates a new timer with the default thread factory
     * ({@link Executors#defaultThreadFactory()}), default tick duration, and
     * default number of ticks per wheel.
     */
    public HashedWheelTimer() {
        this(Executors.defaultThreadFactory());
    }

    /**
     * Creates a new timer with the default thread factory
     * ({@link Executors#defaultThreadFactory()}) and default number of ticks
     * per wheel.
     *
     * @param tickDuration the duration between tick
     * @param unit         the time unit of the {@code tickDuration}
     * @throws NullPointerException     if {@code unit} is {@code null}
     * @throws IllegalArgumentException if {@code tickDuration} is &lt;= 0
     */
    public HashedWheelTimer(long tickDuration, TimeUnit unit) {
        this(Executors.defaultThreadFactory(), tickDuration, unit);
    }

    /**
     * Creates a new timer with the default thread factory
     * ({@link Executors#defaultThreadFactory()}).
     *
     * @param tickDuration  the duration between tick
     * @param unit          the time unit of the {@code tickDuration}
     * @param ticksPerWheel the size of the wheel
     * @throws NullPointerException     if {@code unit} is {@code null}
     * @throws IllegalArgumentException if either of {@code tickDuration} and {@code ticksPerWheel} is &lt;= 0
     */
    public HashedWheelTimer(long tickDuration, TimeUnit unit, int ticksPerWheel) {
        this(Executors.defaultThreadFactory(), tickDuration, unit, ticksPerWheel);
    }

    /**
     * Creates a new timer with the default tick duration and default number of
     * ticks per wheel.
     *
     * @param threadFactory a {@link ThreadFactory} that creates a
     *                      background {@link Thread} which is dedicated to
     *                      {@link TimerTask} execution.
     * @throws NullPointerException if {@code threadFactory} is {@code null}
     */
    public HashedWheelTimer(ThreadFactory threadFactory) {
        this(threadFactory, 100, TimeUnit.MILLISECONDS);
    }

    /**
     * Creates a new timer with the default number of ticks per wheel.
     *
     * @param threadFactory a {@link ThreadFactory} that creates a
     *                      background {@link Thread} which is dedicated to
     *                      {@link TimerTask} execution.
     * @param tickDuration  the duration between tick
     * @param unit          the time unit of the {@code tickDuration}
     * @throws NullPointerException     if either of {@code threadFactory} and {@code unit} is {@code null}
     * @throws IllegalArgumentException if {@code tickDuration} is &lt;= 0
     */
    public HashedWheelTimer(
            ThreadFactory threadFactory, long tickDuration, TimeUnit unit) {
        this(threadFactory, tickDuration, unit, 512);
    }

    /**
     * Creates a new timer.
     *
     * @param threadFactory a {@link ThreadFactory} that creates a
     *                      background {@link Thread} which is dedicated to
     *                      {@link TimerTask} execution.
     * @param tickDuration  the duration between tick
     * @param unit          the time unit of the {@code tickDuration}
     * @param ticksPerWheel the size of the wheel
     * @throws NullPointerException     if either of {@code threadFactory} and {@code unit} is {@code null}
     * @throws IllegalArgumentException if either of {@code tickDuration} and {@code ticksPerWheel} is &lt;= 0
     */
    public HashedWheelTimer(
            ThreadFactory threadFactory,
            long tickDuration, TimeUnit unit, int ticksPerWheel) {
        this(threadFactory, tickDuration, unit, ticksPerWheel, true);
    }

    /**
     * Creates a new timer.
     *
     * @param threadFactory a {@link ThreadFactory} that creates a
     *                      background {@link Thread} which is dedicated to
     *                      {@link TimerTask} execution.
     * @param tickDuration  the duration between tick
     * @param unit          the time unit of the {@code tickDuration}
     * @param ticksPerWheel the size of the wheel
     * @param leakDetection {@code true} if leak detection should be enabled always,
     *                      if false it will only be enabled if the worker thread is not
     *                      a daemon thread.
     * @throws NullPointerException     if either of {@code threadFactory} and {@code unit} is {@code null}
     * @throws IllegalArgumentException if either of {@code tickDuration} and {@code ticksPerWheel} is &lt;= 0
     */
    public HashedWheelTimer(
            ThreadFactory threadFactory,
            long tickDuration, TimeUnit unit, int ticksPerWheel, boolean leakDetection) {
        this(threadFactory, tickDuration, unit, ticksPerWheel, leakDetection, -1);
    }

    /**
     * Creates a new timer.
     *
     * @param threadFactory        线程工厂，用于创建执行 TimerTask 任务的工作线程
     * @param tickDuration         定义了一格的时间长度，默认的就是 100ms
     * @param unit                 定义了一格的时间单位，默认的就是 ms
     * @param ticksPerWheel        定义了一圈有多少格，默认的就是 512
     * @param leakDetection        用于追踪内存泄漏
     * @param  maxPendingTimeouts  最大允许等待的 Timeout 实例数，也就是我们可以设置不允许太多的任务等待。
     *                             如果未执行任务数达到阈值，那么再次提交任务会抛出 RejectedExecutionException 异常，默认不限制
     * @throws NullPointerException     if either of {@code threadFactory} and {@code unit} is {@code null}
     * @throws IllegalArgumentException if either of {@code tickDuration} and {@code ticksPerWheel} is &lt;= 0
     */
    public HashedWheelTimer(
            ThreadFactory threadFactory,
            long tickDuration, TimeUnit unit, int ticksPerWheel, boolean leakDetection,
            long maxPendingTimeouts) {
        this(threadFactory, tickDuration, unit, ticksPerWheel, leakDetection,
                maxPendingTimeouts, ImmediateExecutor.INSTANCE);
    }
    /**
     * Creates a new timer.
     * HashedWheelTimer 完成了初始化，初始化了时间轮数组 HashedWheelBucket[]，
     * 稍微看一下内部类 HashedWheelBucket，可以看到它是一个链表的结构。这个很好理解，因为每一格可能有多个任务。
     * @param threadFactory        a {@link ThreadFactory} that creates a
     *                             background {@link Thread} which is dedicated to
     *                             {@link TimerTask} execution.
     * @param tickDuration         the duration between tick
     * @param unit                 the time unit of the {@code tickDuration}
     * @param ticksPerWheel        the size of the wheel
     * @param leakDetection        {@code true} if leak detection should be enabled always,
     *                             if false it will only be enabled if the worker thread is not
     *                             a daemon thread.
     * @param maxPendingTimeouts   The maximum number of pending timeouts after which call to
     *                             {@code newTimeout} will result in
     *                             {@link java.util.concurrent.RejectedExecutionException}
     *                             being thrown. No maximum pending timeouts limit is assumed if
     *                             this value is 0 or negative.
     * @param taskExecutor         The {@link Executor} that is used to execute the submitted {@link TimerTask}s.
     *                             The caller is responsible to shutdown the {@link Executor} once it is not needed
     *                             anymore.
     * @throws NullPointerException     if either of {@code threadFactory} and {@code unit} is {@code null}
     * @throws IllegalArgumentException if either of {@code tickDuration} and {@code ticksPerWheel} is &lt;= 0
     */
    public HashedWheelTimer(
            ThreadFactory threadFactory,
            long tickDuration, TimeUnit unit, int ticksPerWheel, boolean leakDetection,
            long maxPendingTimeouts, Executor taskExecutor) {

        //参数检查
        checkNotNull(threadFactory, "threadFactory");
        checkNotNull(unit, "unit");
        checkPositive(tickDuration, "tickDuration");
        checkPositive(ticksPerWheel, "ticksPerWheel");
        this.taskExecutor = checkNotNull(taskExecutor, "taskExecutor");

        // 创建时间轮 bucket 结构，这里做了向上"取整"，保持数组长度为 2 的 n 次方
        wheel = createWheel(ticksPerWheel);
        // 掩码，bucket - 1，用来做取模，计算任务应该放到哪个 bucket 中
        // HashMap 在进行 hash 之后，进行index的hash寻址寻址的算法也是和这个一样的
        mask = wheel.length - 1;

        // 100ms 转换为纳秒 100*10^6
        long duration = unit.toNanos(tickDuration);

        // Prevent overflow.
        // 防止溢出
        if (duration >= Long.MAX_VALUE / wheel.length) {
            throw new IllegalArgumentException(String.format(
                    "tickDuration: %d (expected: 0 < tickDuration in nanos < %d",
                    tickDuration, Long.MAX_VALUE / wheel.length));
        }
        // 延迟时间不能小于 1 毫秒
        if (duration < MILLISECOND_NANOS) {
            logger.warn("Configured tickDuration {} smaller than {}, using 1ms.",
                        tickDuration, MILLISECOND_NANOS);
            this.tickDuration = MILLISECOND_NANOS;
        } else {
            this.tickDuration = duration;
        }
        // 创建工作线程，这里没有启动线程。后面会看到，在第一次提交任务的时候会启动线程
        workerThread = threadFactory.newThread(worker);

        // 追踪内存泄露
        leak = leakDetection || !workerThread.isDaemon() ? leakDetector.track(this) : null;

        // 赋值最大允许等待任务数
        this.maxPendingTimeouts = maxPendingTimeouts;

        // 如果超过 64 个 HashedWheelTimer 实例，它会打印错误日志提醒你
        // 因为时间轮是一个非常耗费资源的结构，所以一个 jvm 中的实例数目不能太高
        if (INSTANCE_COUNTER.incrementAndGet() > INSTANCE_COUNT_LIMIT &&
            WARNED_TOO_MANY_INSTANCES.compareAndSet(false, true)) {
            reportTooManyInstances();
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            super.finalize();
        } finally {
            // This object is going to be GCed and it is assumed the ship has sailed to do a proper shutdown. If
            // we have not yet shutdown then we want to make sure we decrement the active instance count.
            if (WORKER_STATE_UPDATER.getAndSet(this, WORKER_STATE_SHUTDOWN) != WORKER_STATE_SHUTDOWN) {
                INSTANCE_COUNTER.decrementAndGet();
            }
        }
    }

    private static HashedWheelBucket[] createWheel(int ticksPerWheel) {
        //每轮的格子数不能超过2^30
        checkInRange(ticksPerWheel, 1, 1073741824, "ticksPerWheel");
        //标准化时间轮大小，向上取整，达到 2 的 n 次方
        ticksPerWheel = normalizeTicksPerWheel(ticksPerWheel);
        // 创建 HashedWheelBucket 数组
        HashedWheelBucket[] wheel = new HashedWheelBucket[ticksPerWheel];
        for (int i = 0; i < wheel.length; i ++) {
            wheel[i] = new HashedWheelBucket();
        }
        return wheel;
    }

    private static int normalizeTicksPerWheel(int ticksPerWheel) {
        int normalizedTicksPerWheel = 1;
        // 取第一个大于 ticksPerWheel 的 2 的 n 次方的值
        while (normalizedTicksPerWheel < ticksPerWheel) {
            // 左移一位，即 扩大 2 倍
            normalizedTicksPerWheel <<= 1;
        }
        return normalizedTicksPerWheel;
    }

    /**
     * Starts the background thread explicitly.  The background thread will
     * start automatically on demand even if you did not call this method.
     * 启动工作线程
     * @throws IllegalStateException if this timer has been
     *                               {@linkplain #stop() stopped} already
     */
    public void start() {
        switch (WORKER_STATE_UPDATER.get(this)) {
            case WORKER_STATE_INIT:
                // 如果是初始化状态
                if (WORKER_STATE_UPDATER.compareAndSet(this, WORKER_STATE_INIT, WORKER_STATE_STARTED)) {
                    // 启动工作线程
                    workerThread.start();
                }
                break;
            case WORKER_STATE_STARTED:
                break;
            case WORKER_STATE_SHUTDOWN:
                throw new IllegalStateException("cannot be started once stopped");
            default:
                throw new Error("Invalid WorkerState");
        }

        // 阻塞等待，直到 startTime 被工作线程初始化
        while (startTime == 0) {
            try {
                startTimeInitialized.await();
            } catch (InterruptedException ignore) {
                // Ignore - it will be ready very soon.
            }
        }
    }

    @Override
    public Set<Timeout> stop() {
        // 工作线程不能停止时间轮
        if (Thread.currentThread() == workerThread) {
            throw new IllegalStateException(
                    HashedWheelTimer.class.getSimpleName() +
                            ".stop() cannot be called from " +
                            TimerTask.class.getSimpleName());
        }
        // 尝试 CAS 替换当前状态为 “停止：
        if (!WORKER_STATE_UPDATER.compareAndSet(this, WORKER_STATE_STARTED, WORKER_STATE_SHUTDOWN)) {
            // workerState can be 0 or 2 at this moment - let it always be 2.
            if (WORKER_STATE_UPDATER.getAndSet(this, WORKER_STATE_SHUTDOWN) != WORKER_STATE_SHUTDOWN) {
                INSTANCE_COUNTER.decrementAndGet();
                if (leak != null) {
                    boolean closed = leak.close(this);
                    assert closed;
                }
            }

            return Collections.emptySet();
        }

        try {
            // 中断 worker线程，尝试把正在进行任务的线程中断掉,如果某些任务正在执行则会抛出interrupt异常，并且任务会尝试立即中断
            boolean interrupted = false;
            while (workerThread.isAlive()) {
                workerThread.interrupt();
                try {
                    workerThread.join(100);
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            // 当前前程会等待stop的结果
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        } finally {
            INSTANCE_COUNTER.decrementAndGet();
            if (leak != null) {
                boolean closed = leak.close(this);
                assert closed;
            }
        }
        //返回未处理的任务
        return worker.unprocessedTimeouts();
    }

    /**
     * 提交任务，3个步骤：
     * 1 尝试启动工作线程 workerThread
     * 2 计算延时任务的触发时间，创建 HashedWheelTimeout 对象进一步封装任务对象
     * 3 将创建的 HashedWheelTimeout 对象加入到任务队列
     *
     * 时间轮在初始化后就可以接收业务方提交的延时任务请求了，任务的处理都是交给工作线程这个后台线程。
     * @param task  任务
     * @param delay 延时时间
     * @param unit  延迟时间单位
     * @return Timeout
     */
    @Override
    public Timeout newTimeout(TimerTask task, long delay, TimeUnit unit) {
        // 校验参数
        checkNotNull(task, "task");
        checkNotNull(unit, "unit");

        long pendingTimeoutsCount = pendingTimeouts.incrementAndGet();

        // 校验等待任务数是否达到阈值 maxPendingTimeouts
        if (maxPendingTimeouts > 0 && pendingTimeoutsCount > maxPendingTimeouts) {
            pendingTimeouts.decrementAndGet();
            throw new RejectedExecutionException("Number of pending timeouts ("
                + pendingTimeoutsCount + ") is greater than or equal to maximum allowed pending "
                + "timeouts (" + maxPendingTimeouts + ")");
        }

        // 如果工作线程没有启动，则启动工作线程。一般由第一个提交任务的线程负责工作线程的启动
        start();
        // 下面是将任务添加到队列中，该队列将在下一个 tick 时进行处理，
        // 在处理过程中，所有排队的 HashedWheelTimeout 将被添加到正确的 HashedWheelBucket 中

        // deadline 是一个相对时间，相对于 HashedWheelTimer 的启动时间
        long deadline = System.nanoTime() + unit.toNanos(delay) - startTime;

        // Guard against overflow.
        if (delay > 0 && deadline < 0) {
            deadline = Long.MAX_VALUE;
        }
        // 创建 HashedWheelTimeout 对象，进一步封装任务对象
        HashedWheelTimeout timeout = new HashedWheelTimeout(this, task, deadline);
        // 加入到 timeouts 队列中，等待被加入到 Bucket 中，注意此时，还没有加入到时间轮中
        timeouts.add(timeout);
        return timeout;
    }

    /**
     * Returns the number of pending timeouts of this {@link Timer}.
     */
    public long pendingTimeouts() {
        return pendingTimeouts.get();
    }

    private static void reportTooManyInstances() {
        if (logger.isErrorEnabled()) {
            String resourceType = simpleClassName(HashedWheelTimer.class);
            logger.error("You are creating too many " + resourceType + " instances. " +
                    resourceType + " is a shared resource that must be reused across the JVM, " +
                    "so that only a few instances are created.");
        }
    }

    //Worker 是工作线程的任务体，里面封装了时间轮任务触发和执行的逻辑。一旦工作线程启动后，就会不停地 “滴答” bucket ，直到时间轮关闭。
    private final class Worker implements Runnable {
        // 记录没有处理的时间任务
        private final Set<Timeout> unprocessedTimeouts = new HashSet<Timeout>();

        // 记录走了几个 bucket ，不拥堵的情况下每隔 tickDuration 时间走一个 bucket
        private long tick;

        //工作线程启动的第一步是初始化全局的 startTime，它将作为时间轮的基准时间，用来计算延时任务的触发时间。
        //并调用 countDown 方法来通知阻塞在 start 方法上的线程。
        //接着进入主循环中，循环中的行为是每隔一段时间（tickDuration）走一个 bucket
        @Override
        public void run() {

            // 初始化启动时间，在 HashedWheelTimer 中，用的都是相对时间，所以需要启动时间作为基准，并且要用 volatile 修饰
            startTime = System.nanoTime();
            if (startTime == 0) {
                // 因为 startTime = 0 作为工作线程未开始执行任务的标志。这里开始执行了，需要设置非 0
                startTime = 1;
            }
            // 第一个提交任务的线程在 start() 处等待，需要唤醒它
            startTimeInitialized.countDown();

            /*
             * do-while 执行任务逻辑：
             *
             * 工作线程是逐个 bucket 顺序处理的，所以即使有些任务执行时间超过了一次 tick 时间，也没关系，这些任务并不会被漏掉。
             * 但是可能被延迟执行，毕竟工作线程是单线程。
             */
            do {
                // 等待下次 tick 到来，理论上每次等待 tickDuration 就会返回，然后继续往下走
                final long deadline = waitForNextTick();
                if (deadline > 0) {
                    // 当前 tick 下 bucket 数组对应 index，即哪个 bucket
                    int idx = (int) (tick & mask);
                    if (idx == 0 && tick > 0) {
                        currRound ++;
                    }
                    // 处理已经取消的任务
                    processCancelledTasks();
                    // 获取当前 tick 对应的桶
                    HashedWheelBucket bucket =
                            wheel[idx];
                    // 将队列中所有的任务转移到相应的 buckets 中
                    transferTimeoutsToBuckets();
                    // 执行进入到这个 bucket 中的任务
                    bucket.expireTimeouts(deadline, currRound);
                    // 记录走了多少个 tick
                    tick++;
                }
            } while (WORKER_STATE_UPDATER.get(HashedWheelTimer.this) == WORKER_STATE_STARTED);

            /* 到这里，说明这个 timer 要关闭了，做一些清理工作 */
            // 将所有 bucket 中没有执行的任务，添加到 unprocessedTimeouts 这个 HashSet 中，
            // 主要目的是用于 stop() 方法返回
            for (HashedWheelBucket bucket: wheel) {
                // 将当前 bucket 上链表节点任务都加入到 unprocessedTimeouts
                bucket.clearTimeouts(unprocessedTimeouts);
            }
            // 将任务队列中的任务也添加到 unprocessedTimeouts 中
            for (;;) {
                HashedWheelTimeout timeout = timeouts.poll();
                if (timeout == null) {
                    break;
                }
                if (!timeout.isCancelled()) {
                    unprocessedTimeouts.add(timeout);
                }
            }
            // 处理已经取消的任务
            processCancelledTasks();
        }

        //将 HashedWheelTimeout 队列中的任务加入到相应的 bucket 中
        private void transferTimeoutsToBuckets() {
            // 限制每 tick 最大转移 10 万个 HashedWheelTimeout 到 bucket，以免阻塞工作线程
            // 如果有 100万 个，并且 tickDuration 时间为几分钟级别，那这种情况下就会有一批任务延迟。
            // 从侧面说明一个时间轮不能一下子添加特别多的任务
            for (int i = 0; i < 100000; i++) {
                HashedWheelTimeout timeout = timeouts.poll();
                if (timeout == null) {
                    // 没有任务了
                    break;
                }
                if (timeout.state() == HashedWheelTimeout.ST_CANCELLED) {
                    // 该任务刚刚被取消了
                    continue;
                }
                // 计算任务触发时间需要经过多少个 tick
                long calculated = timeout.deadline / tickDuration;
                // 计算任务所属的轮次
                timeout.execRound = calculated / wheel.length;

                // 如果任务在 timeouts 队列里面放久了, 以至于已经过了执行时间(calculated < tick),
                // 这个时候就使用当前 tick 对应的 bucket，从而让那些本应该在过去执行的任务在当前 tick 快速执行掉。
                // 此方法调用完后就会立即执行当前 tick 对应的 bucket 中的任务
                final long ticks = Math.max(calculated, tick);
                // 计算 ticks 对应 bucket
                int stopIndex = (int) (ticks & mask);

                HashedWheelBucket bucket = wheel[stopIndex];
                // 单个 bucket 是由 HashedWheelTimeout 实例组成的一个链表，单个线程不存在并发
                // 这里将 timeout 加入到 bucket 的链表中
                bucket.addTimeout(timeout);
            }
        }

        //处理已经取消的任务。将已经取消的任务从对应的 bucket 中移除
        private void processCancelledTasks() {
            // 遍历任务取消队列
            for (;;) {
                HashedWheelTimeout timeout = cancelledTimeouts.poll();
                if (timeout == null) {
                    // all processed
                    break;
                }
                try {
                    // 将 timeout 从对应的 bucket 中移除
                    // 通过 timeout 持有的 bukcet 进行的操作，即从bucket 链表中删除该 timeout
                    timeout.remove();
                } catch (Throwable t) {
                    if (logger.isWarnEnabled()) {
                        logger.warn("An exception was thrown while process a cancellation task", t);
                    }
                }
            }
        }

        /**
         * calculate goal nanoTime from startTime and current tick number,
         * then wait until that goal has been reached.
         * @return Long.MIN_VALUE if received a shutdown request,
         * current time otherwise (with Long.MIN_VALUE changed by +1)
         */
        /**
         * 下面这个方法大家多看几遍，注意它的返回值
         * 前面说过，我们用的都是相对时间，所以：
         *   第一次进来的时候，工作线程会在 100ms 的时候返回，返回值是 100*10^6
         *   第二次进来的时候，工作线程会在 200ms 的时候返回，依次类推
         * 另外就是注意极端情况，比如第二次进来的时候，由于被前面的任务阻塞，导致进来的时候就已经是 250ms，
         *   那么，一进入这个方法就要立即返回，返回值是 250ms，而不是 200ms
         * 剩下的自己看一下代码
         * 用来控制每隔一定的时间 “滴答” 一次即跳一个 bucket，此外还处理了因上一个 tick 处理任务时间过长问题，采用的是立即触发执行的方式。
         * 不难看出，当遇到较长时间执行的任务时，会打乱原本正常 tick 的节奏，导致其他任务延期执行。
         * tickDuration 控制着时间的精准度，值越小精准度越高，工作线程则越繁忙。
         */
        private long waitForNextTick() {
            // 计算当前 tick 下的 deadline，这值是确定的。即一次 tick 期限是一个固定值
            // 注意，这里就体现了时间轮的核心，理论上每隔 tickDuration 就会 "滴答" 一次
            long deadline = tickDuration * (tick + 1);
            // 嵌套在一个死循环里面，等待当前 tick 时间到达
            for (;;) {
                // 基于 startTime 计算距离当前时间的时间戳，该值的理论值认为等于 dealine，
                // 但由于任务执行时间没法控制，实际值一般大于 deadline
                // 注意，startTime 值是固定的，在工作线程启动就定了
                final long currentTime = System.nanoTime() - startTime;
                // 判断是否可以进行 tick
                // 标准是：tick 触发的时间值 - currentTime <= 0，没有到触发时间则休眠 sleepTimeMs 毫秒
                // 这里加 999999 是补偿精度，不足 1ms 的补足 1ms
                long sleepTimeMs = (deadline - currentTime + 999999) / 1000000;

                // 因为每次执行任务消耗的时间是不受控制的，因此计算出来的 sleepTimeMs 可能为负数
                // 当为负数时，说明前面的任务执行时间过长，导致本该 tick 的时候错过了。这个时候不需要休眠等待，需要立刻处理
                if (sleepTimeMs <= 0) {
                    if (currentTime == Long.MIN_VALUE) {
                        return -Long.MAX_VALUE;
                    } else {
                        // 返回值是基于 startTime 计算的距离当前时间的时间戳
                        return currentTime;
                    }
                }
                // windows 平台特别处理。先除以10再乘以10，是因为windows平台下最小调度单位是10ms，
                // 如果不处理成10ms的倍数，可能导致sleep更不准了
                // See https://github.com/netty/netty/issues/356
                if (PlatformDependent.isWindows()) {
                    sleepTimeMs = sleepTimeMs / 10 * 10;
                    if (sleepTimeMs == 0) {
                        sleepTimeMs = 1;
                    }
                }

                try {
                    // 没有到 tick 时间，则休眠
                    Thread.sleep(sleepTimeMs);
                } catch (InterruptedException ignored) {
                    // 如果 timer 已经 shutdown，那么返回 Long.MIN_VALUE
                    if (WORKER_STATE_UPDATER.get(HashedWheelTimer.this) == WORKER_STATE_SHUTDOWN) {
                        return Long.MIN_VALUE;
                    }
                }
            }
        }

        public Set<Timeout> unprocessedTimeouts() {
            return Collections.unmodifiableSet(unprocessedTimeouts);
        }
    }

    private static final class HashedWheelTimeout implements Timeout, Runnable {
        // 初始化
        private static final int ST_INIT = 0;
        // 取消
        private static final int ST_CANCELLED = 1;
        // 到期
        private static final int ST_EXPIRED = 2;
        // 用CAS方式更新任务状态
        private static final AtomicIntegerFieldUpdater<HashedWheelTimeout> STATE_UPDATER =
                AtomicIntegerFieldUpdater.newUpdater(HashedWheelTimeout.class, "state");

        private final HashedWheelTimer timer;
        private final TimerTask task;
        /**
         * 任务触发时间
         */
        private final long deadline;

        @SuppressWarnings({"unused", "FieldMayBeFinal", "RedundantFieldInitialization" })
        private volatile int state = ST_INIT;

        // 离任务执行的轮数，0 表示当前轮次执行。当任务从队列加入 bucket 时会计算这个值。
        // 对与轮次非 0 的任务，那么时间轮执行到对应的 bucket 时会将该任务的该属性值 -1
        long execRound;

        // 这将用于通过双向链表在 hashhedwheeltimerbucket 中链接超时 的前后指针
        HashedWheelTimeout next;
        HashedWheelTimeout prev;

        // 当前 HashedWheelTimeout 所在的 bucket
        HashedWheelBucket bucket;

        HashedWheelTimeout(HashedWheelTimer timer, TimerTask task, long deadline) {
            this.timer = timer;
            this.task = task;
            this.deadline = deadline;
        }

        @Override
        public Timer timer() {
            return timer;
        }

        @Override
        public TimerTask task() {
            return task;
        }

        @Override
        public boolean cancel() {
            // only update the state it will be removed from HashedWheelBucket on next tick.
            // 这里只是修改状态为取消，实际会在下次tick的时候移除
            if (!compareAndSetState(ST_INIT, ST_CANCELLED)) {
                return false;
            }
            // If a task should be canceled we put this to another queue which will be processed on each tick.
            // So this means that we will have a GC latency of max. 1 tick duration which is good enough. This way
            // we can make again use of our MpscLinkedQueue and so minimize the locking / overhead as much as possible.
            // 加入到时间轮的全局待取消队列，并在每次tick的时候，从相应 bucket 中移除。
            timer.cancelledTimeouts.add(this);
            return true;
        }

        //将当前 Timeout 从对应的 bucket 链表中移除
        void remove() {
            HashedWheelBucket bucket = this.bucket;
            if (bucket != null) {
                bucket.remove(this);
            } else {
                timer.pendingTimeouts.decrementAndGet();
            }
        }

        public boolean compareAndSetState(int expected, int state) {
            return STATE_UPDATER.compareAndSet(this, expected, state);
        }

        public int state() {
            return state;
        }

        @Override
        public boolean isCancelled() {
            return state() == ST_CANCELLED;
        }

        @Override
        public boolean isExpired() {
            return state() == ST_EXPIRED;
        }

        /**
         * 到期并执行任务
         * 执行任务本质上是调用 HashedWheelTimeout 对象中封装的 TimerTask 对象的 run 方法。
         */
        public void expire() {
            if (!compareAndSetState(ST_INIT, ST_EXPIRED)) {
                return;
            }

            try {
                // 执行 TimerTask.run 方法
                timer.taskExecutor.execute(this);
            } catch (Throwable t) {
                if (logger.isWarnEnabled()) {
                    logger.warn("An exception was thrown while submit " + TimerTask.class.getSimpleName()
                            + " for execution.", t);
                }
            }
        }

        @Override
        public void run() {
            try {
                task.run(this);
            } catch (Throwable t) {
                if (logger.isWarnEnabled()) {
                    logger.warn("An exception was thrown by " + TimerTask.class.getSimpleName() + '.', t);
                }
            }
        }

        @Override
        public String toString() {
            final long currentTime = System.nanoTime();
            long remaining = deadline - currentTime + timer.startTime;

            StringBuilder buf = new StringBuilder(192)
               .append(simpleClassName(this))
               .append('(')
               .append("deadline: ");
            if (remaining > 0) {
                buf.append(remaining)
                   .append(" ns later");
            } else if (remaining < 0) {
                buf.append(-remaining)
                   .append(" ns ago");
            } else {
                buf.append("now");
            }

            if (isCancelled()) {
                buf.append(", cancelled");
            }

            return buf.append(", task: ")
                      .append(task())
                      .append(')')
                      .toString();
        }
    }

    /**
     * Bucket that stores HashedWheelTimeouts. These are stored in a linked-list like datastructure to allow easy
     * removal of HashedWheelTimeouts in the middle. Also the HashedWheelTimeout act as nodes themself and so no
     * extra object creation is needed.
     */
    private static final class HashedWheelBucket {
        // Used for the linked-list datastructure
        // 头指针
        private HashedWheelTimeout head;
        // 尾指针
        private HashedWheelTimeout tail;

        /**
         * Add {@link HashedWheelTimeout} to this bucket.
         * 添加 HashedWheelTimeout 到 当前 bucket 中，即加入到链中
         */
        public void addTimeout(HashedWheelTimeout timeout) {
            assert timeout.bucket == null;
            // 设置 timeout 的桶，即聚合桶对象
            timeout.bucket = this;
            // 维护桶中的 HashedWheelTimeout
            if (head == null) {
                head = tail = timeout;
            } else {
                tail.next = timeout;
                timeout.prev = tail;
                tail = timeout;
            }
        }

        /**
         * 这里会执行这个 bucket 中，轮次为 0 的任务，也就是到期的任务。
         * 这个方法的入参 deadline 其实没什么用，因为轮次为 0 的都是应该被执行的。
         */
        public void expireTimeouts(long deadline, long currRound) {
            // 获取时间任务链表的头
            HashedWheelTimeout timeout = head;

            // 处理链表上的所有 timeout 实例
            while (timeout != null) {
                HashedWheelTimeout next = timeout.next;
                // 尝试执行任务
                if (timeout.execRound <= currRound) {
                    // 调整当前 bucket 的任务链表
                    next = remove(timeout);
                    // 到达触发时间
                    if (timeout.deadline <= deadline) {
                        // 这行代码负责执行具体的任务，即执行 timeout 中的 TimerTask.run 方法
                        timeout.expire();
                    } else {
                        // 这里的代码注释也说，不可能进入到这个分支
                        // The timeout was placed into a wrong slot. This should never happen.
                        throw new IllegalStateException(String.format(
                                "timeout.deadline (%d) > deadline (%d)", timeout.deadline, deadline));
                    }
                } else if (timeout.isCancelled()) {
                    // 任务被取消了
                    next = remove(timeout);
                } else {
                    break;
                }
                // 处理下个任务
                timeout = next;
            }
        }

        //将 timeout 从链表中移除
        public HashedWheelTimeout remove(HashedWheelTimeout timeout) {
            HashedWheelTimeout next = timeout.next;
            // remove timeout that was either processed or cancelled by updating the linked-list
            if (timeout.prev != null) {
                timeout.prev.next = next;
            }
            if (timeout.next != null) {
                timeout.next.prev = timeout.prev;
            }

            if (timeout == head) {
                // if timeout is also the tail we need to adjust the entry too
                if (timeout == tail) {
                    tail = null;
                    head = null;
                } else {
                    head = next;
                }
            } else if (timeout == tail) {
                // if the timeout is the tail modify the tail to be the prev node.
                tail = timeout.prev;
            }
            // null out prev, next and bucket to allow for GC.
            timeout.prev = null;
            timeout.next = null;
            timeout.bucket = null;
            // timeout 对应的 timer 的等待任务数减 1
            timeout.timer.pendingTimeouts.decrementAndGet();
            return next;
        }

        /**
         * Clear this bucket and return all not expired / cancelled {@link Timeout}s.
         */
        public void clearTimeouts(Set<Timeout> set) {
            for (;;) {
                HashedWheelTimeout timeout = pollTimeout();
                if (timeout == null) {
                    return;
                }
                if (timeout.isExpired() || timeout.isCancelled()) {
                    continue;
                }
                set.add(timeout);
            }
        }

        private HashedWheelTimeout pollTimeout() {
            HashedWheelTimeout head = this.head;
            if (head == null) {
                return null;
            }
            HashedWheelTimeout next = head.next;
            if (next == null) {
                tail = this.head =  null;
            } else {
                this.head = next;
                next.prev = null;
            }

            // null out prev and next to allow for GC.
            head.next = null;
            head.prev = null;
            head.bucket = null;
            return head;
        }
    }
}
