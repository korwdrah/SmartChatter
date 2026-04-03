# Agent 评估测试系统实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 PaiSmart 的 Agentic RAG 系统建立 JUnit 评估测试套件，量化 Router、Planner、Evaluator、Rewriter 四个组件的性能。

**Architecture:** 使用规则评估 + LLM-as-Judge 混合方案。测试数据存储在 JSON 文件中，通过 TestDataLoader 加载。各组件评估器独立运行，最终由 ConsoleReporter 聚合输出量化指标。

**Tech Stack:** Spring Boot 3.4, Spring AI 1.0, JUnit 5, Jackson, Guava RateLimiter

---

## File Structure

```
src/test/java/com/yizhaoqi/smartpai/agent/evaluation/
├── testdata/
│   ├── RouterTestCase.java         # Router 测试用例 record
│   ├── PlannerTestCase.java        # Planner 测试用例 record
│   ├── EvaluatorTestCase.java      # Evaluator 测试用例 record
│   ├── RewriterTestCase.java       # Rewriter 测试用例 record
│   └── TestDataLoader.java         # JSON 数据加载器
├── evaluator/
│   ├── RouterEvaluator.java        # Router 评估器
│   └── RouterMetrics.java          # Router 评估指标
├── judge/
│   ├── LLMJudge.java               # LLM 评委接口
│   ├── JudgeResult.java            # 评委结果 record
│   └── GLMJudge.java               # GLM 实现
├── report/
│   ├── EvaluationReport.java       # 报告数据结构
│   ├── EvaluationSection.java      # 组件评估结果
│   ├── FailureDetail.java          # 失败详情
│   └── ConsoleReporter.java        # 控制台输出
└── AgentEvaluationSuite.java       # 主入口

src/main/java/com/yizhaoqi/smartpai/config/
└── EvaluationConfig.java           # 评委 ChatClient 配置

src/test/resources/evaluation/
├── router/
│   ├── cases.json                  # 主测试用例 (40条)
│   └── edge_cases.json             # 边界用例 (10条)
├── planner/
│   └── cases.json                  # 20条
├── evaluator/
│   └── cases.json                  # 15条
└── rewriter/
    └── cases.json                  # 10条
```

---

## Task 1: 测试数据结构和加载器

**Files:**
- Create: `src/test/java/com/yizhaoqi/smartpai/agent/evaluation/testdata/RouterTestCase.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/agent/evaluation/testdata/PlannerTestCase.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/agent/evaluation/testdata/EvaluatorTestCase.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/agent/evaluation/testdata/RewriterTestCase.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/agent/evaluation/testdata/TestDataLoader.java`

- [ ] **Step 1: 创建 RouterTestCase record**

```java
package com.yizhaoqi.smartpai.agent.evaluation.testdata;

import java.util.List;
import java.util.Map;

public record RouterTestCase(
    String id,
    String query,
    String expectedRoute,
    List<Map<String, String>> history,
    String category,
    String description
) {
    /**
     * 检查是否为边界模糊用例（接受多种路由）
     */
    public boolean isAmbiguous() {
        return expectedRoute != null && expectedRoute.contains("/");
    }

    /**
     * 获取可接受的路由列表
     */
    public List<String> getAcceptableRoutes() {
        if (expectedRoute == null) return List.of();
        return List.of(expectedRoute.split("/"));
    }
}
```

- [ ] **Step 2: 创建 PlannerTestCase record**

```java
package com.yizhaoqi.smartpai.agent.evaluation.testdata;

import java.util.List;

public record PlannerTestCase(
    String id,
    String query,
    List<String> expectedSubQueries,
    int minSubQueryCount,
    int maxSubQueryCount,
    List<String> keyConcepts,
    String difficulty
) {
    public boolean isSubQueryCountValid(int actualCount) {
        return actualCount >= minSubQueryCount && actualCount <= maxSubQueryCount;
    }
}
```

- [ ] **Step 3: 创建 EvaluatorTestCase record**

```java
package com.yizhaoqi.smartpai.agent.evaluation.testdata;

public record EvaluatorTestCase(
    String id,
    String query,
    String searchResultsJson,
    boolean expectedSufficient,
    String expectedGap,
    String scenario
) {}
```

- [ ] **Step 4: 创建 RewriterTestCase record**

```java
package com.yizhaoqi.smartpai.agent.evaluation.testdata;

public record RewriterTestCase(
    String id,
    String originalQuery,
    String identifiedGap,
    String expectedRewrite,
    String improvementFocus
) {}
```

- [ ] **Step 5: 创建 TestDataLoader**

```java
package com.yizhaoqi.smartpai.agent.evaluation.testdata;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;

@Component
public class TestDataLoader {

    private final ObjectMapper objectMapper;

    public TestDataLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<RouterTestCase> loadRouterCases() {
        return loadCases("evaluation/router/cases.json", RouterTestCase.class);
    }

    public List<RouterTestCase> loadRouterEdgeCases() {
        return loadCases("evaluation/router/edge_cases.json", RouterTestCase.class);
    }

    public List<PlannerTestCase> loadPlannerCases() {
        return loadCases("evaluation/planner/cases.json", PlannerTestCase.class);
    }

    public List<EvaluatorTestCase> loadEvaluatorCases() {
        return loadCases("evaluation/evaluator/cases.json", EvaluatorTestCase.class);
    }

    public List<RewriterTestCase> loadRewriterCases() {
        return loadCases("evaluation/rewriter/cases.json", RewriterTestCase.class);
    }

    private <T> List<T> loadCases(String path, Class<T> clazz) {
        try {
            InputStream is = getClass().getClassLoader().getResourceAsStream(path);
            if (is == null) {
                throw new RuntimeException("Test data file not found: " + path);
            }
            return objectMapper.readValue(is,
                objectMapper.getTypeFactory().constructCollectionType(List.class, clazz));
        } catch (Exception e) {
            throw new RuntimeException("Failed to load test data: " + path, e);
        }
    }
}
```

- [ ] **Step 6: 编译验证**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add src/test/java/com/yizhaoqi/smartpai/agent/evaluation/testdata/
git commit -m "feat(evaluation): 添加测试数据结构和加载器

