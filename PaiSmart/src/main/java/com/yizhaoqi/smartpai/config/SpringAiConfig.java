package com.yizhaoqi.smartpai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SpringAiConfig {

    /**
     * 查询路由器：三路分类 (direct/rag/agent)
     */
    @Bean
    public ChatClient routerChatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel)
            .defaultSystem("判断用户消息的类型，只回复以下三个词之一：\n"
                         + "direct - 仅限问候、感谢、告别等纯社交性对话（如：你好、谢谢、再见）\n"
                         + "rag - 需要从知识库文档检索回答的问题，包括通用知识问题（如：什么是机器学习）\n"
                         + "agent - 包含多个独立子问题、需要从多个角度检索的复杂问题\n\n"
                         + "示例:\n"
                         + "\"你好\" → direct\n"
                         + "\"谢谢\" → direct\n"
                         + "\"什么是机器学习\" → rag\n"
                         + "\"公司的年假政策是什么\" → rag\n"
                         + "\"上次说的那个文档在哪\" → rag\n"
                         + "\"年假和加班制度分别是什么\" → agent\n"
                         + "\"帮我对比一下Q1和Q2的季度报告\" → agent")
            .defaultOptions(ChatOptions.builder().temperature(0.1).build())
            .build();
    }

    /**
     * 查询规划器：拆解子问题
     */
    @Bean
    public ChatClient plannerChatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel)
            .defaultSystem("你是一个查询规划专家。分析用户的问题，识别其中包含的独立子问题，"
                         + "为每个子问题生成一个精确的搜索查询。"
                         + "子查询必须与用户输入使用相同的语言。"
                         + "输出 JSON 数组格式，例如: [\"查询1\", \"查询2\"]。"
                         + "如果问题只涉及一个方面，返回只包含一个元素的数组。"
                         + "最多拆分为4个子查询。")
            .defaultOptions(ChatOptions.builder().temperature(0.1).build())
            .build();
    }

    /**
     * 结果评估器：判断检索结果是否充分
     */
    @Bean
    public ChatClient evaluatorChatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel)
            .defaultSystem("你是一个检索结果质量评估器。判断给定的搜索结果是否能充分回答用户的问题。\n"
                         + "只回复 JSON 格式: {\"sufficient\": true或false, \"gap\": \"缺失的信息描述\"或null}\n"
                         + "如果结果能回答问题，sufficient 为 true，gap 为 null。\n"
                         + "如果结果不能回答问题，sufficient 为 false，gap 描述缺少什么关键信息。")
            .defaultOptions(ChatOptions.builder().temperature(0.1).build())
            .build();
    }

    /**
     * 查询改写器：改写查询以提高检索质量
     */
    @Bean
    public ChatClient rewriterChatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel)
            .defaultSystem("你是一个搜索查询改写专家。根据原始查询和缺失信息，生成1-3个改写后的搜索查询。\n"
                         + "输出 JSON 数组格式，例如: [\"改写查询1\", \"改写查询2\"]。\n"
                         + "改写后的查询应该更具体、更精确，能检索到缺失的信息。")
            .defaultOptions(ChatOptions.builder().temperature(0.3).build())
            .build();
    }
}
