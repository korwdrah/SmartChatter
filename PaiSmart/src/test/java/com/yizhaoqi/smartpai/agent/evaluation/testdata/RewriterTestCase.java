package com.yizhaoqi.smartpai.agent.evaluation.testdata;

public record RewriterTestCase(
    String id,
    String originalQuery,
    String identifiedGap,
    String expectedRewrite,
    String improvementFocus
) {}
