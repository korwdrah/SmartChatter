# RAG 技术演进与 Agentic RAG 架构全景

## 一、RAG 的核心逻辑

RAG（Retrieval-Augmented Generation，检索增强生成）由两大模块构成：

- **检索模块**：从预设知识库中定位与用户问题相关的信息片段
- **生成模块**：基于检索到的信息，结合 LLM 生成逻辑连贯的答案

这种"先检索再生成"的模式，既保留了 LLM 的语言理解与生成能力，又通过外部知识弥补了模型训练数据过时、事实准确性不足的缺陷。

四种 RAG 模式的差异，本质上是对 **检索效率、生成质量、系统灵活性** 的不同优化方向。

---

## 二、Naive RAG（朴素 RAG）

### 2.1 架构

```
用户问题 → 向量检索 Top-K → 拼接 Prompt → LLM 生成
```

最简单的线性流水线，不可逆，无优化环节。

### 2.2 核心问题（具体例子）

**问题 1：检索精度低 — 语义理解弱的 query 召回大量噪声**

```
用户问："报销流程是什么？"
知识库中有："员工差旅费用申请与报销管理办法.pdf"

Naive RAG：如果 query embedding 与文档 chunk 的向量距离较远
（比如用户用了口语化表达，而文档用的是正式公文用语），
可能完全召回不到这篇文档，LLM 只能"胡编"。
```

**问题 2：无质量校验 — 检索到什么就用什么**

```
用户问："Java 17 有哪些新特性？"
知识库中有：
  - Chunk A："Java 17 是 LTS 版本，2021年9月发布"  ← 相关
  - Chunk B："公司年会定于2024年1月举办"           ← 完全无关

Naive RAG：两个 chunk 都被送进 LLM，LLM 可能回答：
"Java 17 于 2021 年 9 月发布，公司年会定于 2024 年 1 月举办。"
→ 无关信息污染了答案。
```

**问题 3：固定分块破坏语义**

```
文档原文："Record 类是 Java 14 引入的预览特性，经过两个版本的迭代，
在 Java 16 中正式转正。它提供了一种简洁的语法来创建不可变数据载体。"

Naive RAG 按 512 字符硬切分：
  Chunk 1："...Record 类是 Java 14 引入的预览特性，经过两个版本的迭代，在 Java 16"
  Chunk 2："中正式转正。它提供了一种简洁的语法来创建不可变数据载体。"

→ "在 Java 16" 和 "中正式转正" 被切断，语义不完整。
```

**问题 4：无法处理代词/上下文指代**

```
对话上下文：
  用户："我们公司的技术栈是什么？"
  助手："公司主要使用 Spring Boot 3.x + Vue 3 + MySQL 8.0。"
  用户："它的部署方式是什么？"   ← "它" 指 Spring Boot

Naive RAG：直接拿 "它的部署方式是什么？" 去检索，
向量匹配的是"部署方式"相关文档，可能返回 Docker、K8s 的通用文档，
而不是公司具体的 Spring Boot 部署方案。
```

**问题 5：复杂问题无法多步推理**

```
用户问："对比我们公司的两种权限模型，各自适合什么场景？"

这需要：
  1. 检索到"权限模型 A"的文档
  2. 检索到"权限模型 B"的文档
  3. 理解两者的差异
  4. 结合业务场景给出建议

Naive RAG：单次检索，最多只能拿到其中一个模型的文档。
```

---

## 三、Advanced RAG（高级 RAG）

### 3.1 架构

```
用户问题 → 查询改写/扩展 → 混合检索(KNN + BM25) → 重排序 → 上下文压缩 → LLM 生成
```

在 Naive RAG 基础上，对每个环节做优化。

### 3.2 解决了什么（对照上面的例子）

**解决 1：混合检索 + 重排序**

```
用户问："报销流程是什么？"

Advanced RAG：
  - BM25 关键词匹配：命中 "报销" "流程" 关键词 → 召回相关文档
  - KNN 向量检索：即使口语化表达，语义相似度也能匹配
  - 两路结果合并后，CrossEncoder 重排序 → 精确的 Top-5

结果：成功召回 "员工差旅费用申请与报销管理办法.pdf"
```

