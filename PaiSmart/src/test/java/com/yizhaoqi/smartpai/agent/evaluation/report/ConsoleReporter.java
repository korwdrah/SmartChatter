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
