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

import java.util.Arrays;

/**
 * 智能客服答案生成模式枚举。
 * <p>
 * 枚举名即对外 JSON 取值（Jackson 默认按 {@code name()} 序列化），前端据此渲染模式标签，
 * 因此新增模式时需同步前端 {@code MODE_LABEL} 映射，避免退化为裸枚举名展示。
 */
@Getter
@RequiredArgsConstructor
public enum AnswerModeEnum {

    /**
     * 大模型生成：启用 LLM 且检索到相关资料，由大模型基于资料生成答案
     */
    LLM("大模型生成", true),

    /**
     * 离线抽取式：未启用 LLM，直接返回最相关的规则原文，保证可演示、可核对
     */
    EXTRACTIVE("离线抽取式", true),

    /**
     * 知识库兜底：未检索到相关内容或 LLM 调用异常，返回兜底话术
     */
    FALLBACK("知识库兜底", false),

    /**
     * 限流熔断：请求被 Sentinel 限流/熔断，返回兜底话术
     */
    BLOCKED("限流熔断", false);

    /**
     * 模式中文描述，用于日志与诊断
     */
    private final String desc;

    /**
     * 是否允许写入语义缓存。兜底/被限流的答案并非真实检索结果，缓存后会污染后续相似问题
     */
    private final boolean cacheable;

    /**
     * 按名称解析模式，忽略大小写；无匹配返回 {@code null}
     */
    public static AnswerModeEnum of(String mode) {
        return Arrays.stream(AnswerModeEnum.values())
                .filter(each -> each.name().equalsIgnoreCase(mode))
                .findFirst()
                .orElse(null);
    }
}