- RouterTestCase/PlannerTestCase/EvaluatorTestCase/RewriterTestCase
- TestDataLoader 从 JSON 文件加载测试用例

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

## Task 2: Router 评估器和指标

**Files:**
- Create: `src/test/java/com/yizhaoqi/smartpai/agent/evaluation/evaluator/RouterMetrics.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/agent/evaluation/evaluator/RouterEvaluator.java`
- Modify: `src/main/java/com/yizhaoqi/smartpai/agent/QueryRouter.java` (确保有 classify 方法)

- [ ] **Step 1: 创建 RouterMetrics record**

```java
package com.yizhaoqi.smartpai.agent.evaluation.evaluator;

import java.util.Map;

public record RouterMetrics(
    double accuracy,
    double ambiguousAccuracy,
    Map<String, Double> precision,
    Map<String, Double> recall,
    Map<String, Double> f1,
    int totalCases,
    int correctCases
) {
    public double getPrimaryScore() {
        return accuracy;
    }
}
```

- [ ] **Step 2: 创建 RouterEvaluator**

```java
package com.yizhaoqi.smartpai.agent.evaluation.evaluator;

import com.yizhaoqi.smartpai.agent.QueryRouter;
import com.yizhaoqi.smartpai.agent.evaluation.testdata.RouterTestCase;
import com.yizhaoqi.smartpai.agent.evaluation.report.FailureDetail;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class RouterEvaluator {

    private static final List<String> ROUTES = List.of("direct", "rag", "agent");

    public EvaluationResult evaluate(List<RouterTestCase> testCases, QueryRouter router) {
        int correct = 0;
        int ambiguousCorrect = 0;
        int ambiguousTotal = 0;
        List<FailureDetail> failures = new ArrayList<>();

        // TP/FP/FN counters
        Map<String, Integer> tp = new HashMap<>();
        Map<String, Integer> fp = new HashMap<>();
        Map<String, Integer> fn = new HashMap<>();
        ROUTES.forEach(r -> { tp.put(r, 0); fp.put(r, 0); fn.put(r, 0); });

        for (RouterTestCase tc : testCases) {
            String predicted = router.classify(tc.query(), tc.history());

            if (tc.isAmbiguous()) {
                ambiguousTotal++;
                if (tc.getAcceptableRoutes().contains(predicted)) {
                    correct++;
                    ambiguousCorrect++;
                } else {
                    failures.add(new FailureDetail("Router", tc.query(),
                        tc.expectedRoute(), predicted));
                }
            } else {
                String expected = tc.expectedRoute();
                if (predicted.equals(expected)) {
                    correct++;
                    tp.merge(expected, 1, Integer::sum);
                } else {
                    fp.merge(predicted, 1, Integer::sum);
                    fn.merge(expected, 1, Integer::sum);
                    failures.add(new FailureDetail("Router", tc.query(),
                        expected, predicted));
                }
            }
        }

        // Calculate metrics
        double accuracy = (double) correct / testCases.size();
        double ambiguousAccuracy = ambiguousTotal > 0
            ? (double) ambiguousCorrect / ambiguousTotal : -1;

        Map<String, Double> precision = new HashMap<>();
        Map<String, Double> recall = new HashMap<>();
        Map<String, Double> f1 = new HashMap<>();

        for (String route : ROUTES) {
            double p = tp.get(route) > 0
                ? (double) tp.get(route) / (tp.get(route) + fp.get(route)) : 0;
            double r = tp.get(route) > 0
                ? (double) tp.get(route) / (tp.get(route) + fn.get(route)) : 0;
            double f = (p + r) > 0 ? 2 * p * r / (p + r) : 0;

            precision.put(route, p);
            recall.put(route, r);
            f1.put(route, f);
        }

        RouterMetrics metrics = new RouterMetrics(
            accuracy, ambiguousAccuracy, precision, recall, f1,
            testCases.size(), correct
        );

        return new EvaluationResult("Router", metrics, failures);
    }

    public record EvaluationResult(
        String componentName,
        RouterMetrics metrics,
        List<FailureDetail> failures
    ) {}
}
```

- [ ] **Step 3: 验证 QueryRouter 接口**

Run: `grep -n "public.*classify" src/main/java/com/yizhaoqi/smartpai/agent/QueryRouter.java`
Expected: 确认存在 `public String classify(String userMessage, List<Map<String, String>> recentHistory)`

- [ ] **Step 4: 编译验证**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/yizhaoqi/smartpai/agent/evaluation/evaluator/
git commit -m "feat(evaluation): 添加 Router 评估器和指标计算

- RouterMetrics: accuracy, P/R/F1 per class
- RouterEvaluator: 支持 ambiguous 用例软评分

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

## Task 3: LLM Judge 接口和实现

**Files:**
- Create: `src/test/java/com/yizhaoqi/smartpai/agent/evaluation/judge/LLMJudge.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/agent/evaluation/judge/JudgeResult.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/agent/evaluation/judge/GLMJudge.java`
- Create: `src/main/java/com/yizhaoqi/smartpai/config/EvaluationConfig.java`

- [ ] **Step 1: 创建 LLMJudge 接口**

```java
package com.yizhaoqi.smartpai.agent.evaluation.judge;

public interface LLMJudge {

    /**
     * 使用 LLM 评估内容
     *
     * @param content  待评估内容
     * @param criteria 评估标准
     * @return 评估结果
     */
    JudgeResult judge(String content, String criteria);

    /**
     * 评估并返回指定类型的 JSON 对象
     */
    default <T> T judgeAndParse(String content, String criteria, Class<T> clazz) {
        JudgeResult result = judge(content, criteria);
        if (!result.success()) {
            throw new RuntimeException("Judge failed: " + result.errorMessage());
        }
        return result.parsedAs(clazz);
    }
}
```

- [ ] **Step 2: 创建 JudgeResult record**

