/*
 * Copyright 1999-2020 Alibaba Group Holding Ltd.
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

package com.alibaba.nacos.naming.core.v2.event.publisher;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.common.notify.Event;
import com.alibaba.nacos.common.notify.ShardedEventPublisher;
import com.alibaba.nacos.common.notify.listener.Subscriber;
import com.alibaba.nacos.common.utils.ThreadUtils;
import com.alibaba.nacos.naming.misc.Loggers;
import com.alipay.sofa.jraft.util.concurrent.ConcurrentHashSet;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * 用于命名事件的事件发布者。
 * Event publisher for naming event.
 *
 * @author xiweng.yy
 */
public class NamingEventPublisher extends Thread implements ShardedEventPublisher {

    private static final String THREAD_NAME = "naming.publisher-";

    private static final int DEFAULT_WAIT_TIME = 60;

    // 我的理解是 key是事件 value应该是该事件的订阅者集合，即向这些订阅者（本质也是事件，因为实现了事件接口）进行广播事件信息
    private final Map<Class<? extends Event>, Set<Subscriber<? extends Event>>> subscribes = new ConcurrentHashMap<>();

    private volatile boolean initialized = false;

    private volatile boolean shutdown = false;

    private int queueMaxSize = -1;

    private BlockingQueue<Event> queue;

    private String publisherName;

    @Override
    public void init(Class<? extends Event> type, int bufferSize) {
        this.queueMaxSize = bufferSize;
        this.queue = new ArrayBlockingQueue<>(bufferSize);
        this.publisherName = type.getSimpleName();
        super.setName(THREAD_NAME + this.publisherName);
        super.setDaemon(true);
        super.start();
        initialized = true;
        System.out.println("publisherName============="+publisherName);
        // ClientOperationEvent
        // MetadataEvent
        // ServiceEvent
        // ClientEvent
    }

    @Override
    public long currentEventSize() {
        return this.queue.size();
    }

    @Override
    public void addSubscriber(Subscriber subscriber) {
        addSubscriber(subscriber, subscriber.subscribeType());
    }

    @Override
    public void addSubscriber(Subscriber subscriber, Class<? extends Event> subscribeType) {
        subscribes.computeIfAbsent(subscribeType, inputType -> new ConcurrentHashSet<>());
        subscribes.get(subscribeType).add(subscriber);
    }

    @Override
    public void removeSubscriber(Subscriber subscriber) {
        removeSubscriber(subscriber, subscriber.subscribeType());
    }

    @Override
    public void removeSubscriber(Subscriber subscriber, Class<? extends Event> subscribeType) {
        subscribes.computeIfPresent(subscribeType, (inputType, subscribers) -> {
            subscribers.remove(subscriber);
            return subscribers.isEmpty() ? null : subscribers;
        });
    }

    @Override
    public boolean publish(Event event) {
        checkIsStart();
        // 入队 成功则true 反之
        // 将事件放到阻塞队列
        boolean success = this.queue.offer(event);
        System.out.println("-------->添加queue事件" + event);
        if (!success) {
            Loggers.EVT_LOG.warn("unable to plug in due to interruption, synchronize sending time, event : {}", event);
            handleEvent(event);
            return true;
        }
        return true;
    }

    // 向订阅者发送通知的落地
    /** demo:
     * {@link com.alibaba.nacos.naming.core.v2.index.ClientServiceIndexesManager#onEvent} 客户端操作时间，like 注册 注销 可以看这个里
     * @param subscriber {@link Subscriber}
     * @param event      {@link Event}
     */
    @Override
    public void notifySubscriber(Subscriber subscriber, Event event) {
        if (Loggers.EVT_LOG.isDebugEnabled()) {
            Loggers.EVT_LOG.debug("[NotifyCenter] the {} will received by {}", event, subscriber);
        }
        // 打印的clz 可link到onevent的回调处
        System.out.println("subscriber 执行 onEvent: 【" + subscriber.getClass() + "】");
        // 执行订阅者们的onEvent方法，即每个订阅者对于该事件的处理逻辑
        final Runnable job = () -> subscriber.onEvent(event);
        // 判断当前事件是异步还是同步
        final Executor executor = subscriber.executor();
        if (executor != null) {
            // 异步
            executor.execute(job);
        } else {
            try {
                // 同步
                job.run();
            } catch (Throwable e) {
                Loggers.EVT_LOG.error("Event callback exception: ", e);
            }
        }
    }

    @Override
    public void shutdown() throws NacosException {
        this.shutdown = true;
        this.queue.clear();
    }

    // publish
    // 项目在启动的时候，该线程会在 shutdown = false 的情况下 死循环取消息进行发送
    @Override
    public void run() {
        try {

            System.out.println("=========> NamingEventPublisher start running ~ this thread name : " + Thread.currentThread().getName());
            // 项目启动的时候打印了四次。。
            //=========> NamingEventPublisher start running ~ this thread name : naming.publisher-ClientEvent
            //=========> NamingEventPublisher start running ~ this thread name : naming.publisher-ClientOperationEvent
            //=========> NamingEventPublisher start running ~ this thread name : naming.publisher-MetadataEvent
            //=========> NamingEventPublisher start running ~ this thread name : naming.publisher-ServiceEvent
            // 可以理解为项目启动的时候，nacos启动了四个线程（关于事件发布这块），分别为上述blabula
            // 每个线程本地均保留了一份 阻塞队列queue 然后一个死循环进行take操作，来对事件进行发布

            // 等待订阅者进行初始化
            waitSubscriberForInit();
            handleEvents();
        } catch (Exception e) {
            Loggers.EVT_LOG.error("Naming Event Publisher {}, stop to handle event due to unexpected exception: ",
                    this.publisherName, e);
        }
    }

    private void waitSubscriberForInit() {
        // To ensure that messages are not lost, enable EventHandler when
        // waiting for the first Subscriber to register
        for (int waitTimes = DEFAULT_WAIT_TIME; waitTimes > 0; waitTimes--) {
            if (shutdown || !subscribes.isEmpty()) {
                break;
            }
            ThreadUtils.sleep(1000L);
        }
    }

    private void handleEvents() {
        while (!shutdown) {
            try {
                // 取出事件
                final Event event = queue.take();
                System.out.println("---------------->>取出queue事件：" + event);
                // 事件处理
                handleEvent(event);
            } catch (InterruptedException e) {
                Loggers.EVT_LOG.warn("Naming Event Publisher {} take event from queue failed:", this.publisherName, e);
            }
        }
    }

    private void handleEvent(Event event) {
        Class<? extends Event> eventType = event.getClass();
        // 拿到当前事件订阅者的集合？
        Set<Subscriber<? extends Event>> subscribers = subscribes.get(eventType);
        if (null == subscribers) {
            if (Loggers.EVT_LOG.isDebugEnabled()) {
                Loggers.EVT_LOG.debug("[NotifyCenter] No subscribers for slow event {}", eventType.getName());
            }
            return;
        }
        // 给每个订阅者发送通知
        for (Subscriber subscriber : subscribers) {
            notifySubscriber(subscriber, event);
        }
    }

    void checkIsStart() {
        if (!initialized) {
            throw new IllegalStateException("Publisher does not start");
        }
    }

    public String getStatus() {
        return String.format("Publisher %-30s: shutdown=%5s, queue=%7d/%-7d", publisherName, shutdown,
                currentEventSize(), queueMaxSize);
    }
}
