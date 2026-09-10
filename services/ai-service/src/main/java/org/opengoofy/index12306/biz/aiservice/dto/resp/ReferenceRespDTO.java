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

/**
 * 引用溯源片段。客服问答必须可核对，避免幻觉带来的资损与投诉。
 */
@Data
@Accessors(chain = true)
public class ReferenceRespDTO {

    /**
     * 来源文档，如“退票规则.md”
     */
    private String source;

    /**
     * 小节标题，如“退票手续费标准”
     */
    private String title;

    /**
     * 命中片段摘要
     */
    private String snippet;

    /**
     * 相似度分数（0~1）
     */
    private Double score;
}
