package com.yizhaoqi.smartpai.agent.evaluation.judge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * LLM 评估结果
 * 封装原始响应和解析能力
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JudgeResult(
    boolean success,
    String rawResponse,
    String errorMessage
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 创建成功的评估结果
     */
    public static JudgeResult success(String rawResponse) {
        return new JudgeResult(true, rawResponse, null);
    }

    /**
     * 创建失败的评估结果
     */
    public static JudgeResult failed(String errorMessage) {
        return new JudgeResult(false, null, errorMessage);
    }

    /**
     * 将原始响应解析为指定类型的对象
     * 自动处理 Markdown 代码块格式
     *
     * @param clazz 目标类型
     * @param <T>   泛型类型
     * @return 解析后的对象
     * @throws RuntimeException 如果解析失败
     */
    public <T> T parsedAs(Class<T> clazz) {
        try {
            String json = rawResponse.trim();
            // 处理 Markdown 代码块格式 ```json ... ```
            if (json.startsWith("```")) {
                json = json.replaceAll("^```(?:json)?\\n?", "").replaceAll("\\n?```$", "");
            }
            return MAPPER.readValue(json, clazz);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse judge response: " + e.getMessage(), e);
        }
    }
}
