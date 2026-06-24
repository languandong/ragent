# Agentic RAG 入门指南 — 以 Ragent 项目为例

## 目录

1. [什么是 Agentic RAG？](#1-什么是-agentic-rag)
2. [传统 RAG vs Agentic RAG](#2-传统-rag-vs-agentic-rag)
3. [Ragent 项目架构总览](#3-ragent-项目架构总览)
4. [核心流水线详解（附代码指引）](#4-核心流水线详解附代码指引)
5. [七大关键知识点](#5-七大关键知识点)
6. [学习路径建议](#6-学习路径建议)

---

## 1. 什么是 Agentic RAG？

**RAG（Retrieval-Augmented Generation）** 是指：在 LLM 回答用户问题之前，先从知识库中检索相关文档，将检索到的内容作为上下文"喂"给 LLM，让 LLM 基于这些材料作答。这解决了 LLM 知识过时、产生幻觉的问题。

**Agentic RAG（智能体 RAG）** 是在传统 RAG 基础上引入"智能体"的思维：

- 传统 RAG 像一个"直筒"：问题 → 检索 → 生成，路径固定
- Agentic RAG 像一个"大脑"：会**思考**用户想干什么（意图识别）、**决策**用哪种方式检索（多通道）、**调用工具**（MCP）、**判断**是否需要追问澄清

**一句话总结：Agentic RAG = 传统 RAG + 意图路由 + 工具调用 + 多步推理。**

---

## 2. 传统 RAG vs Agentic RAG

| 对比维度 | 传统 RAG | Agentic RAG（本项目的做法） |
|:---|:---|:---|
| **查询处理** | 直接检索 | 先改写、拆分子问题 |
| **意图感知** | 无差别检索所有知识库 | 先识别用户意图，定向检索 |
| **检索通道** | 单一向量检索 | 多通道（意图定向 + 全局搜索）+ MCP 工具 |
| **工具调用** | 不支持 | 通过 MCP 协议调用天气/工单等外部工具 |
| **歧义处理** | 返回不确定的结果 | 检测歧义并向用户追问澄清 |
| **模型容错** | 单模型，失败即报错 | 多模型候选 + 断路器 + 故障转移 |
| **记忆管理** | 简单的消息拼接 | 摘要压缩 + 多轮记忆 + TTL 上下文传递 |

---

## 3. Ragent 项目架构总览

```
┌─────────────────────────────────────────────────────────────────────┐
│                       用户浏览器 (React)                             │
└─────────────────────────┬───────────────────────────────────────────┘
                          │ SSE (text/event-stream)
                          ▼
┌──────────────────────────────────────────────────────────────────────┐
│                    RAGChatController (入口)                          │
│                    GET /api/ragent/rag/v3/chat                      │
│                    @ChatRateLimit + @IdempotentSubmit               │
└─────────────────────────┬────────────────────────────────────────────┘
                          ▼
┌──────────────────────────────────────────────────────────────────────┐
│                    StreamChatPipeline (核心管线)                     │
│                                                                      │
│  loadMemory() → rewriteQuery() → resolveIntents()                    │
│       → handleGuidance()? → handleSystemOnly()?                      │
│       → retrieve() → handleEmptyRetrieval()? → streamRagResponse()   │
└─────────────────────────┬────────────────────────────────────────────┘
                          ▼
┌──────────────────────────────────────────────────────────────────────┐
│                    LLM Service (模型路由 + 容错)                     │
│        百炼 / SiliconFlow / Ollama ← 断路器保护                      │
└──────────────────────────────────────────────────────────────────────┘
```

### 模块划分

| 模块 | 功能 |
|:---|:---|
| `bootstrap` | 主业务模块：RAG 管线、知识库、意图系统、用户管理 |
| `infra-ai` | AI 基础设施：LLM 路由/容错、Embedding、Rerank、Token 计数 |
| `framework` | 通用框架：异常、幂等、全链路追踪、SSE 封装、分布式 ID |
| `mcp-server` | MCP 工具服务器（独立进程）：天气、工单、销售查询 |

---

## 4. 核心流水线详解（附代码指引）

整个管线的编排在 `StreamChatPipeline.execute()` 中（源码位置：`bootstrap/.../rag/service/pipeline/StreamChatPipeline.java:77`）。一共 **7 个阶段**：

### 阶段 1：记忆加载（loadMemory）

```java
// StreamChatPipeline.java:99
private void loadMemory(StreamChatContext ctx) {
    List<ChatMessage> history = memoryService.loadAndAppend(
            ctx.getConversationId(), ctx.getUserId(),
            ChatMessage.user(ctx.getQuestion())
    );
    ctx.setHistory(history);
}
```

**做了什么？** 从数据库加载最近的 N 轮对话历史（默认 4 轮），如果历史超过阈值（默认 5 轮），还会加载一个**历史摘要**替代早期消息。

**关键知识：记忆管理**

- **窗口记忆**：只保留最近的 K 轮消息，防止 Token 溢出
- **摘要记忆**：当对话足够长时，用 LLM 把早期内容压缩为摘要
- 配置位置：`application.yaml:65-70` → `history-keep-turns: 4`, `summary-start-turns: 5`

---

### 阶段 2：查询改写与拆分（rewriteQuery）

```java
// StreamChatPipeline.java:108
private void rewriteQuery(StreamChatContext ctx) {
    RewriteResult rewriteResult = queryRewriteService.rewriteWithSplit(ctx.getQuestion(), ctx.getHistory());
    ctx.setRewriteResult(rewriteResult);
}
```

**做了什么？**

1. **术语归一化**：把同义词映射为标准术语（如 "薪资" → "薪酬"）
2. **查询改写**：让 LLM 把用户的口语化问题改写为更适合检索的形式，例如：
   - 用户问："公司附近有啥好吃的？"
   - 改写为："公司周边 1 公里范围内的餐厅推荐"
3. **子问题拆分**：如果问题包含多个意图（如"本周的销售数据和天气情况"），拆成两个子问题分别处理

**关键知识：Query Rewriting**

- 用户的原始问题往往不适合直接向量检索（口语化、指代模糊）
- 通过 LLM 改写可以显著提升检索命中率
- 子问题拆分让复杂问题的每部分都能被精准检索

---

### 阶段 3：意图解析（resolveIntents）

```java
// StreamChatPipeline.java:113
private void resolveIntents(StreamChatContext ctx) {
    List<SubQuestionIntent> subIntents = intentResolver.resolve(ctx.getRewriteResult());
    ctx.setSubIntents(subIntents);
}
```

**这是 Agentic RAG 最核心的环节。** 系统不再"无脑检索所有知识库"，而是先问 LLM："用户想干什么？"

**工作流程**（`DefaultIntentClassifier.java:137`）：

1. 从 Redis 加载一棵**意图树**（树形结构，叶子节点是具体意图）
2. 把所有叶子节点序列化为文本，构建分类 Prompt
3. 让 LLM 对每个叶子节点打分（0-1 分），输出 JSON
4. 过滤低分（< 0.3），取 Top-5

**意图的类型**（`IntentKind` 枚举）：

| 类型 | 含义 | 示例 |
|:---|:---|:---|
| `KB` | 需要检索知识库 | "报销流程是什么？" → 检索 HR 知识库 |
| `MCP` | 需要调用外部工具 | "今天北京天气" → 调用天气 MCP |
| `SYSTEM` | 只需 LLM 回答 | "你好"、"你是谁" |

**关键知识：意图驱动的检索**

- 传统 RAG 检索所有文档 → LLM 筛选，浪费 Token 且引入噪声
- Agentic RAG 先识别意图 → 只检索相关的知识库，更精准
- 这是"检索"从"被动"变为"主动"的关键一步

---

### 阶段 4：歧义引导（handleGuidance，可选短路）

```java
// StreamChatPipeline.java:118
private boolean handleGuidance(StreamChatContext ctx) {
    GuidanceDecision decision = guidanceService.detectAmbiguity(...);
    if (!decision.isPrompt()) return false;
    callback.onContent(decision.getPrompt());
    callback.onComplete();
    return true;  // 短路，不再继续执行管线
}
```

**做了什么？** 检查意图分类是否在不同类别的节点之间摇摆不定。例如系统检测到"报销"既可能属于"OA 系统"也可能属于"财务系统"，就会生成追问：

> "您指的是 OA 系统中的报销审批流程，还是财务系统中的报销到账查询？"

**关键知识：Ambiguity Detection**

- 当 LLM 对意图的分类置信度在多个类别间接近时，提前向用户澄清
- 这避免了"猜错意图 → 检索错误内容 → 生成错误答案"的级联错误
- 这是 Agent 系统的关键特征：知道**自己不知道**，并主动寻求澄清

---

### 阶段 5：检索阶段（retrieve）

这是项目中**最复杂的阶段**，分两条线并行执行：

```
retrieve()
  ├── 知识库检索 (MultiChannelRetrievalEngine)
  │     ├── 通道1: 意图定向搜索 (IntentDirectedSearchChannel)
  │     │     └── 只搜索高置信度意图关联的特定知识库
  │     └── 通道2: 全局向量搜索 (VectorGlobalSearchChannel)
  │           └── 当置信度不足时，搜索所有知识库兜底
  │
  └── MCP 工具调用
        └── 提取参数 → 执行工具（天气/工单/销售查询）
```

**后处理链**（责任链模式）：

1. **去重**（`DeduplicationPostProcessor`）：多通道结果按 chunk-id 去重
2. **重排序**（`RerankPostProcessor`）：用专门的 Rerank 模型对结果重新排序

**关键知识：Multi-Channel Retrieval**

- 意图定向搜索：精确、噪声少（precision 导向）
- 全局搜索：兜底、覆盖广（recall 导向）
- 两者结合 = 既有精度又有召回

**关键知识：MCP（Model Context Protocol）**

MCP 是 Anthropic 提出的开放协议，让 LLM 应用能通过标准接口调用外部工具。本项目中：

- `mcp-server` 模块是独立的工具服务器（端口 9099）
- 提供 `tools/list`、`tools/call` 等标准接口
- 内置三个示例工具：WeatherMCPExecutor、TicketMCPExecutor、SalesMCPExecutor

---

### 阶段 6：LLM 响应生成（streamRagResponse）

```java
// StreamChatPipeline.java:169
private void streamRagResponse(StreamChatContext ctx, RetrievalContext retrievalCtx) {
    IntentGroup mergedGroup = intentResolver.mergeIntentGroup(ctx.getSubIntents());
    StreamCancellationHandle handle = streamLLMResponse(
            ctx.getRewriteResult(), retrievalCtx, mergedGroup,
            ctx.getHistory(), ctx.isDeepThinking(), ctx.getCallback()
    );
    taskManager.bindHandle(ctx.getTaskId(), handle);
}
```

**Prompt 组装**（`RAGPromptService.buildStructuredMessages()`）：

根据场景选择不同的提示词模板：

| 场景 | 提示词模板 | 包含内容 |
|:---|:---|:---|
| 仅 KB | `answer-chat-kb.st` | 系统提示 + 历史 + 知识库上下文 + 问题 |
| 仅 MCP | `answer-chat-mcp.st` | 系统提示 + 历史 + 工具结果 + 问题 |
| KB + MCP 混合 | `answer-chat-mcp-kb-mixed.st` | 以上全部 + 要求 LLM 融合两类信息 |

---

### 阶段 7：模型路由与容错（LLM Service）

文件：`infra-ai/.../chat/RoutingLLMService.java:100`

这是项目的"最后一道防线"，保证即使某个模型挂了，系统仍可用。

**选择算法**（`ModelSelector.selectChatCandidates()`）：

1. 根据 `deepThinking` 标志匹配首选模型
2. 按优先级排序候选模型
3. 过滤掉不支持必要能力的模型
4. 过滤掉当前不可用的模型（断路器 OPEN 状态）

**执行与容错**：

```
for (候选模型 in 排序后的列表) {
    if (!断路器允许调用) continue;
    
    result = 调用模型流式接口();
    if (首包在 60s 内到达) → 用此模型继续，退出循环
    else → 标记失败，可能触发断路器 OPEN，尝试下一个
}
```

**断路器**（`ModelHealthStore`）：

- **CLOSED**：正常调用
- **OPEN**：拒绝调用（冷却 30s），到期后转为 HALF_OPEN
- **HALF_OPEN**：只放行一个探测请求，成功则 CLOSED，失败则回到 OPEN

---

## 5. 七大关键知识点

### 知识点 1：RAG 的基本原理

- **Embedding**：把文本转为向量（高维空间中的坐标）
- **向量检索**：在向量空间中找"最近邻"（余弦相似度 / 欧氏距离）
- **检索 + 生成**：检索到的文本作为 Prompt 的一部分送给 LLM
- 本项目使用的向量数据库：Milvus（生产级）或 PG Vector（轻量级）
- 关键类：`MilvusVectorStoreService` / `PgVectorStoreService`

### 知识点 2：意图识别（Intent Classification）

这是"Agentic"的核心体现，让系统理解用户意图而不是盲目检索。

- **分类方法**：用 LLM 对预定义的意图节点打分（few-shot 分类）
- **意图树**：用树形结构组织意图，支持层级分类
- **置信度阈值**：低于 0.3 的意图被丢弃，防止噪声
- 关键类：`DefaultIntentClassifier`（`bootstrap/.../rag/core/intent/`）

### 知识点 3：查询改写（Query Rewriting）

- **查改写**：把口语化问题转为适合检索的书面语
- **子问题拆分**：处理复合问题
- **术语归一化**：同义词映射（领域字典表）
- 关键类：`QueryRewriteService` / `QueryTermMappingService`

### 知识点 4：多通道检索（Multi-Channel Retrieval）

- **单一检索的局限**：一个检索策略无法覆盖所有场景
- **通道设计**：不同通道负责不同类型的检索
- **结果融合**：去重 + 重排序 = 最终结果
- 关键类：`MultiChannelRetrievalEngine`、`SearchChannel` 接口

### 知识点 5：MCP 协议与工具调用

- **MCP = Model Context Protocol**：LLM 应用调用外部工具的标准协议
- **工具注册**：通过 `MCPToolRegistry` 发现和注册工具
- **参数提取**：用 LLM 从用户问题中提取工具所需的参数
- **并行执行**：多个 MCP 工具可并行调用
- 关键类：`MCPToolExecutor`、`MCPParameterExtractor`、`MCPToolRegistry`

### 知识点 6：模型容错（Failover + Circuit Breaker）

- **多模型候选**：配置多个 LLM，一个挂了自动切换
- **首包探测**：60s 内收到第一个 Token 才算模型可用
- **断路器**：三态机制防止反复调用已失败的模型
- **健康存储**：`ConcurrentHashMap` + 时间戳实现线程安全的健康状态管理
- 关键类：`RoutingLLMService`、`ModelHealthStore`、`ModelSelector`

### 知识点 7：全链路追踪（Observability）

- **Trace ID**：贯穿整个请求的唯一标识
- **@RagTraceNode 注解**：AOP 切面自动记录每个环节的耗时和结果
- **上下文传递**：`TransmittableThreadLocal` 跨线程池传递跟踪信息
- 关键类：`RagTraceContext`、`RagTraceAspect`

---

## 6. 学习路径建议

如果你是刚入门的初学者，建议按以下顺序学习：

### 第一阶段：打好 RAG 基础（1-2 周）

1. 理解 Embedding 和向量检索的基本概念（推荐动手用 ChromaDB 或 FAISS 做个小 demo）
2. 学习 Milvus 或 PG Vector 的基本用法
3. 本项目入口：`VectorStoreService` → `MilvusVectorStoreService`
4. 理解检索 → 重排序 → 生成的基本管线

### 第二阶段：理解 Agent 机制（1 周）

1. 重点阅读 `DefaultIntentClassifier`，理解 LLM 怎么做分类
2. 理解 `StreamChatPipeline.execute()` 的完整流程
3. 理解意图树的结构和加载方式

### 第三阶段：掌握工程化要点（1 周）

1. `RoutingLLMService` 的模型路由和断路器机制
2. `ChatRateLimitAspect` 的限流设计
3. `RagTraceAspect` 的全链路追踪
4. `ConversationMemoryService` 的记忆管理

### 第四阶段：MCP 与工具调用（3-4 天）

1. 阅读 `mcp-server` 模块的代码
2. 理解 JSON-RPC 协议和工具发现机制
3. 理解参数提取和工具执行流程

### 推荐阅读资料

- **论文**：[Retrieval-Augmented Generation for Knowledge-Intensive NLP Tasks](https://arxiv.org/abs/2005.11401)（Lewis et al. 2020）
- **论文**：[Searching for Best Practices in Retrieval-Augmented Generation](https://arxiv.org/abs/2407.01219)（检索增强生成的最佳实践综述）
- **MCP 协议**：[https://modelcontextprotocol.io/](https://modelcontextprotocol.io/)
- **本项目关键文件**：

| 目的 | 文件路径 |
|:---|:---|
| 管线编排 | `bootstrap/.../rag/service/pipeline/StreamChatPipeline.java` |
| 意图分类 | `bootstrap/.../rag/core/intent/DefaultIntentClassifier.java` |
| 多通道检索 | `bootstrap/.../rag/core/retrieve/MultiChannelRetrievalEngine.java` |
| 模型路由 | `infra-ai/.../chat/RoutingLLMService.java` |
| 断路器 | `infra-ai/.../model/ModelHealthStore.java` |
| MCP 服务端 | `mcp-server/.../mcp/endpoint/MCPDispatcher.java` |

---

> 本指南基于 Ragent 项目（拿个 offer 社群出品的 Agentic RAG 平台）编写，建议结合源码一起阅读效果更佳。
