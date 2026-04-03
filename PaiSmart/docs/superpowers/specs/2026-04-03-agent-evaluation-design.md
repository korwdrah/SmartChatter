# Agent 评估测试系统设计

## 1. 概述

### 1.1 目标

为 PaiSmart 的 Agentic RAG 系统建立基线评估体系，量化各组件性能，为后续优化提供对照基准。

### 1.2 评估范围

| 组件 | 优先级 | 说明 |
|------|--------|------|
| QueryRouter | P0 | 三路分类准确率 |
| PlannerAgent | P0 | 子查询拆解质量 |
| EvaluateResultsTool | P0 | 结果充分性评估准确度 |
| QueryRewriteTool | P0 | 查询重写效果 |
| KnowledgeSearchTool | P1 | 检索质量（逻辑预留） |

### 1.3 评估方法

- **规则评估**：确定性指标，无需 LLM 参与
- **LLM-as-Judge**：语义质量评估，使用独立 LLM 评委

---

## 2. 架构设计

### 2.1 目录结构

```
src/test/java/com/yizhaoqi/smartpai/agent/evaluation/
├── AgentEvaluationSuite.java           # 总入口，聚合所有指标
├── testdata/
│   ├── AgentTestData.java              # 测试数据容器
│   ├── RouterTestData.java             # Router 测试数据
│   ├── PlannerTestData.java            # Planner 测试数据
│   ├── EvaluatorTestData.java          # Evaluator 测试数据
│   └── RewriterTestData.java           # Rewriter 测试数据
├── evaluator/
│   ├── ComponentEvaluator.java         # 评估器接口
│   ├── RouterEvaluator.java            # Router 评估器
│   ├── PlannerEvaluator.java           # Planner 评估器
│   ├── RetrievalEvaluator.java         # 检索质量评估器
│   ├── ResultEvaluatorEvaluator.java   # Evaluator 评估器
│   └── RewriteEvaluator.java           # Rewriter 评估器
├── judge/
│   ├── LLMJudge.java                   # LLM 评委接口
│   └── GLMJudge.java                   # GLM 实现
└── report/
    ├── EvaluationReport.java           # 报告数据结构
    └── ConsoleReporter.java            # 控制台输出
```

### 2.2 核心接口

```java
// 评估器接口
public interface ComponentEvaluator<T> {
    EvaluationResult evaluate(T input, Object output);
    String getComponentName();
}

// LLM 评委接口
public interface LLMJudge {
    JudgeResult judge(String prompt, String criteria);
}

// 评估结果
public record EvaluationResult(
    String metricName,
    double score,
    Map<String, Object> details
) {}
```

---

## 3. 测试数据设计

### 3.1 Router 测试数据

```java
public record RouterTestCase(
    String query,                    // 用户查询
    String expectedRoute,            // 期望路由: direct/rag/agent
    List<Map<String, String>> history, // 对话历史（可选）
    String category,                 // 分类标签（用于分组统计）
    String description               // 测试说明
) {}
```

**数据构造策略**：

| 类别 | 示例查询 | 期望路由 | 数量 |
|------|---------|---------|------|
| 闲聊 | "你好"、"今天天气不错" | direct | 20 |
| 简单事实 | "公司的报销流程是什么" | rag | 30 |
| 复杂推理 | "对比A和B方案的优劣，给出建议" | agent | 30 |
| 多跳问题 | "去年Q4销售额最高的产品经理是谁" | agent | 20 |
| 边界模糊 | "帮我看看这个文档" | rag/agent | 10 |

**总计：110 条测试用例**

### 3.2 Planner 测试数据

```java
public record PlannerTestCase(
    String query,                    // 复杂查询
    List<String> expectedSubQueries, // 期望的子查询（参考）
    int expectedSubQueryCount,       // 期望的子查询数量范围
    List<String> keyConcepts,        // 必须覆盖的关键概念
    String difficulty                // 难度: easy/medium/hard
) {}
```

**数据构造策略**：

| 难度 | 特征 | 示例 | 数量 |
|------|------|------|------|
| easy | 2个子查询 | "比较产品A和B的价格和功能" | 15 |
| medium | 3个子查询 | "分析Q3销售下滑原因，对比去年同期" | 15 |
| hard | 4个子查询 | "综合评估市场趋势、竞品动态、内部资源，制定下季度策略" | 10 |

**总计：40 条测试用例**

### 3.3 Evaluator 测试数据

```java
public record EvaluatorTestCase(
    String query,                    // 原始查询
    String searchResultsJson,        // 检索结果 JSON
    boolean expectedSufficient,      // 期望的充分性判断
    String expectedGap,              // 期望的 gap 描述（可选）
    String scenario                  // 场景描述
) {}
```

**数据构造策略**：

| 场景 | 充分性 | 说明 |
|------|--------|------|
| 完全匹配 | true | 检索结果直接包含答案 |
| 部分匹配 | false | 检索结果缺失关键信息 |
| 无关结果 | false | 检索结果与查询无关 |
| 过载结果 | true | 检索结果过多但包含答案 |

