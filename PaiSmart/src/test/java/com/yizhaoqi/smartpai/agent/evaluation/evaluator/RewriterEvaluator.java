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
        Objects.requireNonNull(testCases, "testCases must not be null");
        Objects.requireNonNull(rewriter, "rewriter must not be null");

        if (testCases.isEmpty()) {
            return new EvaluationResult(new RewriterMetrics(0, 0), Collections.emptyList());
        }

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

                RewriterJudgeResult result = llmJudge.judgeAndParse(prompt, RewriterJudgeResult.class);

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