```java
package com.yizhaoqi.smartpai.agent.evaluation.judge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

@JsonIgnoreProperties(ignoreUnknown = true)
public record JudgeResult(
    boolean success,
    String rawResponse,
    String errorMessage
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static JudgeResult success(String rawResponse) {
        return new JudgeResult(true, rawResponse, null);
    }

    public static JudgeResult failed(String errorMessage) {
        return new JudgeResult(false, null, errorMessage);
    }

    public <T> T parsedAs(Class<T> clazz) {
        try {
            String json = rawResponse.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("^```(?:json)?\\n?", "").replaceAll("\\n?```$", "");
            }
            return MAPPER.readValue(json, clazz);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse judge response: " + e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 3: 创建 EvaluationConfig (judgeChatClient)**

```java
package com.yizhaoqi.smartpai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EvaluationConfig {

    /**
     * 独立的评委 ChatClient，避免与被评估组件共用
     * 使用低温度确保评估稳定性
     */
    @Bean
    @Qualifier("judgeChatClient")
    public ChatClient judgeChatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel)
            .defaultOptions(ChatOptions.builder().temperature(0.1).build())
            .build();
    }
}
```

- [ ] **Step 4: 创建 GLMJudge 实现**

```java
package com.yizhaoqi.smartpai.agent.evaluation.judge;

import com.google.common.util.concurrent.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class GLMJudge implements LLMJudge {

    private static final Logger log = LoggerFactory.getLogger(GLMJudge.class);
    private static final int MAX_RETRIES = 3;

    private final ChatClient judgeClient;
    private final RateLimiter rateLimiter;

    public GLMJudge(@Qualifier("judgeChatClient") ChatClient judgeClient) {
        this.judgeClient = judgeClient;
        this.rateLimiter = RateLimiter.create(10.0); // 10 calls/sec
    }

    @Override
    public JudgeResult judge(String content, String criteria) {
        rateLimiter.acquire();

        String prompt = buildPrompt(content, criteria);

        int retries = MAX_RETRIES;
        while (retries > 0) {
            try {
                String response = judgeClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

                if (response != null && !response.isBlank()) {
                    return JudgeResult.success(response);
                }
            } catch (Exception e) {
                log.warn("Judge call failed, retries left: {} - {}", retries - 1, e.getMessage());
            }
            retries--;
        }

        return JudgeResult.failed("Failed to get valid response after " + MAX_RETRIES + " retries");
    }

    private String buildPrompt(String content, String criteria) {
        return """
            你是一个公正的评估专家。请根据以下标准评估内容。

            评估标准：
            %s

            待评估内容：
            %s

            请严格按照指定 JSON 格式输出评估结果，不要包含任何其他文字。
            """.formatted(criteria, content);
    }
}
```

- [ ] **Step 5: 添加 Guava 依赖（如需要）**

检查 pom.xml 是否已有 guava 依赖，若无则添加：

```xml
<dependency>
    <groupId>com.google.guava</groupId>
    <artifactId>guava</artifactId>
    <version>33.0.0-jre</version>
</dependency>
```

- [ ] **Step 6: 编译验证**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add src/test/java/com/yizhaoqi/smartpai/agent/evaluation/judge/
git add src/main/java/com/yizhaoqi/smartpai/config/EvaluationConfig.java
git commit -m "feat(evaluation): 添加 LLM Judge 接口和 GLMJudge 实现

- LLMJudge 接口支持泛型解析
- GLMJudge 使用独立 judgeChatClient，含限流和重试
- EvaluationConfig 配置低温度 ChatClient

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

## Task 4: 报告数据结构和控制台输出

**Files:**
- Create: `src/test/java/com/yizhaoqi/smartpai/agent/evaluation/report/FailureDetail.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/agent/evaluation/report/EvaluationSection.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/agent/evaluation/report/EvaluationReport.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/agent/evaluation/report/ConsoleReporter.java`

- [ ] **Step 1: 创建 FailureDetail record**

```java
package com.yizhaoqi.smartpai.agent.evaluation.report;

