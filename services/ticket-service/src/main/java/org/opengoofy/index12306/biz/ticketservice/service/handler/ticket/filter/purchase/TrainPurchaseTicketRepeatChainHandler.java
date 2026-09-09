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

package org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.filter.purchase;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.PurchaseTicketPassengerDetailDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.TicketOrderRemoteService;
import org.opengoofy.index12306.biz.ticketservice.remote.UserRemoteService;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.PassengerRespDTO;
import org.opengoofy.index12306.framework.starter.convention.exception.ClientException;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.opengoofy.index12306.framework.starter.convention.result.Result;
import org.opengoofy.index12306.frameworks.starter.user.core.UserContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 购票流程过滤器之验证乘客是否重复购买
 * <p>
 * 业务规则：同一乘车人在未取消 / 未退票 / 未改签的前提下，不能重复预订同一车次。
 * <p>
 * 数据来源说明：车票表 t_ticket 在下单时以「未支付」写入，且订单关闭 / 取消时并不会回写其状态，
 * 因此不能作为重复购票的判定依据；真正的权威数据是订单服务的 t_order_item，
 * 其状态在取消（CLOSED）、退票（REFUNDED）、改签（RESCHEDULED）时会被更新，
 * 故通过 Feign 调用订单服务查询「有效订单」的证件号集合来做校验。
 * 公众号：马丁玩编程，回复：加群，添加马哥微信（备注：12306）获取项目资料
 */
@Component
@RequiredArgsConstructor
public class TrainPurchaseTicketRepeatChainHandler implements TrainPurchaseTicketChainFilter<PurchaseTicketReqDTO> {

    private final UserRemoteService userRemoteService;
    private final TicketOrderRemoteService ticketOrderRemoteService;

    @Override
    public void handler(PurchaseTicketReqDTO requestParam) {
        String username = UserContext.getUsername();
        List<PurchaseTicketPassengerDetailDTO> passengers = requestParam.getPassengers();
        // 缺少登录用户或乘车人信息时无法校验，直接放行（前置过滤器已保证参数非空）
        if (StrUtil.isBlank(username) || CollUtil.isEmpty(passengers)) {
            return;
        }
        // 1. 取出本次购票的乘车人 ID，查询其真实证件号与姓名
        List<String> passengerIds = passengers.stream()
                .map(PurchaseTicketPassengerDetailDTO::getPassengerId)
                .toList();
        Result<List<PassengerRespDTO>> passengerResult = userRemoteService.listPassengerQueryByIds(username, passengerIds);
        // 失败关闭：远程调用失败（含熔断降级返回的失败 Result）必须拒绝购票，不能跳过乘车人校验
        if (passengerResult == null || !passengerResult.isSuccess()) {
            throw new ServiceException("乘车人校验服务异常，请稍后重试");
        }
        if (CollUtil.isEmpty(passengerResult.getData())) {
            return;
        }
        // 2. 组装「证件号 -> 乘车人姓名」映射，便于命中后提示具体人员
        Map<String, String> idCardNameMap = passengerResult.getData().stream()
                .filter(each -> StrUtil.isNotBlank(each.getIdCard()))
                .collect(Collectors.toMap(PassengerRespDTO::getIdCard, PassengerRespDTO::getRealName, (a, b) -> a));
        if (idCardNameMap.isEmpty()) {
            return;
        }
        // 3. 调用订单服务，查询这些证件号在当前车次是否已存在有效订单
        Result<List<String>> activeResult = ticketOrderRemoteService.listActiveOrderIdCards(
                requestParam.getTrainId(), new ArrayList<>(idCardNameMap.keySet()));
        // 校验服务异常时必须「失败关闭」，否则远程调用出错会被误判为「无重复」而放行重复下单
        if (activeResult == null || !activeResult.isSuccess()) {
            throw new ServiceException("重复购票校验服务异常，请稍后重试");
        }
        if (CollUtil.isEmpty(activeResult.getData())) {
            return;
        }
        // 4. 命中则拒绝下单，提示已购票的乘车人姓名
        String repeatNames = activeResult.getData().stream()
                .map(idCard -> idCardNameMap.getOrDefault(idCard, "乘车人"))
                .distinct()
                .collect(Collectors.joining("、"));
        throw new ClientException(StrUtil.format(
                "乘车人【{}】已购买本次列车车票，不能重复预订；如需重新购票，请先取消或退掉原订单", repeatNames));
    }

    @Override
    public int getOrder() {
        return 30;
    }
}
