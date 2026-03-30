package com.yizhaoqi.smartpai.agent;

import com.yizhaoqi.smartpai.entity.SearchResult;
import java.util.List;

public class AgentResult {
    private final List<SearchResult> sources;

    public AgentResult(List<SearchResult> sources) {
        this.sources = sources;
    }

    public List<SearchResult> getSources() {
        return sources;
    }

    /**
     * 将检索结果构建为 context 字符串，复用 ChatHandler 的格式
     * 作为中间结果
     */
    public String buildContext() {
        if (sources == null || sources.isEmpty()) {
            return "";
        }
        final int MAX_SNIPPET_LEN = 300;
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < sources.size(); i++) {
            SearchResult result = sources.get(i);
            String snippet = result.getTextContent();
            if (snippet.length() > MAX_SNIPPET_LEN) {
                snippet = snippet.substring(0, MAX_SNIPPET_LEN) + "\u2026";
            }
            String fileLabel = result.getFileName() != null ? result.getFileName() : "unknown";
            context.append(String.format("[%d] (%s) %s\n", i + 1, fileLabel, snippet));
        }
        return context.toString();
    }
}
