# Ragent Agentic RAG 原理、源码与断点调试指南

> 本文不讲安装和启动。默认前后端、中间件、模型与知识库已经可用。
>
> 目标不是“页面能回答”，而是亲手沿着源码观察：系统如何理解问题、选择路径、召回证据、组织 Prompt，并把模型结果流式返回。

## 先说清楚：这个项目的 Agentic 在哪里

传统 RAG 常被简化为：

```text
用户问题 -> Embedding -> 向量 TopK -> 拼 Prompt -> LLM 回答
```

Ragent 的实际链路是：

```text
用户问题
  -> 加载会话记忆
  -> 术语归一化、问题改写、复杂问题拆分
  -> 对每个子问题做树形意图识别
  -> 判断是否需要澄清
  -> 动态选择 SYSTEM / KB / MCP
  -> 动态选择定向向量、全局向量、关键词等检索通道
  -> 多路结果融合、去重、Rerank
  -> 按 KB / MCP / Mixed 场景构造证据 Prompt
  -> 选择模型，首包探测，失败降级
  -> SSE 流式输出并记录 Trace
```

它的 Agentic 主要体现在四类决策：

1. **任务重构**：把依赖上下文的问题改写成独立问题，把复合问题拆成子问题。
2. **意图路由**：决定问题属于系统回答、知识库还是 MCP 工具。
3. **检索路由**：根据意图和置信度决定检索哪些知识库、启用哪些通道。
4. **运行时容错**：某个检索通道或模型失败时，允许降级而不是整条链路失败。

但要准确理解边界：当前核心不是一个自由规划、反复执行 “Thought -> Action -> Observation” 的 ReAct 循环，而是一条**带动态决策、并行执行和短路分支的确定性 Agentic RAG 工作流**。这一区分很重要。

## 你的学习成果

完成本文的实验后，你应该能回答：

- Query Rewrite 为什么不能只做同义词替换？
- 多问题拆分如何影响后续并行检索？
- 意图识别与向量检索分别解决什么问题？
- 定向检索和全局检索如何互相兜底？
- 关键词检索为什么仍然有价值？
- RRF、去重、Rerank 的先后顺序为什么不能随便换？
- Prompt 中哪些是指令，哪些是证据，哪些是用户问题？
- 为什么断点会从当前线程“跳走”？
- 哪些结果来自模型，哪些决策由 Java 代码确定？
- 这个项目与普通 RAG、ReAct Agent 的差别是什么？

## 调试前只做三项准备

1. 使用 IDEA 的 Debug 模式启动 `RagentApplication`。
2. 准备一个已经完成入库、能稳定命中的知识库。
3. 打开管理后台 Trace 页面和浏览器 Network 面板，作为断点之外的旁证。

不要一开始在几十个方法上下断点。每次实验只观察一个阶段，完成记录后清除断点，再进入下一阶段。

建议为每次实验记录：

| 输入问题 | 阶段输入 | 阶段输出 | 当前线程 | Trace 节点 | 你的结论 |
| --- | --- | --- | --- | --- | --- |
| 示例问题 | 原始问题 | 改写结果 | 线程名 | rewrite | 改写消除了指代 |

## 全链路源码地图