**总计：30 条测试用例**

### 3.4 Rewriter 测试数据

```java
public record RewriterTestCase(
    String originalQuery,            // 原始查询
    String identifiedGap,            // 已识别的信息缺口
    String expectedRewrite,          // 期望的重写结果（参考）
    String improvementFocus          // 改进焦点
) {}
```

**总计：25 条测试用例**

---

## 4. 评估指标详解

### 4.1 QueryRouter 评估

**规则指标**：

```java
public record RouterMetrics(
    double accuracy,                 // 总体准确率
    Map<String, Double> precision,   // 各类别精确率
    Map<String, Double> recall,      // 各类别召回率
    Map<String, Double> f1,          // 各类别 F1
    ConfusionMatrix confusionMatrix  // 混淆矩阵
) {}
```

**计算公式**：

- Accuracy = 正确分类数 / 总测试数
- Precision(class) = TP(class) / (TP(class) + FP(class))
- Recall(class) = TP(class) / (TP(class) + FN(class))
- F1(class) = 2 * P * R / (P + R)

### 4.2 PlannerAgent 评估

**混合指标**：

| 指标 | 类型 | 说明 |
|------|------|------|
| subQueryCountMean | 规则 | 平均子查询数量 |
| subQueryCountVariance | 规则 | 子查询数量方差 |
| coverageScore | LLM | 子查询对原查询意图的覆盖度 (0-1) |
| relevanceScore | LLM | 每个子查询与原任务的相关性 (0-1) |
| redundancyScore | LLM | 子查询间的冗余度 (0-1，越低越好) |

**LLM Judge Prompt 模板**：

```
你是一个查询规划评估专家。请评估以下子查询拆解质量。

原始查询: {query}
子查询列表: {subQueries}

请从以下维度评分 (0-1):
1. 覆盖度: 子查询是否完整覆盖原始查询的所有方面
2. 相关性: 每个子查询是否与原始任务直接相关
3. 低冗余: 子查询之间是否有不必要的重复

以 JSON 格式输出:
{
  "coverage": 0.8,
  "relevance": 0.9,
  "redundancy": 0.2,
  "reasoning": "简要说明"
}
```

### 4.3 EvaluateResultsTool 评估

**混合指标**：

| 指标 | 类型 | 说明 |
|------|------|------|
| agreementRate | 规则 | 与预定义标签的一致率 |
| gapPrecision | LLM | 识别的 gap 是否准确 |
| gapRecall | LLM | 是否遗漏了重要的 gap |

**LLM Judge Prompt 模板**：

```
你是一个检索结果评估专家。请评估以下充分性判断是否正确。

查询: {query}
检索结果摘要: {results}
系统判断: sufficient={sufficient}, gap={gap}

请评估:
1. 充分性判断是否正确
2. 识别的 gap 是否准确（如有）
3. 是否遗漏了重要信息缺口

以 JSON 格式输出:
{
  "sufficiencyCorrect": true,
  "gapAccurate": true,
  "missedGaps": [],
  "reasoning": "简要说明"
}
```

### 4.4 QueryRewriteTool 评估

**混合指标**：

| 指标 | 类型 | 说明 |
|------|------|------|
| retrievalLift | 规则 | 重写后检索 Recall@5 提升比例 |
| queryQuality | LLM | 重写查询的质量评分 |
| gapResolution | LLM | 重写是否有效解决了原 gap |

**LLM Judge Prompt 模板**：

```
你是一个查询优化评估专家。请评估查询重写效果。

原始查询: {originalQuery}
识别的缺口: {gap}
重写后的查询: {rewrittenQuery}

请评估:
1. 重写查询是否保持原意
2. 重写是否有效解决了识别的缺口
3. 重写查询的表达清晰度

以 JSON 格式输出:
{
  "preservesIntent": 0.9,
  "gapResolution": 0.8,
  "clarity": 0.85,
  "reasoning": "简要说明"
}
```

### 4.5 KnowledgeSearchTool 评估（预留）

**规则指标**：

```java
public record RetrievalMetrics(
    double precisionAt5,             // P@5
    double precisionAt10,            // P@10
    double recallAt5,                // R@5
    double recallAt10,               // R@10
    double mrr,                      // Mean Reciprocal Rank
    double ndcgAt5,                  // NDCG@5
    double ndcgAt10                  // NDCG@10
) {}
```

**计算公式**：

- Precision@K = 前 K 个结果中相关文档数 / K
- Recall@K = 前 K 个结果中相关文档数 / 总相关文档数
- MRR = avg(1 / rank_of_first_relevant)
- NDCG@K = DCG@K / IDCG@K

---

## 5. 实现细节

### 5.1 AgentEvaluationSuite 主入口