public record FailureDetail(
    String component,
    String input,
    String expected,
    String actual
) {}
```

- [ ] **Step 2: 创建 EvaluationSection**

```java
package com.yizhaoqi.smartpai.agent.evaluation.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class EvaluationSection {

    private final String name;
    private final Map<String, Double> metrics = new LinkedHashMap<>();
    private double primaryScore;
    private final List<FailureDetail> failures = new ArrayList<>();

    public EvaluationSection(String name) {
        this.name = name;
    }

    public EvaluationSection addMetric(String name, double value) {
        metrics.put(name, value);
        return this;
    }

    public EvaluationSection setPrimaryScore(double score) {
        this.primaryScore = score;
        return this;
    }

    public EvaluationSection addFailure(FailureDetail failure) {
        failures.add(failure);
        return this;
    }

    public EvaluationSection addFailures(List<FailureDetail> failureList) {
        failures.addAll(failureList);
        return this;
    }

    // Getters
    public String getName() { return name; }
    public Map<String, Double> getMetrics() { return metrics; }
    public double getPrimaryScore() { return primaryScore; }
    public List<FailureDetail> getFailures() { return failures; }
}
```

- [ ] **Step 3: 创建 EvaluationReport**

```java
package com.yizhaoqi.smartpai.agent.evaluation.report;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class EvaluationReport {

    private final Map<String, EvaluationSection> sections = new LinkedHashMap<>();

    public EvaluationReport addSection(String name, EvaluationSection section) {
        sections.put(name, section);
        return this;
    }

    public EvaluationSection getSection(String name) {
        return sections.get(name);
    }

    public Map<String, EvaluationSection> getSections() {
        return sections;
    }

    public List<FailureDetail> getAllFailures() {
        return sections.values().stream()
            .flatMap(s -> s.getFailures().stream())
            .toList();
    }
}
```

- [ ] **Step 4: 创建 ConsoleReporter**

```java
package com.yizhaoqi.smartpai.agent.evaluation.report;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class ConsoleReporter {

    private static final String LINE = "=".repeat(50);
    private static final String THIN_LINE = "-".repeat(50);

    // Component weights for overall score
    private static final double ROUTER_WEIGHT = 0.30;
    private static final double PLANNER_WEIGHT = 0.25;
    private static final double EVALUATOR_WEIGHT = 0.25;
    private static final double REWRITER_WEIGHT = 0.20;

    public void printReport(EvaluationReport report) {
        System.out.println();
        System.out.println(LINE);
        System.out.println("          Agent Evaluation Report");
        System.out.println(LINE);
        System.out.println();

        // Print each section
        for (EvaluationSection section : report.getSections().values()) {
            printSection(section);
        }

        // Print failures
        printFailures(report.getAllFailures());

        // Print overall score
        double overall = calculateOverallScore(report);
        System.out.println(THIN_LINE);
        System.out.printf("[Overall Score: %.1f%%]%n", overall * 100);
        System.out.println(LINE);
        System.out.println();
    }

    private void printSection(EvaluationSection section) {
        System.out.printf("[%s]%n", section.getName());
        for (Map.Entry<String, Double> metric : section.getMetrics().entrySet()) {
            String name = metric.getKey();
            double value = metric.getValue();

            // Format based on metric type
            if (name.contains("Avg") || name.contains("Variance") || name.contains("Count")) {
                System.out.printf("  %-22s %.2f%n", name + ":", value);
            } else if (value <= 1.0) {
                System.out.printf("  %-22s %.1f%%%n", name + ":", value * 100);
            } else {
                System.out.printf("  %-22s %.1f%n", name + ":", value);
            }
        }
        System.out.println();
    }

    private void printFailures(List<FailureDetail> failures) {
        if (failures.isEmpty()) return;

        System.out.println("[Failures]");
        Map<String, List<FailureDetail>> byComponent = failures.stream()
            .collect(Collectors.groupingBy(FailureDetail::component));

        for (Map.Entry<String, List<FailureDetail>> entry : byComponent.entrySet()) {
            System.out.printf("  %s (%d failures):%n",
                entry.getKey(), entry.getValue().size());

            List<FailureDetail> toShow = entry.getValue().stream().limit(5).toList();
            for (FailureDetail f : toShow) {
                System.out.printf("    - \"%s\" → expected: %s, got: %s%n",
                    truncate(f.input(), 40), f.expected(), f.actual());
            }
            if (entry.getValue().size() > 5) {
                System.out.printf("    ... and %d more%n", entry.getValue().size() - 5);
            }
        }
        System.out.println();
    }

    private double calculateOverallScore(EvaluationReport report) {
        double score = 0;
        EvaluationSection router = report.getSection("Router");
        EvaluationSection planner = report.getSection("Planner");
        EvaluationSection evaluator = report.getSection("Evaluator");
        EvaluationSection rewriter = report.getSection("Rewriter");

        if (router != null) score += router.getPrimaryScore() * ROUTER_WEIGHT;
        if (planner != null) score += planner.getPrimaryScore() * PLANNER_WEIGHT;
        if (evaluator != null) score += evaluator.getPrimaryScore() * EVALUATOR_WEIGHT;
        if (rewriter != null) score += rewriter.getPrimaryScore() * REWRITER_WEIGHT;

        return score;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen - 3) + "..." : s;
    }
}
```

- [ ] **Step 5: 编译验证**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add src/test/java/com/yizhaoqi/smartpai/agent/evaluation/report/
git commit -m "feat(evaluation): 添加报告数据结构和控制台输出

- FailureDetail/EvaluationSection/EvaluationReport
- ConsoleReporter: 格式化输出指标和失败详情
- Overall Score 加权计算

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

## Task 5: 测试数据 JSON 文件

**Files:**
- Create: `src/test/resources/evaluation/router/cases.json`
- Create: `src/test/resources/evaluation/router/edge_cases.json`
- Create: `src/test/resources/evaluation/planner/cases.json`
- Create: `src/test/resources/evaluation/evaluator/cases.json`
- Create: `src/test/resources/evaluation/rewriter/cases.json`

- [ ] **Step 1: 创建 Router 测试数据**

`src/test/resources/evaluation/router/cases.json`:
```json
[
  {"id": "router-001", "query": "你好", "expectedRoute": "direct", "history": [], "category": "闲聊", "description": "简单问候"},
  {"id": "router-002", "query": "谢谢你的帮助", "expectedRoute": "direct", "history": [], "category": "闲聊", "description": "感谢"},
  {"id": "router-003", "query": "再见", "expectedRoute": "direct", "history": [], "category": "闲聊", "description": "告别"},
  {"id": "router-004", "query": "今天天气不错", "expectedRoute": "direct", "history": [], "category": "闲聊", "description": "闲聊天气"},
  {"id": "router-005", "query": "公司的报销流程是什么", "expectedRoute": "rag", "history": [], "category": "简单事实", "description": "单文档事实查询"},
  {"id": "router-006", "query": "什么是机器学习", "expectedRoute": "rag", "history": [], "category": "简单事实", "description": "概念解释"},
  {"id": "router-007", "query": "项目的截止日期是哪天", "expectedRoute": "rag", "history": [], "category": "简单事实", "description": "事实查询"},
  {"id": "router-008", "query": "上次说的那个文档在哪", "expectedRoute": "rag", "history": [], "category": "简单事实", "description": "引用上下文"},
  {"id": "router-009", "query": "对比A方案和B方案的优劣，给出建议", "expectedRoute": "agent", "history": [], "category": "复杂推理", "description": "对比分析"},
  {"id": "router-010", "query": "年假和加班制度分别是什么", "expectedRoute": "agent", "history": [], "category": "多跳问题", "description": "两个独立子问题"},
  {"id": "router-011", "query": "帮我对比一下Q1和Q2的季度报告", "expectedRoute": "agent", "history": [], "category": "复杂推理", "description": "对比分析"},
  {"id": "router-012", "query": "去年Q4销售额最高的产品经理是谁", "expectedRoute": "agent", "history": [], "category": "多跳问题", "description": "需要先查销售额再查人员"},
  {"id": "router-013", "query": "分析市场趋势和竞品动态，制定策略", "expectedRoute": "agent", "history": [], "category": "复杂推理", "description": "多维度分析"},
  {"id": "router-014", "query": "公司的年假政策是什么", "expectedRoute": "rag", "history": [], "category": "简单事实", "description": "单文档查询"},
  {"id": "router-015", "query": "帮我看看这个文档", "expectedRoute": "rag/agent", "history": [], "category": "边界模糊", "description": "歧义查询"}
]
```

- [ ] **Step 2: 创建 Router 边界用例**

`src/test/resources/evaluation/router/edge_cases.json`:
```json
[
  {"id": "edge-001", "query": "", "expectedRoute": "direct", "history": [], "category": "边界条件", "description": "空查询"},
  {"id": "edge-002", "query": "查询", "expectedRoute": "direct", "history": [], "category": "边界条件", "description": "单字查询"},
  {"id": "edge-003", "query": "这是一个超过五百个字符的超长查询文本这是一个超过五百个字符的超长查询文本这是一个超过五百个字符的超长查询文本这是一个超过五百个字符的超长查询文本这是一个超过五百个字符的超长查询文本这是一个超过五百个字符的超长查询文本这是一个超过五百个字符的超长查询文本这是一个超过五百个字符的超长查询文本这是一个超过五百个字符的超长查询文本这是一个超过五百个字符的超长查询文本这是一个超过五百个字符的超长查询文本这是一个超过五百个字符的超长查询文本这是一个超过五百个字符的超长查询文本这是一个超过五百个字符的超长查询文本这是一个超过五百个字符的超长查询文本", "expectedRoute": "rag", "history": [], "category": "边界条件", "description": "超长查询"},
  {"id": "edge-004", "query": "查询包含\n换行符\n的文本", "expectedRoute": "rag", "history": [], "category": "边界条件", "description": "换行符"},
  {"id": "edge-005", "query": "SELECT * FROM users WHERE id = 1", "expectedRoute": "rag", "history": [], "category": "边界条件", "description": "代码片段"}
]
```

- [ ] **Step 3: 创建 Planner 测试数据**

`src/test/resources/evaluation/planner/cases.json`:
```json
[
  {"id": "planner-001", "query": "比较产品A和产品B的价格和功能", "expectedSubQueries": ["产品A的价格", "产品B的价格", "产品A的功能", "产品B的功能"], "minSubQueryCount": 2, "maxSubQueryCount": 4, "keyConcepts": ["价格", "功能"], "difficulty": "easy"},
  {"id": "planner-002", "query": "年假和加班制度分别是什么", "expectedSubQueries": ["年假制度", "加班制度"], "minSubQueryCount": 2, "maxSubQueryCount": 2, "keyConcepts": ["年假", "加班"], "difficulty": "easy"},
  {"id": "planner-003", "query": "分析Q3销售下滑原因，对比去年同期", "expectedSubQueries": ["Q3销售数据", "去年同期销售数据", "销售下滑原因分析"], "minSubQueryCount": 2, "maxSubQueryCount": 4, "keyConcepts": ["Q3销售", "去年同期", "下滑原因"], "difficulty": "medium"},
  {"id": "planner-004", "query": "对比A和B方案的优劣，给出建议", "expectedSubQueries": ["A方案优势", "A方案劣势", "B方案优势", "B方案劣势"], "minSubQueryCount": 2, "maxSubQueryCount": 4, "keyConcepts": ["A方案", "B方案", "优劣对比"], "difficulty": "medium"},
  {"id": "planner-005", "query": "综合评估市场趋势、竞品动态、内部资源，制定下季度策略", "expectedSubQueries": ["市场趋势", "竞品动态", "内部资源", "下季度策略"], "minSubQueryCount": 3, "maxSubQueryCount": 4, "keyConcepts": ["市场趋势", "竞品动态", "内部资源", "策略"], "difficulty": "hard"}
]
```

- [ ] **Step 4: 创建 Evaluator 测试数据**

`src/test/resources/evaluation/evaluator/cases.json`:
```json
[
  {"id": "eval-001", "query": "公司的报销流程是什么", "searchResultsJson": "[{\"content\": \"公司报销流程：1. 填写报销单 2. 主管审批 3. 财务审核 4. 打款\"}]", "expectedSufficient": true, "expectedGap": null, "scenario": "完全匹配"},
  {"id": "eval-002", "query": "公司的报销流程是什么", "searchResultsJson": "[{\"content\": \"公司福利包括五险一金和带薪年假\"}]", "expectedSufficient": false, "expectedGap": "缺少报销流程的具体步骤", "scenario": "无关结果"},
  {"id": "eval-003", "query": "对比A和B方案", "searchResultsJson": "[{\"content\": \"A方案成本100万\"}, {\"content\": \"B方案成本80万\"}]", "expectedSufficient": false, "expectedGap": "缺少两个方案的优劣势分析", "scenario": "部分匹配"},
  {"id": "eval-004", "query": "年假政策", "searchResultsJson": "[{\"content\": \"年假5天\"}, {\"content\": \"工龄满5年增加3天\"}, {\"content\": \"年假申请需提前一周\"}]", "expectedSufficient": true, "expectedGap": null, "scenario": "完全匹配"}
]
```

- [ ] **Step 5: 创建 Rewriter 测试数据**

`src/test/resources/evaluation/rewriter/cases.json`:
```json
[
  {"id": "rewrite-001", "originalQuery": "报销流程", "identifiedGap": "缺少具体审批环节和时间要求", "expectedRewrite": "公司报销审批流程和各环节时间要求", "improvementFocus": "具体化"},
  {"id": "rewrite-002", "originalQuery": "A方案", "identifiedGap": "缺少方案的详细内容", "expectedRewrite": "A方案的具体内容和实施细节", "improvementFocus": "明确化"},
  {"id": "rewrite-003", "originalQuery": "对比分析", "identifiedGap": "缺少对比维度", "expectedRewrite": "从成本、时间、风险等维度对比分析", "improvementFocus": "补充维度"}
]
```

- [ ] **Step 6: Commit**

```bash
git add src/test/resources/evaluation/
git commit -m "feat(evaluation): 添加测试数据 JSON 文件

