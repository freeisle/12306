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
import dev.langchain4j.model.output.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 离线“哈希词袋”向量模型。
 * <p>
 * 设计目的：让 RAG 骨架在<b>零外部依赖、零 API Key、无需联网</b>的情况下即可跑通完整链路，
 * 便于本地演示与单元测试。它把文本切成 token（中文按字 + 相邻二元组，英文数字按词），
 * 用哈希映射到固定维度向量并做 L2 归一化，从而以“词面重合度”近似语义相似度。
 * <p>
 * 生产环境应替换为真实语义模型：
 * <ul>
 *     <li>{@code AllMiniLmL6V2EmbeddingModel}：本地 ONNX，离线但语义更好（需引入 langchain4j-embeddings-all-minilm-l6-v2）</li>
 *     <li>{@code OpenAiEmbeddingModel}：云端 Embedding（通义/OpenAI 等），语义最佳，需 API Key 与网络</li>
 * </ul>
 * 替换方式见 {@code RagConfiguration#embeddingModel}。
 */
public class HashingEmbeddingModel implements EmbeddingModel {

    private final int dimension;

    public HashingEmbeddingModel(int dimension) {
        this.dimension = dimension > 0 ? dimension : 512;
    }

    @Override
    public Response<Embedding> embed(String text) {
        return Response.from(vectorize(text));
    }

    @Override
    public Response<Embedding> embed(TextSegment textSegment) {
        return Response.from(vectorize(textSegment.text()));
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
        List<Embedding> embeddings = new ArrayList<>(textSegments.size());
        for (TextSegment segment : textSegments) {
            embeddings.add(vectorize(segment.text()));
        }
        return Response.from(embeddings);
    }

    /**
     * 向量维度。父接口在部分版本中提供同名 default 方法，此处显式给出实现。
     */
    public int dimension() {
        return dimension;
    }

    private Embedding vectorize(String text) {
        float[] vector = new float[dimension];
        if (text != null && !text.isBlank()) {
            for (String token : tokenize(text)) {
                int idx = Math.floorMod(token.hashCode(), dimension);
                vector[idx] += 1.0f;
                // 二次哈希，降低碰撞带来的信息损失
                int idx2 = Math.floorMod((token + "\u0001").hashCode(), dimension);
                vector[idx2] += 0.5f;
            }
        }
        // L2 归一化，使点积等价于余弦相似度
        double norm = 0d;
        for (float v : vector) {
            norm += (double) v * v;
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < dimension; i++) {
                vector[i] = (float) (vector[i] / norm);
            }
        }
        return Embedding.from(vector);
    }

    private List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        String lower = text.toLowerCase(Locale.ROOT);
        StringBuilder word = new StringBuilder();
        char[] chars = lower.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (isCjk(c)) {
                flushWord(word, tokens);
                tokens.add(String.valueOf(c));
                if (i + 1 < chars.length && isCjk(chars[i + 1])) {
                    tokens.add("" + c + chars[i + 1]);
                }
            } else if (Character.isLetterOrDigit(c)) {
                word.append(c);
            } else {
                flushWord(word, tokens);
            }
        }
        flushWord(word, tokens);
        return tokens;
    }

    private void flushWord(StringBuilder word, List<String> tokens) {
        if (word.length() > 0) {
            tokens.add(word.toString());
            word.setLength(0);
        }
    }

    private boolean isCjk(char c) {
        return c >= '\u4e00' && c <= '\u9fff';
    }
}
