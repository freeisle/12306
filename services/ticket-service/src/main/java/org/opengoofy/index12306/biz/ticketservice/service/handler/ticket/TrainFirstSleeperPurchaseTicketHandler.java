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

package org.opengoofy.index12306.biz.ticketservice.service.handler.ticket;

import lombok.RequiredArgsConstructor;
import org.opengoofy.index12306.biz.ticketservice.common.enums.VehicleSeatTypeEnum;
import org.opengoofy.index12306.biz.ticketservice.common.enums.VehicleTypeEnum;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.PurchaseTicketPassengerDetailDTO;
import org.opengoofy.index12306.biz.ticketservice.service.SeatService;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.base.AbstractTrainPurchaseTicketTemplate;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.SelectSeatDTO;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.TrainPurchaseTicketRespDTO;
import org.opengoofy.index12306.biz.ticketservice.toolkit.SeatNumberUtil;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 动车一等卧购票选铺组件
 * <p>
 * 数据模型：每节车厢 8 个隔间 x 4 个铺位（A=左下铺, C=左上铺, D=右下铺, F=右上铺）
 * <p>
 * 选铺策略：
 *   1. 铺位优先级：下铺(A/D) > 上铺(C/F)
 *   2. 同行乘客优先分配到同一隔间
 *   3. 当前隔间满员后顺序查找下一个有空铺的隔间
 *   4. 当前车厢满员后切换到下一节车厢
 * <p>
 * 设计说明：
 *   卧铺与二等座的核心区别在于——二等座是平面座位矩阵上的邻座优化问题（需要 SeatSelection.adjacent 算法），
 *   而卧铺是隔间制结构，核心需求是"铺位偏好"而非"邻座"，因此采用更简洁的隔间遍历 + 优先级分配方案。
 *
 * @author fufufu
 */
@Component
@RequiredArgsConstructor
public class TrainFirstSleeperPurchaseTicketHandler extends AbstractTrainPurchaseTicketTemplate {

    private final SeatService seatService;

    /**
     * 一等卧车厢布局：8个隔间
     */
    private static final int COMPARTMENT_COUNT = 8;

    /**
     * 每个隔间 4 个铺位
     */
    private static final int BERTH_PER_COMPARTMENT = 4;

    /**
     * 铺位优先级数组（索引对应矩阵列号），下铺优先（用户选择下铺或未选铺时的默认策略）
     * 列号映射：0=A(左下铺), 1=C(左上铺), 2=D(右下铺), 3=F(右上铺)
     * 优先级：左下铺 > 右下铺 > 左上铺 > 右上铺
     */
    private static final int[] BERTH_PRIORITY_LOWER = {0, 2, 1, 3};

    /**
     * 用户选择上铺时的铺位优先级：左上铺 > 右上铺 > 左下铺 > 右下铺
     */
    private static final int[] BERTH_PRIORITY_UPPER = {1, 3, 0, 2};

    /**
     * 下铺列号：A(左下铺), D(右下铺)
     */
    private static final int[] LOWER_BERTH_COLUMNS = {0, 2};

    /**
     * 上铺列号：C(左上铺), F(右上铺)
     */
    private static final int[] UPPER_BERTH_COLUMNS = {1, 3};

    /**
     * 铺位偏好标识：无偏好
     */
    private static final int PREFERENCE_NONE = 0;

    /**
     * 铺位偏好标识：下铺
     */
    private static final int PREFERENCE_LOWER = 1;

    /**
     * 铺位偏好标识：上铺
     */
    private static final int PREFERENCE_UPPER = 2;

    /**
     * 座位类型编码（用于 SeatNumberUtil.convert）
     */
    private static final int SEAT_TYPE_CODE = 4;

    @Override
    public String mark() {
        return VehicleTypeEnum.BULLET.getName() + VehicleSeatTypeEnum.FIRST_SLEEPER.getName();
    }

