package com.yizhaoqi.smartpai.agent.evaluation.report;

public record FailureDetail(
    String component,
    String input,
    String expected,
    String actual
) {}