| 阶段 | 核心类 | 核心输出 |
| --- | --- | --- |
| 请求入口 | `RAGChatController#chat` | `SseEmitter` |
| 任务上下文 | `RAGChatServiceImpl#streamChat` | `StreamChatContext` |
| 总编排 | `StreamChatPipeline#execute` | 决定继续或短路 |
| 会话记忆 | `ConversationMemoryService#loadAndAppend` | `List<ChatMessage>` |
| 改写拆分 | `MultiQuestionRewriteService#rewriteWithSplit` | `RewriteResult` |
| 意图解析 | `IntentResolver#resolve` | `List<SubQuestionIntent>` |
| 意图分类 | `DefaultIntentClassifier#classifyTargets` | `List<NodeScore>` |
| 歧义判断 | `IntentGuidanceService#detectAmbiguity` | `GuidanceDecision` |
| 检索编排 | `RetrievalEngine#retrieve` | `RetrievalContext` |
| 多路召回 | `MultiChannelRetrievalEngine#retrieveKnowledgeChannels` | `List<RetrievedChunk>` |
| 向量检索 | `PgRetrieverService#retrieveGlobal` | 带相似度的 Chunk |
| 结果处理 | `DeduplicationPostProcessor`、`FusionPostProcessor`、`RerankPostProcessor` | 精炼后的 Chunk |
| Prompt | `RAGPromptService#buildStructuredMessages` | `List<ChatMessage>` |
| 模型路由 | `RoutingLLMService#streamChat` | 流式回调 |
| 输出事件 | `StreamChatEventHandler` | SSE 事件 |

---

## 实验一：先看总编排，而不是钻进细节

### 原理

Agentic RAG 的核心不是某一个模型调用，而是“根据中间状态选择下一步”。总决策点在：

```text
bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/pipeline/StreamChatPipeline.java
```

`execute` 的结构：

```text
loadMemory
  -> rewriteQuery
  -> resolveIntents
  -> handleGuidance       可短路
  -> handleSystemOnly     可短路
  -> retrieve
  -> handleEmptyRetrieval 可短路
  -> streamRagResponse
```

### 断点

只下三个断点：

1. `RAGChatController#chat`
2. `RAGChatServiceImpl#streamChat`
3. `StreamChatPipeline#execute`

### 观察

在 `RAGChatServiceImpl#streamChat` 查看：

- `actualConversationId`：一次会话的稳定标识。
- `taskId`：一次生成任务的标识。
- `callback`：模型内容最终如何回到 SSE。

进入 `StreamChatPipeline#execute` 后查看 `ctx`：

- 不可变输入：问题、会话、任务、用户、深度思考开关。
- 逐步填充的状态：`history`、`rewriteResult`、`subIntents`。

### 必须理解的异步边界

Controller 创建 `SseEmitter` 后很快返回，但回答尚未结束。任务通过排队限流器和线程池继续执行，所以：

- Web 请求线程与 Pipeline 线程可能不同。
- Step Into 不一定自动跨到另一个线程。
- 应在异步任务内部的方法入口下断点。

### 本实验结论

能够画出 `StreamChatContext` 状态随流程增长的图，并解释三个 `handleXxx` 为什么可以提前结束。

---

## 实验二：会话记忆与 Query Rewrite

### RAG 原理

用户在多轮对话中经常问：

```text
第一轮：公司的差旅报销流程是什么？
第二轮：它需要谁审批？
```

第二轮的“它”单独做 Embedding，检索效果会很差。

Query Rewrite 要把上下文依赖问题改写成可独立检索的问题，例如：

```text
公司的差旅报销流程需要谁审批？
```

这个项目还会拆分复合问题：

```text
OA 系统和保险系统分别有哪些数据安全要求？
```

可能得到：

```text
rewrittenQuestion: OA 系统和保险系统分别有哪些数据安全要求？
subQuestions:
  - OA 系统有哪些数据安全要求？
  - 保险系统有哪些数据安全要求？
```

### 对应代码

```text
bootstrap/.../rag/core/memory/DefaultConversationMemoryService.java
bootstrap/.../rag/core/rewrite/MultiQuestionRewriteService.java
bootstrap/src/main/resources/prompt/user-question-rewrite.st
```

`MultiQuestionRewriteService` 做了四件事：

1. 通过 `QueryTermMappingService.normalize` 做确定性术语归一化。
2. 带入最近对话历史。
3. 调用 LLM 输出 `rewrite` 和 `sub_questions` JSON。
4. JSON 解析或模型调用失败时，退回归一化问题。

这里的边界很清楚：

