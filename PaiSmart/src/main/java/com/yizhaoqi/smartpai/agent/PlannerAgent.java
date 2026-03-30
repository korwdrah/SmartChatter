package com.yizhaoqi.smartpai.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class PlannerAgent {

    private static final Logger logger = LoggerFactory.getLogger(PlannerAgent.class);
    private final ChatClient plannerChatClient;
    private final ObjectMapper objectMapper;
    private static final int MAX_SUB_QUERIES = 4;

    public PlannerAgent(ChatClient plannerChatClient, ObjectMapper objectMapper) {
        this.plannerChatClient = plannerChatClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 分析用户问题，拆解为多个子查询。
     *
     * @param message 用户消息
     * @param history 对话历史（最近5条，用于理解上下文）
     * @return 子查询列表（1-4个）
     */
    public List<String> plan(String message, List<Map<String, String>> history) {
        try {
            StringBuilder input = new StringBuilder(message);

            // 附带最近历史帮助理解上下文
            if (history != null && !history.isEmpty()) {
                int start = Math.max(0, history.size() - 5);
                input.append("\n\n最近对话:");
                for (int i = start; i < history.size(); i++) {
                    Map<String, String> msg = history.get(i);
                    input.append("\n").append(msg.get("role")).append(": ").append(msg.get("content"));
                }
            }

            String result = plannerChatClient.prompt()
                .user(input.toString())
                .call()
                .content();

            // 解析 JSON 数组
            result = result.trim();
            // 去除可能的 markdown 代码块标记
            if (result.startsWith("```")) {
                result = result.replaceAll("^```(?:json)?\\n?", "").replaceAll("\\n?```$", "");
            }

            List<String> queries = objectMapper.readValue(result, new TypeReference<List<String>>() {});

            // 限制子查询数量
            if (queries.size() > MAX_SUB_QUERIES) {
                queries = queries.subList(0, MAX_SUB_QUERIES);
            }

            logger.info("Planner 拆解子查询: {} → {}", message, queries);
            return queries;
        } catch (Exception e) {
            logger.error("查询规划失败，使用原始查询: {}", e.getMessage(), e);
            return List.of(message);  // 失败时退化为原始查询
        }
    }
}