**解决 2：查询改写解决代词问题**

```
对话上下文：
  用户："我们公司的技术栈是什么？"
  助手："公司主要使用 Spring Boot 3.x + Vue 3 + MySQL 8.0。"
  用户："它的部署方式是什么？"

Advanced RAG：查询改写模块将 "它的部署方式是什么？" 改写为
"Spring Boot 3.x 的部署方式是什么？"，然后拿改写后的 query 去检索。

结果：精确命中公司 Spring Boot 部署方案文档。
```

**解决 3：动态分块 + 语义感知**

```
文档原文关于 Record 类的完整段落，通过语义分块
（基于段落边界、HanLP 中文分词、512 字符上限）
被完整保留在一个 chunk 中，不再被机械切断。
```

### 3.3 仍然存在的问题

**问题 1：仍是单次、单向流水线**

```
用户问："对比两种权限模型的差异"

Advanced RAG：即使查询改写为 "权限模型 A 差异 权限模型 B"，
单次检索也很难同时精确召回两个模型的完整信息。
→ 没有机制说"第一次检索不够，再检索一次"。
```

**问题 2：所有 query 走同一条路径**

```
"你好"          → 也走完整的 改写→检索→重排序→生成 流程
"报销流程"      → 也走完整的 改写→检索→重排序→生成 流程
"对比分析..."    → 也走完整的 改写→检索→重排序→生成 流程

简单寒暄浪费了一次检索调用（~500ms），复杂分析又只有一次检索机会。
```

---

## 四、Modular RAG（模块化 RAG）

### 4.1 架构

```
┌─────────┐   ┌──────────┐   ┌─────────┐   ┌─────────┐
│ Router  │ → │ Retriever│ → │ Reranker│ → │Generator│
└─────────┘   └──────────┘   └─────────┘   └─────────┘
     ↑              ↑
     └── 可插拔模块 ──┘  (Rewriter, Memory, Fusion...)
```

将系统拆分为独立模块，各模块通过标准化接口通信，可独立升级或替换。

### 4.2 编排模式

```
线性编排：  A → B → C → D
条件编排：  A → [条件] → B 或 C
并行编排：  A → B ┐
                  ├→ 合并 → D
           A → C ┘
循环编排：  A → B → [评估] → 不满足 → 回到 A
```

### 4.3 解决了什么

- **组件解耦**：新增数据源只需加模块，不改核心流程
- **编排灵活**：支持条件、并行、循环编排（上面 PaiSmart 的 agent 路径就是一种循环编排）

### 4.4 仍然存在的问题

- **编排逻辑是预设的**：虽然模块可插拔，但流程是代码写死的
- **没有自主决策能力**：系统不会自己判断"检索结果够不够"

---

## 五、Agentic RAG（智能体 RAG）

### 5.1 核心突破

从 **"流水线"** 到 **"闭环"**，从 **"固定流程"** 到 **"动态决策"**。

```
Naive/Advanced RAG：  查询 → 检索 → 生成（一条直线，走完就结束）
Agentic RAG：         查询 → 判断 → 检索 → 评估 → 够吗？→ 不够 → 改写 → 再检索 → 再评估 → 生成
```

Agent 自主决定：
- **是否需要检索**（"你好"不需要，"报销流程"需要）
- **检索什么**（复杂问题拆解为子查询）
- **检索结果够不够**（评估 + 改写 + 补充检索循环）
- **什么时候停止**（达到质量阈值或超时兜底）

### 5.2 Singh et al. (2025) 四大 Agentic 设计模式

