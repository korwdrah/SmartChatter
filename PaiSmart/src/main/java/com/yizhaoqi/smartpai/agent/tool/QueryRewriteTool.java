package com.yizhaoqi.smartpai.agent.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class QueryRewriteTool {

    private static final Logger logger = LoggerFactory.getLogger(QueryRewriteTool.class);
    private final ChatClient rewriterChatClient;
    private final ObjectMapper objectMapper;

    public QueryRewriteTool(ChatClient rewriterChatClient, ObjectMapper objectMapper) {
        this.rewriterChatClient = rewriterChatClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 根据原始查询和缺失信息，改写查询。
     *
     * @param originalQuery 原始查询
     * @param gap 缺失的信息描述
     * @return 1-3 个改写后的查询
     */
    public List<String> rewrite(String originalQuery, String gap) {
        try {
            String input = "原始查询: " + originalQuery + "\n缺失的信息: " + gap;

            String result = rewriterChatClient.prompt()
                .user(input)
                .call()
                .content();

            // 解析 JSON，处理可能的 markdown 代码块包裹
            result = result.trim();
            if (result.startsWith("```")) {
                result = result.replaceAll("^```(?:json)?\\n?", "").replaceAll("\\n?```$", "");
            }

            List<String> queries = objectMapper.readValue(result, new TypeReference<List<String>>() {});
            logger.info("查询改写: original={}, gap={}, rewritten={}", originalQuery, gap, queries);
            return queries;
        } catch (Exception e) {
            logger.error("查询改写失败，使用原始查询: {}", e.getMessage(), e);
            return List.of(originalQuery);
        }
    }
}
