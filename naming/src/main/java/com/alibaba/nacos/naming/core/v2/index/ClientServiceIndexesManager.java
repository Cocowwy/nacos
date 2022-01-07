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

package com.alibaba.nacos.naming.core.v2.index;

import com.alibaba.nacos.common.notify.Event;
import com.alibaba.nacos.common.notify.NotifyCenter;
import com.alibaba.nacos.common.notify.listener.SmartSubscriber;
import com.alibaba.nacos.common.utils.ConcurrentHashSet;
import com.alibaba.nacos.naming.core.v2.client.Client;
import com.alibaba.nacos.naming.core.v2.event.client.ClientEvent;
import com.alibaba.nacos.naming.core.v2.event.client.ClientOperationEvent;
import com.alibaba.nacos.naming.core.v2.event.publisher.NamingEventPublisherFactory;
import com.alibaba.nacos.naming.core.v2.event.service.ServiceEvent;
import com.alibaba.nacos.naming.core.v2.pojo.Service;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Client and service index manager.
 *
 * @author xiweng.yy
 */
@Component
public class ClientServiceIndexesManager extends SmartSubscriber {
    // key 服务toString value 不太清楚是干嘛的  放了一个IP:端口号
    // 只注册一个服务的时候
    // Service{namespace='public', group='DEFAULT_GROUP', name='server1', ephemeral=true, revision=3} ->
    // size = 0
    private final ConcurrentMap<Service, Set<String>> publisherIndexes = new ConcurrentHashMap<>();
    // Service{namespace='public', group='DEFAULT_GROUP', name='server1', ephemeral=true, revision=3} ->
    // 192.168.1.10:60396#true
    private final ConcurrentMap<Service, Set<String>> subscriberIndexes = new ConcurrentHashMap<>();

    public ClientServiceIndexesManager() {
        NotifyCenter.registerSubscriber(this, NamingEventPublisherFactory.getInstance());
    }

    public Collection<String> getAllClientsRegisteredService(Service service) {
        return publisherIndexes.containsKey(service) ? publisherIndexes.get(service) : new ConcurrentHashSet<>();
    }

    public Collection<String> getAllClientsSubscribeService(Service service) {
        return subscriberIndexes.containsKey(service) ? subscriberIndexes.get(service) : new ConcurrentHashSet<>();
    }

    public Collection<Service> getSubscribedService() {
        return subscriberIndexes.keySet();
    }

    /**
     * Clear the service index without instances.
     *
     * @param service The service of the Nacos.
     */
    public void removePublisherIndexesByEmptyService(Service service) {
        if (publisherIndexes.containsKey(service) && publisherIndexes.get(service).isEmpty()) {
            publisherIndexes.remove(service);
        }
    }

    @Override
    public List<Class<? extends Event>> subscribeTypes() {
        List<Class<? extends Event>> result = new LinkedList<>();
        result.add(ClientOperationEvent.ClientRegisterServiceEvent.class);
        result.add(ClientOperationEvent.ClientDeregisterServiceEvent.class);
        result.add(ClientOperationEvent.ClientSubscribeServiceEvent.class);
        result.add(ClientOperationEvent.ClientUnsubscribeServiceEvent.class);
        result.add(ClientEvent.ClientDisconnectEvent.class);
        return result;
    }

    @Override
    public void onEvent(Event event) {
        if (event instanceof ClientEvent.ClientDisconnectEvent) {
            // 处理客户端断开  不知道为啥这里在启动客户端的时候会调用（还tm调用两次）   但是服务主动shutdown并不会走这里
            System.out.println("[SmartSubscriber:onevent]-->处理客户端断开");
            handleClientDisconnect((ClientEvent.ClientDisconnectEvent) event);
        } else if (event instanceof ClientOperationEvent) {
            // 处理客户端操作  不过注册和断开均走了这段方法
            System.out.println("[SmartSubscriber:onevent]-->处理客户端操作");
            handleClientOperation((ClientOperationEvent) event);
        }
    }

    private void handleClientDisconnect(ClientEvent.ClientDisconnectEvent event) {
        Client client = event.getClient();
        for (Service each : client.getAllSubscribeService()) {
            removeSubscriberIndexes(each, client.getClientId());
        }
        for (Service each : client.getAllPublishedService()) {
            removePublisherIndexes(each, client.getClientId());
        }
    }

    private void handleClientOperation(ClientOperationEvent event) {
        Service service = event.getService();
        String clientId = event.getClientId();
        if (event instanceof ClientOperationEvent.ClientRegisterServiceEvent) {
            // 客户端注册事件的处理
            addPublisherIndexes(service, clientId);
            System.out.println("客户端注册事件的处理：" + clientId);
        } else if (event instanceof ClientOperationEvent.ClientDeregisterServiceEvent) {
            // 客户端注销服务事件
            removePublisherIndexes(service, clientId);
            System.out.println("客户端注销服务事件" + clientId);
        } else if (event instanceof ClientOperationEvent.ClientSubscribeServiceEvent) {
            // 客户端订阅服务事件
            addSubscriberIndexes(service, clientId);
        } else if (event instanceof ClientOperationEvent.ClientUnsubscribeServiceEvent) {
            // 客户端取消订阅服务事件
            removeSubscriberIndexes(service, clientId);
        }
    }

    private void addPublisherIndexes(Service service, String clientId) {
        publisherIndexes.computeIfAbsent(service, (key) -> new ConcurrentHashSet<>());
        publisherIndexes.get(service).add(clientId);
//        publisherIndexes.computeIfAbsent(service, (key) -> new ConcurrentHashSet<String>() {{
//            add(clientId);
//        }});
        // 发布了一个服务变更时间..搁这套娃呢？
        NotifyCenter.publishEvent(new ServiceEvent.ServiceChangedEvent(service, true));
    }
    //  1.publisherIndexes remove
    //  2.publish ServiceChangedEvent
    private void removePublisherIndexes(Service service, String clientId) {
        if (!publisherIndexes.containsKey(service)) {
            return;
        }
        publisherIndexes.get(service).remove(clientId);
        NotifyCenter.publishEvent(new ServiceEvent.ServiceChangedEvent(service, true));
    }

    private void addSubscriberIndexes(Service service, String clientId) {
        subscriberIndexes.computeIfAbsent(service, (key) -> new ConcurrentHashSet<>());
        // Fix #5404, Only first time add need notify event.
        if (subscriberIndexes.get(service).add(clientId)) {
            NotifyCenter.publishEvent(new ServiceEvent.ServiceSubscribedEvent(service, clientId));
        }
    }

    private void removeSubscriberIndexes(Service service, String clientId) {
        if (!subscriberIndexes.containsKey(service)) {
            return;
        }
        subscriberIndexes.get(service).remove(clientId);
        if (subscriberIndexes.get(service).isEmpty()) {
            subscriberIndexes.remove(service);
        }
    }
}