- Java 代码决定输入结构、低温参数、解析和兜底。
- LLM 负责语义改写和拆分。

### 断点

1. `StreamChatPipeline#loadMemory`
2. `DefaultConversationMemoryService#loadAndAppend`
3. `MultiQuestionRewriteService#rewriteWithSplit`
4. `MultiQuestionRewriteService#callLLMRewriteAndSplit`
5. `MultiQuestionRewriteService#parseRewriteAndSplit`

### 观察变量

- `history` 中角色顺序是否正确。
- `normalizedQuestion` 与原问题有什么差异。
- 发给改写模型的 `ChatRequest.messages`。
- 模型原始输出 `raw`。
- 最终 `RewriteResult.rewrittenQuestion`。
- `RewriteResult.subQuestions` 数量。

### 三组实验

| 输入 | 观察目标 |
| --- | --- |
| “报销咋弄？” | 术语归一化与改写 |
| 上轮问 OA，本轮问“它怎么申请？” | 历史是否消除指代 |
| “OA 和保险系统分别有什么要求？” | 是否拆成两个子问题 |

再临时用 Debugger 的 Evaluate Expression 比较配置关闭时的分支，不修改代码：

```text
ragConfigProperties.getQueryRewriteEnabled()
```

### 判断好坏

好的改写应当：

- 保留原意，不偷偷补充事实。
- 消除指代和口语歧义。
- 每个子问题可以独立检索。
- 不把一个简单问题过度拆分。

### 本实验结论

能够解释：Rewrite 优化的是“检索查询”，不是直接生成最终答案；多问题拆分增加召回针对性，但也增加模型调用和检索成本。

---

## 实验三：树形意图识别与 Agent 路由

### 原理

向量检索回答“哪些文本和问题语义相似”，意图识别回答“这个问题应该走哪种业务能力”。两者不是同一件事。

Ragent 的意图节点可以表示：

- `SYSTEM`：问候、通用系统回答等，不需要检索。
- `KB`：路由到某个知识库 Collection。
- `MCP`：路由到某个业务工具。

### 对应代码

```text
bootstrap/.../rag/core/intent/IntentResolver.java
bootstrap/.../rag/core/intent/DefaultIntentClassifier.java
bootstrap/.../rag/core/intent/IntentTreeCacheManager.java
bootstrap/src/main/resources/prompt/intent-classifier.st
```

执行过程：

1. `IntentResolver` 为每个子问题并行提交分类任务。
2. `DefaultIntentClassifier` 从 Redis/数据库加载意图树。
3. 只把叶子节点写入分类 Prompt。
4. LLM 返回 `id + score`。
5. Java 映射回 `IntentNode`，按分数排序。
6. `IntentResolver` 应用最低分和最大意图数限制。

### 断点

1. `IntentResolver#resolve`
2. `IntentResolver#classifyIntents`
3. `DefaultIntentClassifier#loadIntentTreeData`
4. `DefaultIntentClassifier#classifyTargets`
5. `IntentResolver#capTotalIntents`

### 观察变量

- `subQuestions`：每个子问题是否单独分类。
- `leafNodes`：候选空间到底有哪些意图。
- `systemPrompt`：模型是否被限制只能返回候选 ID。
- `raw`：模型的原始分类结果。
- `scores`：ID 是否成功映射到节点。
- 每个 `NodeScore` 的 `score`、`kind`、`collectionName`、`mcpToolId`。
- 最低分过滤前后数量。

### 实验输入

选择与当前意图树对应的三类问题：

1. 明确知识问题：应命中一个 KB 节点。
2. 明确业务实时问题：应命中一个 MCP 节点。
3. 问候语：应只命中 SYSTEM 节点。

再输入一个同时包含两个业务主题的问题，观察：

- 是否先被拆成多个子问题。
- 每个子问题是否在独立线程分类。
- `capTotalIntents` 如何限制总候选数。

### 关键思考

