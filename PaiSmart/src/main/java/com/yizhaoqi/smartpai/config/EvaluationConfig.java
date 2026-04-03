package com.yizhaoqi.smartpai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 评估系统配置
 * 提供独立的评委 ChatClient，避免与被评估组件共用
 */
@Configuration
public class EvaluationConfig {

    /**
     * 独立的评委 ChatClient
     * 使用低温度确保评估结果的稳定性和一致性
     *
     * @param chatModel 底层聊天模型
     * @return 配置好的 ChatClient
     */
    @Bean
    @Qualifier("judgeChatClient")
    public ChatClient judgeChatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel)
            .defaultOptions(ChatOptions.builder().temperature(0.1).build())
            .build();
    }
}
