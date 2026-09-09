/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.opengoofy.index12306.biz.ticketservice.remote;

import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.PassengerRespDTO;
import org.opengoofy.index12306.framework.starter.convention.result.Result;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 用户服务 Feign 熔断降级工厂
 * <p>
 * 兜底返回失败 Result：重复购票校验责任链对「非 success」是失败关闭语义，
 * 熔断期间购票会被明确拒绝，而不是跳过乘车人校验继续下单。
 */
@Slf4j
@Component("ticketUserRemoteServiceFallbackFactory")
public class UserRemoteServiceFallbackFactory implements FallbackFactory<UserRemoteService> {

    private static final String DEGRADE_CODE = "B000901";

    @Override
    public UserRemoteService create(Throwable cause) {
        log.warn("UserRemoteService 进入兜底逻辑, cause: {}", cause.toString());
        return (username, ids) -> new Result<List<PassengerRespDTO>>()
                .setCode(DEGRADE_CODE)
                .setMessage("乘车人查询暂不可用，本次下单被拒绝");
    }
}
