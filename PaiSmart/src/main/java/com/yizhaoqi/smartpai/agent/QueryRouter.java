package com.yizhaoqi.smartpai.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class QueryRouter {

    private static final Logger logger = LoggerFactory.getLogger(QueryRouter.class);
    private final ChatClient routerChatClient;

    public QueryRouter(ChatClient routerChatClient) {
        this.routerChatClient = routerChatClient;
    }

    /**
     * 三路查询分类: direct / rag / agent
     *
     * @param userMessage 用户消息
     * @param recentHistory 最近几条历史（用于理解指代，传最近 3 条即可）
     * @return "direct" / "rag" / "agent"
     */
    public String classify(String userMessage, List<Map<String, String>> recentHistory) {
        try {
            // 构建包含历史的输入，帮助分类器理解指代
            StringBuilder input = new StringBuilder(userMessage);
            if (recentHistory != null && !recentHistory.isEmpty()) {
                input.append("\n\n最近对话:");
                int start = Math.max(0, recentHistory.size() - 3);
                for (int i = start; i < recentHistory.size(); i++) {
                    Map<String, String> msg = recentHistory.get(i);
                    input.append("\n").append(msg.get("role")).append(": ").append(msg.get("content"));
                }
            }

            String result = routerChatClient.prompt()
                .user(input.toString())
                .call()
                .content();

            // 清理输出，只取第一个词
            result = result.trim().toLowerCase();
            if (result.contains("agent")) return "agent";
            if (result.contains("rag")) return "rag";
            return "direct";  // 默认走 direct
        } catch (Exception e) {
            logger.error("查询路由分类失败，默认走 rag: {}", e.getMessage(), e);
            return "rag";  // 分类失败保守走 rag（宁可多查，不能瞎编）
        }
    }
}
