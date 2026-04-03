package com.yizhaoqi.smartpai.agent.evaluation;

import com.yizhaoqi.smartpai.agent.PlannerAgent;
import com.yizhaoqi.smartpai.agent.QueryRouter;
import com.yizhaoqi.smartpai.agent.evaluation.evaluator.PlannerEvaluator;
import com.yizhaoqi.smartpai.agent.evaluation.evaluator.ResultEvaluatorEvaluator;
import com.yizhaoqi.smartpai.agent.evaluation.evaluator.RouterEvaluator;
import com.yizhaoqi.smartpai.agent.evaluation.report.ConsoleReporter;
import com.yizhaoqi.smartpai.agent.evaluation.report.EvaluationReport;
import com.yizhaoqi.smartpai.agent.evaluation.report.EvaluationSection;
import com.yizhaoqi.smartpai.agent.evaluation.testdata.EvaluatorTestCase;
import com.yizhaoqi.smartpai.agent.evaluation.testdata.PlannerTestCase;
import com.yizhaoqi.smartpai.agent.evaluation.testdata.RouterTestCase;
import com.yizhaoqi.smartpai.agent.evaluation.testdata.TestDataLoader;
import com.yizhaoqi.smartpai.agent.tool.EvaluateResultsTool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;

@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class AgentEvaluationSuite {

    @Autowired private QueryRouter queryRouter;
    @Autowired private RouterEvaluator routerEvaluator;
    @Autowired private PlannerAgent plannerAgent;
    @Autowired private PlannerEvaluator plannerEvaluator;
    @Autowired private EvaluateResultsTool evaluateResultsTool;
    @Autowired private ResultEvaluatorEvaluator resultEvaluatorEvaluator;
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
        // Create mutable list to combine cases
        List<RouterTestCase> cases = new ArrayList<>(testDataLoader.loadRouterCases());
        cases.addAll(testDataLoader.loadRouterEdgeCases());

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

    @Test
    @Order(3)
    void evaluateEvaluator() {
        List<EvaluatorTestCase> cases = testDataLoader.loadEvaluatorCases();

        ResultEvaluatorEvaluator.EvaluationResult result = resultEvaluatorEvaluator.evaluate(cases, evaluateResultsTool);

        EvaluationSection section = new EvaluationSection("Evaluator")
            .setPrimaryScore(result.metrics().getPrimaryScore())
            .addMetric("Agreement Rate", result.metrics().agreementRate())
            .addMetric("Gap Precision", result.metrics().gapPrecision())
            .addFailures(result.failures());

        report.addSection("Evaluator", section);
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
