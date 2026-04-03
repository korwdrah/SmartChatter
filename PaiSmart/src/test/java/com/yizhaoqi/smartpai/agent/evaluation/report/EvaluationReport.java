package com.yizhaoqi.smartpai.agent.evaluation.report;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class EvaluationReport {

    private final Map<String, EvaluationSection> sections = new LinkedHashMap<>();

    public EvaluationReport addSection(String name, EvaluationSection section) {
        sections.put(name, section);
        return this;
    }

    public EvaluationSection getSection(String name) {
        return sections.get(name);
    }

    public Map<String, EvaluationSection> getSections() {
        return sections;
    }

    public List<FailureDetail> getAllFailures() {
        return sections.values().stream()
            .flatMap(s -> s.getFailures().stream())
            .toList();
    }
}
