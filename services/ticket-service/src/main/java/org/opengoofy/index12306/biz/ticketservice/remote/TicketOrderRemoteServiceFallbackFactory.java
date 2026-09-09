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
import org.opengoofy.index12306.biz.ticketservice.dto.req.CancelTicketOrderReqDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.TicketOrderItemQueryReqDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderCreateRemoteReqDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderDetailRespDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderPassengerDetailRespDTO;
import org.opengoofy.index12306.framework.starter.convention.result.Result;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 订单服务 Feign 熔断降级工厂
 * <p>
 * 兜底策略统一为「返回失败 Result」而非返回空数据：调用方现有的 isSuccess() 失败分支
 * 会自然接住，避免把降级误判成「查无数据」而产生业务错判。
 */
@Slf4j
@Component("ticketTicketOrderRemoteServiceFallbackFactory")
public class TicketOrderRemoteServiceFallbackFactory implements FallbackFactory<TicketOrderRemoteService> {

    /**
     * 自定义降级错误码：非 0 即 isSuccess() == false，便于调用方失败分支与日志监控识别
     */
    private static final String DEGRADE_CODE = "B000901";

    @Override
    public TicketOrderRemoteService create(Throwable cause) {
        log.warn("TicketOrderRemoteService 进入兜底逻辑, cause: {}", cause.toString());
        return new TicketOrderRemoteService() {

            @Override
            public Result<TicketOrderDetailRespDTO> queryTicketOrderByOrderSn(String orderSn) {
                return degrade("订单详情查询暂不可用");
            }

            @Override
            public Result<List<TicketOrderPassengerDetailRespDTO>> queryTicketItemOrderById(TicketOrderItemQueryReqDTO requestParam) {
                return degrade("子订单查询暂不可用");
            }

            @Override
            public Result<String> createTicketOrder(TicketOrderCreateRemoteReqDTO requestParam) {
                return degrade("创单暂不可用，请稍后重试");
            }

            @Override
            public Result<Boolean> closeTickOrder(CancelTicketOrderReqDTO requestParam) {
                return degrade("订单关闭暂不可用");
            }

            @Override
            public Result<Void> cancelTicketOrder(CancelTicketOrderReqDTO requestParam) {
                return degrade("订单取消暂不可用");
            }

            /**
             * 失败关闭衔接点：这里只返回失败 Result，不吞异常也不返回空集合；
             * TrainPurchaseTicketRepeatChainHandler 中「!isSuccess() 即抛异常」的既有分支
             * 会据此拒绝本次购票，保证熔断期间重复购票校验不会被静默跳过。
             */
            @Override
            public Result<List<String>> listActiveOrderIdCards(String trainId, List<String> idCards) {
                return degrade("重复购票校验不可用，本次下单被拒绝");
            }
        };
    }

    private static <T> Result<T> degrade(String message) {
        return new Result<T>().setCode(DEGRADE_CODE).setMessage(message);
    }
}
