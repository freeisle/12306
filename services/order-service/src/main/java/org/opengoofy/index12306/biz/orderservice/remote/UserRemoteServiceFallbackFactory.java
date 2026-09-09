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

package org.opengoofy.index12306.biz.orderservice.remote;

import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.orderservice.remote.dto.UserQueryActualRespDTO;
import org.opengoofy.index12306.framework.starter.convention.result.Result;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * 用户远程服务调用降级工厂
 * <p>
 * 二次开发：user-service 熔断/异常时返回失败 Result（降级码 B000901），
 * 调用方按 isSuccess() 判定失败后走「失败关闭」逻辑，避免拿 null 数据继续执行产生 NPE 或脏结果。
 */
@Slf4j
@Component("orderUserRemoteServiceFallbackFactory")
public class UserRemoteServiceFallbackFactory implements FallbackFactory<UserRemoteService> {

    /**
     * 降级统一返回码：非 "0"，调用方 isSuccess() 判定为失败
     */
    private static final String DEGRADE_CODE = "B000901";

    @Override
    public UserRemoteService create(Throwable cause) {
        log.warn("[熔断降级] UserRemoteService 进入兜底逻辑, cause: {}", cause.toString());
        return new UserRemoteService() {

            @Override
            public Result<UserQueryActualRespDTO> queryActualUserByUsername(String username) {
                return new Result<UserQueryActualRespDTO>()
                        .setCode(DEGRADE_CODE)
                        .setMessage("用户服务熔断降级：真实用户查询暂不可用，请稍后重试");
            }
        };
    }
}
