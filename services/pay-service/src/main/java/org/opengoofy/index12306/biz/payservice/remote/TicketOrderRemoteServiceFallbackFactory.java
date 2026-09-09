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

package org.opengoofy.index12306.biz.payservice.remote;

import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.payservice.remote.dto.TicketOrderDetailRespDTO;
import org.opengoofy.index12306.framework.starter.convention.result.Result;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * 车票订单远程服务调用降级工厂
 * <p>
 * 二次开发：order-service 熔断/异常时返回失败 Result（降级码 B000901），
 * 退款链路 RefundServiceImpl#createRefund 已按 !isSuccess() 判失败抛「车票订单不存在」，
 * 熔断降级与远程异常走同一条拒绝路径（失败关闭）。
 */
@Slf4j
@Component("payTicketOrderRemoteServiceFallbackFactory")
public class TicketOrderRemoteServiceFallbackFactory implements FallbackFactory<TicketOrderRemoteService> {

    /**
     * 降级统一返回码：非 "0"，调用方 isSuccess() 判定为失败
     */
    private static final String DEGRADE_CODE = "B000901";

    @Override
    public TicketOrderRemoteService create(Throwable cause) {
        log.warn("[熔断降级] TicketOrderRemoteService 进入兜底逻辑, cause: {}", cause.toString());
        return new TicketOrderRemoteService() {

            @Override
            public Result<TicketOrderDetailRespDTO> queryTicketOrderByOrderSn(String orderSn) {
                return new Result<TicketOrderDetailRespDTO>()
                        .setCode(DEGRADE_CODE)
                        .setMessage("订单服务熔断降级：车票订单查询暂不可用，退款流程终止（失败关闭）");
            }
        };
    }
}