意图分数不是向量相似度。它是分类模型对业务路由的置信度。高相似文本不代表路由一定正确；路由正确也不代表检索一定有结果。

### 本实验结论

能够从 `NodeScore` 推导接下来会走 SYSTEM、KB、MCP 还是混合路径。

---

## 实验四：歧义澄清与短路

### 原理

Agentic 系统不应该在信息不足时盲目执行。如果多个候选意图接近，系统可以先向用户澄清。

对应代码：

```text
bootstrap/.../rag/core/guidance/IntentGuidanceService.java
bootstrap/.../rag/core/guidance/AmbiguityLLMChecker.java
bootstrap/.../rag/service/pipeline/StreamChatPipeline#handleGuidance
```

### 断点

1. `IntentGuidanceService#detectAmbiguity`
2. `IntentGuidanceService#findAmbiguityGroup`
3. `StreamChatPipeline#handleGuidance`

### 观察

- 是否只有单个子问题才进入歧义判断。
- 候选意图分差是否达到澄清条件。
- `GuidanceDecision.isPrompt`。
- 返回澄清问题后，`retrieve` 是否完全没有执行。

### 实验

从当前意图树中找两个描述接近的叶子节点，构造不包含区分信息的问题。若没有触发，先查看当前 guidance 配置和候选分数，不要为了触发而直接改业务代码。

### 本实验结论

能够解释“主动追问”为什么也是 Agentic 决策，以及它如何降低错误检索和错误工具调用的风险。

---

## 实验五：检索路由与多通道召回

### 检索原理

向量检索的大致过程：

```text
问题文本 q
  -> Embedding 模型
  -> 查询向量 vq
  -> 与文档 Chunk 向量 vd 计算余弦相似度
  -> 按相似度取候选
```

当前 PGVector 查询使用：

```sql
1 - (embedding <=> query_vector) AS score
```

`<=>` 是余弦距离，`1 - 距离` 转成相似度。向量检索擅长语义匹配，但对订单号、专有名词、精确短语不一定稳定，所以系统保留关键词通道。

### 检索编排层

```text
RetrievalEngine#retrieve
  -> 每个子问题并行 buildSubQuestionContext
  -> 将意图区分为 KB 与 MCP
  -> KB 进入 MultiChannelRetrievalEngine
  -> MCP 进入参数抽取与工具调用
  -> 汇总成 RetrievalContext
```

### 三个主要检索通道

| 通道 | 启用条件 | 解决的问题 |
| --- | --- | --- |
| `IntentDirectedSearchChannel` | 有达到阈值的 KB 意图 | 只检索目标知识库，提高精度 |
| `VectorGlobalSearchChannel` | 无意图、低置信度或需要补充召回 | 防止意图路由漏召回 |
| `KeywordSearchChannel` | 配置启用且实现可用 | 补充精确词、编号和专名匹配 |

### 断点

第一层：

1. `RetrievalEngine#retrieve`
2. `RetrievalEngine#buildSubQuestionContext`
3. `RetrievalEngine#retrieveAndRerank`

第二层：

4. `MultiChannelRetrievalEngine#retrieveKnowledgeChannels`
5. `MultiChannelRetrievalEngine#executeSearchChannels`
6. 三个 Channel 的 `isEnabled`
7. 实际启用 Channel 的 `search`

底层向量：

8. `PgRetrieverService#retrieveGlobal` 或 `PgRetrieverService#retrieve`
9. `PgRetrieverService#queryByCollections`
10. `EmbeddingService#embed`

### 观察变量

- `kbIntents` 与 `mcpIntents`。
- `finalTopK` 和意图节点自定义 `topK`。
- `enabledChannels` 的名称。
- `SearchContext.mainQuestion`。
- 目标 `collectionNames`。
- Embedding 向量长度与归一化前后范数。
- 每个 `SearchChannelResult` 的 Chunk 数、耗时和来源。
- `RetrievedChunk.id/text/score/metadata`。

