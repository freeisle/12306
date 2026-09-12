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

package org.opengoofy.index12306.biz.aiservice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 智能客服可配置项
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.rag")
public class RagProperties {

    /**
     * 是否启用大模型生成。false=离线抽取式（零外部依赖）；true=调用 LLM 基于检索到的资料生成答案
     */
    private boolean llmEnabled = false;

    /**
     * 向量维度
     */
    private int dimension = 512;

    /**
     * 检索返回的最相关片段数量 Top-K
     */
    private int topK = 4;

    /**
     * 检索相似度下限，低于该分数视为“知识库未覆盖”，走兜底话术
     */
    private double minScore = 0.12;

    /**
     * 拼进 Prompt 的上下文最大字符数，控制 token 成本
     */
    private int maxContextChars = 3000;

    /**
     * 大模型配置
     */
    private Llm llm = new Llm();

    /**
     * 向量化（Embedding）模型配置
     */
    private Embedding embedding = new Embedding();

    /**
     * 语义缓存配置
     */
    private Cache cache = new Cache();

    @Data
    public static class Embedding {
        /**
         * 提供方：openai=真实云端向量模型（OpenAI 兼容协议，与 LLM 共用 Key）；
         * hashing=离线哈希向量（零外部依赖，缺 Key 时的降级选项）
         */
        private String provider = "openai";
        /**
         * 模型名
         */
        private String model = "text-embedding-v4";
    }

    @Data
    public static class Llm {
        /**
         * API Key
         */
        private String apiKey = "";
        /**
         * OpenAI 兼容接口地址，默认为通义千问 compatible-mode
         */
        private String baseUrl = "https://ws-uj1iqoj68v25u4jx.cn-beijing.maas.aliyuncs.com/compatible-mode/v1";
        /**
         * 模型名
         */
        private String modelName = "qwen-plus";
        /**
         * 采样温度，客服问答建议偏低以减少发散
         */
        private double temperature = 0.2;
        /**
         * 调用超时（秒）
         */
        private int timeoutSeconds = 30;
    }

    @Data
    public static class Cache {
        /**
         * 是否启用语义缓存
         */
        private boolean enabled = true;
        /**
         * 缓存最大条目数（LRU 淘汰）
         */
        private int maxSize = 512;
        /**
         * 命中阈值：问题向量与缓存项余弦相似度超过该值即命中
         */
        private double similarityThreshold = 0.92;
    }
}
