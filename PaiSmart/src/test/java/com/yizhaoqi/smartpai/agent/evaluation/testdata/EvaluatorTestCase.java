package com.yizhaoqi.smartpai.agent.evaluation.testdata;

public record EvaluatorTestCase(
    String id,
    String query,
    String searchResultsJson,
    boolean expectedSufficient,
    String expectedGap,
    String scenario
) {}
