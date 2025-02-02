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

package com.alibaba.nacos.naming.healthcheck.heartbeat;

import com.alibaba.nacos.api.naming.utils.NamingUtils;
import com.alibaba.nacos.common.notify.NotifyCenter;
import com.alibaba.nacos.naming.core.v2.client.impl.IpPortBasedClient;
import com.alibaba.nacos.naming.core.v2.event.client.ClientEvent;
import com.alibaba.nacos.naming.core.v2.event.service.ServiceEvent;
import com.alibaba.nacos.naming.core.v2.pojo.HealthCheckInstancePublishInfo;
import com.alibaba.nacos.naming.core.v2.pojo.Service;
import com.alibaba.nacos.naming.healthcheck.RsInfo;
import com.alibaba.nacos.naming.misc.Loggers;
import com.alibaba.nacos.naming.misc.UtilsAndCommons;

/**
 * 用于更新 v2.x 客户端节拍触发的临时实例的线程。
 * Thread to update ephemeral instance triggered by client beat for v2.x.
 *
 * @author nkorange
 */
public class ClientBeatProcessorV2 implements BeatProcessor {
    
    private final String namespace;
    
    private final RsInfo rsInfo;
    
    private final IpPortBasedClient client;
    
    public ClientBeatProcessorV2(String namespace, RsInfo rsInfo, IpPortBasedClient ipPortBasedClient) {
        this.namespace = namespace;
        this.rsInfo = rsInfo;
        this.client = ipPortBasedClient;
    }
    
    @Override
    public void run() {
        if (Loggers.EVT_LOG.isDebugEnabled()) {
            Loggers.EVT_LOG.debug("[CLIENT-BEAT] processing beat: {}", rsInfo.toString());
        }
        String ip = rsInfo.getIp();
        int port = rsInfo.getPort();
        String serviceName = NamingUtils.getServiceName(rsInfo.getServiceName());
        String groupName = NamingUtils.getGroupName(rsInfo.getServiceName());
        Service service = Service.newService(namespace, groupName, serviceName, rsInfo.isEphemeral());
        // TODO 这里是拿到了客户端之后，再获取客户端实例的详细信息  publishers 这个 map里面 有点小疑问
        HealthCheckInstancePublishInfo instance = (HealthCheckInstancePublishInfo) client.getInstancePublishInfo(service);
        // 根据之前保存的IP和端口 校验一下还对不对 按道理来说不存在不对的情况吧
        if (instance.getIp().equals(ip) && instance.getPort() == port) {
            if (Loggers.EVT_LOG.isDebugEnabled()) {
                Loggers.EVT_LOG.debug("[CLIENT-BEAT] refresh beat: {}", rsInfo);
            }
            // 设置实例最后一次的心跳检测的时间，并且将状态设置成为健康
            instance.setLastHeartBeatTime(System.currentTimeMillis());
            if (!instance.isHealthy()) {
                instance.setHealthy(true);
                Loggers.EVT_LOG.info("service: {} {POS} {IP-ENABLED} valid: {}:{}@{}, region: {}, msg: client beat ok",
                        rsInfo.getServiceName(), ip, port, rsInfo.getCluster(), UtilsAndCommons.LOCALHOST_SITE);
                NotifyCenter.publishEvent(new ServiceEvent.ServiceChangedEvent(service));
                NotifyCenter.publishEvent(new ClientEvent.ClientChangedEvent(client));
            }
        }
    }
}