参考论文：[Agentic Retrieval-Augmented Generation: A Survey on Agentic RAG](https://arxiv.org/abs/2501.09136)（被引 300+）

这四种模式由 Andrew Ng 在 DeepLearning.AI "Agentic Design Patterns" 系列中提出，Singh et al. 系统性地将其应用到 RAG 领域：

| 模式 | 含义 | 在 RAG 中的体现 |
|------|------|----------------|
| **Reflection（反思）** | Agent 自我批评并迭代改进输出 | 评估检索结果质量，不足时重试 |
| **Tool Use（工具使用）** | Agent 调用外部工具扩展能力 | 调用知识库检索、Web 搜索、计算器等 |
| **Planning（规划）** | 将复杂任务分解为子目标 | 将复杂问题拆解为多个子查询并行检索 |
| **Multi-Agent（多智能体）** | 多个专职 Agent 协作 | 检索 Agent、评估 Agent、生成 Agent 各司其职 |

### 5.3 Agentic RAG 的主要类型

#### 类型 1：Router-Based（路由型）

**代表**：Adaptive RAG（论文 2024）

```
用户问题 → 查询分类器 → [不需要检索 → 直接回答]
                        [需要单次检索 → 标准 RAG]
                        [需要多步检索 → 多步 RAG]
```

核心是一个查询分类器，根据问题复杂度路由到不同处理管道。

**例子**：
```
"你好"                              → 路由到 "直接回答"，节省 ~1s
"什么是 Spring Boot？"              → 路由到 "单次 RAG"
"对比公司三种部署方案的优劣势"        → 路由到 "多步 RAG"
```

优点：简单问题跳过检索，响应快
缺点：路由后仍是单次检索（多步路径除外），无迭代

#### 类型 2：Planner-Based（规划型）

```
"公司 2023 年营收下降的原因是什么？结合财报和行业趋势分析"

→ Planner 分解：
  子查询 1："公司 2023 年财报营收数据"
  子查询 2："2023 年行业整体趋势"
  子查询 3："公司主要竞争对手 2023 年表现"

→ 并行检索 3 个子查询 → 合并结果 → 生成综合分析
```

核心是将复杂查询分解为多个子查询，并行检索后聚合。适合多跳推理（multi-hop reasoning）。

#### 类型 3：Iterative / Reflective（迭代/反思型）

**三大代表论文对比**：

##### CRAG（Corrective RAG，校正型 RAG）

论文：Corrective Retrieval Augmented Generation（2024）

```
查询 → 检索 → [轻量评估器打分]
  ├─ 高分（正确）  → 直接使用 → 生成
  ├─ 低分（错误）  → 丢弃 → Web 搜索兜底 → 生成
  └─ 中分（模糊）  → 保留部分 + Web 搜索补充 → 生成
```

**例子**：
```
用户问："Java 21 的虚拟线程怎么用？"
知识库检索结果：
  - "Java 21 于 2023 年 9 月发布，是最新 LTS 版本"        ← 低分（只有概述）
  - "虚拟线程是 Project Loom 的核心特性，通过 Thread.ofVirtual() 创建" ← 高分

评估器：第一个 chunk 打低分 → 丢弃
        第二个 chunk 打高分 → 保留
```

特点：不需要微调，即插即用，有 Web 搜索兜底

##### Self-RAG（自反思型 RAG）

论文：Learning to Retrieve, Generate, and Critique through Self-Reflection（2023）

```
查询 → [模型自判：需要检索吗？]
  → 检索 → [模型自判：文档相关吗？]
  → 生成 → [模型自判：有证据支撑吗？]
  → [模型自判：答案有用吗？]
```

关键区别：反思能力是 **内化到模型权重中** 的（通过特殊反思 token 训练），不需要外部评估组件。

特点：需要微调，延迟最低（反思融入生成过程），事实准确性最高

##### Adaptive RAG（自适应型 RAG）

论文：Adaptive-RAG: Learning to Adapt Retrieval-Augmented Large Language Models through Question Complexity（2024）

```
查询 → [复杂度分类器]
  ├─ 简单（事实类）   → 不检索，直接回答
  ├─ 中等（单主题）   → 单次检索 + 生成
  └─ 复杂（多跳推理） → 多步检索 + 多次修正
```

**三种反思型 RAG 对比**：

| 维度 | CRAG | Self-RAG | Adaptive RAG |
|------|------|----------|-------------|
| 校正时机 | 检索后、生成前 | 生成全程 | 检索前（决策层） |
| 校正对象 | 检索结果质量 | 检索 + 生成质量 | 检索策略选择 |
| 需要额外模型 | 轻量评估器 | 不需要（内化到权重） | 查询分类器 |
| 需要微调 | 不需要 | 需要 | 需要 |
| Web 搜索兜底 | 有 | 无 | 无 |
| 落地难度 | 低 | 高 | 中 |

##### 三者可以组合

```
Adaptive 决定策略 → CRAG 校正检索 → Self-RAG 校正生成
```

#### 类型 4：Multi-Agent（多智能体型）

```
用户问题 → Orchestrator Agent
              ├─ → Retriever Agent（专职检索）
              ├─ → Critic Agent（专职评估质量）
              ├─ → Synthesizer Agent（专职综合生成）
              └─ → 协调执行顺序和依赖关系
```

多个专职 Agent 协作，各自负责 RAG 管道的不同环节。

优点：各 Agent 专注一个任务，容错性好
缺点：Agent 间通信开销大，调试极其复杂

#### 类型 5：Tool-Augmented（工具增强型）

```
用户问题 → Agent 判断需要什么
  ├─ 需要知识 → 调用知识库检索工具
  ├─ 需要计算 → 调用计算器工具
  ├─ 需要实时数据 → 调用 API 工具
  └─ 需要代码执行 → 调用 Code Interpreter
```

检索只是众多工具之一，Agent 自主决定用哪个工具。

---

## 六、四大模式总览对比

| 维度 | Naive RAG | Advanced RAG | Modular RAG | Agentic RAG |
|------|-----------|--------------|-------------|-------------|
| 架构 | 线性流水线 | 多环节优化流水线 | 可插拔模块化 | Agent 闭环决策 |
| 检索策略 | 单次向量检索 | 混合检索 + 重排序 | 可定制检索策略 | 动态多轮检索 |
| 查询处理 | 原始 query | 改写/扩展 | 可插拔改写模块 | Agent 自主规划 |
| 质量控制 | 无 | 重排序过滤 | 模块级过滤 | 评估 → 改写 → 重试循环 |
| 复杂问题 | 不支持 | 单轮支持 | 依赖模块组合 | 多步推理 + 自我修正 |
| 延迟 | 低 (~500ms) | 中 (~1-2s) | 中 (~1-2s) | 高 (~3-10s) |
| 典型场景 | FAQ 问答 | 企业 helpdesk | 多源数据平台 | 金融投研、法律咨询 |

---

## 七、PaiSmart 的 Agentic RAG 架构分析

### 7.1 架构总览

```
用户消息 → ChatHandler
  → QueryRouter.classify()
    ├─ [direct]  → 轻量 system prompt → GLM 流式生成（寒暄/闲聊，不检索）
    ├─ [rag]     → 单次 HybridSearchService.searchWithPermission() → GLM 流式生成
    └─ [agent]   → AgentOrchestrator.executeAsync()
                     → PlannerAgent.plan()（1-4 个子查询）
                     → 并行 KnowledgeSearchTool（每个子查询）
                     → 去重 + 排序 + 截断 Top-10
                     → EvaluateResultsTool（评估结果是否充分）
                     → 若不充分：QueryRewriteTool → 补充检索 → 合并
                     → 失败/超时 → 降级到 rag 路径
                   → GLM 流式生成
```

### 7.2 模式对照

| Agentic 模式 | PaiSmart 实现 | 对应组件 |
|---|---|---|
| **Router-Based** | 三路分类：direct / rag / agent | `QueryRouter` |
| **Planner-Based** | 复杂查询分解为 1-4 个子查询并行检索 | `PlannerAgent` |
| **Iterative / Reflective** | 评估 → 改写 → 补充检索循环（类 CRAG） | `EvaluateResultsTool` → `QueryRewriteTool` |
| **Tool-Use** | Agent 调用知识库检索工具 | `KnowledgeSearchTool` |
| **Multi-Agent** | 未实现（单 Agent 编排） | — |

用 Singh et al. 四大模式对照：

| 模式 | 是否具备 | 说明 |
|---|---|---|
| **Reflection** | 有 | `EvaluateResultsTool` 评估检索质量，不足时触发重试 |
| **Tool Use** | 有 | `KnowledgeSearchTool` 封装检索能力 |
| **Planning** | 有 | `PlannerAgent` 将复杂问题分解为子查询 |
| **Multi-Agent** | 无 | 单 `AgentOrchestrator` 编排所有工具，没有多 Agent 协作 |

### 7.3 分类结论

PaiSmart 属于 **Router + Planner + CRAG-like Reflection 混合型 Agentic RAG**，
业界也称为 **"Routed Agentic RAG with Self-Correction"**。

具体来说：
- **Router 层**类似 Adaptive RAG，根据问题类型路由到不同处理路径
- **Agent 路径**在路由基础上增加了 Planner（查询分解）和 Reflective Loop（评估 → 改写 → 补充检索），这是 CRAG 的核心思想
- 与纯 Adaptive RAG 的区别：Adaptive RAG 路由后仍是单次检索，PaiSmart 在 agent 路径上有完整的迭代循环

### 7.4 生产级设计亮点

| 设计 | 说明 |
|------|------|
| 信号量限流 | `Semaphore(20)` + 3s 超时，防止高并发打垮系统 |
| 超时兜底 | 总超时 15s，单工具超时 5s，超时自动降级到 rag 路径 |
| 失败降级 | Agent 路径任何异常 → 回退到标准 RAG，保证可用性 |
| 权限透传 | `AgentContext`（ThreadLocal）将 userId 传递到工具层，保证多租户隔离 |
| 四级线程池 | chat / agent / llm / tool 四个隔离池，避免互相争抢 |

### 7.5 潜在演进方向

1. **生成后反思**：目前 `EvaluateResultsTool` 只在检索后评估，可以加一步生成后的事实核查（Self-RAG 思想）
2. **Multi-Agent**：把评估和检索拆成独立 Agent，支持更灵活的协作模式
3. **记忆机制**：Agent 记住跨会话的检索偏好和常见改写模式
4. **更多工具**：接入 Web 搜索、代码执行、SQL 查询等外部工具（Tool-Augmented）

---

## 参考资料

- [Agentic Retrieval-Augmented Generation: A Survey (Singh et al., 2025)](https://arxiv.org/abs/2501.09136)
- [Corrective RAG (CRAG) 论文](https://arxiv.org/abs/2401.15884)
- [Self-RAG 论文](https://arxiv.org/abs/2310.11511)
- [Adaptive-RAG 论文](https://arxiv.org/abs/2403.14486)
- [RAG 技术路线图（火山引擎）](https://developer.volcengine.com/articles/7408394331880751116)
- [深度解读 RAG 技术发展历程（掘金）](https://juejin.cn/post/7407340295116161024)
- [一文讲清楚 RAG 四大模式（CSDN）](https://grapecity.csdn.net/6881a7b5080e555a88d1e3d0.html)
- [From Naive to Agentic: A Developer's Guide (DEV.to)](https://dev.to/ayas_tech_2b0560ee159e661/from-naive-to-agentic-a-developers-guide-to-rag-architectures-4hap)
- [The 4 RAG Architectures (Towards AI)](https://pub.towardsai.net/the-4-rag-architectures-how-to-give-ai-perfect-memory-without-retraining-a9645d9b516d)
- [Advanced RAG: Comparing GraphRAG, CRAG, and Self-RAG (Towards AI)](https://pub.towardsai.net/advanced-rag-comparing-graphrag-corrective-rag-and-self-rag-00491de494e4)
- [Microsoft: AI Agents for Beginners — Agentic RAG](https://microsoft.github.io/ai-agents-for-beginners/05-agentic-rag/)
- [18 种 RAG 技术实战对比](https://apframework.com/blog/essay/2025-04-09-18-RAG-Techniques)
