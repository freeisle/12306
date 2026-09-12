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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 轻量 Markdown 切分器（Chunking）。
 * <p>
 * 策略：按 Markdown 标题（{@code #}）划分小节，一个小节即一个语义片段，并保留标题作为元数据；
 * 小节过长时再按空行分段、必要时按字符窗口二次切分，避免单个片段过大稀释向量语义、超出上下文预算。
 * <p>
 */
public final class MarkdownSplitter {

    /**
     * 单片段最大字符数
     */
    private static final int MAX_CHUNK_CHARS = 600;

    private MarkdownSplitter() {
    }

    public static List<KnowledgeChunk> split(String source, String markdown) {
        List<KnowledgeChunk> chunks = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) {
            return chunks;
        }
        String[] lines = markdown.split("\\r?\\n");
        String currentTitle = stripExtension(source);
        StringBuilder buffer = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                flush(chunks, source, currentTitle, buffer);
                currentTitle = trimmed.replaceAll("^#+\\s*", "").trim();
            } else {
                buffer.append(line).append('\n');
            }
        }
        flush(chunks, source, currentTitle, buffer);
        return chunks;
    }

    private static void flush(List<KnowledgeChunk> chunks, String source, String title, StringBuilder buffer) {
        String content = buffer.toString().trim();
        buffer.setLength(0);
        if (content.isEmpty()) {
            return;
        }
        if (content.length() <= MAX_CHUNK_CHARS) {
            chunks.add(new KnowledgeChunk(UUID.randomUUID().toString(), source, title, content));
            return;
        }
        for (String paragraph : content.split("\\n{2,}")) {
            String p = paragraph.trim();
            if (p.isEmpty()) {
                continue;
            }
            for (int i = 0; i < p.length(); i += MAX_CHUNK_CHARS) {
                String sub = p.substring(i, Math.min(p.length(), i + MAX_CHUNK_CHARS));
                chunks.add(new KnowledgeChunk(UUID.randomUUID().toString(), source, title, sub));
            }
        }
    }

    private static String stripExtension(String filename) {
        if (filename == null) {
            return "knowledge";
        }
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