### 四组实验

| 场景 | 预期 |
| --- | --- |
| 明确高置信 KB 意图 | 定向检索为主 |
| 模糊、低置信问题 | 全局向量兜底 |
| 包含精确编号或专名 | 比较关键词与向量结果 |
| 两个子问题 | 两个 `SubQuestionContext` 并行构造 |

### 必须注意

通道按优先级排序后提交，但通过 `CompletableFuture` 并行执行。优先级不等于完成顺序。断点可能交错，这是正常现象。

### 本实验结论

能够解释 Precision 与 Recall 的取舍：定向检索偏精度，全局检索偏召回，多通道组合是为了覆盖单一检索方式的盲区。

---

## 实验六：去重、RRF 与 Rerank

### 为什么召回后不能直接交给 LLM

多通道会产生：

- 同一 Chunk 被多路召回。
- 不同通道分数不可直接比较。
- 语义大致相关但并不能回答问题的 Chunk。
- 超过上下文预算的候选。

因此候选要经过后处理链。

### 去重

```text
DeduplicationPostProcessor#process
```

重点观察：

- 有 ID 时如何构造去重键。
- 没有 ID 时如何避免只用 `String.hashCode` 导致碰撞误删。
- 重复 Chunk 如何选择保留项。
- `score == null` 时如何处理。

仓库中的 `DeduplicationPostProcessorTest` 正好展示了哈希碰撞和空分数两个真实缺陷。

### RRF 融合

Reciprocal Rank Fusion 不直接比较不同通道的原始分数，而比较排名：

```text
RRF(d) = Σ 1 / (k + rank_i(d))
```

同一文档在多个通道都排名靠前时，融合分更高。对应：

```text
FusionPostProcessor#process
```

当前处理顺序是去重 `order=1`、融合 `order=5`、Rerank `order=10`。虽然生成去重列表在先，`FusionPostProcessor` 仍会读取各个 `SearchChannelResult` 的原始排名，按相同 Chunk key 累计多通道 RRF 分，因此不会丢掉“多路共同命中”的信号。融合后还会按 `rerankCandidateLimit` 截断候选池。

### Rerank

Embedding 检索通常是双编码：

```text
query -> vector
document -> vector
独立编码后快速计算相似度
```

Reranker 通常联合看 query 与 document：

```text
reranker(query, document) -> relevance score
```

它更慢，但对少量候选排序更准。因此正确策略通常是：

```text
宽召回 -> 融合/去重 -> 限制候选 -> Rerank -> TopK
```

对应：

```text
RerankPostProcessor#process
RoutingRerankService
```

### 断点

1. `MultiChannelRetrievalEngine#executePostProcessors`
2. 每个 Processor 的 `isEnabled`、`getOrder`、`process`
3. `RoutingRerankService#rerank`

每执行一个 Processor，都记录：

| Processor | 输入数量 | 输出数量 | 分数含义 | 排名变化 |
| --- | ---: | ---: | --- | --- |
| Dedup | | | 保留项分数 | |
| Fusion/RRF | | | 融合排名分 | |
| Rerank | | | 相关性分 | |

### 关键思考

- 去重键与 RRF 融合键必须一致，否则同一 Chunk 无法正确累计跨通道排名。
- Rerank 前候选太多会增加成本和延迟。
- Rerank 后再使用旧向量分数排序，会破坏精排结果。
- TopK 不是越大越好，噪声会挤占 Prompt 上下文。

### 本实验结论

能够拿一条 Chunk，说明它从哪个通道进入、如何融合、是否与其他结果重复、Rerank 后为什么上升或下降。

---

## 实验七：从检索结果到 Grounded Prompt

### 原理

RAG 的“增强”不是让模型访问数据库，而是把检索证据序列化进模型消息。模型最终仍只看到一组 `ChatMessage`。

对应代码：

