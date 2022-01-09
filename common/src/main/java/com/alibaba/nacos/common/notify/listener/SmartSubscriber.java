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

package com.alibaba.nacos.common.notify.listener;

import com.alibaba.nacos.common.notify.Event;

import java.util.List;

/**
 * 可以监听多个事件的订阅者
 * Subscribers to multiple events can be listened to.
 *
 * @author <a href="mailto:liaochuntao@live.com">liaochuntao</a>
 * @author zongtanghu
 */
@SuppressWarnings("PMD.AbstractClassShouldStartWithAbstractNamingRule")
public abstract class SmartSubscriber extends Subscriber {
    
    /**
     * 实现该方法，用来返回多订阅的指定集合
     * Returns which event type are smartsubscriber interested in.
     *
     * @return The interestd event types.
     */
    public abstract List<Class<? extends Event>> subscribeTypes();
    
    @Override
    public final Class<? extends Event> subscribeType() {
        return null;
    }
    
    @Override
    public final boolean ignoreExpireEvent() {
        return false;
    }
}
