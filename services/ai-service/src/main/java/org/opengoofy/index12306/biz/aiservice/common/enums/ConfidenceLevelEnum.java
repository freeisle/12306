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

package org.opengoofy.index12306.biz.aiservice.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 智能客服答案置信度枚举。
 * <p>
 * 由检索命中的最高相似度分档得出，用于前端信号标识与人工核对提示。
 * 枚举名即对外 JSON 取值（Jackson 默认按 {@code name()} 序列化），前端据此渲染
 * {@code .high / .medium / .low} 样式，因此常量名不可随意调整。
 */
@Getter
@RequiredArgsConstructor
public enum ConfidenceLevelEnum {

    /**
     * 高置信：检索分数达到高档阈值，答案可直接采信
     */
    HIGH("高置信"),

    /**
     * 中置信：检索分数达到中档阈值，答案基本可用但建议核对引用来源
     */
    MEDIUM("中置信"),

    /**
     * 低置信：检索分数未达中档阈值，或走兜底话术，答案仅供参考
     */
    LOW("低置信");

    /**
     * 置信度中文描述，用于日志与诊断
     */
    private final String desc;

    /**
     * 按检索分数分档。
     * <p>
     * 阈值由调用方按当前向量模型给定：真实语义模型余弦基线高，分档阈值须比离线哈希模式高一档。
     *
     * @param score           检索命中的最高相似度分数
     * @param highThreshold   高置信下限，{@code score >= highThreshold} 判为 {@link #HIGH}
     * @param mediumThreshold 中置信下限，{@code score >= mediumThreshold} 判为 {@link #MEDIUM}，否则 {@link #LOW}
     */
    public static ConfidenceLevelEnum of(double score, double highThreshold, double mediumThreshold) {
        if (score >= highThreshold) {
            return HIGH;
        }
        return score >= mediumThreshold ? MEDIUM : LOW;
    }
}