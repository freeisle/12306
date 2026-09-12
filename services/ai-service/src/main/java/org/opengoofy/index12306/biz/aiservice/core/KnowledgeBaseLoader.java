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
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 知识库加载器。
 * <p>
 * 启动时扫描 {@code classpath*:/knowledge/*.md}，切分成片段并向量化后写入 {@link EmbeddingStore}，
 * 同时维护 “向量库存储 ID -> 业务片段元数据” 的索引，使检索命中后能还原来源与标题（用于引用溯源）。
 * <p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeBaseLoader {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    /**
     * 单次向量化请求的片段条数上限（云端 Embedding API 有 batch 限制）
     */
    private static final int EMBED_BATCH_SIZE = 10;

    private final Map<String, KnowledgeChunk> chunkIndex = new ConcurrentHashMap<>();
    private final List<KnowledgeChunk> chunks = new CopyOnWriteArrayList<>();

    @PostConstruct
    public void load() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath*:/knowledge/*.md");
        for (Resource resource : resources) {
            String filename = resource.getFilename();
            String markdown;
            try (InputStream in = resource.getInputStream()) {
                markdown = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            List<KnowledgeChunk> parsed = MarkdownSplitter.split(filename, markdown);
            for (int from = 0; from < parsed.size(); from += EMBED_BATCH_SIZE) {
                List<KnowledgeChunk> batch = parsed.subList(from, Math.min(from + EMBED_BATCH_SIZE, parsed.size()));
                List<TextSegment> segments = new ArrayList<>(batch.size());
                for (KnowledgeChunk chunk : batch) {
                    segments.add(TextSegment.from(embedText(chunk)));
                }
                // 批量向量化：云端 Embedding API单次请求有条数上限，不能整库一次发
                List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
                for (int i = 0; i < batch.size(); i++) {
                    KnowledgeChunk chunk = batch.get(i);
                    String storeId = embeddingStore.add(embeddings.get(i), TextSegment.from(chunk.content()));
                    KnowledgeChunk indexed = new KnowledgeChunk(storeId, chunk.source(), chunk.title(), chunk.content());
                    chunkIndex.put(storeId, indexed);
                    chunks.add(indexed);
                }
            }
            log.info("已加载知识文档：{}，切分 {} 个片段", filename, parsed.size());
        }
        log.info("知识库加载完成，共 {} 个片段，来源 {} 个文档", chunks.size(), resources.length);
    }

    /**
     * 把标题拼进被向量化的文本，增强主题相关性；正文单独用于展示
     */
    private String embedText(KnowledgeChunk chunk) {
        return (chunk.title() == null || chunk.title().isBlank())
                ? chunk.content()
                : chunk.title() + "\n" + chunk.content();
    }

    /**
     * 根据向量库存储 ID 取回业务片段（来源/标题/正文）
     */
    public KnowledgeChunk getChunk(String storeId) {
        return chunkIndex.get(storeId);
    }

    /**
     * 已加载片段总数，用于健康检查与前端展示
     */
    public int size() {
        return chunks.size();
    }
}