```text
bootstrap/.../rag/core/prompt/DefaultContextFormatter.java
bootstrap/.../rag/core/prompt/RAGPromptService.java
bootstrap/src/main/resources/prompt/context-format.st
bootstrap/src/main/resources/prompt/answer-chat-kb.st
bootstrap/src/main/resources/prompt/answer-chat-mcp.st
bootstrap/src/main/resources/prompt/answer-chat-mcp-kb-mixed.st
```

`RAGPromptService` 会根据 `PromptContext` 选择：

- `KB_ONLY`
- `MCP_ONLY`
- `MIXED`

最终消息顺序：

```text
System: 回答规则和边界
History: 会话历史或摘要
User: <证据结构> + <用户问题或多个子问题>
```

### 断点

1. `DefaultContextFormatter#formatKbContext`
2. `RAGPromptService#plan`
3. `RAGPromptService#buildEvidenceBody`
4. `RAGPromptService#buildUserQuestion`
5. `RAGPromptService#buildStructuredMessages`
6. `StreamChatPipeline#streamLLMResponse`

### 观察变量

- `RetrievalContext.kbContext`。
- `RetrievalContext.mcpContext`。
- `intentChunks` 如何按意图节点组织。
- `PromptBuildPlan.scene`。
- `systemPrompt` 使用哪个模板。
- `evidenceBody` 是否只包含真正召回的证据。
- 最终 `messages` 的角色、顺序和长度。
- 多子问题是否保留各自的问题与证据边界。

### 防幻觉学习点

Prompt 中的规则要求模型只依据资料回答，但这只是约束，不是数学保证。Grounding 的质量还依赖：

1. 入库内容是否正确。
2. Query Rewrite 是否保持原意。
3. 意图路由是否正确。
4. 检索是否召回关键证据。
5. Rerank 是否把关键证据排到前面。
6. 上下文是否被正确格式化且未截断。

所以“模型幻觉”不能一律归因于模型，也可能是前面任何一层提供了错误或不足的证据。

### 实验

同一个问题分别观察：

1. 正常证据命中。
2. 无证据命中。
3. 两个子问题各有证据。
4. KB 与 MCP 混合证据。

不要把完整敏感 Prompt 复制到日志或截图中。

### 本实验结论

能够指出最终答案中的每个事实由哪一段 Chunk 支撑，并定位“没有依据的内容”究竟是检索缺失还是生成越界。

---

## 实验八：模型路由、首包探测与 SSE

### 原理

RAG 在 Prompt 构造完后，才进入生成阶段。生成失败不代表前面的检索失败。

对应代码：

```text
infra-ai/.../chat/RoutingLLMService.java
infra-ai/.../model/ModelSelector.java
infra-ai/.../model/ModelHealthStore.java
infra-ai/.../chat/ProbeStreamBridge.java
bootstrap/.../rag/service/handler/StreamChatEventHandler.java
frontend/src/hooks/useStreamResponse.ts
frontend/src/stores/chatStore.ts
```

模型路由过程：

1. `ModelSelector` 根据普通/深度思考选择候选。
2. `ModelHealthStore.allowCall` 跳过熔断模型。
3. Client 发起流式请求。
4. `ProbeStreamBridge` 等待首包。
5. 首包成功后确认该模型可用。
6. 启动失败、超时或无内容时取消并切换候选。

### 断点

1. `RoutingLLMService#streamChat`
2. `ModelSelector#selectChatCandidates`
3. `ModelHealthStore#allowCall`
4. 具体 `ChatClient#streamChat`
5. `RoutingLLMService#awaitFirstPacket`
6. `StreamChatEventHandler#onContent`
7. `StreamChatEventHandler#onComplete`

### 观察变量

- `request.messages`：确认生成模型看到的最终内容。
- `targets`：候选顺序。
- `target.id/provider/model`。
- `healthStore` 当前状态。
- 首包结果类型和耗时。
- 每次 `onContent` 的增量片段。
- `taskId` 与 `conversationId` 是否贯穿事件。

