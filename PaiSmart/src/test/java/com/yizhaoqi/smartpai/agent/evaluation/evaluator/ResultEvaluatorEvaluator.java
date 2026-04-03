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
        Objects.requireNonNull(testCases, "testCases must not be null");
        Objects.requireNonNull(evaluator, "evaluator must not be null");

        if (testCases.isEmpty()) {
            return new EvaluationResult(new EvaluatorMetrics(0, 0), Collections.emptyList());
        }

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

                EvaluatorJudgeResult judgeResult = llmJudge.judgeAndParse(prompt, EvaluatorJudgeResult.class);
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
