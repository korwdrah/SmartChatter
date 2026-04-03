package com.yizhaoqi.smartpai.agent.evaluation.testdata;

import java.util.List;

public record PlannerTestCase(
    String id,
    String query,
    List<String> expectedSubQueries,
    int minSubQueryCount,
    int maxSubQueryCount,
    List<String> keyConcepts,
    String difficulty
) {
    public boolean isSubQueryCountValid(int actualCount) {
        return actualCount >= minSubQueryCount && actualCount <= maxSubQueryCount;
    }
}