### 浏览器旁证

Network 选中 `/rag/v3/chat`：

- `meta` 事件应先提供任务信息。
- 内容事件逐段到达。
- 完成、错误和取消应有明确终态。

### 本实验结论

能够把总延迟拆成：改写耗时、意图耗时、检索耗时、Rerank 耗时、模型首包耗时和完整生成耗时，而不是只说“模型很慢”。

---

## 实验九：反向调试知识如何进入向量库

这一实验不是启动教程，而是理解检索结果从哪里来。没有正确入库，就没有可靠 RAG。

### 入库链路

```text
FetcherNode
  -> ParserNode
  -> EnhancerNode
  -> ChunkerNode
  -> EnricherNode
  -> IndexerNode
  -> EmbeddingService
  -> PgVectorStoreService
```

### 核心知识

**解析**：不同文件格式先变成可处理的统一内容。

**分块**：将长文档拆成检索单元。Chunk 太大，混入无关内容；太小，语义和上下文不完整。

**Overlap**：相邻 Chunk 保留部分重叠，减少答案刚好跨边界时的语义断裂，但会增加重复召回。

**Embedding**：把 Chunk 转成固定维度向量。入库模型与查询模型的向量空间必须一致。

**索引**：当前 PGVector 使用 HNSW 加速近似最近邻检索。它提升速度，但参数和过滤条件也会影响召回。

### 断点

1. `ChunkerNode` 中分块策略入口。
2. 具体 ChunkStrategy 的分块方法。
3. `IndexerNode`。
4. `ChunkEmbeddingService#embed`。
5. `PgVectorStoreService#indexDocumentChunks`。

### 观察

- 原文长度、Chunk 数量。
- 每个 Chunk 的起止边界和重叠内容。
- Chunk metadata 是否包含文档和知识库标识。
- Embedding 维度。
- 写入的 `collection_name`。
- 同一 Chunk 在问答时是否能通过 `RetrievedChunk.id` 反查回来。

### 对照实验

选一份短文档，以不同分块大小做两次独立实验，分别记录：

| 分块策略 | Chunk 数 | 目标问题排名 | TopK 是否含答案 | 重复内容 |
| --- | ---: | ---: | --- | --- |
| 较小 Chunk | | | | |
| 较大 Chunk | | | | |

不要只比较“最终答对没有”，还要比较目标 Chunk 的排名和噪声数量。

---

## 用 Trace 复盘整条链路

代码中通过 `@RagTraceNode` 标记了关键阶段，例如：

```text
query-rewrite-and-split
intent-resolve
guidance-detect
retrieval-engine
multi-channel-retrieval
llm-stream-routing
```

流式任务跨线程，普通 AOP 只能看到任务提交，`StreamChatTraceRunner` 和 `RagStreamTraceSupportImpl` 用于维持完整 Trace。

完成一次问答后，按 `taskId` 对照：

| Trace 节点 | 断点中看到的输入 | 断点中看到的输出 | 耗时 | 是否降级 |
| --- | --- | --- | ---: | --- |
| rewrite | | | | |
| intent | | | | |
| retrieval | | | | |
| rerank | | | | |
| LLM | | | | |

如果 Trace 与断点认知不一致，优先检查：

- 是否发生短路。
- 是否切换了异步线程。
- 是否在异常捕获中降级为空结果。
- 是否实际走了另一个 Spring Bean 实现。

## 推荐的完整单步顺序

第一次全链路调试只保留下列方法断点：

1. `StreamChatPipeline#execute`
2. `MultiQuestionRewriteService#rewriteWithSplit`
3. `IntentResolver#resolve`
4. `DefaultIntentClassifier#classifyTargets`
5. `IntentGuidanceService#detectAmbiguity`
6. `RetrievalEngine#retrieve`
7. `MultiChannelRetrievalEngine#executeSearchChannels`
8. `MultiChannelRetrievalEngine#executePostProcessors`
9. `RAGPromptService#buildStructuredMessages`
10. `RoutingLLMService#streamChat`
11. `StreamChatEventHandler#onContent`