- Router: 15 主用例 + 5 边界用例
- Planner: 5 用例（easy/medium/hard）
- Evaluator: 4 用例
- Rewriter: 3 用例

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

## Task 6: 主评估套件入口

**Files:**
- Create: `src/test/java/com/yizhaoqi/smartpai/agent/evaluation/AgentEvaluationSuite.java`

- [ ] **Step 1: 创建 AgentEvaluationSuite**

```java
package com.yizhaoqi.smartpai.agent.evaluation;

import com.yizhaoqi.smartpai.agent.PlannerAgent;
import com.yizhaoqi.smartpai.agent.QueryRouter;
import com.yizhaoqi.smartpai.agent.evaluation.evaluator.RouterEvaluator;
import com.yizhaoqi.smartpai.agent.evaluation.report.ConsoleReporter;
import com.yizhaoqi.smartpai.agent.evaluation.report.EvaluationReport;
import com.yizhaoqi.smartpai.agent.evaluation.report.EvaluationSection;
import com.yizhaoqi.smartpai.agent.evaluation.testdata.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class AgentEvaluationSuite {

    @Autowired private QueryRouter queryRouter;
    @Autowired private RouterEvaluator routerEvaluator;
    @Autowired private TestDataLoader testDataLoader;
    @Autowired private ConsoleReporter consoleReporter;

    private EvaluationReport report;

    @BeforeAll
    void setup() {
        report = new EvaluationReport();
    }

    @Test
    @Order(1)
    void evaluateRouter() {
        List<RouterTestCase> cases = testDataLoader.loadRouterCases();
        List<RouterTestCase> edgeCases = testDataLoader.loadRouterEdgeCases();

        // Combine all cases
        cases.addAll(edgeCases);

        // Run evaluation
        RouterEvaluator.EvaluationResult result = routerEvaluator.evaluate(cases, queryRouter);

        // Build section
        EvaluationSection section = new EvaluationSection("Router")
            .setPrimaryScore(result.metrics().accuracy())
            .addMetric("Accuracy", result.metrics().accuracy())
            .addFailures(result.failures());

        // Add per-class metrics
        result.metrics().f1().forEach((route, score) ->
            section.addMetric(route + " F1", score));

        if (result.metrics().ambiguousAccuracy() >= 0) {
            section.addMetric("Ambiguous Accuracy", result.metrics().ambiguousAccuracy());
        }

        section.addMetric("Total Cases", result.metrics().totalCases());
        section.addMetric("Correct Cases", result.metrics().correctCases());

        report.addSection("Router", section);
    }

    @Test
    @Order(100)
    void printReport() {
        consoleReporter.printReport(report);
    }

    @AfterAll
    void teardown() {
        // Summary assertions
        EvaluationSection router = report.getSection("Router");
        if (router != null) {
            double accuracy = router.getPrimaryScore();
            if (accuracy < 0.70) {
                System.err.println("WARNING: Router accuracy below 70%: " + (accuracy * 100) + "%");
            }
        }
    }
}
```

