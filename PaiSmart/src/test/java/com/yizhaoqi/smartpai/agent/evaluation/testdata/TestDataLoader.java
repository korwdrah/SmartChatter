package com.yizhaoqi.smartpai.agent.evaluation.testdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;

@Component
public class TestDataLoader {

    private final ObjectMapper objectMapper;

    public TestDataLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<RouterTestCase> loadRouterCases() {
        return loadCases("evaluation/router/cases.json", RouterTestCase.class);
    }

    public List<RouterTestCase> loadRouterEdgeCases() {
        return loadCases("evaluation/router/edge_cases.json", RouterTestCase.class);
    }

    public List<PlannerTestCase> loadPlannerCases() {
        return loadCases("evaluation/planner/cases.json", PlannerTestCase.class);
    }

    public List<EvaluatorTestCase> loadEvaluatorCases() {
        return loadCases("evaluation/evaluator/cases.json", EvaluatorTestCase.class);
    }

    public List<RewriterTestCase> loadRewriterCases() {
        return loadCases("evaluation/rewriter/cases.json", RewriterTestCase.class);
    }

    private <T> List<T> loadCases(String path, Class<T> clazz) {
        try {
            InputStream is = getClass().getClassLoader().getResourceAsStream(path);
            if (is == null) {
                throw new RuntimeException("Test data file not found: " + path);
            }
            return objectMapper.readValue(is,
                objectMapper.getTypeFactory().constructCollectionType(List.class, clazz));
        } catch (Exception e) {
            throw new RuntimeException("Failed to load test data: " + path, e);
        }
    }
}
