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

package com.alibaba.nacos.example;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingFactory;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.remote.request.AbstractNamingRequest;
import com.alibaba.nacos.client.naming.remote.gprc.NamingGrpcClientProxy;

import java.util.Properties;

/**
 * Hello world.
 *
 * register request see -> {@link NamingGrpcClientProxy#requestToServer(AbstractNamingRequest, Class)}
 *
 * @author xxc
 */
public class App {
    public static void main(String[] args) throws NacosException {
        Properties properties = new Properties();
        properties.setProperty("serverAddr", "localhost:8848");
        // like public,dev,test,prod...
        properties.setProperty("namespace", "public");
        NamingService naming = NamingFactory.createNamingService(properties);
        naming.registerInstance("service1", "11.11.11.11", 8888, "DEFAULT");
        naming.registerInstance("service1", "2.2.2.2", 9999, "DEFAULT");
        System.out.println("example----------App");
        System.out.println(naming.getAllInstances("nacos.test.3"));
    }
}