```java
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class AgentEvaluationSuite {

    @Autowired private QueryRouter queryRouter;
    @Autowired private PlannerAgent plannerAgent;
    @Autowired private EvaluateResultsTool evaluateResultsTool;
    @Autowired private QueryRewriteTool queryRewriteTool;
    @Autowired private KnowledgeSearchTool knowledgeSearchTool;

    private LLMJudge llmJudge;

    @BeforeAll
    void setup() {
        llmJudge = new GLMJudge();
        ConsoleReporter.printHeader();
    }

    @Test
    void evaluateAllComponents() {
        EvaluationReport report = new EvaluationReport();

        // 1. Router 评估
        report.addSection("Router", evaluateRouter());

        // 2. Planner 评估
        report.addSection("Planner", evaluatePlanner());

        // 3. Evaluator 评估
        report.addSection("Evaluator", evaluateResultEvaluator());

        // 4. Rewriter 评估
        report.addSection("Rewriter", evaluateRewriter());

        // 5. Retrieval 评估（可选）
        // report.addSection("Retrieval", evaluateRetrieval());

        // 输出报告
        ConsoleReporter.printReport(report);
    }

    @AfterAll
    void teardown() {
        ConsoleReporter.printFooter();
    }
}
```

### 5.2 RouterEvaluator 实现

```java
@Component
public class RouterEvaluator {

    public RouterMetrics evaluate(List<RouterTestCase> testCases, QueryRouter router) {
        int correct = 0;
        Map<String, Integer> tp = new HashMap<>();
        Map<String, Integer> fp = new HashMap<>();
        Map<String, Integer> fn = new HashMap<>();

        for (RouterTestCase tc : testCases) {
            String predicted = router.classify(tc.query(), tc.history());
            String expected = tc.expectedRoute();

            if (predicted.equals(expected)) {
                correct++;
                tp.merge(expected, 1, Integer::sum);
            } else {
                fp.merge(predicted, 1, Integer::sum);
                fn.merge(expected, 1, Integer::sum);
            }
        }

        // 计算各指标
        double accuracy = (double) correct / testCases.size();
        Map<String, Double> precision = calculatePrecision(tp, fp);
        Map<String, Double> recall = calculateRecall(tp, fn);
        Map<String, Double> f1 = calculateF1(precision, recall);

        return new RouterMetrics(accuracy, precision, recall, f1, null);
    }
}
```

### 5.3 ConsoleReporter 输出格式

```java
public class ConsoleReporter {

    public static void printReport(EvaluationReport report) {
        printSection("Router", report.getSection("Router"));
        printSection("Planner", report.getSection("Planner"));
        printSection("Evaluator", report.getSection("Evaluator"));
        printSection("Rewriter", report.getSection("Rewriter"));

        // 总览分数
        double overall = calculateOverallScore(report);
        System.out.printf("[Overall Score: %.1f%%]%n", overall * 100);
    }

    private static void printSection(String name, EvaluationSection section) {
        System.out.printf("[%s]%n", name);
        for (Metric m : section.getMetrics()) {
            System.out.printf("  %-20s %.1f%%%n", m.getName() + ":", m.getValue() * 100);
        }
        System.out.println();
    }
}
```

---

## 6. 测试数据生成策略

### 6.1 手工设计 + LLM 辅助

由于没有真实用户查询，采用以下策略：

1. **手工设计核心模板**：定义各类型查询的典型模式
2. **LLM 扩展变体**：基于模板生成更多测试用例
3. **领域专家审核**：确保测试用例符合业务场景

### 6.2 数据生成脚本

```java
public class TestDataGenerator {

    private final ChatClient generatorClient;

    public List<RouterTestCase> generateRouterCases(int count) {
        String prompt = """
            生成 %d 个知识库查询场景的测试用例。
            要求覆盖以下类别：
            - direct: 闲聊、寒暄、无关问题
            - rag: 简单事实查询、单文档问答
            - agent: 复杂推理、多跳问题、对比分析

            以 JSON 数组格式输出，每个对象包含 query, expectedRoute, category, description。
            """.formatted(count);

        // 调用 LLM 生成，然后人工审核
        String result = generatorClient.prompt().user(prompt).call().content();
        return parseAndValidate(result);
    }
}
```

---

## 7. 运行方式

```bash
# 运行完整评估
mvn test -Dtest=AgentEvaluationSuite

# 运行单个组件评估
mvn test -Dtest=AgentEvaluationSuite#evaluateRouter

# 指定输出格式
mvn test -Dtest=AgentEvaluationSuite -Dreport.format=json
```

---

## 8. 后续扩展

1. **持久化报告**：将评估结果保存到数据库或文件
2. **趋势追踪**：记录历史评估结果，追踪性能变化
3. **CI 集成**：设置阈值，低于阈值时 CI 失败
4. **A/B 测试支持**：对比不同模型/策略的效果

---

## 9. 实施计划

| 阶段 | 内容 | 预计工作量 |
|------|------|-----------|
| Phase 1 | 基础框架 + Router 评估 | 1 天 |
| Phase 2 | Planner 评估 + LLM Judge | 0.5 天 |
| Phase 3 | Evaluator + Rewriter 评估 | 0.5 天 |
| Phase 4 | 测试数据完善 + 报告优化 | 0.5 天 |

**总计：2.5 天**
