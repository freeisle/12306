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

package org.opengoofy.index12306.biz.aiservice.service.impl;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.aiservice.common.enums.AnswerModeEnum;
import org.opengoofy.index12306.biz.aiservice.common.enums.ConfidenceLevelEnum;
import org.opengoofy.index12306.biz.aiservice.config.RagProperties;
import org.opengoofy.index12306.biz.aiservice.core.HashingEmbeddingModel;
import org.opengoofy.index12306.biz.aiservice.core.KnowledgeBaseLoader;
import org.opengoofy.index12306.biz.aiservice.core.KnowledgeChunk;
import org.opengoofy.index12306.biz.aiservice.core.SemanticCache;
import org.opengoofy.index12306.biz.aiservice.dto.req.AiSupportAskReqDTO;
import org.opengoofy.index12306.biz.aiservice.dto.resp.AiSupportAskRespDTO;
import org.opengoofy.index12306.biz.aiservice.dto.resp.ReferenceRespDTO;
import org.opengoofy.index12306.biz.aiservice.service.RagCustomerSupportService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * RAG 智能客服实现。
 * <p>
 * 完整链路：语义缓存 → 向量检索(Top-K + 阈值) → 组装带来源的上下文 → 生成(LLM/抽取式) → 引用溯源 → 回写缓存。
 * <p>
 * 三条抗幻觉防线：① Prompt 强约束“只依据资料作答”；② 强制返回可核对的引用片段；
 * ③ 检索置信度不足时走兜底话术，绝不编造。整个方法用 {@link SentinelResource} 包裹，
 * LLM 慢/超时/限流时自动降级，保证“AI 挂了业务不挂”。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagCustomerSupportServiceImpl implements RagCustomerSupportService {

    /**
     * 真实向量模型下的检索相似度下限：域内无关文本余弦也能到 0.7 上下，
     * 相关片段通常 0.85+，故用 0.75 把“知识库未覆盖”干净地切出去
     */
    private static final double REAL_MODEL_MIN_SCORE = 0.75d;

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final KnowledgeBaseLoader knowledgeBaseLoader;
    private final RagProperties props;
    private final SemanticCache semanticCache;
    private final ObjectProvider<ChatLanguageModel> chatModelProvider;

    @Override
    @SentinelResource(value = "ai-support-ask", fallback = "askFallback", blockHandler = "askBlockHandler")
    public AiSupportAskRespDTO ask(AiSupportAskReqDTO requestParam) {
        long start = System.currentTimeMillis();
        String question = requestParam.getQuestion().trim();

        // 1. 问题向量化
        Embedding queryEmbedding = embeddingModel.embed(question).content();

        // 2. 语义缓存命中则直接返回（降本 + 降延迟）
        AiSupportAskRespDTO cached = semanticCache.get(queryEmbedding);
        if (cached != null) {
            return cached.setLatencyMs(System.currentTimeMillis() - start);
        }

        // 3. 向量检索
        List<EmbeddingMatch<TextSegment>> matches = retrieve(queryEmbedding);

        // 4. 生成答案
        AiSupportAskRespDTO resp = matches.isEmpty() ? fallbackAnswer() : generate(question, matches);
        resp.setLatencyMs(System.currentTimeMillis() - start);

        // 5. 回写语义缓存
        semanticCache.put(queryEmbedding, resp);
        return resp;
    }

    private List<EmbeddingMatch<TextSegment>> retrieve(Embedding queryEmbedding) {
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(props.getTopK())
                // 真实向量模型余弦基线高（域内无关文本也能到 0.7 左右），阈值须比哈希模式高一个档位
                .minScore(hashingMode() ? props.getMinScore() : Math.max(props.getMinScore(), REAL_MODEL_MIN_SCORE))
                .build();
        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(request);
        return result.matches();
    }

    /**
     * 当前是否运行在离线哈希向量降级模式（缺 Key 时自动启用）
     */
    private boolean hashingMode() {
        return embeddingModel instanceof HashingEmbeddingModel;
    }

    private AiSupportAskRespDTO generate(String question, List<EmbeddingMatch<TextSegment>> matches) {
        List<ReferenceRespDTO> references = new ArrayList<>();
        StringBuilder context = new StringBuilder();
        double topScore = 0d;
        int index = 1;
        for (EmbeddingMatch<TextSegment> match : matches) {
            KnowledgeChunk chunk = knowledgeBaseLoader.getChunk(match.embeddingId());
            if (chunk == null) {
                continue;
            }
            double score = match.score() == null ? 0d : match.score();
            topScore = Math.max(topScore, score);
            context.append("【资料").append(index++)
                    .append(" | 来源：").append(chunk.source())
                    .append(" | 主题：").append(chunk.title()).append("】\n")
                    .append(chunk.content()).append("\n\n");
            references.add(new ReferenceRespDTO()
                    .setSource(chunk.source())
                    .setTitle(chunk.title())
                    .setSnippet(snippet(chunk.content()))
                    .setScore(round(score)));
            if (context.length() > props.getMaxContextChars()) {
                break;
            }
        }

        // 置信度分级：真实向量模型与离线哈希的分数分布不同，分档阈值随活跃模型切换
        double high = hashingMode() ? 0.60d : 0.85d;
        double medium = hashingMode() ? 0.35d : 0.75d;
        ConfidenceLevelEnum confidence = ConfidenceLevelEnum.of(topScore, high, medium);
        ChatLanguageModel chatModel = chatModelProvider.getIfAvailable();

        if (props.isLlmEnabled() && chatModel != null) {
            String answer = chatModel.generate(buildPrompt(question, context.toString()));
            return new AiSupportAskRespDTO()
                    .setAnswer(answer)
                    .setConfidence(confidence)
                    .setHitCache(false)
                    .setMode(AnswerModeEnum.LLM)
                    .setReferences(references);
        }

        // 离线抽取式：未启用 LLM 时，直接把最相关的规则原文作为答案，保证可演示、可核对
        String answer = "根据 12306 相关规定（置信度 " + confidence.name() + "）：\n\n" + context.toString().trim();
        return new AiSupportAskRespDTO()
                .setAnswer(answer)
                .setConfidence(confidence)
                .setHitCache(false)
                .setMode(AnswerModeEnum.EXTRACTIVE)
                .setReferences(references);
    }

    private String buildPrompt(String question, String context) {
        return """
                你是 12306 铁路官方智能客服。请严格依据下面提供的【资料】回答用户问题。
                要求：
                1. 只能使用【资料】中的信息作答，不得编造资料之外的规定、数字或费率；
                2. 若【资料】不足以回答，请直接说明“该问题现有资料未覆盖，建议咨询 12306 官方客服”；
                3. 回答要简洁、准确、条理清晰，涉及费率/时限等关键信息务必与资料一致；
                4. 在答案末尾用“（依据：来源-主题）”的形式标注引用。

                【资料】
                %s

                【用户问题】
                %s
                """.formatted(context, question);
    }

    private AiSupportAskRespDTO fallbackAnswer() {
        return new AiSupportAskRespDTO()
                .setAnswer("抱歉，我在现有 12306 规则知识库中没有检索到与该问题直接相关的内容。"
                        + "为避免提供不准确的信息，建议您拨打 12306 官方客服电话，或在 12306 App 内咨询在线客服。")
                .setConfidence(ConfidenceLevelEnum.LOW)
                .setHitCache(false)
                .setMode(AnswerModeEnum.FALLBACK)
                .setReferences(Collections.emptyList());
    }

    /**
     * Sentinel 降级：LLM 调用异常（超时/网络/额度）时触发，退回兜底话术，保证接口不 500。
     */
    public AiSupportAskRespDTO askFallback(AiSupportAskReqDTO requestParam, Throwable throwable) {
        log.warn("问答降级，question={}, cause={}", requestParam.getQuestion(), throwable.getMessage());
        return fallbackAnswer().setMode(AnswerModeEnum.FALLBACK);
    }

    /**
     * Sentinel 限流/熔断：请求被拦截时触发，同样退回兜底话术。
     */
    public AiSupportAskRespDTO askBlockHandler(AiSupportAskReqDTO requestParam, BlockException ex) {
        log.warn("问答被限流/熔断，question={}, rule={}", requestParam.getQuestion(), ex.getRule());
        return fallbackAnswer().setMode(AnswerModeEnum.BLOCKED);
    }

    private String snippet(String content) {
        if (content == null) {
            return "";
        }
        String oneLine = content.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 80 ? oneLine : oneLine.substring(0, 80) + "…";
    }

    private double round(double score) {
        return Math.round(score * 10000d) / 10000d;
    }
}