    @Override
    protected List<TrainPurchaseTicketRespDTO> selectSeats(SelectSeatDTO requestParam) {
        String trainId = requestParam.getRequestParam().getTrainId();
        String departure = requestParam.getRequestParam().getDeparture();
        String arrival = requestParam.getRequestParam().getArrival();
        Integer seatType = requestParam.getSeatType();
        List<PurchaseTicketPassengerDetailDTO> passengerSeatDetails = requestParam.getPassengerSeatDetails();
        int passengerCount = passengerSeatDetails.size();

        // 1. 获取当前车次可用的卧铺车厢列表
        List<String> trainCarriageList = seatService.listUsableCarriageNumber(trainId, seatType, departure, arrival);
        List<Integer> carriageRemainingTickets = seatService.listSeatRemainingTicket(trainId, departure, arrival, trainCarriageList);

        // 2. 校验余票是否充足
        int remainingTicketSum = carriageRemainingTickets.stream().mapToInt(Integer::intValue).sum();
        if (remainingTicketSum < passengerCount) {
            throw new ServiceException("卧铺余票不足，请尝试更换席别或选择其它车次");
        }

        // 3. 执行选铺分配（传入前端选铺偏好，与乘车人顺序一一对应）
        List<TrainPurchaseTicketRespDTO> result = allocateBerths(
                trainId, departure, arrival, seatType,
                trainCarriageList, carriageRemainingTickets,
                passengerSeatDetails,
                requestParam.getRequestParam().getChooseSeats()
        );

        if (result.size() < passengerCount) {
            throw new ServiceException("卧铺余票不足，请尝试更换席别或选择其它车次");
        }
        return result;
    }

    /**
     * 核心选铺算法：「全列车严格偏好筛选 + 售罄降级」两段式扫描
     * <p>
     * 分配规则：
     *   1. 有偏好（下铺/上铺）：先严格按该铺别在全列车所有车厢、隔间顺序扫描，
     *      仅当该铺别全列车售罄时才降级到另一铺别（对应前端"系统自动分配铺位"提示），
     *      避免"下一隔间还有下铺却把用户塞进上铺"的错误；
     *   2. 无偏好：按下铺优先的默认优先级扫描，按隔间顺序填充，使同行乘客尽量同隔间；
     *   3. 出票顺序与乘车人顺序一致。
     *
     * @param chooseSeats 前端选铺偏好列表，与乘车人顺序一一对应（A/D=下铺, C/F=上铺, 空串=无偏好）
     * @return 已分配的铺位结果列表（顺序与乘车人顺序一致）
     */
    private List<TrainPurchaseTicketRespDTO> allocateBerths(
            String trainId, String departure, String arrival, Integer seatType,
            List<String> trainCarriageList, List<Integer> carriageRemainingTickets,
            List<PurchaseTicketPassengerDetailDTO> passengerSeatDetails,
            List<String> chooseSeats) {

        int passengerCount = passengerSeatDetails.size();

        // 预构建全部可用车厢的铺位矩阵：berthMatrix[隔间号][铺位号] = 0(空闲) / 1(已售)
        List<String> usableCarriageNumbers = new ArrayList<>();
        List<int[][]> berthMatrices = new ArrayList<>();
        for (int i = 0; i < trainCarriageList.size(); i++) {
            if (carriageRemainingTickets.get(i) <= 0) {
                continue;
            }
            usableCarriageNumbers.add(trainCarriageList.get(i));
            berthMatrices.add(buildBerthMatrix(trainId, trainCarriageList.get(i), seatType, departure, arrival));
        }

        // 按乘车人下标保存出票结果，保证返回顺序与乘车人顺序一致
        TrainPurchaseTicketRespDTO[] ticketSlots = new TrainPurchaseTicketRespDTO[passengerCount];
        for (int passengerIndex = 0; passengerIndex < passengerCount; passengerIndex++) {
            int berthPreference = resolveBerthPreference(chooseSeats, passengerIndex);
            int[] levelColumns = berthPreference == PREFERENCE_LOWER ? LOWER_BERTH_COLUMNS
                    : berthPreference == PREFERENCE_UPPER ? UPPER_BERTH_COLUMNS : null;
            int[] fallbackPriority = berthPreference == PREFERENCE_UPPER ? BERTH_PRIORITY_UPPER : BERTH_PRIORITY_LOWER;

            int[] matchedBerth = null;
            int matchedCarriageIdx = -1;
            // 第一阶段：有偏好时严格按铺别全列车扫描
            if (levelColumns != null) {
                for (int c = 0; c < berthMatrices.size() && matchedBerth == null; c++) {
                    matchedBerth = matchBerthByColumns(berthMatrices.get(c), levelColumns);
                    matchedCarriageIdx = matchedBerth == null ? -1 : c;
                }
            }
            // 第二阶段：无偏好或偏好铺别全列车售罄 -> 按完整优先级降级扫描
            for (int c = 0; c < berthMatrices.size() && matchedBerth == null; c++) {
                matchedBerth = matchBerthByColumns(berthMatrices.get(c), fallbackPriority);
                matchedCarriageIdx = matchedBerth == null ? -1 : c;
            }

            if (matchedBerth != null) {
                int[][] berthMatrix = berthMatrices.get(matchedCarriageIdx);
                berthMatrix[matchedBerth[0]][matchedBerth[1]] = 1;
                ticketSlots[passengerIndex] = buildTicketResp(
                        passengerSeatDetails.get(passengerIndex),
                        usableCarriageNumbers.get(matchedCarriageIdx),
                        matchedBerth[0], matchedBerth[1]);
            }
        }

        List<TrainPurchaseTicketRespDTO> result = new ArrayList<>(passengerCount);
        for (TrainPurchaseTicketRespDTO ticketResp : ticketSlots) {
            if (ticketResp != null) {
                result.add(ticketResp);
            }
        }
        return result;
    }