- [ ] **Step 2: 运行测试验证**

Run: `mvn test -Dtest=AgentEvaluationSuite -q`
Expected: Tests run with evaluation report output

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/yizhaoqi/smartpai/agent/evaluation/AgentEvaluationSuite.java
git commit -m "feat(evaluation): 添加主评估套件入口

- AgentEvaluationSuite: Router 评估集成
- 支持 @Order 控制执行顺序
- 最终输出完整评估报告

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

## Task 7: Planner 评估器（LLM Judge）

**Files:**
- Create: `src/test/java/com/yizhaoqi/smartpai/agent/evaluation/evaluator/PlannerEvaluator.java`
- Modify: `src/test/java/com/yizhaoqi/smartpai/agent/evaluation/AgentEvaluationSuite.java`

- [ ] **Step 1: 创建 PlannerEvaluator**

```java
package com.yizhaoqi.smartpai.agent.evaluation.evaluator;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.yizhaoqi.smartpai.agent.PlannerAgent;
import com.yizhaoqi.smartpai.agent.evaluation.judge.LLMJudge;
import com.yizhaoqi.smartpai.agent.evaluation.report.FailureDetail;
import com.yizhaoqi.smartpai.agent.evaluation.testdata.PlannerTestCase;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class PlannerEvaluator {

    private final LLMJudge llmJudge;

    private static final String EVAL_CRITERIA = """
        评估以下子查询拆解质量。

        原始查询: {query}
        子查询列表: {subQueries}

        请从以下维度评分 (0-1):
        1. 覆盖度: 子查询是否完整覆盖原始查询的所有方面
        2. 相关性: 每个子查询是否与原始任务直接相关
        3. 低冗余: 子查询之间是否有不必要的重复 (越低越好)

        以 JSON 格式输出:
        {"coverage": 0.8, "relevance": 0.9, "redundancy": 0.2, "reasoning": "简要说明"}
        """;

    public PlannerEvaluator(LLMJudge llmJudge) {
        this.llmJudge = llmJudge;
    }

    public EvaluationResult evaluate(List<PlannerTestCase> testCases, PlannerAgent planner) {
        List<Double> coverageScores = new ArrayList<>();
        List<Double> relevanceScores = new ArrayList<>();
        List<Double> redundancyScores = new ArrayList<>();
        List<Integer> subQueryCounts = new ArrayList<>();
        List<FailureDetail> failures = new ArrayList<>();

        for (PlannerTestCase tc : testCases) {
            try {
                List<String> subQueries = planner.plan(tc.query(), Collections.emptyList());
                subQueryCounts.add(subQueries.size());

                // Check sub-query count validity
                if (!tc.isSubQueryCountValid(subQueries.size())) {
                    failures.add(new FailureDetail("Planner", tc.query(),
                        tc.minSubQueryCount() + "-" + tc.maxSubQueryCount() + " sub-queries",
                        subQueries.size() + " sub-queries"));
                }

                // LLM evaluation
                String prompt = EVAL_CRITERIA
                    .replace("{query}", tc.query())
                    .replace("{subQueries}", subQueries.toString());

                PlannerJudgeResult result = llmJudge.judgeAndParse(prompt, EVAL_CRITERIA, PlannerJudgeResult.class);

                coverageScores.add(result.coverage());
                relevanceScores.add(result.relevance());
                redundancyScores.add(result.redundancy());

            } catch (Exception e) {
                failures.add(new FailureDetail("Planner", tc.query(), "valid result", "error: " + e.getMessage()));
            }
        }

        // Calculate averages
        double avgCoverage = coverageScores.stream().mapToDouble(d -> d).average().orElse(0);
        double avgRelevance = relevanceScores.stream().mapToDouble(d -> d).average().orElse(0);
        double avgRedundancy = redundancyScores.stream().mapToDouble(d -> d).average().orElse(0);
        double avgCount = subQueryCounts.stream().mapToInt(i -> i).average().orElse(0);
        double countVariance = calculateVariance(subQueryCounts);

        PlannerMetrics metrics = new PlannerMetrics(
            avgCount, countVariance, avgCoverage, avgRelevance, avgRedundancy
        );

        return new EvaluationResult(metrics, failures);
    }

    private double calculateVariance(List<Integer> values) {
        if (values.isEmpty()) return 0;
        double mean = values.stream().mapToInt(i -> i).average().orElse(0);
        return values.stream()
            .mapToDouble(i -> Math.pow(i - mean, 2))
            .average().orElse(0);
    }

    public record PlannerMetrics(
        double avgSubQueryCount,
        double subQueryCountVariance,
        double coverageScore,
        double relevanceScore,
        double redundancyScore
    ) {
        public double getPrimaryScore() {
            return (coverageScore + relevanceScore + (1 - redundancyScore)) / 3;
        }
    }

    public record EvaluationResult(PlannerMetrics metrics, List<FailureDetail> failures) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PlannerJudgeResult(double coverage, double relevance, double redundancy, String reasoning) {}
}
```