每到一个断点，只回答四个问题：

1. 当前输入是什么？
2. 当前代码在做确定性处理，还是调用 LLM 做语义判断？
3. 输出会决定哪个下游分支？
4. 失败时是终止、短路，还是降级？

## 五组建议测试问题

具体内容要替换成你当前知识库和意图树中真实存在的主题。

| 编号 | 问题类型 | 学习目标 |
| --- | --- | --- |
| A | 明确的单知识库问题 | 跑通最短 KB RAG |
| B | “它怎么办？”式追问 | 观察记忆与指代改写 |
| C | 同时问两个主题 | 观察拆分、并行意图和检索 |
| D | 模糊地跨两个相近意图 | 观察澄清或全局兜底 |
| E | 一个知识问题 + 一个实时业务问题 | 观察 KB + MCP Mixed Prompt |

每组都保存：

- 原始问题。
- 改写问题与子问题。
- 意图及分数。
- 启用的检索通道。
- 召回前五条及其分数。
- Rerank 后前五条。
- 最终 Prompt 中的证据。
- Trace 各阶段耗时。
- 最终答案是否能逐句找到依据。

## 常见误区

| 误区 | 正确认识 |
| --- | --- |
| 使用了向量数据库就是 Agentic RAG | 向量库只是检索基础设施 |
| 意图分数就是向量相似度 | 一个是业务分类置信度，一个是文本相似度 |
| TopK 越大回答越好 | 噪声和上下文成本也会增大 |
| Rerank 可以替代召回 | Rerank 只能重排已经召回的候选 |
| Prompt 写得严格就不会幻觉 | 错误或缺失证据仍会导致错误回答 |
| Controller 返回就是请求完成 | SSE 任务仍在异步执行 |
| 断点没按顺序就是代码有问题 | 多个线程并行时顺序本来就不稳定 |
| 最终回答正确就证明 RAG 正确 | 模型可能靠参数知识答对，必须检查证据 |
| 有 MCP 意图就证明调用了工具 | 必须用 Executor 断点或 Trace 证明 |
| 这是完整 ReAct Agent | 当前核心是带决策的工作流，不是自由循环 Agent |

## 最终验收

- [ ] 能画出从 `question` 到 `ChatRequest.messages` 的数据变化。
- [ ] 能解释 `RewriteResult` 两个字段的作用。
- [ ] 能从 `NodeScore` 判断 SYSTEM、KB、MCP 路由。
- [ ] 能解释歧义澄清为什么会短路检索。
- [ ] 能说出三个检索通道各自的启用条件。
- [ ] 能解释余弦相似度、TopK、Recall 与 Precision。
- [ ] 能解释 RRF、去重、Rerank 的顺序。
- [ ] 能找到最终 Prompt 中证据与问题的边界。
- [ ] 能证明答案依据来自实际召回 Chunk，而非模型记忆。
- [ ] 能识别所有主要异步边界。
- [ ] 能用 Trace 拆解一次问答的阶段耗时。
- [ ] 能解释当前实现为什么是 Agentic Workflow，而不是 ReAct Loop。
- [ ] 能从检索结果反查到入库 Chunk 和原文。

## 延伸阅读顺序

1. [现有 Agentic RAG 学习指南](./agentic-rag-learning-guide.md)
2. [多通道检索设计](./multi-channel-retrieval.md)
3. [整体架构](./ragent-architecture.md)
4. `bootstrap/src/main/resources/prompt/` 下的真实 Prompt
5. `bootstrap/src/test/` 下的改写、意图、检索与去重测试

读源码时始终沿着“输入 -> 状态变化 -> 决策 -> 输出”前进。不要从类名开始背，也不要一次读完整个包。