    /**
     * 在单节车厢铺位矩阵中，按指定列号顺序查找第一个空闲铺位
     *
     * @return int[]{隔间号, 铺位列号}；本车厢无满足条件的空铺时返回 null
     */
    private int[] matchBerthByColumns(int[][] berthMatrix, int[] columns) {
        for (int compartment = 0; compartment < COMPARTMENT_COUNT; compartment++) {
            for (int berthColumn : columns) {
                if (berthMatrix[compartment][berthColumn] == 0) {
                    return new int[]{compartment, berthColumn};
                }
            }
        }
        return null;
    }

    /**
     * 解析单个乘客的铺位偏好
     * <p>
     * 前端约定：chooseSeats 按乘车人顺序传入，取首字符——A/D 表示下铺偏好，C/F 表示上铺偏好，空串表示无偏好。
     *
     * @return PREFERENCE_LOWER / PREFERENCE_UPPER / PREFERENCE_NONE
     */
    private int resolveBerthPreference(List<String> chooseSeats, int passengerIndex) {
        if (chooseSeats != null && passengerIndex < chooseSeats.size()) {
            String chooseSeat = chooseSeats.get(passengerIndex);
            if (chooseSeat != null && !chooseSeat.isEmpty()) {
                char berthFlag = Character.toUpperCase(chooseSeat.charAt(0));
                if (berthFlag == 'C' || berthFlag == 'F') {
                    return PREFERENCE_UPPER;
                }
                if (berthFlag == 'A' || berthFlag == 'D') {
                    return PREFERENCE_LOWER;
                }
            }
        }
        return PREFERENCE_NONE;
    }

    /**
     * 组装单个乘客的出票结果
     */
    private TrainPurchaseTicketRespDTO buildTicketResp(PurchaseTicketPassengerDetailDTO passenger,
                                                       String carriageNumber, int compartment, int berthColumn) {
        TrainPurchaseTicketRespDTO ticketResp = new TrainPurchaseTicketRespDTO();
        ticketResp.setCarriageNumber(carriageNumber);
        ticketResp.setSeatNumber(buildSeatNumber(compartment, berthColumn));
        ticketResp.setSeatType(passenger.getSeatType());
        ticketResp.setPassengerId(passenger.getPassengerId());
        return ticketResp;
    }

    /**
     * 构建单节车厢的铺位占用矩阵
     * <p>
     * 矩阵结构：int[8][4]
     *   行 = 隔间号（1~8）
     *   列 = 铺位（0=A左下, 1=C左上, 2=D右下, 3=F右上）
     *   值 = 0 表示空闲，1 表示已售
     */
    private int[][] buildBerthMatrix(String trainId, String carriageNumber, Integer seatType, String departure, String arrival) {
        // 从缓存/数据库获取当前车厢可售座位号列表
        List<String> availableSeats = seatService.listAvailableSeat(trainId, carriageNumber, seatType, departure, arrival);
        int[][] matrix = new int[COMPARTMENT_COUNT][BERTH_PER_COMPARTMENT];

        for (int row = 1; row <= COMPARTMENT_COUNT; row++) {
            for (int col = 1; col <= BERTH_PER_COMPARTMENT; col++) {
                // 座位号格式："01A", "01C", "02D" ...（行号 <= 9 时带前缀 "0"）
                String seatNumber = "0" + row + SeatNumberUtil.convert(SEAT_TYPE_CODE, col);
                // listAvailableSeat 返回的是「可售」座位号，命中表示空闲(0)，否则表示已售/锁定(1)
                matrix[row - 1][col - 1] = availableSeats.contains(seatNumber) ? 0 : 1;
            }
        }
        return matrix;
    }

    /**
     * 根据隔间号和铺位列号生成座位号字符串
     * <p>
     * 格式示例：隔间1 + 铺位A -> "01A"，隔间8 + 铺位F -> "08F"
     */
    private String buildSeatNumber(int compartment, int berthColumn) {
        int rowNumber = compartment + 1;
        String berthLetter = SeatNumberUtil.convert(SEAT_TYPE_CODE, berthColumn + 1);
        // 一等卧只有 8 个隔间，行号始终 <= 9，统一加 "0" 前缀
        return "0" + rowNumber + berthLetter;
    }
}
