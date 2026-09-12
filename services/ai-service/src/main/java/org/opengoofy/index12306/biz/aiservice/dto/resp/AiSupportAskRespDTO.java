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

package org.opengoofy.index12306.biz.aiservice.dto.resp;

import lombok.Data;
import lombok.experimental.Accessors;
import org.opengoofy.index12306.biz.aiservice.common.enums.AnswerModeEnum;
import org.opengoofy.index12306.biz.aiservice.common.enums.ConfidenceLevelEnum;

import java.util.List;

/**
 * 智能客服问答响应
 */
@Data
@Accessors(chain = true)
public class AiSupportAskRespDTO {

    /**
     * 答案正文
     */
    private String answer;

    /**
     * 置信度，取值与分档规则见 {@link ConfidenceLevelEnum}
     */
    private ConfidenceLevelEnum confidence;

    /**
     * 是否命中语义缓存
     */
    private Boolean hitCache;

    /**
     * 生成模式，取值与语义见 {@link AnswerModeEnum}
     */
    private AnswerModeEnum mode;

    /**
     * 端到端耗时（毫秒）
     */
    private Long latencyMs;

    /**
     * 引用来源，支持人工核对
     */
    private List<ReferenceRespDTO> references;
}
