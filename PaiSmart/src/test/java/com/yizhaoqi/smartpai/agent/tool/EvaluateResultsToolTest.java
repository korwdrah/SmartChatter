package com.yizhaoqi.smartpai.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EvaluateResultsToolTest {

    @Mock
    private ChatClient evaluatorChatClient;
    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock
    private ChatClient.CallResponseSpec responseSpec;

    private EvaluateResultsTool tool;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        tool = new EvaluateResultsTool(evaluatorChatClient, objectMapper);
        lenient().when(evaluatorChatClient.prompt()).thenReturn(requestSpec);
        lenient().when(requestSpec.user(anyString())).thenReturn(requestSpec);
        lenient().when(requestSpec.call()).thenReturn(responseSpec);
    }

    @Test
    void evaluate_sufficient_true() {
        when(responseSpec.content()).thenReturn("{\"sufficient\": true, \"gap\": null}");

        EvaluateResultsTool.EvaluationResult result = tool.evaluate("什么是机器学习", "[1] 机器学习是...");

        assertTrue(result.sufficient());
        assertNull(result.gap());
    }

    @Test
    void evaluate_sufficient_false() {
        when(responseSpec.content()).thenReturn("{\"sufficient\": false, \"gap\": \"缺少深度学习的具体应用案例\"}");

        EvaluateResultsTool.EvaluationResult result = tool.evaluate("深度学习应用", "[1] 深度学习是...");

        assertFalse(result.sufficient());
        assertEquals("缺少深度学习的具体应用案例", result.gap());
    }

    @Test
    void evaluate_handles_markdown_code_block() {
        when(responseSpec.content()).thenReturn("```json\n{\"sufficient\": false, \"gap\": \"缺少对比数据\"}\n```");

        EvaluateResultsTool.EvaluationResult result = tool.evaluate("对比Q1和Q2", "[1] Q1数据...");

        assertFalse(result.sufficient());
        assertEquals("缺少对比数据", result.gap());
    }

    @Test
    void evaluate_failure_returns_sufficient_by_default() {
        when(responseSpec.content()).thenThrow(new RuntimeException("LLM 调用超时"));

        EvaluateResultsTool.EvaluationResult result = tool.evaluate("测试", "[1] 结果");

        assertTrue(result.sufficient());
        assertNull(result.gap());
    }

    @Test
    void evaluate_invalid_json_returns_sufficient_by_default() {
        when(responseSpec.content()).thenReturn("这不是合法的JSON");

        EvaluateResultsTool.EvaluationResult result = tool.evaluate("测试", "[1] 结果");

        assertTrue(result.sufficient());
    }
}
