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
import org.opengoofy.index12306.biz.ticketservice.remote.dto.PayInfoRespDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.RefundReqDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.RefundRespDTO;
import org.opengoofy.index12306.framework.starter.convention.result.Result;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * 支付服务 Feign 熔断降级工厂
 * <p>
 * 支付单查询降级：返回失败 Result，前端展示「支付信息暂不可用」而非空支付单；
 * 退款降级：返回失败 Result，由调用方提示用户稍后重试，退款动作不静默丢失。
 */
@Slf4j
@Component
public class PayRemoteServiceFallbackFactory implements FallbackFactory<PayRemoteService> {

    private static final String DEGRADE_CODE = "B000901";

    @Override
    public PayRemoteService create(Throwable cause) {
        log.warn("PayRemoteService 进入兜底逻辑, cause: {}", cause.toString());
        return new PayRemoteService() {

            @Override
            public Result<PayInfoRespDTO> getPayInfo(String orderSn) {
                return new Result<PayInfoRespDTO>()
                        .setCode(DEGRADE_CODE)
                        .setMessage("支付单查询暂不可用");
            }

            @Override
            public Result<RefundRespDTO> commonRefund(RefundReqDTO requestParam) {
                return new Result<RefundRespDTO>()
                        .setCode(DEGRADE_CODE)
                        .setMessage("退款暂不可用，请稍后重试");
            }
        };
    }
}