- [ ] **Step 2: 更新 AgentEvaluationSuite 添加 Planner 评估**

在 `AgentEvaluationSuite.java` 中添加：

```java
@Autowired private PlannerAgent plannerAgent;
@Autowired private PlannerEvaluator plannerEvaluator;

@Test
@Order(2)
void evaluatePlanner() {
    List<PlannerTestCase> cases = testDataLoader.loadPlannerCases();

    PlannerEvaluator.EvaluationResult result = plannerEvaluator.evaluate(cases, plannerAgent);

    EvaluationSection section = new EvaluationSection("Planner")
        .setPrimaryScore(result.metrics().getPrimaryScore())
        .addMetric("Avg Sub-queries", result.metrics().avgSubQueryCount())
        .addMetric("Sub-query Variance", result.metrics().subQueryCountVariance())
        .addMetric("Coverage Score", result.metrics().coverageScore())
        .addMetric("Relevance Score", result.metrics().relevanceScore())
        .addMetric("Redundancy Score", result.metrics().redundancyScore())
        .addFailures(result.failures());

    report.addSection("Planner", section);
}
```

- [ ] **Step 3: 编译验证**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/yizhaoqi/smartpai/agent/evaluation/
git commit -m "feat(evaluation): 添加 Planner 评估器

- PlannerEvaluator: LLM Judge 评估覆盖度/相关性/冗余度
- 集成到 AgentEvaluationSuite

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

## Task 8: Evaluator 和 Rewriter 评估器

**Files:**
- Create: `src/test/java/com/yizhaoqi/smartpai/agent/evaluation/evaluator/ResultEvaluatorEvaluator.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/agent/evaluation/evaluator/RewriterEvaluator.java`
- Modify: `src/test/java/com/yizhaoqi/smartpai/agent/evaluation/AgentEvaluationSuite.java`

- [ ] **Step 1: 创建 ResultEvaluatorEvaluator**

```java
package com.yizhaoqi.smartpai.agent.evaluation.evaluator;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.yizhaoqi.smartpai.agent.tool.EvaluateResultsTool;
import com.yizhaoqi.smartpai.agent.evaluation.judge.LLMJudge;
import com.yizhaoqi.smartpai.agent.evaluation.report.FailureDetail;
import com.yizhaoqi.smartpai.agent.evaluation.testdata.EvaluatorTestCase;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class ResultEvaluatorEvaluator {

    private final LLMJudge llmJudge;

    private static final String EVAL_CRITERIA = """
        评估以下充分性判断是否正确。

        查询: {query}
        检索结果摘要: {results}
        系统判断: sufficient={sufficient}, gap={gap}

        请评估:
        1. 充分性判断是否正确
        2. 识别的 gap 是否准确（如有）
        3. 是否遗漏了重要信息缺口

        以 JSON 格式输出:
        {"sufficiencyCorrect": true, "gapAccurate": true, "missedGaps": [], "reasoning": "简要说明"}
        """;

    public ResultEvaluatorEvaluator(LLMJudge llmJudge) {
        this.llmJudge = llmJudge;
    }

    public EvaluationResult evaluate(List<EvaluatorTestCase> testCases, EvaluateResultsTool evaluator) {
        int agreements = 0;
        List<Double> gapPrecisions = new ArrayList<>();
        List<FailureDetail> failures = new ArrayList<>();

        for (EvaluatorTestCase tc : testCases) {
            try {
                EvaluateResultsTool.EvaluationResult result = evaluator.evaluate(tc.query(), tc.searchResultsJson());

                // Check agreement
                if (result.sufficient() == tc.expectedSufficient()) {
                    agreements++;
                } else {
                    failures.add(new FailureDetail("Evaluator", tc.query(),
                        "sufficient=" + tc.expectedSufficient(),
                        "sufficient=" + result.sufficient()));
                }

                // LLM evaluation for gap accuracy
                String prompt = EVAL_CRITERIA
                    .replace("{query}", tc.query())
                    .replace("{results}", tc.searchResultsJson())
                    .replace("{sufficient}", String.valueOf(result.sufficient()))
                    .replace("{gap}", String.valueOf(result.gap()));

                EvaluatorJudgeResult judgeResult = llmJudge.judgeAndParse(prompt, EVAL_CRITERIA, EvaluatorJudgeResult.class);
                gapPrecisions.add(judgeResult.gapAccurate() ? 1.0 : 0.0);

            } catch (Exception e) {
                failures.add(new FailureDetail("Evaluator", tc.query(), "valid result", "error: " + e.getMessage()));
            }
        }

        double agreementRate = (double) agreements / testCases.size();
        double avgGapPrecision = gapPrecisions.stream().mapToDouble(d -> d).average().orElse(0);

        EvaluatorMetrics metrics = new EvaluatorMetrics(agreementRate, avgGapPrecision);
        return new EvaluationResult(metrics, failures);
    }

    public record EvaluatorMetrics(double agreementRate, double gapPrecision) {
        public double getPrimaryScore() {
            return (agreementRate + gapPrecision) / 2;
        }
    }

    public record EvaluationResult(EvaluatorMetrics metrics, List<FailureDetail> failures) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EvaluatorJudgeResult(boolean sufficiencyCorrect, boolean gapAccurate, List<String> missedGaps, String reasoning) {}
}
```

