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
