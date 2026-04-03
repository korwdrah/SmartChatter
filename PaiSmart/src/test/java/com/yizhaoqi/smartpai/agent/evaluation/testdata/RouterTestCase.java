package com.yizhaoqi.smartpai.agent.evaluation.testdata;

import java.util.List;
import java.util.Map;

public record RouterTestCase(
    String id,
    String query,
    String expectedRoute,
    List<Map<String, String>> history,
    String category,
    String description
) {
    public boolean isAmbiguous() {
        return expectedRoute != null && expectedRoute.contains("/");
    }

    public List<String> getAcceptableRoutes() {
        if (expectedRoute == null) return List.of();
        return List.of(expectedRoute.split("/"));
    }
}