- [ ] **Step 2: 创建 RewriterEvaluator**

```java
package com.yizhaoqi.smartpai.agent.evaluation.evaluator;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.yizhaoqi.smartpai.agent.tool.QueryRewriteTool;
import com.yizhaoqi.smartpai.agent.evaluation.judge.LLMJudge;
import com.yizhaoqi.smartpai.agent.evaluation.report.FailureDetail;
import com.yizhaoqi.smartpai.agent.evaluation.testdata.RewriterTestCase;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class RewriterEvaluator {

    private final LLMJudge llmJudge;

    private static final String EVAL_CRITERIA = """
        评估查询重写效果。

        原始查询: {originalQuery}
        识别的缺口: {gap}
        重写后的查询: {rewrittenQuery}

        请评估:
        1. 重写查询是否保持原意
        2. 重写是否有效解决了识别的缺口
        3. 重写查询的表达清晰度

        以 JSON 格式输出:
        {"preservesIntent": 0.9, "gapResolution": 0.8, "clarity": 0.85, "reasoning": "简要说明"}
        """;

    public RewriterEvaluator(LLMJudge llmJudge) {
        this.llmJudge = llmJudge;
    }

    public EvaluationResult evaluate(List<RewriterTestCase> testCases, QueryRewriteTool rewriter) {
        List<Double> qualityScores = new ArrayList<>();
        List<Double> gapResolutions = new ArrayList<>();
        List<FailureDetail> failures = new ArrayList<>();

        for (RewriterTestCase tc : testCases) {
            try {
                List<String> rewrittenQueries = rewriter.rewrite(tc.originalQuery(), tc.identifiedGap());
                String rewrittenQuery = String.join("; ", rewrittenQueries);

                // LLM evaluation
                String prompt = EVAL_CRITERIA
                    .replace("{originalQuery}", tc.originalQuery())
                    .replace("{gap}", tc.identifiedGap())
                    .replace("{rewrittenQuery}", rewrittenQuery);

                RewriterJudgeResult result = llmJudge.judgeAndParse(prompt, EVAL_CRITERIA, RewriterJudgeResult.class);

                qualityScores.add(result.clarity());
                gapResolutions.add(result.gapResolution());

            } catch (Exception e) {
                failures.add(new FailureDetail("Rewriter", tc.originalQuery(), "valid rewrite", "error: " + e.getMessage()));
            }
        }

        double avgQuality = qualityScores.stream().mapToDouble(d -> d).average().orElse(0);
        double avgGapResolution = gapResolutions.stream().mapToDouble(d -> d).average().orElse(0);

        RewriterMetrics metrics = new RewriterMetrics(avgQuality, avgGapResolution);
        return new EvaluationResult(metrics, failures);
    }

    public record RewriterMetrics(double queryQuality, double gapResolution) {
        public double getPrimaryScore() {
            return (queryQuality + gapResolution) / 2;
        }
    }

    public record EvaluationResult(RewriterMetrics metrics, List<FailureDetail> failures) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RewriterJudgeResult(double preservesIntent, double gapResolution, double clarity, String reasoning) {}
}
```

- [ ] **Step 3: 更新 AgentEvaluationSuite**

添加 Evaluator 和 Rewriter 评估方法，参考 Task 7 的模式。

- [ ] **Step 4: 编译验证**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/yizhaoqi/smartpai/agent/evaluation/
git commit -m "feat(evaluation): 添加 Evaluator 和 Rewriter 评估器

- ResultEvaluatorEvaluator: agreement rate + gap precision
- RewriterEvaluator: query quality + gap resolution
- 集成到 AgentEvaluationSuite

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

## Task 9: 最终集成测试

**Files:**
- Modify: `src/test/java/com/yizhaoqi/smartpai/agent/evaluation/AgentEvaluationSuite.java`

- [ ] **Step 1: 运行完整评估套件**

Run: `mvn test -Dtest=AgentEvaluationSuite -q`
Expected: 完整评估报告输出

- [ ] **Step 2: 验证输出格式**

确认输出包含：
- [Router] 准确率和各类别 F1
- [Planner] 覆盖度/相关性/冗余度
- [Evaluator] 一致率和 gap precision
- [Rewriter] 查询质量和 gap resolution
- [Failures] 失败详情
- [Overall Score] 加权总分

- [ ] **Step 3: 最终 commit**

```bash
git add .
git commit -m "feat(evaluation): Agent 评估测试系统完成

完整的 JUnit 评估套件，支持:
- Router: Accuracy, P/R/F1 per class
- Planner: Coverage, Relevance, Redundancy (LLM Judge)
- Evaluator: Agreement Rate, Gap Precision
- Rewriter: Query Quality, Gap Resolution
- 控制台格式化输出，含失败详情

运行: mvn test -Dtest=AgentEvaluationSuite

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

## Summary

| Task | Description | Files |
|------|-------------|-------|
| 1 | 测试数据结构和加载器 | 5 files |
| 2 | Router 评估器 | 2 files |
| 3 | LLM Judge | 4 files |
| 4 | 报告数据结构 | 4 files |
| 5 | 测试数据 JSON | 5 files |
| 6 | 主评估套件 | 1 file |
| 7 | Planner 评估器 | 2 files |
| 8 | Evaluator/Rewriter 评估器 | 3 files |
| 9 | 最终集成测试 | 1 file |

**Total: 27 files**
