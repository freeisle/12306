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

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.aiservice.core.HashingEmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * RAG 组件装配。
 * <p>
 * 三个核心 Bean 都可平滑替换为生产实现：
 * <ul>
 *     <li>{@link EmbeddingModel}：默认真实云端模型（DashScope text-embedding-v3）→ 缺 Key 自动降级离线哈希 → 亦可换 AllMiniLmL6V2（本地 ONNX）</li>
 *     <li>{@link EmbeddingStore}：默认内存 → 可换 Redis Stack / Milvus / pgvector</li>
 *     <li>{@link ChatLanguageModel}：仅在 {@code ai.rag.llm-enabled=true} 时创建，未配置也能启动</li>
 * </ul>
 */
@Slf4j
@Configuration
public class RagConfiguration {

    /**
     * 真实云端向量模型（OpenAI 兼容协议，与 LLM 共用 API Key）。
     * <p>
     * 仅当 {@code ai.rag.embedding.provider=openai}（默认）<b>且</b> Key 非空时装配；
     * DashScope compatible-mode 推荐 text-embedding-v3 / v4，维度跟随 {@code ai.rag.dimension}。
     */
    @Bean
    @ConditionalOnExpression("'${ai.rag.embedding.provider:openai}' == 'openai' and !'${ai.rag.llm.api-key:}'.isEmpty()")
    public EmbeddingModel openAiEmbeddingModel(RagProperties props) {
        RagProperties.Llm llm = props.getLlm();
        log.info("[AI-RAG] EmbeddingModel=真实云端模型, provider=openai, model={}, dimensions={}",
                props.getEmbedding().getModel(), props.getDimension());
        return OpenAiEmbeddingModel.builder()
                .apiKey(llm.getApiKey())
                .baseUrl(llm.getBaseUrl())
                .modelName(props.getEmbedding().getModel())
                .dimensions(props.getDimension())
                .timeout(Duration.ofSeconds(llm.getTimeoutSeconds()))
                .maxRetries(2)
                .logRequests(false)
                .logResponses(false)
                .build();
    }

    /**
     * fail-safe 降级：未启用真实向量模型（provider=hashing 或缺 Key）时，
     * 用零依赖的离线哈希向量保证服务“永远能跑”，检索质量弱于真实模型。
     */
    @Bean
    @ConditionalOnExpression("'${ai.rag.embedding.provider:openai}' != 'openai' or '${ai.rag.llm.api-key:}'.isEmpty()")
    public EmbeddingModel hashingEmbeddingModel(RagProperties props) {
        log.warn("[AI-RAG] 未启用真实向量模型（provider={}, 有Key={}），降级为离线哈希向量",
                props.getEmbedding().getProvider(), !props.getLlm().getApiKey().isEmpty());
        return new HashingEmbeddingModel(props.getDimension());
    }

    /**
     * 向量存储。骨架用内存实现，重启即失效，仅用于演示。
     * <p>
     * 生产替换示例（复用项目已有的 Redis）：Redis Stack 的 RediSearch 向量索引；数据量大时用 Milvus。
     */
    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        return new InMemoryEmbeddingStore<>();
    }

    /**
     * 对话大模型。仅当「显式开启 {@code ai.rag.llm-enabled=true}」<b>且</b>「{@code ai.rag.llm.api-key} 非空」时才装配。
     * <p>
     * fail-safe：若开启了 LLM 却没配 Key，不会创建该 Bean（避免 OpenAiChatModel 因 apiKey 为空在启动期直接抛异常导致整个服务崩溃），
     * 服务照常启动并自动降级为“离线抽取式”问答。配置了 Key 才走大模型生成。
     */
    @Bean
    @ConditionalOnExpression("'${ai.rag.llm-enabled:false}' == 'true' and !'${ai.rag.llm.api-key:}'.isEmpty()")
    public ChatLanguageModel chatLanguageModel(RagProperties props) {
        RagProperties.Llm llm = props.getLlm();
        return OpenAiChatModel.builder()
                .apiKey(llm.getApiKey())
                .baseUrl(llm.getBaseUrl())
                .modelName(llm.getModelName())
                .temperature(llm.getTemperature())
                .timeout(Duration.ofSeconds(llm.getTimeoutSeconds()))
                .logRequests(false)
                .logResponses(false)
                .build();
    }
}
