package com.yizhaoqi.smartpai.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

@Component
public class EvaluateResultsTool {

    private static final Logger logger = LoggerFactory.getLogger(EvaluateResultsTool.class);
    private final ChatClient evaluatorChatClient;
    private final ObjectMapper objectMapper;

    public EvaluateResultsTool(ChatClient evaluatorChatClient, ObjectMapper objectMapper) {
        this.evaluatorChatClient = evaluatorChatClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 评估检索结果是否足以回答用户问题。
     *
     * @param query 用户的原始问题
     * @param searchResultsJson 检索结果的 JSON 摘要
     * @return EvaluationResult
     */
    public EvaluationResult evaluate(String query, String searchResultsJson) {
        try {
            String input = "用户问题: " + query + "\n\n搜索结果:\n" + searchResultsJson;

            String result = evaluatorChatClient.prompt()
                .user(input)
                .call()
                .content();

            // 解析 JSON，处理可能的 markdown 代码块包裹
            result = result.trim();
            if (result.startsWith("```")) {
                result = result.replaceAll("^```(?:json)?\\n?", "").replaceAll("\\n?```$", "");
            }

            JsonNode node = objectMapper.readTree(result);
            boolean sufficient = node.path("sufficient").asBoolean(true);
            String gap = node.path("gap").isNull() ? null : node.path("gap").asText();

            logger.info("结果评估: sufficient={}, gap={}", sufficient, gap);
            return new EvaluationResult(sufficient, gap);
        } catch (Exception e) {
            logger.error("结果评估失败，默认充足: {}", e.getMessage(), e);
            return new EvaluationResult(true, null);
        }
    }

    public record EvaluationResult(boolean sufficient, String gap) {}
}
