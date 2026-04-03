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
