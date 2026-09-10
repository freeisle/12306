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

package org.opengoofy.index12306.biz.aiservice.core;

/**
 * 知识库片段。
 *
 * @param id      片段在向量库中的存储 ID（用于把检索命中映射回业务元数据）
 * @param source  来源文档名，如“退票规则.md”
 * @param title   所属小节标题，如“退票手续费标准”
 * @param content 片段正文
 */
public record KnowledgeChunk(String id, String source, String title, String content) {
}
