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

package com.alibaba.nacos.naming.core.v2.event.metadata;

import com.alibaba.nacos.common.notify.Event;
import com.alibaba.nacos.naming.core.v2.pojo.Service;

/**
 * Metadata event.
 *
 * @author xiweng.yy
 */
public class MetadataEvent extends Event {
    
    private static final long serialVersionUID = -5842659852664110805L;
    
    private final Service service;
    
    /**
     * 标记此元数据是否已过期
     * Mark this metadata whether is expired.
     * 表示原对象（服务或实例）已被删除，因此元数据已过期，需要删除。
     * <p>If value is {@code true}, means that the original object (service or instance) has been removed, so the
     * metadata has been expired, need be delete.
     * 如果值为{code false}，则表示原始对象（服务或实例）已注册，元数据应停止删除
     * <p>If value is {code false}, means that the original object (service or instance) is registered, The metadata
     * should stop to delete.
     */
    private final boolean expired;
    
    public MetadataEvent(Service service, boolean expired) {
        this.service = service;
        this.expired = expired;
    }
    
    public Service getService() {
        return service;
    }
    
    public boolean isExpired() {
        return expired;
    }
    
    public static class ServiceMetadataEvent extends MetadataEvent {
        
        private static final long serialVersionUID = -2888112042649967804L;
        
        public ServiceMetadataEvent(Service service, boolean expired) {
            super(service, expired);
        }
    }
    
    public static class InstanceMetadataEvent extends MetadataEvent {
        
        private static final long serialVersionUID = 5781016126117637520L;
        
        private final String metadataId;
        
        public InstanceMetadataEvent(Service service, String metadataId, boolean expired) {
            super(service, expired);
            this.metadataId = metadataId;
        }
        
        public String getMetadataId() {
            return metadataId;
        }
    }
}
