# ai-service · RAG 智能客服（第一阶段骨架）

index12306 项目的 AI 智能化模块第一阶段落地：基于 **RAG（检索增强生成）** 的 12306 规则智能客服。
配套设计文档见 `12306-AI智能化模块设计方案.md`。

## 一、这个骨架能做什么

- 用户用自然语言提问（退票、改签、学生票、儿童票、安检、候补购票等）。
- 服务把问题向量化，从知识库检索最相关的规则片段（Top-K + 相似度阈值）。
- 基于检索到的资料生成答案，并**强制返回可核对的引用来源**（抗幻觉）。
- 知识库未覆盖时**诚实兜底**，不编造。
- 内置**语义缓存**降本降延迟；整条链路用 **Sentinel** 包裹，LLM 慢/挂时优雅降级。
- 自带一个**聊天前端页面**，启动即可演示。

## 二、开箱即跑（零外部依赖）

骨架默认使用「离线哈希向量模型 + 内存向量库 + 抽取式回答」，**无需 API Key、无需联网、无需 Nacos/Redis**：

```bash
# 在项目根目录，只构建并运行 ai-service
mvn -pl services/ai-service -am spring-boot:run
```

或在 IDE 里直接运行 `AiServiceApplication`。启动后：

- 前端界面：http://localhost:10006/
- 健康检查：http://localhost:10006/api/ai-service/support/health
- 问答接口：`POST http://localhost:10006/api/ai-service/support/ask`

```bash
curl -X POST http://localhost:10006/api/ai-service/support/ask \
  -H "Content-Type: application/json" \
  -d '{"question":"开车前多久退票不收手续费？"}'
```

> 首次构建需要 Maven 联网下载 `dev.langchain4j` 依赖；运行阶段离线即可。

## 三、启用真实大模型

在 `application.yaml` 或通过环境变量开启，即从「离线抽取式」升级为「LLM 基于资料生成」：

```yaml
ai:
  rag:
    llm-enabled: true
    llm:
      base-url: https://dashscope.aliyuncs.com/compatible-mode/v1   # 通义千问兼容模式
      model-name: qwen-plus
      api-key: ${AI_LLM_API_KEY}
```

```bash
export AI_LLM_API_KEY=sk-xxxx     # 切勿硬编码进仓库
```

有 Key 时**真实向量模型与真实 LLM 同时启用**（embedding 默认 `provider: openai`，与 LLM 共用同一个 Key）：

```yaml
ai:
  rag:
    embedding:
      provider: openai           # openai=真实云端向量模型；hashing=离线哈希（零依赖降级）
      model: text-embedding-v3   # DashScope compatible-mode，维度跟随 ai.rag.dimension
```

缺 Key 时自动降级为「离线哈希向量 + 抽取式/兜底」，检索阈值与置信度分档会随活跃模型自动切换档位（真实向量余弦基线高，阈值更高一档）。

Prompt 已内置强约束：**只依据检索到的资料作答、资料不足则明说、末尾标注引用**，从源头抑制幻觉。

## 四、接入注册中心与网关

1. `application.yaml` 里把 `spring.cloud.nacos.discovery.enabled` 改为 `true` 并配置正确的 `server-addr`。
2. 网关路由已在 `gateway-service/application-dev.yaml` 追加 `/api/ai-service/**`（公开、无需 Token）。
3. 之后前端可把 `API_BASE` 指向网关地址，统一走 `/api/ai-service/**`。

## 五、目录结构

```
ai-service/
├── pom.xml                          # langchain4j + nacos + sentinel + 项目 convention/web starter
└── src/main/
    ├── java/org/opengoofy/index12306/biz/aiservice/
    │   ├── AiServiceApplication.java          # 启动类
    │   ├── config/
    │   │   ├── RagProperties.java             # ai.rag.* 配置绑定
    │   │   └── RagConfiguration.java          # EmbeddingModel / EmbeddingStore / ChatModel 装配
    │   ├── controller/AiSupportController.java# /api/ai-service/support/**
    │   ├── core/
    │   │   ├── HashingEmbeddingModel.java     # 离线向量模型（可换 AllMiniLm/OpenAi）
    │   │   ├── KnowledgeChunk.java            # 知识片段
    │   │   ├── MarkdownSplitter.java          # 文档切分
    │   │   ├── KnowledgeBaseLoader.java       # 启动加载知识库→向量库
    │   │   └── SemanticCache.java             # 语义缓存（Cache-Aside 延伸）
    │   ├── dto/req|resp/...                   # 请求/响应对象
    │   └── service/
    │       ├── RagCustomerSupportService.java
    │       └── impl/RagCustomerSupportServiceImpl.java  # RAG 主链路 + Sentinel 降级
    └── resources/
        ├── application.yaml
        ├── knowledge/*.md                     # 12306 规则示例知识库（6 篇）
        └── static/index.html                  # 聊天前端页面
```

## 六、RAG 主链路（对应代码）

```
用户问题
  → 向量化 (EmbeddingModel.embed)
  → 语义缓存命中? (SemanticCache.get)  ── 命中 ──▶ 直接返回（⚡缓存命中）
  → 向量检索 Top-K + minScore (EmbeddingStore.search)
  → 命中为空? ──▶ 兜底话术 (FALLBACK，不编造)
  → 组装带来源的上下文 (资料N | 来源 | 主题)
  → 生成：LLM (llm-enabled=true) 或 离线抽取式 (EXTRACTIVE)
  → 置信度分级 (HIGH/MEDIUM/LOW) + 引用溯源 (references)
  → 回写语义缓存 (SemanticCache.put)
  └─ 整个方法被 @SentinelResource 包裹：异常→askFallback，限流/熔断→askBlockHandler
```

## 七、生产化升级路线（骨架已预留替换点）

| 组件 | 骨架实现 | 生产替换 |
|---|---|---|
| EmbeddingModel | 真实云端 text-embedding-v3（缺 Key 自动降级离线哈希） | 本地 ONNX `AllMiniLmL6V2` / 私有化向量服务 |
| EmbeddingStore | 内存 InMemory | **Redis Stack（复用项目已有 Redis）** / Milvus / pgvector |
| 文档切分 | 按标题的 MarkdownSplitter | `DocumentSplitters.recursive` / 按 token 切分 + overlap |
| 检索 | 单路向量检索 | 向量 + BM25 混合检索 + Rerank 重排 |
| 语义缓存 | 进程内 LRU | Redis 存向量 + 向量判命中 |
| LLM 治理 | @SentinelResource 降级 | 叠加限流阈值、多模型路由、流式输出(SSE) |

## 八、说明

- `knowledge/*.md` 为**演示用示例规则**，用于跑通 RAG 链路；真实上线前请以 12306 官方最新公告为准替换。
- 骨架默认不连数据库；会话/消息/工具审计/反馈的建表脚本已备好：`src/main/resources/db/12306-ai-module-ddl.sql`
  （`t_ai_conversation` / `t_ai_message` / `t_ai_tool_call` / `t_ai_feedback`，另有可选的 `t_ai_knowledge_doc`），
  需要持久化时建表并接入 `database` starter 即可。
