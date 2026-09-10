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

package org.opengoofy.index12306.biz.aiservice.controller;

import lombok.RequiredArgsConstructor;
import org.opengoofy.index12306.biz.aiservice.core.KnowledgeBaseLoader;
import org.opengoofy.index12306.biz.aiservice.dto.req.AiSupportAskReqDTO;
import org.opengoofy.index12306.biz.aiservice.dto.resp.AiSupportAskRespDTO;
import org.opengoofy.index12306.biz.aiservice.service.RagCustomerSupportService;
import org.opengoofy.index12306.framework.starter.convention.exception.ClientException;
import org.opengoofy.index12306.framework.starter.convention.result.Result;
import org.opengoofy.index12306.framework.starter.web.Results;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 智能客服控制层
 * <p>
 * 路径遵循项目约定 {@code /api/ai-service/**}，可经网关路由，也可直接访问本服务端口调试。
 */
@RestController
@RequiredArgsConstructor
public class AiSupportController {

    private final RagCustomerSupportService ragCustomerSupportService;
    private final KnowledgeBaseLoader knowledgeBaseLoader;

    /**
     * 智能客服问答（RAG）
     */
    @PostMapping("/api/ai-service/support/ask")
    public Result<AiSupportAskRespDTO> ask(@RequestBody AiSupportAskReqDTO requestParam) {
        if (requestParam == null || requestParam.getQuestion() == null || requestParam.getQuestion().isBlank()) {
            throw new ClientException("问题不能为空");
        }
        return Results.success(ragCustomerSupportService.ask(requestParam));
    }

    /**
     * 推荐问题（前端引导用）
     */
    @GetMapping("/api/ai-service/support/suggested-questions")
    public Result<List<String>> suggestedQuestions() {
        return Results.success(List.of(
                "开车前多久退票不收手续费？",
                "退票手续费是怎么收的？",
                "学生票一年可以买几次？",
                "改签需要手续费吗？",
                "哪些物品不能带上火车？",
                "候补购票是什么意思，怎么提高成功率？",
                "儿童票的身高标准是多少？"
        ));
    }

    /**
     * 健康检查：返回知识库规模，便于确认向量库已就绪
     */
    @GetMapping("/api/ai-service/support/health")
    public Result<Map<String, Object>> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "UP");
        data.put("knowledgeChunks", knowledgeBaseLoader.size());
        return Results.success(data);
    }
}
