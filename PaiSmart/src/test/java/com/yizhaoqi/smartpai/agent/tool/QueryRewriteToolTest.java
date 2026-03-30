package com.yizhaoqi.smartpai.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QueryRewriteToolTest {

    @Mock
    private ChatClient rewriterChatClient;
    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock
    private ChatClient.CallResponseSpec responseSpec;

    private QueryRewriteTool tool;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        tool = new QueryRewriteTool(rewriterChatClient, objectMapper);
        lenient().when(rewriterChatClient.prompt()).thenReturn(requestSpec);
        lenient().when(requestSpec.user(anyString())).thenReturn(requestSpec);
        lenient().when(requestSpec.call()).thenReturn(responseSpec);
    }

    @Test
    void rewrite_returns_parsed_queries() {
        when(responseSpec.content()).thenReturn("[\"深度学习的具体应用案例\", \"深度学习在医疗领域的应用\"]");

        List<String> result = tool.rewrite("深度学习应用", "缺少具体应用案例");

        assertEquals(2, result.size());
        assertEquals("深度学习的具体应用案例", result.get(0));
        assertEquals("深度学习在医疗领域的应用", result.get(1));
    }

    @Test
    void rewrite_handles_markdown_code_block() {
        when(responseSpec.content()).thenReturn("```json\n[\"改写查询1\", \"改写查询2\", \"改写查询3\"]\n```");

        List<String> result = tool.rewrite("原始查询", "缺失信息");

        assertEquals(3, result.size());
    }

    @Test
    void rewrite_failure_falls_back_to_original_query() {
        when(responseSpec.content()).thenThrow(new RuntimeException("LLM 调用超时"));

        List<String> result = tool.rewrite("原始查询", "缺失信息");

        assertEquals(1, result.size());
        assertEquals("原始查询", result.get(0));
    }

    @Test
    void rewrite_invalid_json_falls_back_to_original_query() {
        when(responseSpec.content()).thenReturn("不是JSON数组");

        List<String> result = tool.rewrite("原始查询", "缺失信息");

        assertEquals(1, result.size());
        assertEquals("原始查询", result.get(0));
    }
}
