/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.nacos.common.notify;

import com.alibaba.nacos.common.notify.listener.Subscriber;
import com.alibaba.nacos.common.utils.CollectionUtils;
import com.alibaba.nacos.common.utils.ConcurrentHashSet;
import com.alibaba.nacos.common.utils.ThreadUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import static com.alibaba.nacos.common.notify.NotifyCenter.ringBufferSize;

/**
 * The default event publisher implementation.
 *
 * <p>Internally, use {@link ArrayBlockingQueue <Event/>} as a message staging queue.
 *
 * @author <a href="mailto:liaochuntao@live.com">liaochuntao</a>
 * @author zongtanghu
 */
public class DefaultPublisher extends Thread implements EventPublisher {

    protected static final Logger LOGGER = LoggerFactory.getLogger(NotifyCenter.class);

    private volatile boolean initialized = false;

    private volatile boolean shutdown = false;

    private Class<? extends Event> eventType;

    // 事件发布者的订阅者ConcurrentHashSet
    protected final ConcurrentHashSet<Subscriber> subscribers = new ConcurrentHashSet<>();

    private int queueMaxSize = -1;

    private BlockingQueue<Event> queue;

    protected volatile Long lastEventSequence = -1L;

    private static final AtomicReferenceFieldUpdater<DefaultPublisher, Long> UPDATER = AtomicReferenceFieldUpdater
            .newUpdater(DefaultPublisher.class, Long.class, "lastEventSequence");

    @Override
    public void init(Class<? extends Event> type, int bufferSize) {
        setDaemon(true);
        setName("nacos.publisher-" + type.getName());
        this.eventType = type;
        this.queueMaxSize = bufferSize;
        this.queue = new ArrayBlockingQueue<>(bufferSize);
//        this.setDaemon(true); how use? TODO
        start();
    }

    public ConcurrentHashSet<Subscriber> getSubscribers() {
        return subscribers;
    }

    @Override
    public synchronized void start() {
        if (!initialized) {
            // start just called once
            super.start();
            if (queueMaxSize == -1) {
                queueMaxSize = ringBufferSize;
            }
            // 打开开关
            initialized = true;
        }
    }

    @Override
    public long currentEventSize() {
        return queue.size();
    }

    @Override
    public void run() {
        System.out.println("=========> DefaultEventPublisher start running ~ this thread name : " + Thread.currentThread().getName());
//        =========> DefaultEventPublisher start running ~ this thread name : nacos.publisher-com.alibaba.nacos.common.event.ServerConfigChangeEvent
//        =========> DefaultEventPublisher start running ~ this thread name : nacos.publisher-com.alibaba.nacos.core.cluster.MembersChangeEvent
//        =========> DefaultEventPublisher start running ~ this thread name : nacos.publisher-com.alibaba.nacos.naming.consistency.ValueChangeEvent
//        =========> DefaultEventPublisher start running ~ this thread name : nacos.publisher-com.alibaba.nacos.naming.core.v2.upgrade.UpgradeStates$UpgradeStateChangedEvent
//        =========> DefaultEventPublisher start running ~ this thread name : nacos.publisher-com.alibaba.nacos.core.remote.event.ConnectionLimitRuleChangeEvent
//        =========> DefaultEventPublisher start running ~ this thread name : nacos.publisher-com.alibaba.nacos.config.server.model.event.LocalDataChangeEvent
//        =========> DefaultEventPublisher start running ~ this thread name : nacos.publisher-com.alibaba.nacos.core.remote.control.TpsControlRuleChangeEvent
//        =========> DefaultEventPublisher start running ~ this thread name : nacos.publisher-com.alibaba.nacos.config.server.model.event.ConfigDataChangeEvent
        openEventHandler();
    }

    void openEventHandler() {
        try {
            // 定义该变量是为了解决队列中消息积压的问题
            // This variable is defined to resolve the problem which message overstock in the queue.
            int waitTimes = 60;
            // 为确保消息不丢失，启用 EventHandler 时 ，等待第一个订阅者注册
            // To ensure that messages are not lost, enable EventHandler when
            // waiting for the first Subscriber to register
            for (; ; ) {
                if (shutdown || hasSubscriber() || waitTimes <= 0) {
                    break;
                }
                ThreadUtils.sleep(1000L);
                // 即此处会等待60s
                waitTimes--;
            }

            for (; ; ) {
                if (shutdown) {
                    break;
                }
                // 从队列中取出事件
                final Event event = queue.take();
                // 接收时间.. 进行处理、
                receiveEvent(event);
                UPDATER.compareAndSet(this, lastEventSequence, Math.max(lastEventSequence, event.sequence()));
            }
        } catch (Throwable ex) {
            LOGGER.error("Event listener exception : ", ex);
        }
    }

    private boolean hasSubscriber() {
        return CollectionUtils.isNotEmpty(subscribers);
    }

    @Override
    public void addSubscriber(Subscriber subscriber) {
        subscribers.add(subscriber);
    }

    @Override
    public void removeSubscriber(Subscriber subscriber) {
        subscribers.remove(subscriber);
    }

    @Override
    public boolean publish(Event event) {
        // 校验发布者是否开启
        checkIsStart();
        // BlockingQueue
        // 通过阻塞队列发布该慢事件 offer 加入阻塞队列，如果可以容纳则反true，反之
        boolean success = this.queue.offer(event);
        if (!success) {
            LOGGER.warn("Unable to plug in due to interruption, synchronize sending time, event : {}", event);
            receiveEvent(event);
            return true;
        }
        return true;
    }

    void checkIsStart() {
        if (!initialized) {
            throw new IllegalStateException("Publisher does not start");
        }
    }

    @Override
    public void shutdown() {
        this.shutdown = true;
        this.queue.clear();
    }

    public boolean isInitialized() {
        return initialized;
    }

    /**
     * 接收并通知订阅者来处理事件
     * Receive and notifySubscriber to process the event.
     *
     * @param event {@link Event}.
     */
    void receiveEvent(Event event) {
        final long currentEventSequence = event.sequence();

        if (!hasSubscriber()) {
            LOGGER.warn("[NotifyCenter] the {} is lost, because there is no subscriber.", event);
            return;
        }

        // Notification single event listener 通知单事件监听器
        for (Subscriber subscriber : subscribers) {
            // Whether to ignore expiration events 是否忽略过期事件
            if (subscriber.ignoreExpireEvent() && lastEventSequence > currentEventSequence) {
                LOGGER.debug("[NotifyCenter] the {} is unacceptable to this subscriber, because had expire",
                        event.getClass());
                continue;
            }

            // Because unifying smartSubscriber and subscriber, so here need to think of compatibility.
            // Remove original judge part of codes.
            notifySubscriber(subscriber, event);
        }
    }

    @Override
    public void notifySubscriber(final Subscriber subscriber, final Event event) {

        LOGGER.debug("[NotifyCenter] the {} will received by {}", event, subscriber);

        final Runnable job = () -> subscriber.onEvent(event);

        final Executor executor = subscriber.executor();

        if (executor != null) {
            executor.execute(job);
        } else {
            try {
                job.run();
            } catch (Throwable e) {
                LOGGER.error("Event callback exception: ", e);
            }
        }
    }
}
