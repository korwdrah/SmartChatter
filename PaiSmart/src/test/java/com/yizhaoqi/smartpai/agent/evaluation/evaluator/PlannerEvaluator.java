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

                PlannerJudgeResult result = llmJudge.judgeAndParse(prompt, PlannerJudgeResult.class);

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
