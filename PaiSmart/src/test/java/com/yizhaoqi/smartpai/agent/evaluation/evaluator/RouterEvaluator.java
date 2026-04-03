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
        Objects.requireNonNull(testCases, "testCases must not be null");
        Objects.requireNonNull(router, "router must not be null");

        if (testCases.isEmpty()) {
            Map<String, Double> zeros = new HashMap<>();
            ROUTES.forEach(r -> zeros.put(r, 0.0));
            return new EvaluationResult("Router",
                new RouterMetrics(0.0, -1.0, zeros, zeros, zeros, 0, 0),
                List.of());
        }

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
                if (Objects.equals(predicted, expected)) {
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
