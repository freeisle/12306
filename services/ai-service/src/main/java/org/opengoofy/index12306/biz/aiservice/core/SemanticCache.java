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

import dev.langchain4j.data.embedding.Embedding;
import lombok.RequiredArgsConstructor;
import org.opengoofy.index12306.biz.aiservice.common.enums.AnswerModeEnum;
import org.opengoofy.index12306.biz.aiservice.config.RagProperties;
import org.opengoofy.index12306.biz.aiservice.dto.resp.AiSupportAskRespDTO;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 语义缓存（Semantic Cache。
 * <p>
 * 与普通缓存“精确 key 命中”不同，语义缓存以问题向量的余弦相似度判定命中：
 * “退票要手续费吗”和“退票收不收钱”虽然字面不同，但向量相近即可复用答案，
 * 从而在高频客服场景显著降低 LLM 调用次数（省钱）与响应延迟。
 * <p>
 */
@Component
@RequiredArgsConstructor
public class SemanticCache {

    private final RagProperties props;

    /**
     * access-order 的 LRU，超过容量淘汰最久未使用项
     */
    private final Map<String, Entry> store = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
            return size() > props.getCache().getMaxSize();
        }
    };

    /**
     * 命中返回缓存答案，未命中返回 null
     */
    public synchronized AiSupportAskRespDTO get(Embedding query) {
        // 如果未启用语义缓存，则直接返回 null
        if (!props.getCache().isEnabled()) {
            return null;
        }
        double threshold = props.getCache().getSimilarityThreshold();
        Entry best = null;
        double bestScore = -1d;
        for (Entry entry : store.values()) {
            double score = cosine(query, entry.embedding());
            if (score > bestScore) {
                bestScore = score;
                best = entry;
            }
        }
        if (best != null && bestScore >= threshold) {
            // 返回副本，避免调用方修改缓存对象
            return new AiSupportAskRespDTO()
                    .setAnswer(best.resp().getAnswer())
                    .setConfidence(best.resp().getConfidence())
                    .setMode(best.resp().getMode())
                    .setReferences(best.resp().getReferences())
                    .setHitCache(true);
        }
        return null;
    }

    /*
    * 存储缓存项
    * */
    public synchronized void put(Embedding query, AiSupportAskRespDTO resp) {
        if (!props.getCache().isEnabled() || resp == null) {
            return;
        }
        // 兜底/被限流的答案不缓存，避免污染
        if (AnswerModeEnum.FALLBACK.equals(resp.getMode()) || AnswerModeEnum.BLOCKED.equals(resp.getMode())) {
            return;
        }
        store.put(java.util.UUID.randomUUID().toString(), new Entry(query, resp));
    }

    private double cosine(Embedding a, Embedding b) {
        float[] va = a.vector();
        float[] vb = b.vector();
        if (va == null || vb == null || va.length != vb.length) {
            return 0d;
        }
        double dot = 0d;
        double na = 0d;
        double nb = 0d;
        for (int i = 0; i < va.length; i++) {
            dot += (double) va[i] * vb[i];
            na += (double) va[i] * va[i];
            nb += (double) vb[i] * vb[i];
        }
        if (na == 0d || nb == 0d) {
            return 0d;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private record Entry(Embedding embedding, AiSupportAskRespDTO resp) {
    }
}
